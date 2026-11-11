package pro.sitescope.app.vpn

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import org.json.JSONArray
import org.json.JSONObject
import pro.sitescope.app.VpnDiagnostics
import pro.sitescope.app.XrayConfigBuilder
import java.io.FileDescriptor
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.net.URL
import java.net.URLEncoder

/** Настройки VPN, которые можно менять на ходу. */
object VpnSettings {
    const val PREFS = "vpn_settings"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** DNS-серверы внутри туннеля (пусто — стандартные). */
    @JvmStatic fun dnsServers(context: Context): List<String> =
        (prefs(context).getString("dns_servers", "") ?: "").split(',', ' ', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }
    @JvmStatic fun setDnsServers(context: Context, value: String) = prefs(context).edit().putString("dns_servers", value.trim()).apply()

    /** DNS-сервер для режима «только свои адреса» (пусто — тот, что выдала сеть). */
    @JvmStatic fun basicUpstream(context: Context): String = prefs(context).getString("basic_upstream", "") ?: ""
    @JvmStatic fun setBasicUpstream(context: Context, value: String) = prefs(context).edit().putString("basic_upstream", value.trim()).apply()

    /** Проверки самого приложения (сайт, DNS, IP, ТВ) идут через сервер VPN. */
    @JvmStatic fun appThroughTunnel(context: Context): Boolean = prefs(context).getBoolean("app_through_tunnel", true)
    @JvmStatic fun setAppThroughTunnel(context: Context, value: Boolean) = prefs(context).edit().putBoolean("app_through_tunnel", value).apply()

    /** Режим «без сервера»: весь трафик идёт как обычно, но проходит через приложение — для записи и своих адресов. */
    @JvmStatic fun directMode(context: Context): Boolean = prefs(context).getBoolean("direct_mode", false)
    @JvmStatic fun setDirectMode(context: Context, value: Boolean) = prefs(context).edit().putBoolean("direct_mode", value).apply()

    /** Расшифровка TLS при записи трафика. */
    @JvmStatic fun mitm(context: Context): Boolean = prefs(context).getBoolean("mitm", false)
    @JvmStatic fun setMitm(context: Context, value: Boolean) = prefs(context).edit().putBoolean("mitm", value).apply()

    /** Запрет QUIC и закрытого DNS, чтобы приложения использовали обычный TLS, который можно расшифровать. */
    @JvmStatic fun blockQuic(context: Context): Boolean = prefs(context).getBoolean("block_quic", true)
    @JvmStatic fun setBlockQuic(context: Context, value: Boolean) = prefs(context).edit().putBoolean("block_quic", value).apply()

    /** Приложения, для которых действует VPN и снимается дамп (пусто — все). */
    @JvmStatic fun targetApps(context: Context): Set<String> = prefs(context).getStringSet("target_apps", emptySet())?.toSet() ?: emptySet()
    @JvmStatic fun setTargetApps(context: Context, value: Set<String>) = prefs(context).edit().putStringSet("target_apps", HashSet(value)).apply()

    /** Подставляет выбранные DNS-серверы в готовую конфигурацию Xray. */
    @JvmStatic fun applyDns(context: Context, json: String): String {
        val servers = dnsServers(context)
        if (servers.isEmpty()) return json
        return try {
            val root = JSONObject(json)
            val dns = root.optJSONObject("dns") ?: JSONObject()
            val array = JSONArray(); servers.forEach { array.put(it) }
            dns.put("servers", array); root.put("dns", dns); root.toString()
        } catch (e: Exception) { json }
    }
}

/**
 * Пока VPN через сервер активен, веб-запросы самого приложения направляются в локальный вход туннеля.
 * Иначе приложение, исключённое из VPN ради защиты от петли, ходило бы в сеть напрямую и не могло бы
 * проверить сайт, недоступный без VPN.
 */
object TunnelProxy {
    @Volatile private var installed: Selector? = null
    @Volatile var active = false; private set

    private class Selector(val previous: ProxySelector?, port: Int) : ProxySelector() {
        val socks = listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        val direct = listOf(Proxy.NO_PROXY)
        override fun select(uri: URI?): List<Proxy> {
            val scheme = uri?.scheme?.lowercase() ?: return direct
            if (scheme != "http" && scheme != "https") return direct
            val host = uri.host?.lowercase() ?: return direct
            return if (isLocalHost(host)) direct else socks
        }
        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) { }
    }

    fun isLocalHost(host: String): Boolean {
        val h = host.trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || !h.contains('.') && !h.contains(':')) return true
        if (h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") || h.startsWith("169.254.") || h == "::1" || h.startsWith("fe80:") || h.startsWith("fc") || h.startsWith("fd")) return true
        if (h.startsWith("172.")) { val second = h.split('.').getOrNull(1)?.toIntOrNull() ?: return false; return second in 16..31 }
        return false
    }

    @JvmStatic @Synchronized fun install(context: Context) {
        if (installed != null) return
        val selector = Selector(ProxySelector.getDefault(), XrayConfigBuilder.SOCKS_PORT)
        ProxySelector.setDefault(selector); installed = selector; active = true
        VpnDiagnostics.log(context, "APP_PROXY_ON", "app web requests go through the tunnel")
    }

    @JvmStatic @Synchronized fun remove(context: Context) {
        val selector = installed ?: return
        if (ProxySelector.getDefault() === selector) ProxySelector.setDefault(selector.previous)
        installed = null; active = false
        VpnDiagnostics.log(context, "APP_PROXY_OFF", "app web requests are direct again")
    }
}

/**
 * «Тройник» между системным VPN-интерфейсом и туннелем: каждый пакет проходит через приложение,
 * чтобы его можно было записать. Нативному туннелю вместо интерфейса отдаётся один конец пары сокетов,
 * которая сохраняет границы пакетов.
 */
class TunTee(private val tun: ParcelFileDescriptor, private val context: Context) {
    private val ours = FileDescriptor()
    private val theirs = FileDescriptor()
    @Volatile private var running = false
    private var up: Thread? = null
    private var down: Thread? = null
    lateinit var tunnelSide: ParcelFileDescriptor; private set

    fun start() {
        Os.socketpair(OsConstants.AF_UNIX, OsConstants.SOCK_DGRAM, 0, ours, theirs)
        for (fd in arrayOf(ours, theirs)) {
            try { Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_SNDBUF, 1 shl 20) } catch (e: Exception) { }
            try { Os.setsockoptInt(fd, OsConstants.SOL_SOCKET, OsConstants.SO_RCVBUF, 1 shl 20) } catch (e: Exception) { }
        }
        tunnelSide = ParcelFileDescriptor.dup(theirs)
        running = true
        val tunFd = tun.fileDescriptor
        up = Thread({ pump(tunFd, ours) }, "SiteScopeTeeUp").apply { start() }
        down = Thread({ pump(ours, tunFd) }, "SiteScopeTeeDown").apply { start() }
    }

    private fun wait(fd: FileDescriptor, events: Int): Boolean {
        val p = StructPollfd(); p.fd = fd; p.events = events.toShort()
        return try { Os.poll(arrayOf(p), 400) > 0 } catch (e: ErrnoException) { false }
    }

    private fun pump(from: FileDescriptor, to: FileDescriptor) {
        val buffer = ByteArray(65536)
        try {
            while (running) {
                if (!wait(from, OsConstants.POLLIN)) continue
                val n = try { Os.read(from, buffer, 0, buffer.size) } catch (e: ErrnoException) { if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) continue else throw e }
                if (n <= 0) continue
                CaptureHub.tap(buffer, n)
                var attempts = 0
                while (running) {
                    try { Os.write(to, buffer, 0, n); break } catch (e: ErrnoException) {
                        if ((e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR || e.errno == OsConstants.ENOBUFS) && ++attempts < 50) wait(to, OsConstants.POLLOUT) else break
                    }
                }
            }
        } catch (e: Throwable) {
            if (running) VpnDiagnostics.log(context, "CAPTURE_PUMP_ERROR", e.message ?: e.javaClass.simpleName)
        }
    }

    fun stop() {
        running = false
        try { up?.join(1200) } catch (e: Exception) { }
        try { down?.join(1200) } catch (e: Exception) { }
        try { tunnelSide.close() } catch (e: Exception) { }
        try { Os.close(ours) } catch (e: Exception) { }
        try { Os.close(theirs) } catch (e: Exception) { }
    }
}

/** DNS-запрос через защищённый канал (DoH) — напрямую или через сервер VPN. */
object DnsTool {
    class Record(val type: String, val name: String, val value: String, val ttl: Long)
    class Answer(val records: List<Record>, val status: String, val via: String, val millis: Long, val error: String)

    private val typeNames = mapOf(1 to "A", 2 to "NS", 5 to "CNAME", 6 to "SOA", 15 to "MX", 16 to "TXT", 28 to "AAAA", 33 to "SRV", 257 to "CAA")
    val resolvers = listOf("Cloudflare" to "https://1.1.1.1/dns-query", "Google" to "https://8.8.8.8/resolve")

    fun query(name: String, type: String, resolverUrl: String, throughTunnel: Boolean): Answer {
        val started = System.currentTimeMillis()
        val via = if (throughTunnel) "через сервер VPN" else "напрямую"
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(resolverUrl + "?name=" + URLEncoder.encode(name.trim().trimEnd('.'), "UTF-8") + "&type=" + type)
            val proxy = if (throughTunnel) Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", XrayConfigBuilder.SOCKS_PORT)) else Proxy.NO_PROXY
            connection = url.openConnection(proxy) as HttpURLConnection
            connection.connectTimeout = 8000; connection.readTimeout = 8000
            connection.setRequestProperty("Accept", "application/dns-json")
            val code = connection.responseCode
            if (code !in 200..299) return Answer(emptyList(), "", via, System.currentTimeMillis() - started, "Сервер DNS ответил $code")
            val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val status = when (root.optInt("Status", -1)) { 0 -> "Найдено"; 3 -> "Такого имени нет"; 2 -> "Сервер не смог ответить"; 5 -> "Отказано"; else -> "Код " + root.optInt("Status") }
            val list = ArrayList<Record>()
            val answers = root.optJSONArray("Answer")
            if (answers != null) for (i in 0 until answers.length()) {
                val a = answers.getJSONObject(i)
                list.add(Record(typeNames[a.optInt("type")] ?: a.optInt("type").toString(), a.optString("name").trimEnd('.'), a.optString("data"), a.optLong("TTL")))
            }
            Answer(list, status, via, System.currentTimeMillis() - started, "")
        } catch (e: Exception) {
            Answer(emptyList(), "", via, System.currentTimeMillis() - started, e.message ?: e.javaClass.simpleName)
        } finally { connection?.disconnect() }
    }
}
