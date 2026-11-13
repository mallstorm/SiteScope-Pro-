package pro.sitescope.app.anon

import java.net.URI

/** Куда направить запрос браузера: напрямую, через Tor или через I2P. Чистая логика, без Android. */
object AnonRouting {
    enum class Route { DIRECT, TOR, I2P }

    fun hostOf(input: String): String {
        val raw = input.trim()
        if (raw.isEmpty()) return ""
        val withScheme = if (raw.contains("://")) raw else "http://$raw"
        return try {
            (URI(withScheme).host ?: "").lowercase().trimEnd('.')
        } catch (e: Exception) {
            raw.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
                .substringAfterLast('@').substringBefore(':').lowercase().trimEnd('.')
        }
    }

    fun isOnion(host: String) = host.lowercase().trimEnd('.').let { it == "onion" || it.endsWith(".onion") }
    fun isI2p(host: String) = host.lowercase().trimEnd('.').let { it.endsWith(".i2p") }
    fun isAnonHost(host: String) = isOnion(host) || isI2p(host)
    fun looksAnon(input: String) = isAnonHost(hostOf(input))

    /** Адрес из локальной сети / loopback: через Tor его не отправляем даже при режиме «весь трафик». */
    fun isLocalHost(host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        if (h.isEmpty() || h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
        if (!h.contains('.') && !h.contains(':')) return true            // короткое имя роутера
        val parts = h.split('.')
        if (parts.size == 4 && parts.all { p -> p.toIntOrNull()?.let { it in 0..255 } == true }) {
            val a = parts[0].toInt(); val b = parts[1].toInt()
            return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 169 && b == 254) || (a == 100 && b in 64..127)
        }
        if (h.contains(':')) return h == "::1" || h.startsWith("fe80") || h.startsWith("fc") || h.startsWith("fd")
        return false
    }

    fun routeOf(host: String, torForAll: Boolean = false): Route = when {
        isOnion(host) -> Route.TOR
        isI2p(host) -> Route.I2P
        torForAll && !isLocalHost(host) -> Route.TOR
        else -> Route.DIRECT
    }

    /** Приводит введённое в адресную строку к URL. Для .onion/.i2p и локальных адресов по умолчанию http://. */
    fun normalize(input: String): String {
        val value = input.trim()
        if (value.isEmpty()) return value
        if (value.contains("://") || value.startsWith("about:") || value.startsWith("data:") || value.startsWith("javascript:")) return value
        return "http://$value"
    }
}

/** Куда подключаться вместо прямого соединения. */
sealed class Upstream {
    /** SOCKS5 с удалённым разрешением имён (Tor, i2pd SOCKS). */
    data class Socks5(val host: String, val port: Int) : Upstream()
    /** HTTP-прокси (I2P: 127.0.0.1:4444). */
    data class Http(val host: String, val port: Int) : Upstream()
}

/** Куда вести трафик для каждого направления; null — направление недоступно. */
data class AnonConfig(
    val tor: Upstream? = null,
    val i2p: Upstream? = null,
    val torForAll: Boolean = false,
    /** Если задан — обычные (не локальные) адреса идут через этот SOCKS5 (обход DPI). */
    val direct: Upstream? = null,
)
