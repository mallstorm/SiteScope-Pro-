package pro.sitescope.app.tv

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Проверка потока по шагам: имя сервера → соединение → ответ → плейлист → первый кусок видео.
 * Чистая логика на java.net; MPEG-TS разбирается прежним [pro.sitescope.app.IptvEngine] через [tsInspector].
 */
object StreamChecker {
    const val DEFAULT_UA = "VLC/3.0.21 LibVLC/3.0.21"
    private const val MAX_BYTES = 262_144
    private const val SLOW_START_MS = 5000L

    class Result {
        var status = StreamStatus.DEAD
        var detail = ""
        var httpCode = 0
        var startMs = 0L
        var dnsMs = 0L
        var bitrateKbps = 0L       // заявленный или измеренный битрейт
        var speedKbps = 0L         // фактическая скорость загрузки
        var container = ""
        var video = ""
        var audio = ""
        var resolution = ""
        var serviceName = ""
        var finalUrl = ""
        var redirects = 0
        var encrypted = false
        val steps = ArrayList<String>()
    }

    class TsInfo(val video: String, val audio: String, val serviceName: String)

    /** Подключается из Android-слоя: разбор PAT/PMT/SDT прежним движком. */
    @Volatile var tsInspector: ((ByteArray) -> TsInfo?)? = null

    class Variant(val url: String, val bandwidth: Long, val resolution: String, val codecs: String)

    private class Fetched(val code: Int, val contentType: String, val data: ByteArray, val finalUrl: String,
                          val redirects: Int, val firstByteMs: Long, val readMs: Long)

    fun check(url: String, userAgent: String = "", referrer: String = "", timeoutMs: Int = 8000): Result {
        val r = Result()
        val started = System.currentTimeMillis()
        try {
            val lower = url.lowercase()
            if (!(lower.startsWith("http://") || lower.startsWith("https://"))) {
                r.status = StreamStatus.UNKNOWN
                r.detail = "Этот тип потока проверяется только при просмотре"
                return r
            }
            val host = URL(url).host
            val dnsStart = System.currentTimeMillis()
            try { InetAddress.getByName(host) } catch (e: UnknownHostException) {
                r.detail = "Сервер «$host» не найден"; r.steps.add("Имя сервера: не найдено"); return r
            }
            r.dnsMs = System.currentTimeMillis() - dnsStart
            r.steps.add("Имя сервера найдено за ${r.dnsMs} мс")

            val first = fetch(url, userAgent, referrer, timeoutMs, MAX_BYTES, 4500)
            r.httpCode = first.code; r.finalUrl = first.finalUrl; r.redirects = first.redirects
            r.steps.add("Ответ сервера: ${first.code}" + if (first.redirects > 0) " (переадресаций: ${first.redirects})" else "")
            if (first.code !in 200..299) { applyHttpError(r, first.code); return r }

            val head = String(first.data, 0, minOf(first.data.size, 4096), Charsets.ISO_8859_1).trimStart('﻿', ' ', '\r', '\n')
            val type = first.contentType.lowercase()
            when {
                head.startsWith("#EXTM3U") || type.contains("mpegurl") -> checkHls(r, first, userAgent, referrer, timeoutMs, started)
                findTsSync(first.data) >= 0 -> {
                    r.container = "MPEG-TS"
                    fillTs(r, first.data)
                    r.speedKbps = kbps(first.data.size, first.readMs)
                    if (r.bitrateKbps == 0L) r.bitrateKbps = r.speedKbps
                    finishOk(r, first.firstByteMs, started, "Видео идёт")
                }
                head.contains("<MPD") -> {
                    r.container = "DASH"
                    Regex("height=\"(\\d+)\"").findAll(head).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()?.let { h ->
                        val w = Regex("width=\"(\\d+)\"").findAll(head).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull() ?: 0
                        r.resolution = "${w}x$h"
                    }
                    r.encrypted = head.contains("ContentProtection")
                    finishOk(r, first.firstByteMs, started, if (r.encrypted) "Поток с защитой DRM" else "Манифест DASH получен")
                }
                type.startsWith("video/") || type.startsWith("audio/") || type.contains("octet-stream") && first.data.size > 1024 -> {
                    r.container = if (type.contains("octet-stream")) "Поток" else first.contentType.substringBefore(';')
                    r.speedKbps = kbps(first.data.size, first.readMs)
                    finishOk(r, first.firstByteMs, started, "Медиапоток получен")
                }
                head.startsWith("<!") || head.startsWith("<html", true) || type.contains("text/html") -> {
                    r.status = StreamStatus.DEAD; r.detail = "Сервер вернул веб-страницу, а не видео"
                }
                first.data.isEmpty() -> { r.status = StreamStatus.DEAD; r.detail = "Сервер ответил, но данные не идут" }
                else -> { r.status = StreamStatus.DEAD; r.detail = "Ответ не похож на видеопоток" }
            }
        } catch (e: SocketTimeoutException) {
            r.status = StreamStatus.DEAD; r.detail = "Сервер не ответил вовремя"
        } catch (e: javax.net.ssl.SSLException) {
            r.status = StreamStatus.DEAD; r.detail = "Ошибка защищённого соединения"
        } catch (e: java.io.IOException) {
            r.status = StreamStatus.DEAD; r.detail = "Не удалось соединиться: " + (e.message ?: e.javaClass.simpleName)
        } catch (e: Exception) {
            r.status = StreamStatus.DEAD; r.detail = e.message ?: e.javaClass.simpleName
        }
        return r
    }

    private fun checkHls(r: Result, first: Fetched, ua: String, ref: String, timeoutMs: Int, started: Long) {
        r.container = "HLS"
        var playlistText = String(first.data, Charsets.UTF_8)
        var playlistUrl = first.finalUrl
        val variants = parseMaster(playlistText, playlistUrl)
        if (variants.isNotEmpty()) {
            val best = variants.maxByOrNull { it.bandwidth }!!
            r.bitrateKbps = best.bandwidth / 1000
            r.resolution = best.resolution
            val (v, a) = codecNames(best.codecs)
            r.video = v; r.audio = a
            r.steps.add("Вариантов качества: ${variants.size}")
            val media = fetch(best.url, ua, ref, timeoutMs, MAX_BYTES, 4000)
            if (media.code !in 200..299) {
                r.httpCode = media.code; applyHttpError(r, media.code)
                r.detail = "Список качества есть, но поток недоступен (${media.code})"; return
            }
            playlistText = String(media.data, Charsets.UTF_8); playlistUrl = media.finalUrl
        }
        r.encrypted = playlistText.contains("#EXT-X-KEY") && !playlistText.contains("METHOD=NONE")
        val segments = playlistText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
        if (segments.isEmpty()) { r.status = StreamStatus.DEAD; r.detail = "Плейлист пуст — трансляция не идёт"; return }
        val live = !playlistText.contains("#EXT-X-ENDLIST")
        val segmentUrl = resolve(playlistUrl, if (live) segments.last() else segments.first())
        val seg = try { fetch(segmentUrl, ua, ref, timeoutMs, MAX_BYTES, 4000) } catch (e: Exception) {
            r.status = StreamStatus.DEAD; r.detail = "Плейлист есть, но видео не загружается"; return
        }
        if (seg.code !in 200..299 || seg.data.isEmpty()) {
            if (seg.code == 403 || seg.code == 451) { r.status = StreamStatus.BLOCKED; r.detail = "Плейлист открыт, но видео закрыто (${seg.code})" }
            else { r.status = StreamStatus.DEAD; r.detail = "Плейлист есть, но видео не загружается (${seg.code})" }
            return
        }
        r.steps.add("Фрагмент видео получен: ${seg.data.size / 1024} КБ")
        r.speedKbps = kbps(seg.data.size, seg.readMs)
        if (!r.encrypted && findTsSync(seg.data) >= 0) fillTs(r, seg.data)
        if (r.bitrateKbps == 0L) {
            Regex("#EXTINF:([0-9.]+)").find(playlistText)?.groupValues?.get(1)?.toDoubleOrNull()?.let { /* длительность есть, размер неизвестен */ }
        }
        finishOk(r, first.firstByteMs, started, if (r.encrypted) "Работает (поток зашифрован)" else "Видео идёт")
        if (r.status == StreamStatus.OK && r.bitrateKbps > 0 && r.speedKbps in 1 until r.bitrateKbps && seg.data.size >= MAX_BYTES / 2) {
            r.status = StreamStatus.SLOW
            r.detail = "Скорости сервера не хватает: ${fmtRate(r.speedKbps)} при потоке ${fmtRate(r.bitrateKbps)}"
        }
    }

    private fun finishOk(r: Result, firstByteMs: Long, started: Long, okText: String) {
        r.startMs = System.currentTimeMillis() - started
        if (r.startMs > SLOW_START_MS) {
            r.status = StreamStatus.SLOW; r.detail = "Долго запускается: ${"%.1f".format(r.startMs / 1000.0)} с"
        } else { r.status = StreamStatus.OK; r.detail = okText }
        r.steps.add("Первый ответ за $firstByteMs мс, запуск за ${r.startMs} мс")
    }

    private fun applyHttpError(r: Result, code: Int) {
        when (code) {
            401, 407 -> { r.status = StreamStatus.AUTH; r.detail = "Нужен логин или действующая подписка" }
            403 -> { r.status = StreamStatus.BLOCKED; r.detail = "Доступ запрещён: ограничение по региону, токену или плееру" }
            451 -> { r.status = StreamStatus.BLOCKED; r.detail = "Недоступно в вашем регионе" }
            404, 410 -> { r.status = StreamStatus.DEAD; r.detail = "Канал удалён с сервера ($code)" }
            429 -> { r.status = StreamStatus.BLOCKED; r.detail = "Слишком много подключений к серверу" }
            in 500..599 -> { r.status = StreamStatus.DEAD; r.detail = "Сбой на сервере ($code)" }
            else -> { r.status = StreamStatus.DEAD; r.detail = "Сервер ответил кодом $code" }
        }
    }

    private fun fillTs(r: Result, data: ByteArray) {
        val info = try { tsInspector?.invoke(data) } catch (e: Throwable) { null } ?: return
        if (r.video.isEmpty()) r.video = info.video
        if (r.audio.isEmpty()) r.audio = info.audio
        r.serviceName = info.serviceName
    }

    // ---------- Разбор HLS ----------

    fun parseMaster(text: String, baseUrl: String): List<Variant> {
        val out = ArrayList<Variant>()
        val lines = text.lines().map { it.trim() }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXT-X-STREAM-INF")) {
                val bw = Regex("[^-]BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull()
                    ?: Regex("BANDWIDTH=(\\d+)").find(line)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val res = Regex("RESOLUTION=(\\d+x\\d+)").find(line)?.groupValues?.get(1) ?: ""
                val codecs = Regex("CODECS=\"([^\"]*)\"").find(line)?.groupValues?.get(1) ?: ""
                var j = i + 1
                while (j < lines.size && (lines[j].isEmpty() || lines[j].startsWith("#"))) j++
                if (j < lines.size) out.add(Variant(resolve(baseUrl, lines[j]), bw, res, codecs))
                i = j
            }
            i++
        }
        return out
    }

    fun codecNames(codecs: String): Pair<String, String> {
        var video = ""; var audio = ""
        for (c in codecs.split(',').map { it.trim().lowercase() }) {
            when {
                c.startsWith("avc1") || c.startsWith("avc3") -> video = "H.264"
                c.startsWith("hvc1") || c.startsWith("hev1") -> video = "H.265 (HEVC)"
                c.startsWith("av01") -> video = "AV1"
                c.startsWith("vp09") -> video = "VP9"
                c.startsWith("mp4a.40.34") || c.startsWith("mp4a.69") || c.startsWith("mp4a.6b") -> audio = "MP3"
                c.startsWith("mp4a") -> audio = "AAC"
                c.startsWith("ac-3") -> audio = "AC-3"
                c.startsWith("ec-3") -> audio = "E-AC-3"
                c.startsWith("opus") -> audio = "Opus"
            }
        }
        return video to audio
    }

    fun resolve(base: String, ref: String): String = try { URL(URL(base), ref).toString() } catch (e: Exception) { ref }

    fun findTsSync(data: ByteArray): Int {
        if (data.size < 188 * 3) return -1
        val limit = minOf(data.size - 188 * 2, 2048)
        for (i in 0 until limit) if (data[i] == 0x47.toByte() && data[i + 188] == 0x47.toByte() && data[i + 376] == 0x47.toByte()) return i
        return -1
    }

    fun fmtRate(kbps: Long): String = if (kbps >= 1000) "%.1f Мбит/с".format(kbps / 1000.0) else "$kbps кбит/с"

    private fun kbps(bytes: Int, ms: Long): Long = if (bytes <= 0) 0 else bytes * 8L / maxOf(1L, ms)

    // ---------- HTTP ----------

    private fun fetch(startUrl: String, ua: String, ref: String, timeoutMs: Int, maxBytes: Int, maxReadMs: Long): Fetched {
        var url = startUrl
        var redirects = 0
        val t0 = System.currentTimeMillis()
        while (true) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
                c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", ua.ifBlank { DEFAULT_UA })
                c.setRequestProperty("Accept", "*/*")
                c.setRequestProperty("Icy-MetaData", "0")
                if (ref.isNotBlank()) c.setRequestProperty("Referer", ref)
                val code = c.responseCode
                if (code in 300..399 && code != 304) {
                    val location = c.getHeaderField("Location")
                    if (location.isNullOrBlank() || ++redirects > 6) return Fetched(code, "", ByteArray(0), url, redirects, System.currentTimeMillis() - t0, 0)
                    url = resolve(url, location); continue
                }
                val firstByte = System.currentTimeMillis() - t0
                val type = c.contentType ?: ""
                if (code !in 200..299) return Fetched(code, type, ByteArray(0), url, redirects, firstByte, 0)
                val readStart = System.currentTimeMillis()
                val data = readLimited(c.inputStream, maxBytes, maxReadMs)
                return Fetched(code, type, data, url, redirects, firstByte, maxOf(1L, System.currentTimeMillis() - readStart))
            } finally { c.disconnect() }
        }
    }

    private fun readLimited(input: InputStream, limit: Int, maxMs: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16384)
        val deadline = System.currentTimeMillis() + maxMs
        try {
            while (out.size() < limit && System.currentTimeMillis() < deadline) {
                val n = input.read(buf, 0, minOf(buf.size, limit - out.size()))
                if (n < 0) break
                out.write(buf, 0, n)
            }
        } catch (e: SocketTimeoutException) { if (out.size() == 0) throw e }
        return out.toByteArray()
    }

    // ---------- Пакетная проверка ----------

    interface BatchListener {
        fun onChannel(channel: Channel, done: Int, total: Int)
        fun onFinished(cancelled: Boolean)
    }

    class Batch internal constructor(private val pool: ExecutorService, private val cancelled: AtomicBoolean) {
        fun cancel() { cancelled.set(true); pool.shutdownNow() }
        val isCancelled: Boolean get() = cancelled.get()
    }

    /**
     * Проверяет список параллельно. [localCheck] вызывается для udp/rtp-потоков (multicast в локальной сети).
     */
    fun checkAll(channels: List<Channel>, threads: Int, timeoutMs: Int, udpxyBase: String,
                 localCheck: ((Channel) -> Result)?, listener: BatchListener): Batch {
        val pool = Executors.newFixedThreadPool(threads.coerceIn(1, 32))
        val cancelled = AtomicBoolean(false)
        val done = AtomicInteger(0)
        val total = channels.size
        Thread({
            for (ch in channels) {
                if (cancelled.get()) break
                try {
                    pool.execute {
                        if (cancelled.get()) return@execute
                        val target = Playlist.playbackUrl(udpxyBase, ch.url)
                        val result = try {
                            if (target.startsWith("udp://", true) || target.startsWith("rtp://", true))
                                localCheck?.invoke(ch) ?: Result().apply { status = StreamStatus.UNKNOWN; detail = "Локальный поток" }
                            else check(target, ch.userAgent, ch.referrer, timeoutMs)
                        } catch (e: Throwable) { Result().apply { detail = e.message ?: "Ошибка проверки" } }
                        if (cancelled.get()) return@execute
                        apply(ch, result)
                        listener.onChannel(ch, done.incrementAndGet(), total)
                    }
                } catch (e: Exception) { break }
            }
            pool.shutdown()
            try { pool.awaitTermination(6, TimeUnit.HOURS) } catch (e: InterruptedException) { }
            listener.onFinished(cancelled.get())
        }, "SiteScope-TvCheck").start()
        return Batch(pool, cancelled)
    }

    fun apply(ch: Channel, r: Result) {
        ch.status = r.status; ch.detail = r.detail; ch.httpCode = r.httpCode
        ch.startMs = r.startMs; ch.bitrateKbps = r.bitrateKbps
        ch.container = r.container
        if (r.video.isNotEmpty()) ch.video = r.video
        if (r.audio.isNotEmpty()) ch.audio = r.audio
        if (r.resolution.isNotEmpty()) ch.resolution = r.resolution
        ch.checkedAt = System.currentTimeMillis()
        when (r.status) {
            StreamStatus.OK, StreamStatus.SLOW -> ch.okCount++
            StreamStatus.UNKNOWN -> { }
            else -> ch.failCount++
        }
        if (r.serviceName.isNotEmpty() && ch.name.startsWith("Канал ")) ch.name = r.serviceName
    }
}
