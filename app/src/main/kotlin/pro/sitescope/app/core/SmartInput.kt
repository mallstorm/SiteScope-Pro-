package pro.sitescope.app.core

import pro.sitescope.app.ip.Subnet

/** Определяет, что вставил пользователь, чтобы открыть нужный инструмент. */
object SmartInput {
    enum class Kind(val title: String) {
        EMPTY(""), PLAYLIST("Плейлист ТВ"), STREAM("Видеопоток"), URL("Сайт"), DOMAIN("Сайт"),
        IPV4("IP-адрес"), IPV6("IPv6-адрес"), CIDR("Подсеть"), ASN("Автономная система"), TEXT("Поиск провайдера")
    }

    data class Result(val kind: Kind, val value: String)

    private val domain = Regex("^(?=.{1,253}$)([a-zA-Z0-9а-яА-ЯёЁ]([a-zA-Z0-9а-яА-ЯёЁ-]{0,61}[a-zA-Z0-9а-яА-ЯёЁ])?\\.)+[a-zA-Zа-яА-ЯёЁ]{2,24}$")
    private val asn = Regex("^(?i)as\\s?(\\d{1,10})$")

    fun detect(raw: String): Result {
        val text = raw.trim()
        if (text.isEmpty()) return Result(Kind.EMPTY, "")
        val lower = text.lowercase()
        if (lower.startsWith("udp://") || lower.startsWith("rtp://") || lower.startsWith("rtsp://") || lower.startsWith("rtmp://"))
            return Result(Kind.STREAM, text)
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            val path = lower.substringBefore('?').substringBefore('#')
            val query = lower.substringAfter('?', "")
            if (path.endsWith(".m3u") || path.contains("get.php") || query.contains("type=m3u") || path.contains("/playlist"))
                return Result(Kind.PLAYLIST, text)
            if (path.endsWith(".m3u8") || path.endsWith(".ts") || path.endsWith(".mpd") || path.contains("/udp/") || path.contains("/rtp/"))
                return Result(Kind.STREAM, text)
            return Result(Kind.URL, text)
        }
        asn.find(text)?.let { return Result(Kind.ASN, "AS" + it.groupValues[1]) }
        if (text.contains('/') || text.contains(' ')) {
            try { Subnet.parse(text); return Result(Kind.CIDR, text) } catch (_: Exception) { }
            if (text.contains(':') && text.contains('/')) try { Subnet.v6(text); return Result(Kind.CIDR, text) } catch (_: Exception) { }
        }
        if (Subnet.isIpv4(text)) return Result(Kind.IPV4, text)
        if (text.contains(':') && !text.contains(' ') && !text.contains('/') && text.count { it == ':' } >= 2) {
            try { Subnet.v6(text); return Result(Kind.IPV6, text) } catch (_: Exception) { }
        }
        val host = text.substringBefore('/')
        if (domain.matches(host)) {
            val path = lower.substringBefore('?')
            if (path.endsWith(".m3u")) return Result(Kind.PLAYLIST, "http://$text")
            return Result(if (text.contains('/')) Kind.URL else Kind.DOMAIN, text)
        }
        return Result(Kind.TEXT, text)
    }
}
