package pro.sitescope.app.keys

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pro.sitescope.app.IpDataStore
import pro.sitescope.app.XrayProfileParser
import pro.sitescope.app.vpn.Countries
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Proxy
import java.net.Socket
import java.net.URI
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager
import kotlin.coroutines.coroutineContext

/** Найденный ключ и результат его проверки. ms: -1 не проверен, -2 не отвечает, -3 UDP-протокол (отклик не проверяется). */
class KeyItem(val link: String, val name: String, val type: String, val host: String, val port: Int, val source: String, val foundAt: Long) {
    var ip = ""; var country = ""; var ms = -1L; var tls = 0; var checkedAt = 0L
    var exitIp = ""
    var anon = 0    // прокси: 0 неизвестно, 1 прозрачный, 2 анонимный, 3 элитный
    var https = 0   // прокси: 0 не проверялся, 1 HTTPS работает, 2 нет
    val id: String get() = type + "|" + host + ":" + port + "|" + Integer.toHexString(link.substringBefore('#').hashCode())
    val alive: Boolean get() = ms >= 0 && tls != 2
    val proxy: Boolean get() = type == "http" || type == "socks"
    val udp: Boolean get() = type == "hysteria2" || type == "tuic" || type == "wireguard"
    val v6: Boolean get() = host.contains(':')
}

object KeySettings {
    private fun p(c: Context) = c.getSharedPreferences("keyhub", Context.MODE_PRIVATE)
    fun pagesPerSite(c: Context) = p(c).getInt("pages", 10).coerceIn(1, 50)
    fun depth(c: Context) = p(c).getInt("depth", 1).coerceIn(0, 3)
    fun maxSites(c: Context) = p(c).getInt("sites", 15).coerceIn(1, 40)
    fun threads(c: Context) = p(c).getInt("threads", 24).coerceIn(4, 48)
    fun parallelSites(c: Context) = p(c).getInt("psites", 3).coerceIn(1, 4)
    fun keysLimit(c: Context) = p(c).getInt("klimit", 1500).coerceIn(200, 4000)
    fun timeoutMs(c: Context) = p(c).getInt("timeout", 3500).coerceIn(1500, 8000)
    fun tlsCheck(c: Context) = p(c).getBoolean("tls", true)
    fun useSearch(c: Context) = p(c).getBoolean("search", true)
    fun proxyLimit(c: Context) = p(c).getInt("plimit", 400).coerceIn(100, 2000)
    fun ipv6(c: Context) = p(c).getBoolean("ipv6", true)
    fun kinds(c: Context): Set<String> = p(c).getString("kinds", "xray")!!.split(',').filter { it.isNotBlank() }.toSet()
    fun lastQuery(c: Context) = p(c).getString("query", "")!!
    fun lastSources(c: Context) = p(c).getString("sources", "")!!
    fun put(c: Context, k: String, v: Int) { p(c).edit().putInt(k, v).apply() }
    fun put(c: Context, k: String, v: Boolean) { p(c).edit().putBoolean(k, v).apply() }
    fun put(c: Context, k: String, v: String) { p(c).edit().putString(k, v).apply() }
}

object KeyStore {
    private const val LIMIT = 4000
    private fun file(c: Context) = File(c.filesDir, "keyhub.json")

    fun load(c: Context): List<KeyItem> = try {
        val a = JSONArray(file(c).takeIf { it.exists() }?.readText() ?: "[]")
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            KeyItem(o.getString("link"), o.optString("name"), o.optString("type"), o.optString("host"), o.optInt("port"), o.optString("source"), o.optLong("foundAt")).also {
                it.ip = o.optString("ip"); it.country = o.optString("country"); it.ms = o.optLong("ms", -1); it.tls = o.optInt("tls"); it.checkedAt = o.optLong("checkedAt"); it.anon = o.optInt("anon"); it.https = o.optInt("https")
                it.exitIp = o.optString("exitIp")
                if (it.proxy && it.exitIp.isEmpty()) { it.country = ""; it.anon = 0 }
            }
        }
    } catch (e: Exception) { emptyList() }

    @Synchronized fun save(c: Context, items: List<KeyItem>) {
        val keep = items.sortedWith(compareByDescending<KeyItem> { it.alive }.thenByDescending { it.foundAt }).take(LIMIT)
        val a = JSONArray()
        keep.forEach { a.put(JSONObject().put("link", it.link).put("name", it.name).put("type", it.type).put("host", it.host).put("port", it.port).put("source", it.source).put("foundAt", it.foundAt)
            .put("ip", it.ip).put("country", it.country).put("ms", it.ms).put("tls", it.tls).put("checkedAt", it.checkedAt).put("anon", it.anon).put("https", it.https).put("exitIp", it.exitIp)) }
        try { file(c).writeText(a.toString()) } catch (e: Exception) { }
    }
    fun clear(c: Context) { file(c).delete() }
}

/** Поиск сайтов, обход страниц и извлечение ключей. */
/** Защита от нехватки памяти: при малом остатке куче сбор останавливается, а не роняет приложение. */
object KeyGuard {
    @Volatile var tripped = false
    fun low(): Boolean {
        val rt = Runtime.getRuntime()
        val free = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        if (free < 48L * 1024 * 1024) { tripped = true; return true }
        return false
    }
}

object KeyCrawler {
    private val cpuGate = kotlinx.coroutines.sync.Semaphore(2)
    internal const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val MAX_BYTES = 2_000_000
    private val KEY_RE = Regex("(?i)(?<![A-Za-z0-9])(vless|vmess|trojan|ss|hysteria2|hysteria|hy2|tuic|wireguard|wg|socks)://[^\\s\"'<>\\\\`|]{6,}")
    private val PROXY_RE = Regex("(?i)(?<![A-Za-z0-9])(socks5h?|socks4a?|https?)://((?:\\d{1,3}\\.){3}\\d{1,3}|\\[[0-9a-fA-F:]{3,39}\\]):(\\d{2,5})(?!\\d)")
    private val PLAIN_RE = Regex("(?<![\\w.:@/\\[=-])(?:((?:\\d{1,3}\\.){3}\\d{1,3})|\\[([0-9a-fA-F:]{3,39})\\]):(\\d{2,5})(?!\\d)")
    private val URL_RE = Regex("https?://[^\\s\"'<>\\\\)\\]]{12,}")
    private val SUB_HINT = listOf("subscri", "/sub", "sub?", "sub=", "sub/", "v2ray", "clash", "singbox", "sing-box", "xray", "vless", "configs", "token=")
    private val HP_RE = Regex("^[a-zA-Z0-9]+://(?:[^@/?#]*@)?(\\[[0-9a-fA-F:]+\\]|[^:/?#\\[\\]]+):(\\d{1,5})")
    private val HREF_RE = Regex("(?i)href\\s*=\\s*[\"']([^\"'#\\s]+)")
    private val SKIP_EXT = Regex("(?i)\\.(png|jpe?g|gif|svg|webp|ico|css|js|woff2?|ttf|pdf|zip|rar|7z|apk|exe|mp4|mp3|mkv)(\\?.*)?$")
    private val HINTS = listOf("vless", "vmess", "trojan", "vpn", "key", "kluch", "proxy", "config", "sub", "free", "v2ray", "xray", "raw", "list", "mirror", "subscription", "sing")
    private val BLOCKED_HOSTS = listOf("google.", "bing.com", "duckduckgo.", "yandex.", "facebook.", "instagram.", "youtube.", "youtu.be", "twitter.", "x.com", "wikipedia.", "microsoft.", "apple.com", "amazon.", "tiktok.", "vk.com", "reddit.com/r/", "play.google")

    fun fetch(url: String): String? {
        var c: HttpURLConnection? = null
        return try {
            val deadline = System.nanoTime() + 30_000_000_000L
            c = URL(url.replaceFirst("http://", "https://")).openConnection() as HttpURLConnection
            c.connectTimeout = 7000; c.readTimeout = 9000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept-Language", "en,ru;q=0.8")
            if (c.responseCode !in 200..299) { try { c.errorStream?.close() } catch (e: Exception) { }; return null }
            val ct = c.contentType.orEmpty().lowercase()
            if (ct.isNotEmpty() && !(ct.startsWith("text") || ct.contains("json") || ct.contains("xml") || ct.contains("octet") || ct.contains("yaml"))) return null
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { st ->
                val buf = ByteArray(16384); var n: Int
                while (out.size() < MAX_BYTES && System.nanoTime() < deadline) { n = st.read(buf); if (n <= 0) break; out.write(buf, 0, n) }
            }
            out.toString("UTF-8")
        } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null } finally { try { c?.disconnect() } catch (e: Exception) { } }
    }

    fun unescape(s: String): String {
        if (s.indexOf('&') < 0 && s.indexOf('\\') < 0) return s
        var t = s.replace("\\u0026", "&").replace("\\/", "/").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x2F;", "/").replace("&#43;", "+")
        t = Regex("&#x([0-9a-fA-F]+);").replace(t) { m -> try { String(Character.toChars(m.groupValues[1].toInt(16))) } catch (e: Exception) { "" } }
        t = Regex("&#(\\d+);").replace(t) { m -> try { String(Character.toChars(m.groupValues[1].toInt())) } catch (e: Exception) { "" } }
        return t
    }

    fun canon(scheme: String) = when (scheme.lowercase()) {
        "socks5", "socks5h", "socks" -> "socks"; "socks4", "socks4a" -> "socks4"; "http", "https" -> "http"
        "hy2", "hysteria", "hysteria2" -> "hysteria2"; "wg", "wireguard" -> "wireguard"; else -> scheme.lowercase()
    }

    /** Ключи и прокси из текста страницы, включая base64-подписки и списки ip:port. */
    fun extract(body: String, kinds: Set<String>, proxyTypes: Set<String>): List<String> {
        val found = LinkedHashSet<String>()
        fun scan(raw: String) {
            val text = unescape(raw)
            for (m in KEY_RE.findAll(text)) {
                if (canon(m.groupValues[1]) !in kinds) continue
                found += m.value.trimEnd('.', ',', ';', ')', ']', '}', '!', ':')
                if (found.size > 20000) return
            }
            for (m in PROXY_RE.findAll(text)) {
                val t = canon(m.groupValues[1]); if (t !in kinds) continue
                found += (if (t == "socks") "socks5" else "http") + "://" + m.groupValues[2] + ":" + m.groupValues[3]
                if (found.size > 20000) return
            }
            if (proxyTypes.isNotEmpty()) {
                val rest = PROXY_RE.replace(text, " ")
                for (m in PLAIN_RE.findAll(rest)) {
                    val host = m.groupValues[1].ifEmpty { "[" + m.groupValues[2] + "]" }
                    for (t in proxyTypes) found += (if (t == "socks") "socks5" else "http") + "://" + host + ":" + m.groupValues[3]
                    if (found.size > 20000) return
                }
            }
        }
        scan(body)
        val plain = body.trim()
        if (plain.length > 120 && !plain.contains("://") && plain.all { it.isLetterOrDigit() || it in "+/=_-\r\n " }) {
            val b64 = plain.filter { !it.isWhitespace() }.let { it.take(it.length / 4 * 4) }
            for (flags in intArrayOf(Base64.DEFAULT, Base64.URL_SAFE)) {
                try { scan(String(Base64.decode(b64, flags))); break } catch (e: Exception) { }
            }
        }
        return found.toList()
    }

    /** Ссылки на подписки и списки внутри страницы (допускаются другие сайты). */
    fun subLinks(body: String, base: String): List<String> = URL_RE.findAll(unescape(body)).map { it.value.trimEnd('.', ',', ';') }.filter { u ->
        val l = u.lowercase()
        u != base && !SKIP_EXT.containsMatchIn(l) && (SUB_HINT.any { l.contains(it) } || l.endsWith(".txt")) && BLOCKED_HOSTS.none { hostOf(u).contains(it.removeSuffix("/r/")) }
    }.distinct().take(8).toList()

    fun links(base: String, body: String): List<String> {
        val host = hostOf(base)
        val out = LinkedHashSet<String>()
        for (m in HREF_RE.findAll(body)) {
            val href = unescape(m.groupValues[1])
            if (href.startsWith("javascript:") || href.startsWith("mailto:") || href.startsWith("tg:")) continue
            val abs = try { URI(base).resolve(href).toString() } catch (e: Exception) { continue }
            if (!abs.startsWith("http") || SKIP_EXT.containsMatchIn(abs) || hostOf(abs) != host) continue
            out += abs.substringBefore('#')
        }
        return out.sortedByDescending { u -> HINTS.count { u.lowercase().contains(it) } }
    }

    fun hostOf(url: String) = try { URI(url).host.orEmpty().removePrefix("www.").lowercase() } catch (e: Exception) { "" }

    /** Обход одного сайта в ширину: не больше [pages] страниц и [depth] уровней ссылок. */
    suspend fun crawl(start: String, pages: Int, depth: Int, kinds: Set<String>, proxyTypes: Set<String>, followSubs: Boolean, subSeen: MutableSet<String>, onPage: (Int) -> Unit, onKeys: (List<String>) -> Unit) {
        val seen = HashSet<String>(); val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(start to 0); seen += start
        var count = 0
        while (queue.isNotEmpty() && count < pages) {
            coroutineContext.ensureActive()
            val (url, d) = queue.removeFirst()
            if (KeyGuard.low()) { System.gc(); kotlinx.coroutines.delay(400); if (KeyGuard.low()) return }
            val body = withContext(Dispatchers.IO) { fetch(url) } ?: continue
            count++; onPage(count)
            val keys = cpuGate.withPermit { withContext(Dispatchers.Default) { extract(body, kinds, proxyTypes) } }
            if (keys.isNotEmpty()) onKeys(keys)
            if (followSubs) for (sub in subLinks(body, url)) {
                if (!subSeen.add(sub)) continue
                val text = withContext(Dispatchers.IO) { fetch(sub) } ?: continue
                val more = cpuGate.withPermit { withContext(Dispatchers.Default) { extract(text, kinds, proxyTypes) } }
                if (more.isNotEmpty()) onKeys(more)
                kotlinx.coroutines.delay(150)
            }
            if (d < depth) for (l in links(url, body)) if (seen.add(l)) queue.add(l to d + 1)
            kotlinx.coroutines.delay(250)
        }
    }

    // ---------- поиск сайтов ----------
    private fun decodeBing(u: String): String {
        if (!u.contains("bing.com/ck/a")) return u
        val enc = Regex("[?&]u=([^&]+)").find(u)?.groupValues?.get(1) ?: return u
        return try { String(Base64.decode(URLDecoder.decode(enc, "UTF-8").removePrefix("a1"), Base64.URL_SAFE or Base64.NO_PADDING)) } catch (e: Exception) { u }
    }

    fun search(query: String, limit: Int, kinds: Set<String> = emptySet()): List<String> {
        val variants = ArrayList<String>()
        if (query.isNotBlank()) variants += listOf(query, "$query free keys list", "$query подписка бесплатно ключи", "$query github raw subscription")
        if ("socks" in kinds) variants += "free socks5 proxy list ip port"
        if ("http" in kinds) variants += "free https proxy list ip port elite"
        if (kinds.any { it in KeySources.UDP_TYPES }) variants += "hysteria2 tuic free config subscription"
        if (kinds.any { it in KeySources.XRAY_TYPES }) variants += "free vless vmess trojan subscription list"
        val out = LinkedHashSet<String>()
        for (q in variants) {
            val e = URLEncoder.encode(q, "UTF-8")
            fetch("https://html.duckduckgo.com/html/?q=$e")?.let { html ->
                Regex("uddg=([^&\"']+)").findAll(html).forEach { m -> try { out += URLDecoder.decode(m.groupValues[1], "UTF-8") } catch (_: Exception) { } }
            }
            fetch("https://www.bing.com/search?q=$e&count=30&setlang=en")?.let { html ->
                Regex("(?is)<li class=\"b_algo\"[^>]*>.*?<a[^>]+href=\"([^\"]+)\"").findAll(html).forEach { m -> out += decodeBing(unescape(m.groupValues[1])) }
            }
            if (out.size >= limit * 3) break
        }
        return out.filter { u -> u.startsWith("http") && BLOCKED_HOSTS.none { hostOf(u).contains(it.removeSuffix("/r/")) } }
    }

    fun parse(link: String, source: String, allowV6: Boolean = true): KeyItem? {
        try {
            val type = canon(link.substringBefore("://"))
            val now = System.currentTimeMillis()
            val item: KeyItem? = when (type) {
                "socks4" -> null
                "socks", "http", "hysteria2", "tuic", "wireguard" -> {
                    val m = HP_RE.find(link) ?: return null
                    val host = m.groupValues[1].trim('[', ']'); val port = m.groupValues[2].toIntOrNull() ?: 0
                    val name = link.substringAfter('#', "").let { try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it } }.ifBlank { host }
                    val canonical = if ((type == "socks" || type == "http") && !m.value.contains('@')) (if (type == "socks") "socks5" else "http") + "://" + (if (host.contains(':')) "[$host]" else host) + ":" + port else link
                    if (host.isEmpty() || port !in 1..65535 || isPrivate(host)) null else KeyItem(canonical, name, type, host, port, source, now)
                }
                else -> {
                    val p = XrayProfileParser.parse(link)
                    val ep = p.endpoint
                    val host = ep.substringBeforeLast(':', ep).trim('[', ']'); val port = ep.substringAfterLast(':', "").toIntOrNull() ?: 0
                    if (host.isEmpty() || port !in 1..65535 || isPrivate(host)) null else KeyItem(link, p.name.ifBlank { host }, type, host, port, source, now)
                }
            }
            return if (item != null && !allowV6 && item.v6) null else item
        } catch (e: Exception) { return null }
    }

    private fun isPrivate(h: String) = h == "localhost" || h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") || h == "0.0.0.0" ||
        Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(h) || h.endsWith(".local") || h.contains("example.") ||
        h == "::1" || h.startsWith("fe80") || h.startsWith("fc") && h.contains(':') || h.startsWith("fd") && h.contains(':') ||
        Regex("^(0|127|169\\.254|22[4-9]|23\\d|24\\d|25[0-5])\\.").containsMatchIn(h)
}

/** Проверка отклика: TCP-подключение (медиана трёх замеров) и TLS-рукопожатие для ключей с TLS/Reality. */
object KeyChecker {
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun param(link: String, key: String): String = Regex("[?&]$key=([^&#]*)").find(link)?.groupValues?.get(1)?.let { try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it } }.orEmpty()

    private fun tlsInfo(item: KeyItem): String? {   // имя для SNI или null, если TLS не используется
        val l = item.link
        return when (item.type) {
            "vless" -> if (param(l, "security").let { it == "tls" || it == "reality" }) param(l, "sni").ifEmpty { param(l, "host") }.ifEmpty { item.host } else null
            "trojan" -> if (param(l, "security") != "none") param(l, "sni").ifEmpty { param(l, "peer") }.ifEmpty { item.host } else null
            "vmess" -> try {
                val j = JSONObject(String(Base64.decode(l.substringAfter("://").substringBefore('#'), Base64.DEFAULT)))
                if (j.optString("tls") == "tls") j.optString("sni").ifEmpty { j.optString("host") }.ifEmpty { item.host } else null
            } catch (e: Exception) { null }
            else -> null
        }
    }

    suspend fun check(item: KeyItem, timeout: Int, tls: Boolean, referenceIp: String) = withContext(Dispatchers.IO) {
        when {
            item.proxy -> checkProxy(item, timeout, referenceIp)
            item.udp -> checkUdp(item)
            else -> checkTcp(item, timeout, tls)
        }
    }

    private fun checkUdp(item: KeyItem) {
        item.checkedAt = System.currentTimeMillis()
        val addr = resolve(item.host)
        if (addr == null) { item.ms = -2; return }
        item.ip = addr.hostAddress.orEmpty(); item.ms = -3
    }

    private val dnsGate = java.util.concurrent.Semaphore(12)
    private val IP_RE = Regex("^[0-9.]+$|:")
    private fun resolve(host: String): InetAddress? {
        if (IP_RE.containsMatchIn(host)) return try { InetAddress.getByName(host) } catch (e: Exception) { null }
        dnsGate.acquire()
        try { return InetAddress.getByName(host) } catch (e: Exception) { return null } finally { dnsGate.release() }
    }

    private const val EXIT_LOOKUP = "https://api.country.is/"

    private fun numericIp(value: String): String {
        val ipv4 = value.split('.')
        if (ipv4.size == 4 && ipv4.all { it.toIntOrNull()?.let { n -> n in 0..255 } == true && it.all(Char::isDigit) }) {
            return ipv4.joinToString(".") { it.toInt().toString() }
        }
        if (!value.contains(':') || !value.matches(Regex("[0-9a-fA-F:.]+"))) return ""
        return runCatching { InetAddress.getByName(value).hostAddress.orEmpty() }.getOrDefault("")
    }

    private fun exitInfo(response: Pair<Int, String>?): Pair<String, String>? {
        if (response?.first != 200) return null
        return runCatching {
            val payload = JSONObject(response.second)
            val ip = numericIp(payload.optString("ip"))
            val code = payload.optString("country").uppercase(java.util.Locale.ROOT)
            ip to code.takeIf { it.matches(Regex("[A-Z]{2}")) }.orEmpty()
        }.getOrNull()?.takeIf { it.first.isNotEmpty() }
    }

    suspend fun referenceIp(timeout: Int): String = withContext(Dispatchers.IO) {
        exitInfo(viaProxy(EXIT_LOOKUP, Proxy.NO_PROXY, timeout))?.first.orEmpty()
    }

    private fun viaProxy(url: String, proxy: Proxy, timeout: Int): Pair<Int, String>? = try {
        val c = URL(url).openConnection(proxy) as HttpURLConnection
        c.connectTimeout = timeout; c.readTimeout = timeout; c.instanceFollowRedirects = false; c.useCaches = false
        c.setRequestProperty("User-Agent", KeyCrawler.UA); c.setRequestProperty("Connection", "close")
        val code = c.responseCode
        var body = ""
        if (code in 200..299) {
            val deadline = System.nanoTime() + timeout * 2_000_000L
            c.inputStream.use { st ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(2048); var n: Int
                while (out.size() < 8000 && System.nanoTime() < deadline) { n = st.read(buf); if (n <= 0) break; out.write(buf, 0, n) }
                body = out.toString("UTF-8")
            }
        } else try { c.errorStream?.close() } catch (e: Exception) { }
        c.disconnect(); code to body
    } catch (e: Exception) { null }

    /** Check the HTTPS path before classifying the result as usable for a VPN. */
    private fun checkProxy(item: KeyItem, timeout: Int, referenceIp: String) {
        item.checkedAt = System.currentTimeMillis(); item.anon = 0; item.https = 0
        item.country = ""; item.exitIp = ""; item.ip = ""; item.tls = 0
        val addr = resolve(item.host)
        if (addr == null) { item.ms = -2; return }
        item.ip = addr.hostAddress.orEmpty()
        val proxy = Proxy(if (item.type == "socks") Proxy.Type.SOCKS else Proxy.Type.HTTP, InetSocketAddress(addr, item.port))
        val t0 = System.nanoTime()
        val exit = exitInfo(viaProxy(EXIT_LOOKUP, proxy, timeout))
        val https = exit != null || viaProxy("https://www.gstatic.com/generate_204", proxy, timeout)?.first == 204
        item.https = if (https) 1 else 2
        if (!https) {
            val plainStart = System.nanoTime()
            val r = viaProxy("http://httpbin.org/ip", proxy, timeout)
            val origin = runCatching {
                if (r?.first == 200) numericIp(JSONObject(r.second).optString("origin").substringBefore(',').trim()) else ""
            }.getOrDefault("")
            item.ms = if (origin.isNotEmpty()) (System.nanoTime() - plainStart) / 1_000_000 else -2L
            return
        }
        item.ms = (System.nanoTime() - t0) / 1_000_000
        if (exit == null) return
        item.exitIp = exit.first
        item.country = exit.second
        if (referenceIp.isEmpty()) return
        if (exit.first == referenceIp) { item.anon = 1; return }
        val headers = viaProxy("http://httpbin.org/headers", proxy, timeout)
        if (headers?.first == 200) {
            val payload = runCatching { JSONObject(headers.second).optJSONObject("headers") }.getOrNull() ?: return
            val names = payload.keys().asSequence().map { it.lowercase() }.toList()
            item.anon = when {
                payload.toString().contains(referenceIp) -> 1
                names.any { it in listOf("via", "forwarded", "x-real-ip", "proxy-connection", "client-ip", "x-proxy") || it.startsWith("x-forwarded-") } -> 2
                else -> 3
            }
        }
    }

    private fun checkTcp(item: KeyItem, timeout: Int, tls: Boolean) {
        val addr = resolve(item.host)
        if (addr == null) { item.ms = -2; item.checkedAt = System.currentTimeMillis(); return }
        item.ip = addr.hostAddress.orEmpty()
        val samples = ArrayList<Long>()
        repeat(3) {
            try {
                val t0 = System.nanoTime()
                Socket().use { it.connect(InetSocketAddress(addr, item.port), timeout) }
                samples += (System.nanoTime() - t0) / 1_000_000
            } catch (e: Exception) { }
        }
        item.ms = if (samples.isEmpty()) -2 else samples.sorted()[samples.size / 2]
        item.checkedAt = System.currentTimeMillis()
        item.tls = 0
        if (tls && item.ms >= 0) {
            val sni = tlsInfo(item)
            if (sni != null) item.tls = try {
                val ctx = SSLContext.getInstance("TLS"); ctx.init(null, arrayOf(trustAll), SecureRandom())
                val raw = Socket(); raw.connect(InetSocketAddress(addr, item.port), timeout); raw.soTimeout = timeout
                (ctx.socketFactory.createSocket(raw, sni, item.port, true) as SSLSocket).use { s ->
                    if (!Regex("^[0-9.]+$|:").containsMatchIn(sni)) { val prm = s.sslParameters; prm.serverNames = listOf(SNIHostName(sni)); s.sslParameters = prm }
                    s.startHandshake()
                }
                1
            } catch (e: Exception) { 2 }
        }
    }

    /** Страна: по имени ключа (для доменов за CDN), иначе по IP: локальная база DB-IP, затем онлайн. */
    suspend fun geo(context: Context, items: List<KeyItem>) = withContext(Dispatchers.IO) {
        val db = try { IpDataStore(context) } catch (e: Throwable) { null }
        val cache = HashMap<String, String>()
        try {
            for (it in items) {
                if (it.proxy || it.country.isNotEmpty()) continue
                val isIp = Regex("^[0-9.]+$").matches(it.host)
                val byName = if (isIp) "" else Countries.of(it.name, "")
                if (byName.isNotEmpty()) { it.country = byName; continue }
                if (it.ip.isEmpty()) continue
                it.country = cache.getOrPut(it.ip) {
                    var code = try { db?.lookupGeo(it.ip)?.countryCode.orEmpty() } catch (e: Throwable) { "" }
                    if (code.length != 2 && cache.size < 300) code = try { JSONObject(KeyCrawler.fetch("https://api.country.is/" + it.ip) ?: "{}").optString("country") } catch (e: Exception) { "" }
                    if (code.length != 2) code = Countries.of(it.name, it.host)
                    code.uppercase()
                }
            }
        } finally { try { db?.close() } catch (e: Throwable) { } }
    }
}

/** Экспорт: понятные имена, подписка base64 и локальная подписка по Wi-Fi. */
object KeyExport {
    fun label(i: KeyItem): String = (if (i.country.length == 2) Countries.flag(i.country) + " " + i.country + " · " else "") + (if (i.ms >= 0) "${i.ms} мс · " else "") + (if (i.proxy && i.anon == 3) "elite · " else "") + i.host

    fun labeled(i: KeyItem): String = try {
        if (i.type == "vmess") {
            val j = JSONObject(String(Base64.decode(i.link.substringAfter("://").substringBefore('#'), Base64.DEFAULT)))
            j.put("ps", label(i)); "vmess://" + Base64.encodeToString(j.toString().toByteArray(), Base64.NO_WRAP)
        } else i.link.substringBefore('#') + "#" + URLEncoder.encode(label(i), "UTF-8").replace("+", "%20")
    } catch (e: Exception) { i.link }

    fun text(items: List<KeyItem>) = items.joinToString("\n") { k -> labeled(k).let { if (k.type == "socks" && it.startsWith("socks5://")) "socks://" + it.removePrefix("socks5://") else it } }
    /** Простой список host:port для ручного добавления прокси. */
    fun hostPort(items: List<KeyItem>) = items.filter { it.proxy }.joinToString("\n") { (if (it.host.contains(':')) "[" + it.host + "]" else it.host) + ":" + it.port }
    /** HTTP uses CONNECT in Xray. HTTPS on a result denotes a successful HTTPS target check. */
    fun profileLink(key: KeyItem): String? {
        if (key.udp) return null
        val link = labeled(key)
        val body = link.substringAfter("://", "").takeIf(String::isNotEmpty) ?: return null
        return when (key.type) {
            "http" -> "httpproxy://$body"
            "socks" -> (if (link.startsWith("socks://", ignoreCase = true)) "socks://" else "socks5://") + body
            "vless", "vmess", "trojan", "ss" -> link
            else -> null
        }
    }

    fun canConnect(key: KeyItem): Boolean = key.alive && profileLink(key) != null &&
        (!key.proxy || key.https == 1)

    fun subscription(items: List<KeyItem>) = Base64.encodeToString(text(items).toByteArray(), Base64.NO_WRAP)
}

/** Мини-сервер подписки в локальной сети: v2rayNG и аналоги подписываются на http://адрес:порт/sub/токен. */
object KeySubServer {
    private var server: ServerSocket? = null
    @Volatile var body = ""
    var token = ""; var port = 0
    val running get() = server != null

    @Synchronized fun start(content: String): Boolean {
        body = content
        if (server != null) return true
        return try {
            val s = try { ServerSocket(10899) } catch (e: Exception) { ServerSocket(0) }
            server = s; port = s.localPort
            token = (1..10).map { "abcdefghjkmnpqrstuvwxyz23456789"[SecureRandom().nextInt(31)] }.joinToString("")
            Thread {
                while (!s.isClosed) {
                    try {
                        val c = s.accept(); c.soTimeout = 4000
                        Thread {
                            try {
                                c.use { sock ->
                                    val line = sock.getInputStream().bufferedReader().readLine().orEmpty()
                                    val ok = line.startsWith("GET /sub/$token")
                                    val data = (if (ok) body else "not found").toByteArray()
                                    sock.getOutputStream().apply {
                                        write(("HTTP/1.1 ${if (ok) "200 OK" else "404 Not Found"}\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n").toByteArray())
                                        write(data); flush()
                                    }
                                }
                            } catch (e: Exception) { }
                        }.start()
                    } catch (e: Exception) { }
                }
            }.apply { isDaemon = true; name = "KeySubServer" }.start()
            true
        } catch (e: Exception) { server = null; false }
    }

    @Synchronized fun stop() { try { server?.close() } catch (e: Exception) { }; server = null }
}
