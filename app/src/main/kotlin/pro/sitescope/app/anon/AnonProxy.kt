package pro.sitescope.app.anon

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Локальный HTTP-прокси на 127.0.0.1, который WebView использует как единственный прокси.
 * По имени узла выбирает путь: *.onion → Tor (SOCKS5, имя разрешает Tor), *.i2p → I2P,
 * остальное — напрямую (или через Tor в режиме «весь трафик»). Если нужный сервис не запущен,
 * запрос завершается страницей 502 и никогда не уходит в обход.
 */
class AnonProxy(private val config: () -> AnonConfig, private val connectTimeoutMs: Int = 30_000) {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "anon-proxy").apply { isDaemon = true } }
    private val running = AtomicBoolean(false)
    @Volatile private var server: ServerSocket? = null

    val port: Int get() = server?.localPort ?: -1
    val isRunning: Boolean get() = running.get()

    @Synchronized
    fun start(): Int {
        server?.let { if (!it.isClosed) return it.localPort }
        val socket = ServerSocket(0, 64, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        server = socket
        running.set(true)
        pool.execute {
            while (running.get()) {
                val client = try { socket.accept() } catch (e: IOException) { break }
                pool.execute { handle(client) }
            }
        }
        return socket.localPort
    }

    @Synchronized
    fun stop() {
        running.set(false)
        try { server?.close() } catch (_: Exception) { }
        server = null
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            client.soTimeout = 30_000
            val input = BufferedInputStream(client.getInputStream(), 16 * 1024)
            val output = client.getOutputStream()
            val head = readHead(input) ?: return
            client.soTimeout = 0
            val request = ProxyRequest.parse(head) ?: run { reply(output, 400, "Некорректный запрос к прокси"); return }
            val cfg = config()
            val route = AnonRouting.routeOf(request.host, cfg.torForAll)
            val via = when (route) {
                AnonRouting.Route.DIRECT -> if (AnonRouting.isLocalHost(request.host)) null else cfg.direct
                AnonRouting.Route.TOR -> cfg.tor ?: run { reply(output, 502, "Tor не запущен. Откройте «Onion и I2P» и включите Tor."); return }
                AnonRouting.Route.I2P -> cfg.i2p ?: run { reply(output, 502, "I2P-прокси не настроен. Откройте «Onion и I2P» и укажите адрес роутера I2P."); return }
            }
            val up: Socket
            try {
                up = open(request, via)
                upstream = up
            } catch (e: Exception) {
                val label = when (route) { AnonRouting.Route.TOR -> "Tor"; AnonRouting.Route.I2P -> "I2P"; else -> "Сеть" }
                reply(output, 502, "$label: ${e.message ?: e.javaClass.simpleName}\n\nУзел: ${request.host}:${request.port}")
                return
            }
            if (request.connect) {
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray()); output.flush()
                pump(client, input, up, up.getInputStream(), output)
            } else {
                val forward = request.rewrite(originForm = via !is Upstream.Http)
                up.getOutputStream().apply { write(forward); flush() }
                pump(client, input, up, up.getInputStream(), output)
            }
        } catch (_: Exception) {
        } finally {
            try { upstream?.close() } catch (_: Exception) { }
            try { client.close() } catch (_: Exception) { }
        }
    }

    private fun open(request: ProxyRequest, via: Upstream?): Socket = when (via) {
        null -> Socket().apply {
            connect(InetSocketAddress(request.host, request.port), connectTimeoutMs)
            tcpNoDelay = true
        }
        is Upstream.Socks5 -> Socks5.connect(via.host, via.port, request.host, request.port, connectTimeoutMs)
        is Upstream.Http -> Socket().apply {
            connect(InetSocketAddress(via.host, via.port), connectTimeoutMs)
            tcpNoDelay = true
            if (request.connect) {
                soTimeout = connectTimeoutMs
                val out = getOutputStream()
                out.write("CONNECT ${request.host}:${request.port} HTTP/1.1\r\nHost: ${request.host}:${request.port}\r\n\r\n".toByteArray()); out.flush()
                val response = readHead(BufferedInputStream(getInputStream())) ?: throw IOException("I2P-прокси не ответил")
                val status = String(response, Charsets.ISO_8859_1).lineSequence().firstOrNull().orEmpty().split(' ').getOrNull(1)?.toIntOrNull() ?: 0
                if (status !in 200..299) { close(); throw IOException("I2P-прокси отказал в туннеле (HTTP $status)") }
                soTimeout = 0
            }
        }
    }

    private fun pump(client: Socket, clientIn: InputStream, upstream: Socket, upstreamIn: InputStream, clientOut: OutputStream) {
        val up = pool.submit {
            try {
                copy(clientIn, upstream.getOutputStream())
            } catch (_: Exception) {
            } finally {
                try { upstream.shutdownOutput() } catch (_: Exception) { }
            }
        }
        try { copy(upstreamIn, clientOut) } catch (_: Exception) { }
        try { client.shutdownOutput() } catch (_: Exception) { }
        up.cancel(true)
    }

    private fun copy(from: InputStream, to: OutputStream) {
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val n = from.read(buffer)
            if (n < 0) break
            to.write(buffer, 0, n); to.flush()
        }
    }

    private fun reply(output: OutputStream, status: Int, text: String) {
        val body = ("<html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>" +
            "<body style=\"font-family:sans-serif;padding:24px;color:#2b2622\"><h3>Не удалось открыть страницу</h3><p style=\"white-space:pre-wrap\">" +
            text.replace("&", "&amp;").replace("<", "&lt;") + "</p></body></html>").toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status ${if (status == 400) "Bad Request" else "Bad Gateway"}\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        try { output.write(head.toByteArray()); output.write(body); output.flush() } catch (_: Exception) { }
    }

    companion object {
        /** Читает заголовок запроса/ответа до пустой строки; null, если соединение закрыто или заголовок слишком большой. */
        fun readHead(input: InputStream): ByteArray? {
            val buffer = ByteArrayOutputStream()
            var state = 0
            while (buffer.size() < 64 * 1024) {
                val b = input.read()
                if (b < 0) return null
                buffer.write(b)
                state = when {
                    b == '\r'.code && (state == 0 || state == 2) -> state + 1
                    b == '\n'.code && (state == 1 || state == 3) -> state + 1
                    else -> 0
                }
                if (state == 4) return buffer.toByteArray()
            }
            return null
        }
    }
}

/** Разобранный заголовок прокси-запроса (CONNECT или абсолютный URI). */
class ProxyRequest private constructor(
    val method: String,
    val host: String,
    val port: Int,
    val connect: Boolean,
    val absolute: String,
    private val path: String,
    private val version: String,
    private val headers: List<String>,
) {
    /** Заголовок для отправки дальше: без прокси-полей, с Connection: close (кроме Upgrade/WebSocket). */
    fun rewrite(originForm: Boolean): ByteArray {
        val upgrade = headers.any { it.substringBefore(':').trim().equals("Upgrade", true) }
        val out = StringBuilder()
        out.append(method).append(' ').append(if (originForm) path else absolute).append(' ').append(version).append("\r\n")
        for (line in headers) {
            val name = line.substringBefore(':').trim().lowercase()
            if (name == "proxy-connection" || name == "proxy-authorization" || name == "keep-alive") continue
            if (name == "connection" && !upgrade) continue
            out.append(line).append("\r\n")
        }
        if (!upgrade) out.append("Connection: close\r\n")
        out.append("\r\n")
        return out.toString().toByteArray(Charsets.ISO_8859_1)
    }

    companion object {
        fun parse(head: ByteArray): ProxyRequest? {
            val lines = String(head, Charsets.ISO_8859_1).split("\r\n").filter { it.isNotEmpty() }
            if (lines.isEmpty()) return null
            val parts = lines[0].split(' ')
            if (parts.size < 3) return null
            val method = parts[0]
            val target = parts[1]
            val headers = lines.drop(1)
            if (method.equals("CONNECT", true)) {
                if (!target.contains(':')) return null
                val host = target.substringBeforeLast(':').removePrefix("[").removeSuffix("]").lowercase()
                val port = target.substringAfterLast(':').toIntOrNull() ?: return null
                if (host.isEmpty() || port !in 1..65535) return null
                return ProxyRequest(method, host, port, true, target, target, parts[2], headers)
            }
            val uri = try { URI(target) } catch (e: Exception) { return null }
            val scheme = uri.scheme?.lowercase() ?: return null
            if (scheme != "http") return null
            val host = uri.host?.lowercase()?.trimEnd('.') ?: return null
            val port = if (uri.port > 0) uri.port else 80
            val path = (uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (uri.rawQuery?.let { "?$it" } ?: "")
            return ProxyRequest(method, host, port, false, target, path, parts[2], headers)
        }
    }
}
