package pro.sitescope.app

import org.junit.Assert.assertTrue
import org.junit.Test
import pro.sitescope.app.core.SmartInput
import pro.sitescope.app.ip.IpTools
import pro.sitescope.app.ip.Subnet
import pro.sitescope.app.site.*
import pro.sitescope.app.tv.*

private fun ck(c: Boolean, n: String) = assertTrue(n, c)

class EnginesTest {
    @Test fun protocolCodecs() {
    val m = pro.sitescope.app.proto.Snmp.message("public", 0xA2, 77, "1.3.6.1.2.1.1.5.0")
    val vb = pro.sitescope.app.proto.Snmp.parse(m, m.size, 77)!!
    ck(vb.oid == "1.3.6.1.2.1.1.5.0" && vb.value == "", "snmp roundtrip ${vb.oid}")
    ck(pro.sitescope.app.proto.Snmp.uptime(8640000L + 360000L) == "1 дн 1 ч 0 мин", "uptime")
    ck(pro.sitescope.app.proto.Terminal.clean("\u001B[1;32mok\u001B[0m\r\nab\bc\u0007") == "ok\nac", "terminal clean")
    val pkt = pro.sitescope.app.proto.MqttClient.packet(0x30, ByteArray(200))
    ck(pkt.size == 203 && pro.sitescope.app.proto.MqttClient.remaining(java.io.ByteArrayInputStream(pkt, 1, 2)) == 200, "mqtt length")
    ck(pro.sitescope.app.proto.VncClient.auth("secret", ByteArray(16)).size == 16, "vnc auth")
    ck(pro.sitescope.app.proto.joinPath("/a/", "b") == "/a/b" && pro.sitescope.app.proto.parentPath("/a/b/") == "/a" && pro.sitescope.app.proto.parentPath("/a") == "/", "paths")
    ck(pro.sitescope.app.vpn.TunnelProxy.isLocalHost("192.168.1.1") && pro.sitescope.app.vpn.TunnelProxy.isLocalHost("router") && pro.sitescope.app.vpn.TunnelProxy.isLocalHost("172.20.0.1") &&
        !pro.sitescope.app.vpn.TunnelProxy.isLocalHost("172.40.0.1") && !pro.sitescope.app.vpn.TunnelProxy.isLocalHost("example.com"), "local hosts")
    }

    @Test fun trafficCapture() {
    fun ip4(proto: Int, src: IntArray, dst: IntArray, l4: ByteArray): ByteArray {
        val p = ByteArray(20 + l4.size); p[0] = 0x45; p[9] = proto.toByte()
        for (i in 0 until 4) { p[12 + i] = src[i].toByte(); p[16 + i] = dst[i].toByte() }
        System.arraycopy(l4, 0, p, 20, l4.size); return p
    }
    fun udp(sp: Int, dp: Int, data: ByteArray) = byteArrayOf((sp shr 8).toByte(), sp.toByte(), (dp shr 8).toByte(), dp.toByte(), 0, 0, 0, 0) + data
    fun tcp(sp: Int, dp: Int) = byteArrayOf((sp shr 8).toByte(), sp.toByte(), (dp shr 8).toByte(), dp.toByte()) + ByteArray(8) + byteArrayOf(0x50, 0, 0, 0, 0, 0, 0, 0)
    fun qname(n: String) = n.split('.').fold(ByteArray(0)) { acc, l -> acc + byteArrayOf(l.length.toByte()) + l.toByteArray() } + byteArrayOf(0)
    fun dnsQuery(n: String) = byteArrayOf(0, 1, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0) + qname(n) + byteArrayOf(0, 1, 0, 1)
    fun dnsAnswer(n: String, ip: IntArray) = byteArrayOf(0, 1, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0) + qname(n) + byteArrayOf(0, 1, 0, 1) +
        byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4) + ip.map { it.toByte() }.toByteArray()
    val me = intArrayOf(10, 10, 14, 1); val dns = intArrayOf(1, 1, 1, 1); val site = intArrayOf(93, 184, 216, 34); val other = intArrayOf(8, 8, 4, 4)
    val m = pro.sitescope.app.vpn.DnsMessage.parse(dnsAnswer("www.example.com", site), 0, dnsAnswer("www.example.com", site).size)!!
    ck(m.response && m.name == "www.example.com" && m.addresses == listOf("93.184.216.34"), "dns parse ${m.name} ${m.addresses}")
    val info = pro.sitescope.app.vpn.PacketInfo.parse(ip4(6, me, site, tcp(40000, 443)), 40)!!
    ck(info.protocolName == "TCP" && info.dst == "93.184.216.34" && info.dstPort == 443 && info.payloadOffset == 40, "packet parse")
    val buf = java.io.ByteArrayOutputStream()
    val s = pro.sitescope.app.vpn.CaptureSession(null, buf, "https://example.com/page")
    fun feed(p: ByteArray) = s.onPacket(p, p.size, 1700000000123L)
    feed(ip4(6, me, site, tcp(40000, 443)))                       // до DNS-ответа адрес сайта ещё неизвестен
    feed(ip4(17, me, dns, udp(5000, 53, dnsQuery("other.org"))))  // чужой DNS-запрос
    feed(ip4(17, me, dns, udp(5001, 53, dnsQuery("www.example.com"))))
    feed(ip4(17, dns, me, udp(53, 5001, dnsAnswer("www.example.com", site))))
    feed(ip4(6, me, site, tcp(40000, 443))); feed(ip4(6, site, me, tcp(443, 40000))); feed(ip4(6, me, other, tcp(40001, 443)))
    s.close()
    ck(s.filtered && s.seen == 7L && s.written == 4L, "filter seen=${s.seen} written=${s.written}")
    val raw = buf.toByteArray()
    ck(raw.size == 24 + 4 * 16 + 2 * (28 + dnsQuery("www.example.com").size) + 16 + 2 * 40 && raw[0] == 0xd4.toByte() && raw[3] == 0xa1.toByte() && raw[20] == 101.toByte(), "pcap bytes ${raw.size}")
    val flow = s.topFlows(10).first { it.remote == "93.184.216.34" }
    ck(flow.packets == 3L && flow.host == "www.example.com" && flow.sent == 80L && flow.received == 40L && flow.port == 443, "flow ${flow.packets} ${flow.host}")
    val all = pro.sitescope.app.vpn.CaptureSession(null, java.io.ByteArrayOutputStream(), "")
    all.onPacket(ip4(6, me, other, tcp(1, 2)), 40); ck(!all.filtered && all.written == 1L, "unfiltered")
    }

    @Test fun wakeOnLan() {
    ck(pro.sitescope.app.net.NetViewModel.normalizeMac("aa-bb-cc-dd-ee-0f") == "AA:BB:CC:DD:EE:0F", "mac")
    ck(pro.sitescope.app.net.NetViewModel.normalizeMac("aa:bb:cc") == null && pro.sitescope.app.net.NetViewModel.normalizeMac("zz:bb:cc:dd:ee:ff") == null, "bad mac")
    val m = pro.sitescope.app.net.NetViewModel.magicPacket("AA:BB:CC:DD:EE:0F")
    ck(m.size == 102 && m[0] == 0xFF.toByte() && m[5] == 0xFF.toByte() && m[6] == 0xAA.toByte() && m[11] == 0x0F.toByte() && m[101] == 0x0F.toByte(), "magic")
    }

    @Test fun subnetAndSmartInput() {
    val n = Subnet.parse("192.168.1.10/24")
    ck(n.cidr == "192.168.1.0/24" && n.mask == "255.255.255.0" && n.wildcard == "0.0.0.255" && n.usable == 254L, "basic")
    ck(Subnet.toIp(n.broadcast) == "192.168.1.255" && Subnet.toIp(n.firstHost) == "192.168.1.1", "hosts")
    ck(Subnet.parse("10.0.0.5 255.255.252.0").prefix == 22, "mask form")
    ck(Subnet.parse("10.0.0.0 0.0.0.255").prefix == 24, "wildcard form")
    ck(Subnet.parse("10.0.0.0/255.255.0.0").prefix == 16, "slash mask")
    ck(try { Subnet.maskToPrefix("255.0.255.0"); false } catch (e: Exception) { true }, "bad mask")
    ck(Subnet.split(n, 26).map { it.cidr } == listOf("192.168.1.0/26","192.168.1.64/26","192.168.1.128/26","192.168.1.192/26"), "split")
    ck(Subnet.prefixForParts(n, 5) == 27, "parts"); ck(Subnet.prefixForHosts(50) == 26 && Subnet.prefixForHosts(254) == 24 && Subnet.prefixForHosts(255) == 23, "hosts prefix")
    val v = Subnet.vlsm(n, listOf("A" to 100, "B" to 50, "C" to 10, "D" to 2))
    ck(v.map { it.net?.cidr } == listOf("192.168.1.0/25","192.168.1.128/26","192.168.1.192/28","192.168.1.208/30"), "vlsm ${v.map{it.net?.cidr}}")
    ck(Subnet.vlsm(n, listOf("A" to 200, "B" to 100))[1].net == null, "vlsm overflow")
    ck(Subnet.summarize(Subnet.parseList("10.0.0.0/25\n10.0.0.128/25, 10.0.1.0/24")).map { it.cidr } == listOf("10.0.0.0/23"), "summarize")
    ck(Subnet.parseList("192.168.1.0-192.168.1.255").map { it.cidr } == listOf("192.168.1.0/24"), "range")
    ck(Subnet.rangeToCidrs(Subnet.ipToLong("10.0.0.1"), Subnet.ipToLong("10.0.0.6")).map{it.cidr} == listOf("10.0.0.1/32","10.0.0.2/31","10.0.0.4/31","10.0.0.6/32"), "range odd")
    ck(Subnet.supernet(Subnet.parseList("10.0.0.0/24,10.0.3.0/24")).cidr == "10.0.0.0/22", "supernet")
    ck(Subnet.kind(Subnet.ipToLong("100.70.1.1")).contains("CGNAT") && Subnet.kind(Subnet.ipToLong("8.8.8.8")).startsWith("Публичный"), "kind")
    ck(Subnet.binary(Subnet.ipToLong("192.168.1.1")) == "11000000.10101000.00000001.00000001", "binary")
    ck(Subnet.acl(Subnet.AclFormat.CISCO, listOf(n)).contains("permit ip 192.168.1.0 0.0.0.255 any"), "cisco")
    ck(Subnet.parse("0.0.0.0/0").total == 4294967296L && Subnet.parse("255.255.255.255/32").usable == 1L, "edges")
    ck(Subnet.rangeToCidrs(0, 0xFFFFFFFFL).map{it.cidr} == listOf("0.0.0.0/0"), "full range")
    val s = Subnet.v6("2001:db8::1/64")
    ck(s.network == "2001:db8::" && s.expanded == "2001:0db8:0000:0000:0000:0000:0000:0001" && s.last == "2001:db8::ffff:ffff:ffff:ffff" && s.kind.contains("документ"), "v6 $s")
    ck(Subnet.v6("fe80::1").kind.contains("Link") && Subnet.v6("::1").compressed == "::1" && Subnet.v6("::").compressed == "::", "v6 kinds")
    for ((i, k) in listOf("https://example.com/a" to "URL", "example.com" to "DOMAIN", "8.8.8.8" to "IPV4", "10.0.0.0/8" to "CIDR", "10.0.0.1 255.255.255.0" to "CIDR", "AS15169" to "ASN", "http://x.tv/get.php?username=a&password=b&type=m3u_plus" to "PLAYLIST", "http://a.b/list.m3u" to "PLAYLIST", "http://a.b/live/1.m3u8" to "STREAM", "udp://@239.1.1.1:1234" to "STREAM", "2001:db8::1" to "IPV6", "2001:db8::/32" to "CIDR", "ростелеком" to "TEXT", "яндекс.рф" to "DOMAIN"))
        ck(SmartInput.detect(i).kind.name == k, "detect $i -> ${SmartInput.detect(i).kind}")
    }

    @Test fun playlistAndChecker() {
    val m3u = """#EXTM3U url-tvg="http://epg.example/x.xml.gz,http://epg2.example/y.xml"
#EXTINF:-1 tvg-id="one" tvg-logo="http://l/1.png" group-title="Новости, спорт" catchup="shift" catchup-days="3",Первый, канал HD
#EXTVLCOPT:http-user-agent=MyUA/1.0
#EXTVLCOPT:http-referrer=http://ref.example/
http://s.example/1.m3u8
#EXTINF:0,Второй
#EXTGRP:Кино
udp://@239.1.1.1:1234
#EXTINF:-1,Третий
http://s.example/3.ts|User-Agent=Foo&Referer=http://r/
#EXTINF:-1,Дубль
http://s.example/1.m3u8
#EXTINF:-1,Мусор
not-a-url
http://s.example/noname.ts
"""
    val p = Playlist.parse(m3u, "src")
    ck(p.channels.size == 5, "count ${p.channels.size}")
    val a = p.channels[0]
    ck(a.name == "Первый, канал HD" && a.group == "Новости, спорт" && a.tvgId == "one" && a.userAgent == "MyUA/1.0" && a.referrer == "http://ref.example/" && a.catchupDays == 3, "attrs ${a.name}|${a.group}")
    ck(p.channels[1].group == "Кино" && p.channels[1].isLocalStream, "extgrp")
    ck(p.channels[2].url == "http://s.example/3.ts" && p.channels[2].userAgent == "Foo" && p.channels[2].referrer == "http://r/", "pipe headers")
    ck(p.channels[4].name.startsWith("Канал "), "noname")
    ck(p.epgUrls.size == 2, "epg")
    val list = p.channels.toMutableList(); ck(Playlist.dedupe(list) == 1 && list.size == 4, "dedupe")
    val again = Playlist.parse(Playlist.export(p.channels, p.epgUrls))
    ck(again.channels.size == 5 && again.channels[0].name == a.name && again.channels[0].userAgent == a.userAgent && again.epgUrls.size == 2 && again.channels[0].id == a.id, "roundtrip")
    ck(Playlist.xtreamUrl("portal.tv:8080/", "u s", "p&w") == "http://portal.tv:8080/get.php?username=u+s&password=p%26w&type=m3u_plus&output=ts", "xtream")
    ck(Playlist.playbackUrl("192.168.1.1:4022", "udp://@239.1.1.1:1234") == "http://192.168.1.1:4022/udp/239.1.1.1:1234", "udpxy")
    ck(StreamChecker.codecNames("avc1.64001f,mp4a.40.2") == ("H.264" to "AAC") && StreamChecker.codecNames("hvc1.1.6.L120,ec-3").first.contains("HEVC"), "codecs")

    val ts = ByteArray(188 * 400) { if (it % 188 == 0) 0x47 else 0x11 }
    val srv = MiniHttp()
    fun route(path: String, code: Int, type: String, body: ByteArray, ua: String? = null, loc: String? = null) = srv.createContext(path) { ex ->
        if (ua != null && ex.requestHeaders.getFirst("User-Agent") != ua) { ex.sendResponseHeaders(403, -1); ex.close(); return@createContext }
        if (loc != null) ex.responseHeaders.add("Location", loc)
        ex.responseHeaders.add("Content-Type", type)
        ex.sendResponseHeaders(code, if (body.isEmpty()) -1 else body.size.toLong()); if (body.isNotEmpty()) ex.responseBody.write(body); ex.close()
    }
    route("/master.m3u8", 200, "application/vnd.apple.mpegurl", "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,CODECS=\"avc1.4d401e,mp4a.40.2\"\nlow/index.m3u8\n#EXT-X-STREAM-INF:AVERAGE-BANDWIDTH=2500000,BANDWIDTH=3000000,RESOLUTION=1920x1080,CODECS=\"avc1.640028,mp4a.40.2\"\nhi/index.m3u8\n".toByteArray())
    route("/hi/index.m3u8", 200, "application/vnd.apple.mpegurl", "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg1.ts\n#EXTINF:6,\nseg2.ts\n".toByteArray())
    route("/hi/seg2.ts", 200, "video/mp2t", ts)
    route("/dead.m3u8", 200, "application/vnd.apple.mpegurl", "#EXTM3U\n#EXTINF:6,\nmissing.ts\n".toByteArray())
    route("/raw.ts", 200, "application/octet-stream", ts)
    route("/geo", 403, "text/plain", ByteArray(0)); route("/auth", 401, "text/plain", ByteArray(0)); route("/gone", 404, "text/plain", ByteArray(0))
    route("/page", 200, "text/html", "<!DOCTYPE html><html>hi</html>".toByteArray())
    route("/redir", 302, "text/plain", ByteArray(0), loc = "/raw.ts")
    route("/uaonly.ts", 200, "video/mp2t", ts, ua = "Special/1")
    srv.start(); val b = "http://127.0.0.1:${srv.address.port}"
    StreamChecker.tsInspector = { StreamChecker.TsInfo("H.264", "AAC", "Svc") }
    var r = StreamChecker.check("$b/master.m3u8")
    ck(r.status == StreamStatus.OK && r.resolution == "1920x1080" && r.bitrateKbps == 3000L && r.video == "H.264" && r.container == "HLS", "hls ${r.status} ${r.detail} ${r.resolution} ${r.bitrateKbps}")
    r = StreamChecker.check("$b/dead.m3u8"); ck(r.status == StreamStatus.DEAD && r.detail.contains("видео не загружается"), "hls dead ${r.detail}")
    r = StreamChecker.check("$b/raw.ts"); ck(r.status == StreamStatus.OK && r.container == "MPEG-TS" && r.video == "H.264" && r.serviceName == "Svc", "ts ${r.detail}")
    ck(StreamChecker.check("$b/geo").status == StreamStatus.BLOCKED && StreamChecker.check("$b/auth").status == StreamStatus.AUTH && StreamChecker.check("$b/gone").status == StreamStatus.DEAD, "http errors")
    ck(StreamChecker.check("$b/page").detail.contains("веб-страницу"), "html")
    r = StreamChecker.check("$b/redir"); ck(r.status == StreamStatus.OK && r.redirects == 1, "redirect")
    ck(StreamChecker.check("$b/uaonly.ts").status == StreamStatus.BLOCKED && StreamChecker.check("$b/uaonly.ts", "Special/1").status == StreamStatus.OK, "ua header")
    ck(StreamChecker.check("http://no-such-host.invalid/x").detail.contains("не найден"), "dns")
    ck(StreamChecker.check("udp://@239.1.1.1:1").status == StreamStatus.UNKNOWN, "udp")
    val chans = Playlist.parse("#EXTM3U\n#EXTINF:-1,A\n$b/raw.ts\n#EXTINF:-1,B\n$b/gone\n#EXTINF:-1,C\n$b/master.m3u8\n").channels
    val latch = java.util.concurrent.CountDownLatch(1); var n = 0
    StreamChecker.checkAll(chans, 3, 4000, "", null, object : StreamChecker.BatchListener {
        override fun onChannel(channel: Channel, done: Int, total: Int) { n = maxOf(n, done) }
        override fun onFinished(cancelled: Boolean) { latch.countDown() } })
    latch.await(); ck(n == 3 && chans[0].status == StreamStatus.OK && chans[1].failCount == 1 && chans[2].quality == "FHD" && chans[0].uptimePercent == 100, "batch")
    srv.stop(0)
    }

    @Test fun siteEngines() {
    val srv = MiniHttp()
    val html = """<!doctype html><html lang="ru"><head><meta charset="utf-8"><meta name=viewport content="width=device-width">
<meta property="og:title" content="x"><meta property="og:image" content="y"><link rel="icon" href="/f.ico"><link rel="stylesheet" href="/a.css">
<script src="/a.js"></script><script src="/b.js" defer></script><script type="application/ld+json">{"@type":"Organization","x":{"@type":"WebSite"}}</script></head>
<body><h1>T</h1><img src="/1.png" width=1 height=1 loading="lazy"><img src='/2.png'><a href="/ok">a</a><a href="/missing">b</a><a href="#x">c</a><a href="mailto:a@b">m</a></body></html>"""
    srv.createContext("/") { ex ->
        val p = ex.requestURI.path
        when (p) {
            "/start" -> { ex.responseHeaders.add("Location", "/"); ex.sendResponseHeaders(301, -1) }
            "/", "/ok" -> {
                ex.responseHeaders.add("Content-Type", "text/html; charset=utf-8"); ex.responseHeaders.add("Content-Encoding", "gzip")
                ex.responseHeaders.add("X-Content-Type-Options", "nosniff"); ex.responseHeaders.add("Server", "nginx/1.18.0"); ex.responseHeaders.add("Set-Cookie", "a=b; Path=/")
                val bo = java.io.ByteArrayOutputStream(); java.util.zip.GZIPOutputStream(bo).use { it.write(html.toByteArray()) }
                ex.sendResponseHeaders(200, bo.size().toLong()); ex.responseBody.write(bo.toByteArray())
            }
            "/.well-known/security.txt" -> { val b = "Contact: mailto:a@b".toByteArray(); ex.sendResponseHeaders(200, b.size.toLong()); ex.responseBody.write(b) }
            else -> ex.sendResponseHeaders(404, -1)
        }
        ex.close()
    }
    srv.start(); val b = "http://127.0.0.1:${srv.address.port}"
    val x = SiteExtras.collect("$b/start")
    ck(x.reachable && x.chain.size == 2 && x.compression == "gzip" && x.htmlBytes == html.toByteArray().size && x.transferBytes < x.htmlBytes, "fetch ${x.error} ${x.chain.size} ${x.compression}")
    val p = x.page
    ck(p.viewport && p.lang == "ru" && p.charset && p.ogTitle && p.ogImage && p.favicon && p.scripts == 2 && p.blockingScripts == 1 && p.stylesheets == 1 && p.images == 2 && p.lazyImages == 1 && p.imagesNoSize == 1 && p.jsonLd == listOf("Organization", "WebSite"), "page ${p.scripts} ${p.blockingScripts} ${p.jsonLd} ${p.imagesNoSize}")
    ck(x.securityTxt && x.cookiesTotal == 1 && x.cookiesInsecure == 1 && x.headers["server"] == "nginx/1.18.0", "headers")
    ck(x.linksChecked == 2 && x.links.any { it.code == 404 && it.internal }, "links ${x.links.map{it.url to it.code}}")
    val a = Models.AuditResult(); a.target = "$b/"; a.host = "127.0.0.1"; a.httpCode = 200; a.title = "T"; a.titleLength = 1; a.h1Count = 1; a.timestamp = 1; a.robotsAvailable = false
    a.domainExpires = "2099-01-01T00:00:00Z"; a.dnsStatus = "ok"; a.spfPresent = true
    val i = Models.Issue(); i.code = "DESCRIPTION_MISSING"; i.severity = "important"; i.title = "Отсутствует meta description"; i.category = "content"; a.issues.add(i)
    val r = SiteReportBuilder.build(a, x, 50)
    ck(r.reachable && r.score in 30..90 && r.sections.size == 7 && r.delta == r.score - 50, "report ${r.score}")
    val sec = r.sections.first { it.key == "security" }
    ck(sec.checks.any { it.title.contains("без шифрования") && it.level == Level.BAD } && sec.checks.any { it.title.contains("раскрывает") } && sec.checks.any { it.title.contains("Cookie без") }, "security checks")
    ck(r.sections.first { it.key == "links" }.checks.any { it.title.startsWith("Битые ссылки: 1") && it.level == Level.BAD }, "broken")
    ck(r.sections.first { it.key == "seo" }.checks.any { it.title.contains("meta description") && it.level == Level.BAD }, "legacy issue")
    ck(r.sections.first { it.key == "domain" }.checks.any { it.title == "Домен оплачен" } , "domain ${r.sections.first { it.key == "domain" }.checks.map{it.title}}")
    ck(r.tasks.first().second.level == Level.BAD && SiteReportBuilder.toHtml(r).contains("Что сделать") && SiteReportBuilder.toText(r).contains("/100"), "export")
    val dead = SiteExtras.collect("http://no-such-host.invalid/"); val a2 = Models.AuditResult(); a2.error = "x"
    ck(!SiteReportBuilder.build(a2, dead, null).reachable && SiteReportBuilder.build(a2, dead, null).score == 0, "unreachable")
    srv.stop(0)
    ck(IpTools.reversedV4("1.2.3.4") == "4.3.2.1", "reverse")
    val tp = IpTools.tcpPing("127.0.0.1", 2, 500, listOf(9)); ck(tp.ok || tp.error.isNotEmpty(), "tcp ping ${tp.method}")
    }

    @Test fun catchup() {
    val c = Channel(); c.url = "http://s/ch/index.m3u8?token=1"
    ck(Playlist.catchupUrl(c, 1000_000, 2000_000, 5000_000) == null, "none")
    c.catchup = "shift"; ck(Playlist.catchupUrl(c, 1000_000, 2000_000, 5000_000) == "http://s/ch/index.m3u8?token=1&utc=1000&lutc=5000", "shift")
    c.catchup = "append"; c.catchupSource = "&from=\${start}&dur=\${duration}"; ck(Playlist.catchupUrl(c, 1000_000, 2000_000, 5000_000) == "http://s/ch/index.m3u8?token=1&from=1000&dur=1000", "append")
    c.catchup = "default"; c.catchupSource = "http://a/{utc}-{utcend}/{Y}{m}{d}"; ck(Playlist.catchupUrl(c, 1000_000, 2000_000, 5000_000) == "http://a/1000-2000/19700101", "default " + Playlist.catchupUrl(c, 1000_000, 2000_000, 5000_000))
    c.catchup = "flussonic"; c.catchupSource = ""; ck(Playlist.catchupUrl(c, 1000_000, 2000_000) == "http://s/ch/timeshift_abs-1000.m3u8?token=1", "flussonic")
    }
}


/** Крошечный HTTP-сервер для тестов (com.sun.net.httpserver недоступен на Android-classpath). */
class MiniHeaders { val map = LinkedHashMap<String, MutableList<String>>()
    fun add(k: String, v: String) { map.getOrPut(k) { mutableListOf() }.add(v) }
    fun getFirst(k: String): String? = map.entries.firstOrNull { it.key.equals(k, true) }?.value?.firstOrNull() }
class MiniExchange(val method: String, val requestURI: java.net.URI, val requestHeaders: MiniHeaders, private val out: java.io.OutputStream) {
    val responseHeaders = MiniHeaders(); val responseBody = java.io.ByteArrayOutputStream()
    private var code = 200; private var closed = false
    fun sendResponseHeaders(c: Int, len: Long) { code = c }
    fun close() { if (closed) return; closed = true
        val body = responseBody.toByteArray(); val sb = StringBuilder("HTTP/1.1 $code X\r\n")
        responseHeaders.map.forEach { (k, vs) -> vs.forEach { sb.append("$k: $it\r\n") } }
        sb.append("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1)); if (method != "HEAD") out.write(body); out.flush() } }
class MiniHttp { private val ss = java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
    private val routes = ArrayList<Pair<String, (MiniExchange) -> Unit>>()
    val address: java.net.InetSocketAddress get() = java.net.InetSocketAddress("127.0.0.1", ss.localPort)
    fun createContext(path: String, h: (MiniExchange) -> Unit) { routes.add(path to h) }
    fun start() { Thread { while (!ss.isClosed) { val c = try { ss.accept() } catch (e: Exception) { break }
        Thread { try { c.use { sock -> val rd = java.io.BufferedReader(java.io.InputStreamReader(sock.getInputStream(), Charsets.ISO_8859_1))
            val first = rd.readLine()?.split(" ") ?: return@use; val hs = MiniHeaders()
            while (true) { val l = rd.readLine() ?: break; if (l.isEmpty()) break; val i = l.indexOf(':'); if (i > 0) hs.add(l.substring(0, i).trim(), l.substring(i + 1).trim()) }
            val uri = java.net.URI(first[1]); val ex = MiniExchange(first[0], uri, hs, sock.getOutputStream())
            val h = routes.filter { uri.path.startsWith(it.first) }.maxByOrNull { it.first.length }?.second
            if (h == null) ex.sendResponseHeaders(404, -1) else h(ex); ex.close() } } catch (e: Exception) { } }.apply { isDaemon = true }.start() } }.apply { isDaemon = true }.start() }
    fun stop(delay: Int) { ss.close() } }
