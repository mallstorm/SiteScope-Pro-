package pro.sitescope.app.keys

/** Что ищем: категория, типы ключей в ней и готовые источники (публичные списки). */
class KeyKind(val id: String, val title: String, val sub: String, val types: Set<String>, val sources: List<String>)

object KeySources {
    private const val GH = "https://raw.githubusercontent.com/"
    val XRAY_TYPES = setOf("vless", "vmess", "trojan", "ss")
    val UDP_TYPES = setOf("hysteria2", "tuic", "wireguard")
    val PROXY_TYPES = setOf("socks", "http")

    val kinds = listOf(
        KeyKind("xray", "VLESS, VMess, Trojan, SS", "Ключи Xray / v2rayNG", XRAY_TYPES, listOf(
            GH + "barry-far/V2ray-Config/main/Splitted-By-Protocol/vless.txt", GH + "barry-far/V2ray-Config/main/Splitted-By-Protocol/vmess.txt",
            GH + "barry-far/V2ray-Config/main/Splitted-By-Protocol/trojan.txt", GH + "barry-far/V2ray-Config/main/Splitted-By-Protocol/ss.txt",
            GH + "Delta-Kronecker/V2Ray-Config/main/config/protocols/vless.txt", GH + "Delta-Kronecker/V2Ray-Config/main/config/protocols/vmess.txt",
            GH + "Delta-Kronecker/V2Ray-Config/main/config/protocols/trojan.txt", GH + "Delta-Kronecker/V2Ray-Config/main/config/protocols/ss.txt",
            GH + "ts-sf/fly/main/v2")),
        KeyKind("udp", "Hysteria2, TUIC, WireGuard", "Быстрые UDP-протоколы, отклик не проверяется", UDP_TYPES, listOf(
            GH + "barry-far/V2ray-Config/main/All_Configs_Sub.txt", GH + "mrvcoder/V2rayCollector/main/mixed_iran.txt", GH + "mahdibland/V2RayAggregator/master/sub/sub_merge_base64.txt")),
        KeyKind("socks", "SOCKS5", "Открытые серверы IPv4 и IPv6", setOf("socks"), listOf(
            GH + "TheSpeedX/PROXY-List/master/socks5.txt", GH + "monosans/proxy-list/main/proxies/socks5.txt", GH + "hookzof/socks5_list/master/proxy.txt",
            GH + "roosterkid/openproxylist/main/SOCKS5_RAW.txt", GH + "proxifly/free-proxy-list/main/proxies/protocols/socks5/data.txt",
            GH + "vakhov/fresh-proxy-list/master/socks5.txt", GH + "zloi-user/hideip.me/master/socks5.txt", GH + "iplocate/free-proxy-list/main/all-proxies.txt")),
        KeyKind("http", "HTTP и HTTPS", "Прокси с проверкой HTTPS", setOf("http"), listOf(
            GH + "TheSpeedX/PROXY-List/master/http.txt", GH + "monosans/proxy-list/main/proxies/http.txt", GH + "proxifly/free-proxy-list/main/proxies/protocols/http/data.txt",
            GH + "clarketm/proxy-list/master/proxy-list-raw.txt", GH + "jetkai/proxy-list/main/online-proxies/txt/proxies-https.txt", GH + "roosterkid/openproxylist/main/HTTPS_RAW.txt",
            GH + "ShiftyTR/Proxy-List/master/https.txt", GH + "zloi-user/hideip.me/master/https.txt", GH + "Zaeem20/FREE_PROXIES_LIST/master/https.txt",
            GH + "sunny9577/proxy-scraper/master/generated/http_proxies.txt", GH + "vakhov/fresh-proxy-list/master/https.txt", GH + "MuRongPIG/Proxy-Master/main/http.txt")),
        KeyKind("elite", "Элитные прокси", "HTTPS и проверка IP выхода", PROXY_TYPES, listOf(
            "https://api.proxyscrape.com/v2/?request=getproxies&protocol=http&timeout=10000&country=all&ssl=all&anonymity=elite",
            "https://api.proxyscrape.com/v2/?request=getproxies&protocol=socks5&timeout=10000&country=all",
            "https://www.proxy-list.download/api/v1/get?type=https&anon=elite", "https://www.proxy-list.download/api/v1/get?type=http&anon=elite",
            "https://www.proxy-list.download/api/v1/get?type=socks5&anon=elite")),
        KeyKind("sub", "Подписки", "Готовые сборники и ссылки-подписки на страницах", XRAY_TYPES + UDP_TYPES, listOf(
            GH + "mahdibland/V2RayAggregator/master/sub/sub_merge.txt", GH + "peasoft/NoMoreWalls/master/list_raw.txt", GH + "ermaozi/get_subscribe/main/subscribe/v2ray.txt",
            GH + "Pawdroid/Free-servers/main/sub", GH + "freefq/free/master/v2", GH + "barry-far/V2ray-Config/main/All_Configs_Sub.txt", GH + "mrvcoder/V2rayCollector/main/mixed_iran.txt"))
    )

    fun byId(id: String) = kinds.firstOrNull { it.id == id }

    /** Подпись источника для списка: для GitHub — владелец/репозиторий. */
    fun label(url: String): String {
        val host = KeyCrawler.hostOf(url)
        if (host == "raw.githubusercontent.com") { val p = url.substringAfter(host + "/").split('/'); if (p.size >= 2) return p[0] + "/" + p[1] }
        return host.ifEmpty { url }
    }

    /** Какого типа прокси в списке с этой ссылки: по имени файла, иначе все выбранные типы. */
    fun proxyTypesFor(url: String, wanted: Set<String>): Set<String> {
        val want = wanted intersect PROXY_TYPES
        if (want.isEmpty()) return emptySet()
        val u = url.substringAfter("://").lowercase()
        return when {
            u.contains("socks4") -> emptySet()
            u.contains("socks") -> want intersect setOf("socks")
            u.contains("http") -> want intersect setOf("http")
            else -> want
        }
    }
}
