package pro.sitescope.app.dpi

import pro.sitescope.app.anon.Socks5
import java.net.Socket
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Проверка доступности сайта через локальный SOCKS5 byedpi: имя узла отдаётся прокси (DNS провайдера не используется). */
object DpiProbe {
    class Result(val host: String, val ok: Boolean, val ms: Long, val bytes: Int, val reason: String)

    /** HTTPS: настоящий TLS с проверкой сертификата и имени, затем GET и чтение до [readLimit] байт — так видно и подмену, и обрыв потока. */
    fun https(host: String, socksPort: Int, timeoutMs: Int, readLimit: Int = 64 * 1024): Result {
        val t0 = System.currentTimeMillis()
        var raw: Socket? = null
        try {
            val plain: Socket = Socks5.connect("127.0.0.1", socksPort, host, 443, timeoutMs)
            raw = plain
            plain.soTimeout = timeoutMs
            val ssl = ((SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, 443, true) as SSLSocket)
            ssl.soTimeout = timeoutMs
            ssl.startHandshake()
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.session)) return fail(host, t0, "сертификат не для этого имени")
            val out = ssl.outputStream
            out.write(("GET / HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36\r\nAccept: */*\r\nConnection: close\r\n\r\n").toByteArray())
            out.flush()
            val input = ssl.inputStream
            val head = StringBuilder()
            var total = 0
            val buf = ByteArray(8192)
            var headDone = false
            var headBytes = 0
            while (total < readLimit) {
                val n = try { input.read(buf) } catch (e: java.net.SocketTimeoutException) { -2 }
                if (n < 0) { if (n == -2 && total == 0) return fail(host, t0, "нет ответа"); break }
                if (!headDone) {
                    head.append(String(buf, 0, n, Charsets.ISO_8859_1))
                    val i = head.indexOf("\r\n\r\n")
                    if (i >= 0) { headDone = true; headBytes = i + 4; total = head.length - headBytes } else if (head.length > 16384) break
                } else total += n
                if (headDone && total >= 2048 && readLimit <= 4096) break
            }
            val status = head.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: return fail(host, t0, "не HTTP-ответ")
            if (status !in 100..599) return fail(host, t0, "код $status")
            val declared = Regex("(?i)content-length:\\s*(\\d+)").find(head)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
            val need = if (declared > 0) minOf(declared, readLimit.toLong()) else 0L
            // Тело оборвано раньше объявленной длины — признак блокировки/ограничения скорости.
            if (need > 0 && total < need && total < 16 * 1024) return fail(host, t0, "поток оборван на $total из $need Б")
            return Result(host, true, System.currentTimeMillis() - t0, total, "HTTP $status")
        } catch (e: Exception) {
            return fail(host, t0, (e.message ?: e.javaClass.simpleName).take(80))
        } finally {
            try { raw?.close() } catch (_: Exception) { }
        }
    }

    /** HTTP на порту 80: подходит как грубая проверка для сайтов без TLS. */
    fun http(host: String, socksPort: Int, timeoutMs: Int): Result {
        val t0 = System.currentTimeMillis()
        var s: Socket? = null
        return try {
            s = Socks5.connect("127.0.0.1", socksPort, host, 80, timeoutMs)
            s.soTimeout = timeoutMs
            s.getOutputStream().apply { write("GET / HTTP/1.1\r\nHost: $host\r\nUser-Agent: Mozilla/5.0\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
            val line = s.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine() ?: return fail(host, t0, "нет ответа")
            val status = line.split(' ').getOrNull(1)?.toIntOrNull()
            if (status == null) fail(host, t0, "не HTTP-ответ") else Result(host, true, System.currentTimeMillis() - t0, 0, "HTTP $status")
        } catch (e: Exception) {
            fail(host, t0, (e.message ?: e.javaClass.simpleName).take(80))
        } finally { try { s?.close() } catch (_: Exception) { } }
    }

    private fun fail(host: String, t0: Long, why: String) = Result(host, false, System.currentTimeMillis() - t0, 0, why)
}
