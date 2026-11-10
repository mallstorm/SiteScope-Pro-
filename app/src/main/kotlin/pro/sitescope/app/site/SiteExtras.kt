package pro.sitescope.app.site

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.cert.X509Certificate
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Дополнительные проверки сайта поверх прежнего AuditEngine: скорость, защита, цепочка переходов, ссылки. */
object SiteExtras {
    const val UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36 SiteScopePro/1.0"

    class Hop(val url: String, val code: Int, val ms: Long)
    class TlsInfo {
        var valid = false; var protocol = ""; var cipher = ""; var subject = ""; var issuer = ""
        var notAfter = 0L; var daysLeft = 0; var error = ""
    }
    class PageInfo {
        var viewport = false; var lang = ""; var charset = false
        var ogTitle = false; var ogImage = false; var twitterCard = false
        var jsonLd = ArrayList<String>(); var hreflang = 0; var favicon = false
        var scripts = 0; var blockingScripts = 0; var stylesheets = 0
        var images = 0; var lazyImages = 0; var imagesNoSize = 0
        var mixedContent = 0; var inlineBytes = 0; var insecureForms = 0
    }
    class LinkCheck(val url: String, val code: Int, val internal: Boolean)
    class Data {
        var reachable = false; var error = ""
        val chain = ArrayList<Hop>()
        var finalUrl = ""; var httpUpgrades: Boolean? = null
        var dnsMs = 0L; var ttfbMs = 0L; var downloadMs = 0L
        var htmlBytes = 0; var transferBytes = 0; var compression = ""
        val headers = LinkedHashMap<String, String>()
        var cookiesTotal = 0; var cookiesInsecure = 0
        var tls: TlsInfo? = null
        var page = PageInfo()
        var securityTxt = false
        val links = ArrayList<LinkCheck>()
        var linksChecked = 0
        var html = ""
    }

    fun normalize(raw: String): String {
        val t = raw.trim()
        return if (t.startsWith("http://", true) || t.startsWith("https://", true)) t else "https://$t"
    }

    fun collect(rawTarget: String, checkLinks: Boolean = true, maxLinks: Int = 30, cancelled: () -> Boolean = { false }): Data {
        val d = Data()
        val target = normalize(rawTarget)
        try {
            val host = URL(target).host
            val dns0 = System.currentTimeMillis()
            InetAddress.getByName(host)
            d.dnsMs = System.currentTimeMillis() - dns0
            fetchMain(target, d)
            if (cancelled()) return d
            val finalUrl = URL(d.finalUrl)
            if (finalUrl.protocol == "https") d.tls = inspectTls(finalUrl.host, if (finalUrl.port > 0) finalUrl.port else 443)
            d.httpUpgrades = checkHttpUpgrade(finalUrl.host)
            d.page = parsePage(d.html, finalUrl.protocol == "https")
            d.securityTxt = exists(URL(finalUrl, "/.well-known/security.txt").toString(), "contact")
            if (checkLinks && !cancelled()) checkLinks(d, finalUrl, maxLinks, cancelled)
            d.reachable = true
        } catch (e: Exception) {
            d.error = e.javaClass.simpleName + (e.message?.let { ": $it" } ?: "")
        }
        return d
    }

    private fun fetchMain(target: String, d: Data) {
        var url = target
        var hops = 0
        while (true) {
            val t0 = System.currentTimeMillis()
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 9000; c.readTimeout = 12000
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", UA)
                c.setRequestProperty("Accept", "text/html,application/xhtml+xml,*/*;q=0.8")
                c.setRequestProperty("Accept-Encoding", "gzip")
                c.setRequestProperty("Accept-Language", "ru,en;q=0.8")
                val code = c.responseCode
                val ttfb = System.currentTimeMillis() - t0
                d.chain.add(Hop(url, code, ttfb))
                if (code in 300..399 && code != 304) {
                    val location = c.getHeaderField("Location")
                    if (location.isNullOrBlank() || ++hops > 8) { d.finalUrl = url; return }
                    url = URL(URL(url), location).toString(); continue
                }
                d.finalUrl = url; d.ttfbMs = ttfb
                var i = 0
                while (true) {
                    val key = c.getHeaderFieldKey(i); val value = c.getHeaderField(i)
                    if (key == null && value == null) break
                    if (key != null && value != null) {
                        val k = key.lowercase()
                        if (k == "set-cookie") {
                            d.cookiesTotal++
                            val v = value.lowercase()
                            if (!v.contains("secure") || !v.contains("httponly")) d.cookiesInsecure++
                        } else d.headers[k] = if (d.headers.containsKey(k)) d.headers[k] + ", " + value else value
                    }
                    i++
                }
                val dl0 = System.currentTimeMillis()
                val stream = (if (code >= 400) c.errorStream else c.inputStream) ?: return
                val raw = readAll(stream, 3_000_000)
                d.transferBytes = raw.size
                d.compression = (d.headers["content-encoding"] ?: "").lowercase()
                val body = if (d.compression.contains("gzip")) try { readAll(GZIPInputStream(raw.inputStream()), 6_000_000) } catch (e: Exception) { raw } else raw
                d.downloadMs = System.currentTimeMillis() - dl0
                d.htmlBytes = body.size
                val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(d.headers["content-type"] ?: "")?.groupValues?.get(1)
                d.html = try { String(body, charset(charset ?: "UTF-8")) } catch (e: Exception) { String(body, Charsets.UTF_8) }
                return
            } finally { c.disconnect() }
        }
    }

    private fun readAll(input: java.io.InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(); val buf = ByteArray(16384)
        input.use { while (out.size() < limit) { val n = it.read(buf); if (n < 0) break; out.write(buf, 0, n) } }
        return out.toByteArray()
    }

    /** Переводит ли сайт посетителя с http:// на https://. null — не удалось проверить. */
    private fun checkHttpUpgrade(host: String): Boolean? = try {
        var url = "http://$host/"; var result: Boolean? = false
        for (i in 0 until 5) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 6000; c.readTimeout = 6000; c.instanceFollowRedirects = false
                c.requestMethod = "GET"; c.setRequestProperty("User-Agent", UA)
                val code = c.responseCode
                val location = c.getHeaderField("Location")
                if (code in 300..399 && !location.isNullOrBlank()) {
                    url = URL(URL(url), location).toString()
                    if (url.startsWith("https://")) { result = true; break }
                } else break
            } finally { c.disconnect() }
        }
        result
    } catch (e: Exception) { null }

    fun inspectTls(host: String, port: Int): TlsInfo {
        val info = TlsInfo()
        try {
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val plain = java.net.Socket()
            plain.connect(java.net.InetSocketAddress(host, port), 8000)
            (factory.createSocket(plain, host, port, true) as SSLSocket).use { socket ->
                socket.soTimeout = 8000
                socket.startHandshake()
                val session = socket.session
                info.protocol = session.protocol; info.cipher = session.cipherSuite
                val cert = session.peerCertificates.firstOrNull() as? X509Certificate
                if (cert != null) {
                    info.subject = cn(cert.subjectX500Principal.name)
                    info.issuer = cn(cert.issuerX500Principal.name)
                    info.notAfter = cert.notAfter.time
                    info.daysLeft = ((cert.notAfter.time - System.currentTimeMillis()) / 86_400_000L).toInt()
                }
                val verifier = javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
                info.valid = verifier.verify(host, session)
                if (!info.valid) info.error = "Сертификат выдан для другого имени"
            }
        } catch (e: Exception) { info.valid = false; info.error = e.message ?: e.javaClass.simpleName }
        return info
    }

    private fun cn(dn: String): String =
        Regex("(?:^|,)\\s*(?:CN|O)=([^,]+)").find(dn)?.groupValues?.get(1)?.trim('"', ' ') ?: dn

    private val tagRegex = Regex("<(script|link|img|meta|html|form|iframe|a)\\b([^>]*)>", RegexOption.IGNORE_CASE)

    fun attr(tag: String, name: String): String =
        Regex("\\b$name\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", RegexOption.IGNORE_CASE).find(tag)?.let {
            it.groupValues[1].ifEmpty { it.groupValues[2].ifEmpty { it.groupValues[3] } }
        } ?: ""

    fun parsePage(html: String, https: Boolean): PageInfo {
        val p = PageInfo()
        val headEnd = html.indexOf("</head", ignoreCase = true).let { if (it < 0) html.length else it }
        for (m in tagRegex.findAll(html)) {
            val name = m.groupValues[1].lowercase(); val a = m.groupValues[2]
            when (name) {
                "html" -> p.lang = attr(a, "lang")
                "meta" -> {
                    val n = attr(a, "name").lowercase(); val prop = attr(a, "property").lowercase()
                    if (n == "viewport") p.viewport = true
                    if (attr(a, "charset").isNotEmpty() || attr(a, "http-equiv").equals("content-type", true)) p.charset = true
                    if (prop == "og:title") p.ogTitle = true
                    if (prop == "og:image") p.ogImage = true
                    if (n == "twitter:card" || prop == "twitter:card") p.twitterCard = true
                }
                "link" -> {
                    val rel = attr(a, "rel").lowercase()
                    if (rel.contains("stylesheet")) { p.stylesheets++; if (https && attr(a, "href").startsWith("http://")) p.mixedContent++ }
                    if (rel.contains("icon")) p.favicon = true
                    if (rel.contains("alternate") && attr(a, "hreflang").isNotEmpty()) p.hreflang++
                }
                "script" -> {
                    val src = attr(a, "src")
                    if (src.isNotEmpty()) {
                        p.scripts++
                        val lower = a.lowercase()
                        if (m.range.first < headEnd && !lower.contains("async") && !lower.contains("defer") && !attr(a, "type").equals("module", true)) p.blockingScripts++
                        if (https && src.startsWith("http://")) p.mixedContent++
                    }
                }
                "img" -> {
                    p.images++
                    if (attr(a, "loading").equals("lazy", true)) p.lazyImages++
                    if (attr(a, "width").isEmpty() || attr(a, "height").isEmpty()) p.imagesNoSize++
                    if (https && attr(a, "src").startsWith("http://")) p.mixedContent++
                }
                "iframe" -> if (https && attr(a, "src").startsWith("http://")) p.mixedContent++
                "form" -> if (attr(a, "action").startsWith("http://")) p.insecureForms++
            }
        }
        for (m in Regex("<script[^>]*type\\s*=\\s*[\"']application/ld\\+json[\"'][^>]*>(.*?)</script>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(html))
            for (t in Regex("\"@type\"\\s*:\\s*\"([^\"]+)\"").findAll(m.groupValues[1])) if (!p.jsonLd.contains(t.groupValues[1])) p.jsonLd.add(t.groupValues[1])
        for (m in Regex("<(script|style)(?![^>]*\\bsrc=)[^>]*>(.*?)</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).findAll(html))
            p.inlineBytes += m.groupValues[2].length
        return p
    }

    fun extractLinks(html: String, base: URL, max: Int): List<Pair<String, Boolean>> {
        val internal = LinkedHashSet<String>(); val external = LinkedHashSet<String>()
        for (m in Regex("<a\\b([^>]*)>", RegexOption.IGNORE_CASE).findAll(html)) {
            val href = attr(m.groupValues[1], "href").trim()
            if (href.isEmpty() || href.startsWith("#") || href.startsWith("mailto:", true) || href.startsWith("tel:", true) || href.startsWith("javascript:", true)) continue
            val abs = try { URL(base, href) } catch (e: Exception) { continue }
            if (abs.protocol != "http" && abs.protocol != "https") continue
            val clean = abs.toString().substringBefore('#')
            if (abs.host.removePrefix("www.").equals(base.host.removePrefix("www."), true)) internal.add(clean) else external.add(clean)
        }
        val takeInternal = internal.take(max * 2 / 3)
        return takeInternal.map { it to true } + external.take(max - takeInternal.size).map { it to false }
    }

    private fun checkLinks(d: Data, base: URL, max: Int, cancelled: () -> Boolean) {
        val links = extractLinks(d.html, base, max)
        if (links.isEmpty()) return
        val pool = Executors.newFixedThreadPool(6)
        try {
            val futures = links.map { (url, internal) -> pool.submit(Callable { LinkCheck(url, if (cancelled()) 0 else status(url), internal) }) }
            for (f in futures) try { val r = f.get(15, TimeUnit.SECONDS); if (r.code != 0) { d.linksChecked++; d.links.add(r) } } catch (e: Exception) { }
        } finally { pool.shutdownNow() }
    }

    /** Код ответа: сначала HEAD, при отказе — GET. -1 — нет соединения. */
    fun status(url: String): Int {
        for (method in listOf("HEAD", "GET")) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 6000; c.readTimeout = 6000; c.requestMethod = method
                    c.instanceFollowRedirects = true; c.setRequestProperty("User-Agent", UA)
                    val code = c.responseCode
                    if (method == "HEAD" && (code == 405 || code == 403 || code == 501 || code == 400)) continue
                    return code
                } finally { c.disconnect() }
            } catch (e: Exception) { if (method == "GET") return -1 }
        }
        return -1
    }

    private fun exists(url: String, mustContain: String): Boolean = try {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 5000; c.readTimeout = 5000; c.setRequestProperty("User-Agent", UA)
            c.responseCode == 200 && String(readAll(c.inputStream, 20000)).contains(mustContain, true)
        } finally { c.disconnect() }
    } catch (e: Exception) { false }
}
