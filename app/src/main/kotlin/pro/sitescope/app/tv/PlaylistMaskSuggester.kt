package pro.sitescope.app.tv

import java.net.URI

/**
 * Предлагает независимые числовые маски из ссылки импортированного M3U.
 * Не выполняет сетевые запросы и не изменяет query-параметры (в том числе подписи URL).
 * Каждая маска проверяется только после явного запуска пользователем.
 */
object PlaylistMaskSuggester {
    data class Suggestion(val title: String, val template: String, val variants: Int)

    fun suggest(source: String): List<Suggestion> {
        val url = source.substringBefore('|').trim()
        val uri = try { URI(url) } catch (_: Exception) { return emptyList() }
        val scheme = uri.scheme?.lowercase() ?: return emptyList()
        if (scheme !in setOf("http", "https", "rtsp", "rtmp")) return emptyList()
        val host = uri.host ?: return emptyList()
        val authority = uri.rawAuthority ?: return emptyList()
        val prefix = url.substring(0, url.indexOf("://") + 3)
        val tail = url.substring(prefix.length + authority.length)
        val origin = prefix + authority
        val suggestions = LinkedHashMap<String, Suggestion>()
        fun add(title: String, value: String, variants: Int) {
            if (value != url && variants > 1) suggestions.putIfAbsent(value, Suggestion(title, value, variants))
        }

        val ipv4 = host.split('.')
        if (ipv4.size == 4 && ipv4.all { it.isNotEmpty() && it.all(Char::isDigit) && (it.toIntOrNull() ?: 256) in 0..255 }) {
            for (i in 3 downTo 0) {
                val candidateHost = ipv4.toMutableList().apply { this[i] = "%d{0-255}" }.joinToString(".")
                add("IP: октет ${i + 1} (0–255)", origin.replaceFirst(host, candidateHost) + tail, 256)
            }
        }

        val port = uri.port
        if (port in 1..65535) {
            val portToken = ":$port"
            val position = origin.lastIndexOf(portToken)
            if (position >= 0 && position + portToken.length == origin.length) {
                val base = port / 100 * 100
                if (base + 99 <= 65535) {
                    val replacement = ":" + (if (base == 0) "" else (base / 100).toString()) + "%02d{0-99}"
                    add("Порт: $base–${base + 99}", origin.substring(0, position) + replacement + tail, 100)
                }
                val ten = port / 10 * 10
                add("Порт: $ten–${ten + 9}", origin.substring(0, position) + ":" + (port / 10) + "%d{0-9}" + tail, 10)
            }
        }

        // Подстановка только в path: query-параметры и токены доступа не изменяются.
        val rawPath = uri.rawPath ?: ""
        val pathOffset = tail.indexOf(rawPath)
        if (rawPath.isNotEmpty() && pathOffset >= 0) {
            for (match in Regex("\\d+").findAll(rawPath).take(4)) {
                val width = match.value.length.coerceAtMost(3)
                val upper = when (width) { 1 -> 9; 2 -> 99; else -> 999 }
                val token = if (width == 1) "%d{0-$upper}" else "%0${width}d{0-$upper}"
                val changed = rawPath.replaceRange(match.range, token)
                add("Путь: число ${match.value} (0–$upper)", origin + tail.replaceRange(pathOffset, pathOffset + rawPath.length, changed), upper + 1)
            }
        }
        return suggestions.values.toList()
    }
}
