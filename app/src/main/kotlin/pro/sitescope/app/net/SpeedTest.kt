package pro.sitescope.app.net

import java.net.HttpURLConnection
import java.net.URL

/**
 * Измерение скорости интернета: скачивание и отдача через открытый эндпоинт Cloudflare
 * (тот же сервис, которым пользуется speed.cloudflare.com — отдельный ключ не нужен).
 */
object SpeedTest {
    private const val DOWN_URL = "https://speed.cloudflare.com/__down?bytes="
    private const val UP_URL = "https://speed.cloudflare.com/__up"
    private const val UA = "SiteScopePro/1.0"

    class Result(val kbps: Long, val bytes: Long, val ms: Long, val error: String)

    fun download(bytes: Int = 20_000_000, maxMs: Long = 9000): Result {
        val c = URL(DOWN_URL + bytes).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6000; c.readTimeout = maxMs.toInt(); c.setRequestProperty("User-Agent", UA)
            if (c.responseCode !in 200..299) return Result(0, 0, 0, "Сервер ответил ${c.responseCode}")
            val buf = ByteArray(65536)
            var total = 0L
            val start = System.currentTimeMillis()
            val deadline = start + maxMs
            c.inputStream.use { input ->
                while (System.currentTimeMillis() < deadline) {
                    val n = input.read(buf); if (n < 0) break
                    total += n
                }
            }
            val ms = (System.currentTimeMillis() - start).coerceAtLeast(1)
            return Result(total * 8 / ms, total, ms, "")
        } catch (e: Exception) { return Result(0, 0, 0, e.message ?: e.javaClass.simpleName) }
        finally { c.disconnect() }
    }

    fun upload(bytes: Int = 8_000_000, maxMs: Long = 9000): Result {
        val c = URL(UP_URL).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6000; c.readTimeout = maxMs.toInt(); c.doOutput = true; c.requestMethod = "POST"
            c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setFixedLengthStreamingMode(bytes)
            val chunk = ByteArray(65536)
            val start = System.currentTimeMillis()
            val deadline = start + maxMs
            var sent = 0L
            c.outputStream.use { out ->
                while (sent < bytes && System.currentTimeMillis() < deadline) {
                    val n = minOf(chunk.size, (bytes - sent).toInt())
                    out.write(chunk, 0, n); sent += n
                }
            }
            val code = c.responseCode
            val ms = (System.currentTimeMillis() - start).coerceAtLeast(1)
            if (code !in 200..299 && code != 0) return Result(0, sent, ms, "Сервер ответил $code")
            return Result(sent * 8 / ms, sent, ms, "")
        } catch (e: Exception) { return Result(0, 0, 0, e.message ?: e.javaClass.simpleName) }
        finally { c.disconnect() }
    }

    fun fmt(kbps: Long): String = if (kbps >= 1000) "%.1f Мбит/с".format(kbps / 1000.0) else "$kbps кбит/с"
}
