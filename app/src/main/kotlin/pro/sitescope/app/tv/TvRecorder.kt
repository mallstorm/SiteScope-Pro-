package pro.sitescope.app.tv

import android.content.Context
import android.os.Environment
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Запись потока в файл «посмотреть позже». Честная постановка задачи: это не интерактивный
 * просмотр архива, а запись байтов канала на диск, пока идёт прямой эфир. Лучше всего работает
 * для потоков с сегментами MPEG-TS (большинство IPTV-плейлистов, HLS с TS-сегментами);
 * для HLS с fMP4-сегментами результат может не склеиться в проигрываемый файл без перекодирования.
 */
object TvRecorder {
    var recording by mutableStateOf(false)
    var channelId by mutableStateOf("")
    var channelName by mutableStateOf("")
    var startedAt by mutableStateOf(0L)
    var bytesWritten by mutableStateOf(0L)
    var filePath by mutableStateOf("")
    var error by mutableStateOf("")
    var finished by mutableStateOf(false)

    @Volatile private var stopRequested = AtomicBoolean(false)
    @Volatile private var worker: Thread? = null

    fun outputDir(context: Context): File {
        val base = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        val dir = File(base, "Записи ТВ")
        dir.mkdirs()
        return dir
    }

    fun recordings(context: Context): List<File> = outputDir(context).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()

    fun start(context: Context, channel: Channel, udpxyBase: String, durationMinutes: Int, userAgent: String = "", referrer: String = "") {
        if (recording) return
        val target = Playlist.playbackUrl(udpxyBase, channel.url)
        if (!(target.startsWith("http://", true) || target.startsWith("https://", true))) {
            error = "Запись доступна только для потоков по http/https — локальные udp/rtp-трансляции записать нельзя"
            return
        }
        val ua = userAgent.ifBlank { channel.userAgent }
        val ref = referrer.ifBlank { channel.referrer }
        val name = sanitize(channel.name.ifBlank { "Канал" }) + "_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".ts"
        val file = File(outputDir(context), name)

        recording = true; finished = false; error = ""; bytesWritten = 0; channelId = channel.id; channelName = channel.name; filePath = file.absolutePath
        startedAt = System.currentTimeMillis()
        stopRequested = AtomicBoolean(false)
        val stopFlag = stopRequested
        val deadline = if (durationMinutes > 0) startedAt + durationMinutes * 60_000L else Long.MAX_VALUE

        worker = Thread({
            try {
                FileOutputStream(file).use { out ->
                    val probe = fetchBody(target, ua, ref, 8000, 8192)
                    val text = String(probe.second, Charsets.ISO_8859_1)
                    val looksHls = text.trimStart('\uFEFF', ' ', '\n', '\r').startsWith("#EXTM3U")
                    if (looksHls) recordHls(target, String(probe.second, Charsets.UTF_8), ua, ref, out, deadline, stopFlag)
                    else recordDirect(target, ua, ref, out, deadline, stopFlag, probe.second)
                }
            } catch (e: Exception) {
                error = e.message ?: "Ошибка записи"
            }
            recording = false; finished = true
        }, "SiteScope-TvRecord")
        worker?.start()
        TvRecordService.start(context)
    }

    fun stop(context: Context? = null) {
        stopRequested.set(true)
        if (context != null) TvRecordService.stop(context)
    }

    fun delete(file: File): Boolean = try { file.delete() } catch (e: Exception) { false }

    private fun sanitize(name: String): String = name.map { if (it.isLetterOrDigit() || it == ' ' || it == '-') it else '_' }.joinToString("").trim().take(60)

    // ---------- Прямая запись байтов (обычный http(s) поток MPEG-TS) ----------

    private fun recordDirect(url: String, ua: String, ref: String, out: FileOutputStream, deadline: Long, stop: AtomicBoolean, already: ByteArray) {
        out.write(already); bytesWritten += already.size
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10000; conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", ua.ifBlank { StreamChecker.DEFAULT_UA })
            if (ref.isNotBlank()) conn.setRequestProperty("Referer", ref)
            if (conn.responseCode !in 200..299) { error = "Сервер ответил кодом ${conn.responseCode}"; return }
            val buf = ByteArray(32768)
            val input = conn.inputStream
            while (!stop.get() && System.currentTimeMillis() < deadline) {
                val n = try { input.read(buf) } catch (e: SocketTimeoutException) { continue }
                if (n < 0) break
                out.write(buf, 0, n); bytesWritten += n
            }
        } finally { conn.disconnect() }
    }

    // ---------- Запись HLS: скачиваем сегменты по порядку, следим за «хвостом» эфира ----------

    private fun recordHls(startUrl: String, firstPlaylist: String, ua: String, ref: String, out: FileOutputStream, deadline: Long, stop: AtomicBoolean) {
        var mediaUrl = startUrl
        var playlistText = firstPlaylist
        val variants = StreamChecker.parseMaster(playlistText, startUrl)
        if (variants.isNotEmpty()) {
            mediaUrl = variants.maxByOrNull { it.bandwidth }!!.url
            val fetched = fetchBody(mediaUrl, ua, ref, 8000, 2_000_000)
            playlistText = String(fetched.second, Charsets.UTF_8)
        }
        // Для живого эфира сегменты «сползают» вперёд — скачиваем только новые по мере появления.
        // Для готовой записи (VOD, есть #EXT-X-ENDLIST) просто проходим весь список один раз.
        val seen = HashSet<String>()
        var emptyPolls = 0
        while (!stop.get() && System.currentTimeMillis() < deadline) {
            val segments = playlistText.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
            val absolute = segments.map { StreamChecker.resolve(mediaUrl, it) }
            val toDownload = absolute.filter { seen.add(it) }
            if (toDownload.isEmpty()) emptyPolls++ else emptyPolls = 0
            for (seg in toDownload) {
                if (stop.get() || System.currentTimeMillis() >= deadline) break
                try {
                    val data = fetchBody(seg, ua, ref, 8000, 8_000_000).second
                    out.write(data); bytesWritten += data.size
                } catch (e: Exception) { /* пропускаем битый фрагмент, продолжаем запись */ }
            }
            val vod = playlistText.contains("#EXT-X-ENDLIST")
            if (vod || emptyPolls > 20) break
            Thread.sleep(4000)
            if (stop.get() || System.currentTimeMillis() >= deadline) break
            try {
                val fetched = fetchBody(mediaUrl, ua, ref, 8000, 2_000_000)
                playlistText = String(fetched.second, Charsets.UTF_8)
            } catch (e: Exception) { Thread.sleep(2000) }
        }
    }

    /** Простая загрузка без разбора плейлистов/редиректов второй степени — для записи этого достаточно. */
    private fun fetchBody(url: String, ua: String, ref: String, timeoutMs: Int, maxBytes: Int): Pair<Int, ByteArray> {
        var current = url
        for (i in 0 until 5) {
            val c = URL(current).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", ua.ifBlank { StreamChecker.DEFAULT_UA })
                if (ref.isNotBlank()) c.setRequestProperty("Referer", ref)
                val code = c.responseCode
                if (code in 300..399) { current = StreamChecker.resolve(current, c.getHeaderField("Location") ?: throw IOException("Переадресация без адреса")); continue }
                if (code !in 200..299) throw IOException("Код ответа $code")
                val out = java.io.ByteArrayOutputStream()
                val buf = ByteArray(16384)
                val input = c.inputStream
                while (out.size() < maxBytes) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                return code to out.toByteArray()
            } finally { c.disconnect() }
        }
        throw IOException("Слишком много переадресаций")
    }
}
