package pro.sitescope.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Движок измерения скорости в стиле Ookla Speedtest: задержка (ping), джиттер, потери пакетов,
 * многопоточные скачивание и отдача с «живой» скоростью, задержка под нагрузкой.
 * Все параметры берутся из [Settings]; результат попадает в историю [SpeedHistory].
 */
object SpeedEngine {
    const val UA = "SiteScopePro/1.0 (speedtest)"

    class Server(val id: String, val name: String, val place: String, val downUrl: String, val upUrl: String) {
        /** Адрес для замера задержки: пустой ответ у Cloudflare или HEAD к файлу. */
        val pingUrl: String get() = if (downUrl.contains("%d")) downUrl.format(0) else downUrl
        val pingIsHead: Boolean get() = !downUrl.contains("%d")
        fun down(bytes: Int): String = if (downUrl.contains("%d")) downUrl.format(bytes) else downUrl
    }

    val servers = listOf(
        Server("cf", "Cloudflare", "Ближайший узел (авто)", "https://speed.cloudflare.com/__down?bytes=%d", "https://speed.cloudflare.com/__up"),
        Server("tele2", "Tele2", "Стокгольм", "http://speedtest.tele2.net/100MB.zip", "http://speedtest.tele2.net/upload.php"),
        Server("hetzner", "Hetzner", "Фалькенштайн (только скачивание)", "https://speed.hetzner.de/100MB.bin", ""),
        Server("ovh", "OVH", "Роубе (только скачивание)", "http://proof.ovh.net/files/100Mb.dat", ""),
        Server("custom", "Свой сервер", "Адреса из настроек", "", "")
    )

    data class Settings(
        val serverId: String = "cf",
        val customDown: String = "",
        val customUp: String = "",
        val seconds: Int = 10,
        val threads: Int = 4,
        val pings: Int = 15,
        val capMb: Int = 0,          // 0 = без ограничения расхода трафика на фазу
        val doPing: Boolean = true,
        val doDown: Boolean = true,
        val doUp: Boolean = true,
        val loaded: Boolean = true,  // задержка под нагрузкой
        val unit: String = "mbps"    // mbps | mbs | kbps
    ) {
        fun server(): Server {
            val s = servers.firstOrNull { it.id == serverId } ?: servers[0]
            return if (s.id == "custom") Server("custom", s.name, customDown.ifBlank { "не задан" }, customDown.trim(), customUp.trim()) else s
        }
    }

    enum class Phase { IDLE, PING, DOWN, UP, DONE }

    class Result(
        val time: Long, val server: String, val net: String,
        val ping: Double, val jitter: Double, val loss: Double, val loadedPing: Double,
        val down: Double, val up: Double, val downPeak: Double, val upPeak: Double,
        val bytesDown: Long, val bytesUp: Long, val error: String
    )

    interface Listener {
        fun phase(p: Phase)
        fun ping(ms: Double, done: Int, total: Int)
        fun speed(p: Phase, mbps: Double, progress: Float)
    }

    // ------------------------------------------------------------------ вспомогательное

    fun networkLabel(context: Context): String = try {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null -> "Нет сети"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная сеть"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Сеть"
        }
    } catch (e: Exception) { "Сеть" }

    /** Скорость в Мбит/с → текст в выбранных единицах (число без единицы). */
    fun value(mbps: Double, unit: String): String {
        val v = when (unit) { "mbs" -> mbps / 8.0; "kbps" -> mbps * 1000.0; else -> mbps }
        return when {
            v < 0 -> "—"
            v < 10 -> "%.2f".format(v)
            v < 100 -> "%.1f".format(v)
            else -> "%.0f".format(v)
        }
    }
    fun unitLabel(unit: String) = when (unit) { "mbs" -> "МБ/с"; "kbps" -> "кбит/с"; else -> "Мбит/с" }
    fun unitFactor(unit: String) = when (unit) { "mbs" -> 1.0 / 8.0; "kbps" -> 1000.0; else -> 1.0 }

    // ------------------------------------------------------------------ ping

    /** Один замер RTT в мс по уже открытому keep-alive соединению; -1 — потеря/таймаут. */
    internal fun pingOnce(server: Server, timeoutMs: Int = 2500): Double {
        val t0 = System.nanoTime()
        var c: HttpURLConnection? = null
        return try {
            c = URL(server.pingUrl).openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.requestMethod = if (server.pingIsHead) "HEAD" else "GET"
            c.setRequestProperty("User-Agent", UA)
            c.useCaches = false
            val code = c.responseCode
            if (code >= 500) return -1.0
            val buf = ByteArray(2048)
            try { c.inputStream.use { while (it.read(buf) >= 0) { } } } catch (_: Exception) { }
            (System.nanoTime() - t0) / 1_000_000.0
        } catch (e: Exception) { -1.0 }
    }

    class PingStats(val ping: Double, val jitter: Double, val loss: Double)

    fun stats(samples: List<Double>, total: Int): PingStats {
        val ok = samples.filter { it >= 0 }
        if (ok.isEmpty()) return PingStats(-1.0, -1.0, 100.0)
        val sorted = ok.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        var jitter = 0.0
        if (ok.size > 1) { for (i in 1 until ok.size) jitter += abs(ok[i] - ok[i - 1]); jitter /= (ok.size - 1) }
        val loss = if (total <= 0) 0.0 else (total - ok.size) * 100.0 / total
        return PingStats(median, jitter, loss)
    }

    // ------------------------------------------------------------------ скорость

    private class Phased(val mbps: Double, val peak: Double, val bytes: Long, val error: String, val loadedPings: List<Double>)

    private fun transferPhase(
        down: Boolean, s: Settings, server: Server, cancel: AtomicBoolean, listener: Listener, withLoadedPing: Boolean
    ): Phased {
        val phase = if (down) Phase.DOWN else Phase.UP
        val url = if (down) null else server.upUrl
        val seconds = s.seconds.coerceIn(3, 60)
        val threads = s.threads.coerceIn(1, 16)
        val capBytes = if (s.capMb > 0) s.capMb * 1_000_000L else Long.MAX_VALUE
        val counter = AtomicLong(0)
        val stop = AtomicBoolean(false)
        val firstError = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val pool = Executors.newFixedThreadPool(threads + 1)
        val loaded = java.util.Collections.synchronizedList(ArrayList<Double>())
        val chunk = 25_000_000
        val start = System.currentTimeMillis()
        val deadline = start + seconds * 1000L

        repeat(threads) {
            pool.execute {
                val buf = ByteArray(65536)
                while (!stop.get() && !cancel.get() && System.currentTimeMillis() < deadline) {
                    var c: HttpURLConnection? = null
                    try {
                        if (down) {
                            c = URL(server.down(chunk)).openConnection() as HttpURLConnection
                            c.connectTimeout = 6000; c.readTimeout = 5000; c.setRequestProperty("User-Agent", UA); c.useCaches = false
                            if (c.responseCode !in 200..299) { firstError.compareAndSet(null, "Сервер ответил ${c.responseCode}"); stop.set(true); break }
                            c.inputStream.use { input ->
                                while (!stop.get() && !cancel.get() && System.currentTimeMillis() < deadline) {
                                    val n = input.read(buf); if (n < 0) break
                                    if (counter.addAndGet(n.toLong()) >= capBytes) { stop.set(true); break }
                                }
                            }
                        } else {
                            val body = 8_000_000
                            c = URL(url).openConnection() as HttpURLConnection
                            c.connectTimeout = 6000; c.readTimeout = 8000; c.doOutput = true; c.requestMethod = "POST"
                            c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Content-Type", "application/octet-stream")
                            c.setFixedLengthStreamingMode(body)
                            var sent = 0
                            c.outputStream.use { out ->
                                while (sent < body && !stop.get() && !cancel.get() && System.currentTimeMillis() < deadline) {
                                    val n = minOf(buf.size, body - sent)
                                    out.write(buf, 0, n); sent += n
                                    if (counter.addAndGet(n.toLong()) >= capBytes) { stop.set(true); break }
                                }
                            }
                            if (sent >= body) { val code = c.responseCode; if (code >= 400) { firstError.compareAndSet(null, "Сервер ответил $code"); stop.set(true) } }
                        }
                    } catch (e: Exception) {
                        if (!stop.get() && !cancel.get() && System.currentTimeMillis() < deadline) {
                            firstError.compareAndSet(null, e.message ?: e.javaClass.simpleName)
                            if (counter.get() == 0L) stop.set(true)
                        }
                    } finally { try { c?.disconnect() } catch (_: Exception) { } }
                }
            }
        }
        if (withLoadedPing) pool.execute {
            while (!stop.get() && !cancel.get() && System.currentTimeMillis() < deadline) {
                val ms = pingOnce(server, 2000); if (ms >= 0) loaded.add(ms)
                try { Thread.sleep(400) } catch (_: InterruptedException) { break }
            }
        }

        // Выборка скорости: окно ~1 с, первые ~1.5 с разгона не входят в итог.
        val warm = minOf(2000L, seconds * 150L)
        val times = ArrayList<Long>(); val bytes = ArrayList<Long>()
        var warmBytes = -1L; var warmTime = 0L
        var peak = 0.0; var ema = 0.0
        while (!cancel.get() && !stop.get()) {
            val now = System.currentTimeMillis()
            if (now >= deadline) break
            val b = counter.get()
            times.add(now); bytes.add(b)
            var idx = times.size - 1
            while (idx > 0 && now - times[idx - 1] <= 1000) idx--
            val dt = (now - times[idx]).coerceAtLeast(1)
            val inst = if (times.size > 1) (b - bytes[idx]) * 8.0 / dt / 1000.0 else 0.0   // Мбит/с
            ema = if (ema == 0.0) inst else ema * 0.7 + inst * 0.3
            if (now - start >= warm) {
                if (warmBytes < 0) { warmBytes = b; warmTime = now }
                if (inst > peak) peak = inst
            }
            listener.speed(phase, ema, ((now - start).toFloat() / (seconds * 1000f)).coerceIn(0f, 1f))
            try { Thread.sleep(120) } catch (_: InterruptedException) { break }
        }
        stop.set(true)
        val endTime = System.currentTimeMillis()
        val total = counter.get()
        pool.shutdownNow()
        try { pool.awaitTermination(2, TimeUnit.SECONDS) } catch (_: InterruptedException) { }

        val mbps = when {
            warmBytes >= 0 && endTime - warmTime > 500 -> (total - warmBytes) * 8.0 / (endTime - warmTime) / 1000.0
            endTime > start -> total * 8.0 / (endTime - start) / 1000.0
            else -> 0.0
        }
        val err = if (total == 0L) (firstError.get() ?: "Нет данных от сервера") else ""
        listener.speed(phase, if (err.isEmpty()) mbps else 0.0, 1f)
        return Phased(if (err.isEmpty()) mbps else -1.0, if (err.isEmpty()) maxOf(peak, mbps) else -1.0, total, err, loaded.toList())
    }

    fun run(context: Context, s: Settings, cancel: AtomicBoolean, listener: Listener): Result {
        val server = s.server()
        val net = networkLabel(context)
        var ping = -1.0; var jitter = -1.0; var loss = -1.0; var loadedPing = -1.0
        var down = -1.0; var up = -1.0; var downPeak = -1.0; var upPeak = -1.0
        var bytesDown = 0L; var bytesUp = 0L
        val errors = ArrayList<String>()
        if (server.downUrl.isBlank() && (s.doDown || s.doPing)) {
            listener.phase(Phase.DONE)
            return Result(System.currentTimeMillis(), server.name, net, ping, jitter, loss, loadedPing, down, up, downPeak, upPeak, 0, 0, "Для своего сервера укажите адрес скачивания в настройках")
        }
        if (s.doPing) {
            listener.phase(Phase.PING)
            val total = s.pings.coerceIn(3, 60)
            pingOnce(server)                                   // прогрев соединения, в статистику не входит
            val samples = ArrayList<Double>()
            for (i in 0 until total) {
                if (cancel.get()) break
                val ms = pingOnce(server); samples.add(ms)
                listener.ping(ms, i + 1, total)
                try { Thread.sleep(120) } catch (_: InterruptedException) { break }
            }
            val st = stats(samples, samples.size)
            ping = st.ping; jitter = st.jitter; loss = st.loss
            if (ping < 0) errors.add("Сервер не отвечает")
        }
        if (s.doDown && !cancel.get() && errors.isEmpty()) {
            listener.phase(Phase.DOWN)
            val r = transferPhase(true, s, server, cancel, listener, s.loaded)
            down = r.mbps; downPeak = r.peak; bytesDown = r.bytes
            if (r.loadedPings.isNotEmpty()) loadedPing = stats(r.loadedPings, r.loadedPings.size).ping
            if (r.error.isNotEmpty()) errors.add("Скачивание: " + r.error)
        }
        if (s.doUp && !cancel.get() && errors.none { it.startsWith("Сервер не") }) {
            if (server.upUrl.isBlank()) errors.add("У выбранного сервера нет приёма данных — отдача пропущена")
            else {
                listener.phase(Phase.UP)
                val r = transferPhase(false, s, server, cancel, listener, false)
                up = r.mbps; upPeak = r.peak; bytesUp = r.bytes
                if (r.error.isNotEmpty()) errors.add("Отдача: " + r.error)
            }
        }
        listener.phase(Phase.DONE)
        return Result(System.currentTimeMillis(), server.name, net, ping, jitter, loss, loadedPing, down, up, downPeak, upPeak, bytesDown, bytesUp,
            if (cancel.get()) "Прервано" else errors.joinToString("; "))
    }
}

/** Настройки и история замеров (SharedPreferences `speed_test`, входит в резервную копию). */
object SpeedStore {
    const val PREFS = "speed_test"
    private const val MAX_HISTORY = 100

    fun loadSettings(context: Context): SpeedEngine.Settings {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = SpeedEngine.Settings()
        return SpeedEngine.Settings(
            serverId = p.getString("server", d.serverId) ?: d.serverId,
            customDown = p.getString("customDown", "") ?: "",
            customUp = p.getString("customUp", "") ?: "",
            seconds = p.getInt("seconds", d.seconds), threads = p.getInt("threads", d.threads),
            pings = p.getInt("pings", d.pings), capMb = p.getInt("capMb", d.capMb),
            doPing = p.getBoolean("doPing", true), doDown = p.getBoolean("doDown", true), doUp = p.getBoolean("doUp", true),
            loaded = p.getBoolean("loaded", true), unit = p.getString("unit", d.unit) ?: d.unit
        )
    }

    fun saveSettings(context: Context, s: SpeedEngine.Settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("server", s.serverId).putString("customDown", s.customDown).putString("customUp", s.customUp)
            .putInt("seconds", s.seconds).putInt("threads", s.threads).putInt("pings", s.pings).putInt("capMb", s.capMb)
            .putBoolean("doPing", s.doPing).putBoolean("doDown", s.doDown).putBoolean("doUp", s.doUp)
            .putBoolean("loaded", s.loaded).putString("unit", s.unit).apply()
    }

    fun loadHistory(context: Context): List<SpeedEngine.Result> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("history", "[]") ?: "[]"
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                SpeedEngine.Result(o.optLong("t"), o.optString("srv"), o.optString("net"), o.optDouble("ping", -1.0), o.optDouble("jit", -1.0),
                    o.optDouble("loss", -1.0), o.optDouble("lp", -1.0), o.optDouble("down", -1.0), o.optDouble("up", -1.0),
                    o.optDouble("dp", -1.0), o.optDouble("upk", -1.0), o.optLong("bd"), o.optLong("bu"), o.optString("err"))
            }
        } catch (e: Exception) { emptyList() }
    }

    fun saveHistory(context: Context, list: List<SpeedEngine.Result>) {
        val a = JSONArray()
        list.take(MAX_HISTORY).forEach { r ->
            a.put(JSONObject().put("t", r.time).put("srv", r.server).put("net", r.net).put("ping", r.ping).put("jit", r.jitter)
                .put("loss", r.loss).put("lp", r.loadedPing).put("down", r.down).put("up", r.up).put("dp", r.downPeak).put("upk", r.upPeak)
                .put("bd", r.bytesDown).put("bu", r.bytesUp).put("err", r.error))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("history", a.toString()).apply()
    }
}
