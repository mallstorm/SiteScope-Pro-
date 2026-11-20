package pro.sitescope.app.keys

import android.annotation.SuppressLint
import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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

/** Найденный ключ и результат его проверки. ms: -1 не проверен, -2 не отвечает. */
class KeyItem(val link: String, val name: String, val type: String, val host: String, val port: Int, val source: String, val foundAt: Long) {
    var ip = ""; var country = ""; var ms = -1L; var tls = 0; var checkedAt = 0L
    val id: String get() = type + "|" + host + ":" + port + "|" + Integer.toHexString(link.substringBefore('#').hashCode())
    val alive: Boolean get() = ms >= 0 && tls != 2
}

object KeySettings {
    private fun p(c: Context) = c.getSharedPreferences("keyhub", Context.MODE_PRIVATE)
    fun pagesPerSite(c: Context) = p(c).getInt("pages", 10).coerceIn(1, 50)
    fun depth(c: Context) = p(c).getInt("depth", 1).coerceIn(0, 3)
    fun maxSites(c: Context) = p(c).getInt("sites", 15).coerceIn(1, 40)
    fun threads(c: Context) = p(c).getInt("threads", 24).coerceIn(4, 64)
    fun timeoutMs(c: Context) = p(c).getInt("timeout", 3500).coerceIn(1500, 8000)
    fun tlsCheck(c: Context) = p(c).getBoolean("tls", true)
    fun useSearch(c: Context) = p(c).getBoolean("search", true)
    fun lastQuery(c: Context) = p(c).getString("query", "vless")!!
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
                it.ip = o.optString("ip"); it.country = o.optString("country"); it.ms = o.optLong("ms", -1); it.tls = o.optInt("tls"); it.checkedAt = o.optLong("checkedAt")
            }
        }
    } catch (e: Exception) { emptyList() }

    fun save(c: Context, items: List<KeyItem>) {
        val keep = items.sortedWith(compareByDescending<KeyItem> { it.alive }.thenByDescending { it.foundAt }).take(LIMIT)
        val a = JSONArray()
        keep.forEach { a.put(JSONObject().put("link", it.link).put("name", it.name).put("type", it.type).put("host", it.host).put("port", it.port).put("source", it.source).put("foundAt", it.foundAt)
            .put("ip", it.ip).put("country", it.country).put("ms", it.ms).put("tls", it.tls).put("checkedAt", it.checkedAt)) }
        try { file(c).writeText(a.toString()) } catch (e: Exception) { }
    }
    fun clear(c: Context) { file(c).delete() }
}

/** Поиск сайтов, обход страниц и извлечение ключей. */
object KeyCrawler {
    private const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
    private const val MAX_BYTES = 2_000_000
    private val KEY_RE = Regex("(?i)(?<![A-Za-z0-9])(vless|vmess|trojan|ss)://[^\\s\"'<>\\\\`|]{8,}")
    private val HREF_RE = Regex("(?i)href\\s*=\\s*[\"']([^\"'#\\s]+)")
    private val SKIP_EXT = Regex("(?i)\\.(png|jpe?g|gif|svg|webp|ico|css|js|woff2?|ttf|pdf|zip|rar|7z|apk|exe|mp4|mp3|mkv)(\\?.*)?$")
    private val HINTS = listOf("vless", "vmess", "trojan", "vpn", "key", "kluch", "proxy", "config", "sub", "free", "v2ray", "xray", "raw", "list", "mirror", "subscription", "sing")
    private val BLOCKED_HOSTS = listOf("google.", "bing.com", "duckduckgo.", "yandex.", "facebook.", "instagram.", "youtube.", "youtu.be", "twitter.", "x.com", "wikipedia.", "microsoft.", "apple.com", "amazon.", "tiktok.", "vk.com", "reddit.com/r/", "play.google")

    fun fetch(url: String): String? {
        return try {
            val c = URL(url.replaceFirst("http://", "https://")).openConnection() as HttpURLConnection
            c.connectTimeout = 7000; c.readTimeout = 9000; c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Accept-Language", "en,ru;q=0.8")
            if (c.responseCode !in 200..299) { c.disconnect(); return null }
            val ct = c.contentType.orEmpty().lowercase()
            if (ct.isNotEmpty() && !(ct.startsWith("text") || ct.contains("json") || ct.contains("xml") || ct.contains("octet") || ct.contains("yaml"))) { c.disconnect(); return null }
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { s -> val buf = ByteArray(16384); var n: Int; while (s.read(buf).also { n = it } > 0 && out.size() < MAX_BYTES) out.write(buf, 0, n) }
            c.disconnect()
            out.toString("UTF-8")
        } catch (e: Exception) { null }
    }

    fun unescape(s: String): String {
        var t = s.replace("\\u0026", "&").replace("\\/", "/").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x2F;", "/").replace("&#43;", "+")
        t = Regex("&#x([0-9a-fA-F]+);").replace(t) { m -> try { String(Character.toChars(m.groupValues[1].toInt(16))) } catch (e: Exception) { "" } }
        t = Regex("&#(\\d+);").replace(t) { m -> try { String(Character.toChars(m.groupValues[1].toInt())) } catch (e: Exception) { "" } }
        return t
    }

    /** Ключи из текста страницы, включая base64-подписки. */
    fun extract(body: String, protocols: Set<String>): List<String> {
        val found = LinkedHashSet<String>()
        fun scan(text: String) {
            for (m in KEY_RE.findAll(unescape(text))) {
                if (m.groupValues[1].lowercase() !in protocols) continue
                found += m.value.trimEnd('.', ',', ';', ')', ']', '}', '!', ':')
            }
        }
        scan(body)
        val plain = body.trim()
        if (plain.length > 120 && !plain.contains("://") && plain.all { it.isLetterOrDigit() || it in "+/=_-\r\n " }) {
            for (flags in intArrayOf(Base64.DEFAULT, Base64.URL_SAFE)) {
                try { scan(String(Base64.decode(plain, flags))); break } catch (e: Exception) { }
            }
        }
        return found.toList()
    }

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
    suspend fun crawl(start: String, pages: Int, depth: Int, protocols: Set<String>, onPage: (Int) -> Unit, onKeys: (List<String>) -> Unit) {
        val seen = HashSet<String>(); val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(start to 0); seen += start
        var count = 0
        while (queue.isNotEmpty() && count < pages) {
            coroutineContext.ensureActive()
            val (url, d) = queue.removeFirst()
            val body = withContext(Dispatchers.IO) { fetch(url) } ?: continue
            count++; onPage(count)
            val keys = extract(body, protocols)
            if (keys.isNotEmpty()) onKeys(keys)
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

    fun search(query: String, limit: Int): List<String> {
        val variants = listOf(query, "$query free keys list", "$query подписка бесплатно ключи", "$query github raw subscription")
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

    fun parse(link: String, source: String): KeyItem? = try {
        val p = XrayProfileParser.parse(link)
        val ep = p.endpoint
        val host = ep.substringBeforeLast(':', ep).trim('[', ']'); val port = ep.substringAfterLast(':', "").toIntOrNull() ?: 0
        if (host.isEmpty() || port !in 1..65535 || isPrivate(host)) null
        else KeyItem(link, p.name.ifBlank { host }, link.substringBefore("://").lowercase(), host, port, source, System.currentTimeMillis())
    } catch (e: Exception) { null }

    private fun isPrivate(h: String) = h == "localhost" || h.startsWith("127.") || h.startsWith("10.") || h.startsWith("192.168.") || h == "0.0.0.0" ||
        Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(h) || h.endsWith(".local") || h.contains("example.")
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

    suspend fun check(item: KeyItem, timeout: Int, tls: Boolean) = withContext(Dispatchers.IO) {
        val addr = try { InetAddress.getByName(item.host) } catch (e: Exception) { null }
        if (addr == null) { item.ms = -2; item.checkedAt = System.currentTimeMillis(); return@withContext }
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
                if (it.country.isNotEmpty()) continue
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
    fun label(i: KeyItem): String = (if (i.country.length == 2) Countries.flag(i.country) + " " + i.country + " · " else "") + (if (i.ms >= 0) "${i.ms} мс · " else "") + i.host

    fun labeled(i: KeyItem): String = try {
        if (i.type == "vmess") {
            val j = JSONObject(String(Base64.decode(i.link.substringAfter("://").substringBefore('#'), Base64.DEFAULT)))
            j.put("ps", label(i)); "vmess://" + Base64.encodeToString(j.toString().toByteArray(), Base64.NO_WRAP)
        } else i.link.substringBefore('#') + "#" + URLEncoder.encode(label(i), "UTF-8").replace("+", "%20")
    } catch (e: Exception) { i.link }

    fun text(items: List<KeyItem>) = items.joinToString("\n") { labeled(it) }
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
