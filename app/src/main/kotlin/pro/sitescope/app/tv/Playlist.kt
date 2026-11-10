package pro.sitescope.app.tv

import java.net.URLEncoder
import java.security.MessageDigest

/** Состояние канала после проверки — в понятных пользователю терминах. */
enum class StreamStatus(val title: String) {
    UNKNOWN("Не проверен"), OK("Работает"), SLOW("Медленно"), BLOCKED("Доступ закрыт"),
    AUTH("Нужен вход"), DEAD("Не отвечает")
}

class Channel {
    var id: String = ""
    var name: String = ""
    var group: String = ""
    var logo: String = ""
    var url: String = ""
    var tvgId: String = ""
    var tvgName: String = ""
    var userAgent: String = ""
    var referrer: String = ""
    var catchup: String = ""
    var catchupSource: String = ""
    var catchupDays: Int = 0
    var sourceId: String = ""
    var number: Int = 0

    var status: StreamStatus = StreamStatus.UNKNOWN
    var detail: String = ""
    var startMs: Long = 0
    var bitrateKbps: Long = 0
    var video: String = ""
    var audio: String = ""
    var resolution: String = ""
    var container: String = ""
    var httpCode: Int = 0
    var checkedAt: Long = 0
    var okCount: Int = 0
    var failCount: Int = 0
    var favorite: Boolean = false
    var lastWatched: Long = 0

    val uptimePercent: Int get() = if (okCount + failCount == 0) -1 else okCount * 100 / (okCount + failCount)
    val isLocalStream: Boolean get() = url.startsWith("udp://", true) || url.startsWith("rtp://", true)
    val quality: String get() {
        val h = resolution.substringAfter('x', "").toIntOrNull() ?: return ""
        return when { h >= 2000 -> "4K"; h >= 1000 -> "FHD"; h >= 700 -> "HD"; else -> "SD" }
    }
}

class PlaylistData(val channels: List<Channel>, val epgUrls: List<String>)

object Playlist {
    private val attr = Regex("([A-Za-z0-9_-]+)=\"([^\"]*)\"")

    fun channelId(url: String, name: String): String {
        val md = MessageDigest.getInstance("SHA-1").digest((url.trim() + "|" + name.trim()).toByteArray())
        return md.take(8).joinToString("") { "%02x".format(it) }
    }

    fun looksLikeM3u(text: String): Boolean {
        val head = text.trimStart('﻿', ' ', '\n', '\r').take(2048)
        return head.startsWith("#EXTM3U") || head.contains("#EXTINF")
    }

    fun parse(text: String, sourceId: String = ""): PlaylistData {
        val channels = ArrayList<Channel>()
        val epg = LinkedHashSet<String>()
        var pending: Channel? = null
        var pendingGroup = ""
        var number = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim().trimStart('﻿')
            if (line.isEmpty()) continue
            when {
                line.startsWith("#EXTM3U") -> {
                    for (m in attr.findAll(line)) {
                        val key = m.groupValues[1].lowercase()
                        if (key == "url-tvg" || key == "x-tvg-url" || key == "tvg-url")
                            m.groupValues[2].split(',').map { it.trim() }.filter { it.startsWith("http") }.forEach { epg.add(it) }
                    }
                }
                line.startsWith("#EXTINF") -> {
                    val c = Channel()
                    val comma = lastTopLevelComma(line)
                    val head = if (comma >= 0) line.substring(0, comma) else line
                    c.name = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    for (m in attr.findAll(head)) {
                        val value = m.groupValues[2].trim()
                        when (m.groupValues[1].lowercase()) {
                            "tvg-id" -> c.tvgId = value
                            "tvg-name" -> c.tvgName = value
                            "tvg-logo", "logo" -> c.logo = value
                            "group-title" -> c.group = value
                            "catchup", "tvg-rec" -> c.catchup = value
                            "catchup-source" -> c.catchupSource = value
                            "catchup-days", "timeshift" -> c.catchupDays = value.toIntOrNull() ?: 0
                            "user-agent", "http-user-agent" -> c.userAgent = value
                            "http-referrer", "referer" -> c.referrer = value
                        }
                    }
                    if (c.name.isEmpty()) c.name = c.tvgName
                    pending = c
                }
                line.startsWith("#EXTGRP:") -> pendingGroup = line.substringAfter(':').trim()
                line.startsWith("#EXTVLCOPT:") -> {
                    val opt = line.substringAfter(':')
                    val key = opt.substringBefore('=').trim().lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    val c = pending ?: Channel().also { pending = it }
                    when (key) {
                        "http-user-agent" -> c.userAgent = value
                        "http-referrer", "http-referer" -> c.referrer = value
                    }
                }
                line.startsWith("#") -> { /* прочие теги пропускаем */ }
                else -> {
                    if (!isStreamUrl(line)) { pending = null; pendingGroup = ""; continue }
                    val c = pending ?: Channel()
                    var url = line
                    // Формат «url|User-Agent=...&Referer=...»
                    val bar = url.indexOf('|')
                    if (bar > 0) {
                        for (pair in url.substring(bar + 1).split('&')) {
                            val k = pair.substringBefore('=').trim().lowercase()
                            val v = pair.substringAfter('=', "").trim()
                            if (k == "user-agent" && c.userAgent.isEmpty()) c.userAgent = v
                            if ((k == "referer" || k == "referrer") && c.referrer.isEmpty()) c.referrer = v
                        }
                        url = url.substring(0, bar).trim()
                    }
                    c.url = url
                    if (c.group.isEmpty()) c.group = pendingGroup
                    if (c.name.isEmpty()) c.name = "Канал ${number + 1}"
                    c.number = ++number
                    c.sourceId = sourceId
                    c.id = channelId(c.url, c.name)
                    channels.add(c)
                    pending = null; pendingGroup = ""
                }
            }
        }
        return PlaylistData(channels, epg.toList())
    }

    /** Запятая, отделяющая название, — последняя вне кавычек. */
    private fun lastTopLevelComma(line: String): Int {
        var inQuotes = false
        var first = -1
        for (i in line.indices) {
            val ch = line[i]
            if (ch == '"') inQuotes = !inQuotes
            else if (ch == ',' && !inQuotes) { first = i; break }
        }
        return first
    }

    fun isStreamUrl(value: String): Boolean {
        val v = value.lowercase()
        return v.startsWith("http://") || v.startsWith("https://") || v.startsWith("udp://") ||
            v.startsWith("rtp://") || v.startsWith("rtsp://") || v.startsWith("rtmp://") || v.startsWith("mms://")
    }

    /** Убирает повторы по адресу потока; возвращает число удалённых. */
    fun dedupe(channels: MutableList<Channel>): Int {
        val seen = HashSet<String>()
        val before = channels.size
        val it = channels.iterator()
        while (it.hasNext()) if (!seen.add(it.next().url.trim().lowercase())) it.remove()
        return before - channels.size
    }

    fun export(channels: List<Channel>, epgUrls: List<String> = emptyList()): String {
        val sb = StringBuilder("#EXTM3U")
        if (epgUrls.isNotEmpty()) sb.append(" url-tvg=\"").append(epgUrls.joinToString(",")).append('"')
        sb.append('\n')
        for (c in channels) {
            sb.append("#EXTINF:-1")
            if (c.tvgId.isNotEmpty()) sb.append(" tvg-id=\"").append(q(c.tvgId)).append('"')
            if (c.logo.isNotEmpty()) sb.append(" tvg-logo=\"").append(q(c.logo)).append('"')
            if (c.group.isNotEmpty()) sb.append(" group-title=\"").append(q(c.group)).append('"')
            if (c.catchup.isNotEmpty()) sb.append(" catchup=\"").append(q(c.catchup)).append('"')
            if (c.catchupDays > 0) sb.append(" catchup-days=\"").append(c.catchupDays).append('"')
            if (c.catchupSource.isNotEmpty()) sb.append(" catchup-source=\"").append(q(c.catchupSource)).append('"')
            sb.append(',').append(c.name.replace('\n', ' ')).append('\n')
            if (c.userAgent.isNotEmpty()) sb.append("#EXTVLCOPT:http-user-agent=").append(c.userAgent).append('\n')
            if (c.referrer.isNotEmpty()) sb.append("#EXTVLCOPT:http-referrer=").append(c.referrer).append('\n')
            sb.append(c.url).append('\n')
        }
        return sb.toString()
    }

    private fun q(v: String) = v.replace("\"", "'")

    /** Ссылка на плейлист Xtream Codes по адресу портала и учётным данным. */
    fun xtreamUrl(server: String, user: String, password: String): String {
        var base = server.trim().trimEnd('/')
        require(base.isNotEmpty() && user.isNotBlank() && password.isNotBlank()) { "Заполните адрес, логин и пароль" }
        if (!base.startsWith("http://", true) && !base.startsWith("https://", true)) base = "http://$base"
        return base + "/get.php?username=" + URLEncoder.encode(user.trim(), "UTF-8") +
            "&password=" + URLEncoder.encode(password.trim(), "UTF-8") + "&type=m3u_plus&output=ts"
    }

    /** Адрес для воспроизведения через udpxy (если задан) — как в прежней версии. */
    fun playbackUrl(udpxyBase: String, url: String): String {
        val base = udpxyBase.trim().trimEnd('/')
        if (base.isEmpty()) return url
        val lower = url.lowercase()
        val kind = when { lower.startsWith("udp://") -> "udp"; lower.startsWith("rtp://") -> "rtp"; else -> return url }
        val target = url.substring(6).trimStart('@')
        val prefix = if (base.startsWith("http://", true) || base.startsWith("https://", true)) base else "http://$base"
        return "$prefix/$kind/$target"
    }

    /** Ссылка на запись передачи из архива (catch-up). null — архив для канала не описан. */
    fun catchupUrl(c: Channel, startMs: Long, endMs: Long, nowMs: Long = System.currentTimeMillis()): String? {
        val start = startMs / 1000; val end = endMs / 1000; val now = nowMs / 1000
        val mode = c.catchup.lowercase()
        if (mode.isEmpty() && c.catchupSource.isEmpty() && c.catchupDays <= 0) return null
        fun fill(template: String): String {
            val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")); cal.timeInMillis = startMs
            fun two(v: Int) = v.toString().padStart(2, '0')
            return template
                .replace("\${start}", start.toString()).replace("{utc}", start.toString()).replace("\${timestamp}", start.toString())
                .replace("\${end}", end.toString()).replace("{utcend}", end.toString())
                .replace("\${duration}", (end - start).toString()).replace("{duration}", (end - start).toString())
                .replace("\${offset}", (now - start).toString()).replace("{offset}", (now - start).toString())
                .replace("{lutc}", now.toString()).replace("\${now}", now.toString())
                .replace("{Y}", cal.get(java.util.Calendar.YEAR).toString()).replace("{m}", two(cal.get(java.util.Calendar.MONTH) + 1))
                .replace("{d}", two(cal.get(java.util.Calendar.DAY_OF_MONTH))).replace("{H}", two(cal.get(java.util.Calendar.HOUR_OF_DAY)))
                .replace("{M}", two(cal.get(java.util.Calendar.MINUTE))).replace("{S}", two(cal.get(java.util.Calendar.SECOND)))
        }
        return when {
            mode == "append" && c.catchupSource.isNotEmpty() -> c.url + fill(c.catchupSource)
            c.catchupSource.isNotEmpty() -> fill(c.catchupSource)
            mode == "flussonic" || mode == "fs" || mode == "flussonic-hls" || mode == "flussonic-ts" -> {
                val base = c.url.substringBefore('?'); val query = c.url.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
                val root = base.substringBeforeLast('/')
                if (base.endsWith(".m3u8")) "$root/timeshift_abs-$start.m3u8$query" else "$root/timeshift_abs-$start.ts$query"
            }
            else -> c.url + (if (c.url.contains('?')) "&" else "?") + "utc=$start&lutc=$now"
        }
    }
}
