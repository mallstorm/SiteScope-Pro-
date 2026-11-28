package pro.sitescope.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Движок измерения скорости в стиле Ookla Speedtest: задержка (ping), джиттер, потери пакетов,
 * многопоточные скачивание и отдача с «живой» скоростью, задержка под нагрузкой.
 * Все параметры берутся из [Settings], результат сохраняется через [SpeedStore].
 */
object SpeedEngine {
    const val UA = "SiteScopePro/1.0 (speedtest)"

    class Server(
        val id: String, val name: String, val place: String, val downUrl: String, val upUrl: String,
        val pingEndpoint: String? = null, val downloadInMiB: Boolean = false, val binaryDownload: Boolean = false
    ) {
        val pingUrl: String get() = pingEndpoint ?: if (downUrl.contains("%d")) down(1) else downUrl
        val supportsUpload get() = upUrl.isNotBlank()
        fun down(bytes: Int): String {
            val size = if (downloadInMiB) (bytes + 1_048_575) / 1_048_576 else bytes
            return downUrl.replace("%d", size.toString())
        }
    }

    val servers get() = SpeedServers.public + SpeedServers.custom

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
        val unit: String = "mbps",   // mbps | mbs | kbps
        val automatic: Boolean = true
    ) {
        fun server(): Server {
            val s = servers.firstOrNull { it.id == serverId } ?: SpeedServers.public.first()
            return if (s.id == "custom") Server("custom", s.name, customDown.ifBlank { "не задан" }, customDown.trim(), customUp.trim()) else s
        }
    }

    enum class Phase { IDLE, SELECTING, PING, DOWN, UP, DONE }

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
        fun server(server: Server) {}
        fun serverProbe(result: ServerProbe, done: Int, total: Int) {}
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

    /** HTTP-отклик в миллисекундах, включая установку соединения при необходимости. */
    internal fun pingOnce(server: Server, timeoutMs: Int = 2500): Double = probeOnce(server, timeoutMs).ms

    private data class ProbeSample(val ms: Double, val error: String = "")

    private fun probeOnce(server: Server, timeoutMs: Int): ProbeSample {
        val t0 = System.nanoTime()
        var c: HttpURLConnection? = null
        return try {
            c = URL(server.pingUrl).openConnection() as HttpURLConnection
            c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Accept-Encoding", "identity")
            if (server.pingEndpoint == null) c.setRequestProperty("Range", "bytes=0-0")
            c.useCaches = false
            val code = c.responseCode
            if (code !in 200..299) return ProbeSample(-1.0, "HTTP $code")
            val html = c.contentType?.startsWith("text/html", ignoreCase = true) == true
            c.inputStream.use { input ->
                val first = input.read()
                if (server.binaryDownload && (server.pingEndpoint == null && first < 0 || html && first >= 0)) {
                    return ProbeSample(-1.0, "Неверный ответ")
                }
                ProbeSample((System.nanoTime() - t0) / 1_000_000.0)
            }
        } catch (_: java.net.SocketTimeoutException) {
            ProbeSample(-1.0, "Таймаут")
        } catch (_: Exception) {
            ProbeSample(-1.0, "Нет ответа")
        } finally { c?.disconnect() }
    }

    data class ServerProbe(val server: Server, val ping: Double, val failures: Int, val error: String) {
        val available get() = ping >= 0
    }

    internal fun probeServers(
        servers: List<Server>, cancel: AtomicBoolean, onResult: (ServerProbe, Int, Int) -> Unit
    ): List<ServerProbe> {
        if (servers.isEmpty() || cancel.get()) return emptyList()
        val pool = Executors.newFixedThreadPool(minOf(8, servers.size))
        val queue = ExecutorCompletionService<ServerProbe>(pool)
        val results = ArrayList<ServerProbe>()
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
        try {
            servers.forEach { server -> queue.submit {
                val first = probeOnce(server, 1500)
                if (cancel.get() || Thread.currentThread().isInterrupted) return@submit ServerProbe(server, -1.0, 2, "")
                // A second response helps prefer stable routes over a single lucky sample.
                val second = probeOnce(server, 1500)
                val ping = stats(listOf(first.ms, second.ms), 2).ping
                val failures = listOf(first, second).count { it.ms < 0 }
                ServerProbe(server, ping, failures, second.error.ifBlank { first.error })
            } }
            while (!cancel.get() && results.size < servers.size && System.nanoTime() < deadline) {
                val task = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                val result = task.get()
                results.add(result)
                onResult(result, results.size, servers.size)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally { pool.shutdownNow() }
        return results
    }

    internal fun rankedServers(settings: Settings, probes: List<ServerProbe>): List<Server> = probes
        .filter { it.available && (!settings.doUp || settings.doDown || settings.doPing || it.server.supportsUpload) }
        .sortedWith(compareBy<ServerProbe> { settings.doUp && !it.server.supportsUpload }
            .thenBy { it.failures }.thenBy { it.ping }.thenBy { it.server.id })
        .map { it.server }

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
        val uploadsAccepted = AtomicBoolean(false)
        val uploadBudget = AtomicLong(capBytes)
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
                var nextUpload = 256_000
                while (!stop.get() && !cancel.get() && System.currentTimeMillis() < deadline) {
                    var c: HttpURLConnection? = null
                    try {
                        if (down) {
                            c = URL(server.down(chunk)).openConnection() as HttpURLConnection
                            c.connectTimeout = 6000; c.readTimeout = 5000; c.setRequestProperty("User-Agent", UA); c.useCaches = false
                            if (c.responseCode !in 200..299) { firstError.compareAndSet(null, "Сервер ответил ${c.responseCode}"); stop.set(true); break }
                            if (server.binaryDownload && c.contentType?.startsWith("text/html", ignoreCase = true) == true) {
                                firstError.compareAndSet(null, "Вместо тестового файла сервер вернул страницу"); stop.set(true); break
                            }
                            c.inputStream.use { input ->
                                while (!stop.get() && !cancel.get() && System.currentTimeMillis() < deadline) {
                                    val n = input.read(buf); if (n < 0) break
                                    if (counter.addAndGet(n.toLong()) >= capBytes) { stop.set(true); break }
                                }
                            }
                        } else {
                            val remaining = uploadBudget.getAndUpdate { (it - nextUpload).coerceAtLeast(0) }
                            val body = minOf(remaining, nextUpload.toLong()).toInt()
                            if (body == 0) break
                            c = URL(url).openConnection() as HttpURLConnection
                            c.connectTimeout = 6000; c.readTimeout = 8000; c.doOutput = true; c.requestMethod = "POST"
                            c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Content-Type", "application/octet-stream")
                            c.setFixedLengthStreamingMode(body)
                            var sent = 0
                            c.outputStream.use { out ->
                                while (sent < body && !stop.get() && !cancel.get() && System.currentTimeMillis() < deadline) {
                                    val n = minOf(buf.size, body - sent)
                                    out.write(buf, 0, n); sent += n
                                    counter.addAndGet(n.toLong())
                                }
                            }
                            if (sent >= body) {
                                val code = c.responseCode
                                if (code !in 200..299) {
                                    firstError.compareAndSet(null, "Сервер ответил $code"); stop.set(true)
                                } else {
                                    uploadsAccepted.set(true)
                                    nextUpload = (nextUpload * 2).coerceAtMost(8_000_000)
                                    if (counter.get() >= capBytes) stop.set(true)
                                }
                            }
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
        val err = when {
            !down && !uploadsAccepted.get() -> firstError.get() ?: "Сервер не подтвердил приём данных"
            total == 0L -> firstError.get() ?: "Нет данных от сервера"
            else -> ""
        }
        listener.speed(phase, if (err.isEmpty()) mbps else 0.0, 1f)
        return Phased(if (err.isEmpty()) mbps else -1.0, if (err.isEmpty()) maxOf(peak, mbps) else -1.0, total, err, loaded.toList())
    }

    fun run(context: Context, s: Settings, cancel: AtomicBoolean, listener: Listener): Result {
        val net = networkLabel(context)
        fun empty(message: String) = Result(System.currentTimeMillis(), "", net, -1.0, -1.0, -1.0, -1.0,
            -1.0, -1.0, -1.0, -1.0, 0, 0, message)
        if (!s.doPing && !s.doDown && !s.doUp) {
            listener.phase(Phase.DONE)
            return empty("В настройках замера выберите хотя бы один показатель")
        }
        val candidates = if (s.automatic) {
            listener.phase(Phase.SELECTING)
            val probes = probeServers(SpeedServers.public, cancel, listener::serverProbe)
            rankedServers(s, probes).take(3)
        } else listOf(s.server())

        var result: Result? = null
        for (server in candidates) {
            if (cancel.get()) break
            listener.server(server)
            val attempt = measure(s, server, net, cancel, listener)
            result = attempt
            val hasTransfer = attempt.down >= 0 || attempt.up >= 0
            val wantsTransfer = s.doDown || s.doUp && server.supportsUpload
            if (hasTransfer || !wantsTransfer && attempt.ping >= 0) break
        }
        listener.phase(Phase.DONE)
        return result ?: empty(if (cancel.get()) "Прервано" else "Доступных серверов нет. Проверьте подключение или выберите сервер вручную.")
    }

    private fun measure(s: Settings, server: Server, net: String, cancel: AtomicBoolean, listener: Listener): Result {
        var ping = -1.0; var jitter = -1.0; var loss = -1.0; var loadedPing = -1.0
        var down = -1.0; var up = -1.0; var downPeak = -1.0; var upPeak = -1.0
        var bytesDown = 0L; var bytesUp = 0L
        val errors = ArrayList<String>()
        if (server.downUrl.isBlank() && (s.doDown || s.doPing)) {
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
                if (s.automatic && samples.size == 3 && samples.all { it < 0 }) break
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
            if (!server.supportsUpload) errors.add("У выбранного сервера доступно скачивание. Отдача пропущена.")
            else {
                listener.phase(Phase.UP)
                val r = transferPhase(false, s, server, cancel, listener, false)
                up = r.mbps; upPeak = r.peak; bytesUp = r.bytes
                if (r.error.isNotEmpty()) errors.add("Отдача: " + r.error)
            }
        }
        val name = if (server.id == "custom") server.name else "${server.name} · ${server.place}"
        return Result(System.currentTimeMillis(), name, net, ping, jitter, loss, loadedPing, down, up, downPeak, upPeak, bytesDown, bytesUp,
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
        val saved = p.getString("server", d.serverId) ?: d.serverId
        val server = SpeedEngine.servers.firstOrNull { it.id == saved }?.id
        return SpeedEngine.Settings(
            serverId = server ?: d.serverId,
            customDown = p.getString("customDown", "") ?: "",
            customUp = p.getString("customUp", "") ?: "",
            seconds = p.getInt("seconds", d.seconds), threads = p.getInt("threads", d.threads),
            pings = p.getInt("pings", d.pings), capMb = p.getInt("capMb", d.capMb),
            doPing = p.getBoolean("doPing", true), doDown = p.getBoolean("doDown", true), doUp = p.getBoolean("doUp", true),
            loaded = p.getBoolean("loaded", true), unit = p.getString("unit", d.unit) ?: d.unit,
            automatic = server == null || p.getBoolean("automatic", server == d.serverId)
        )
    }

    fun saveSettings(context: Context, s: SpeedEngine.Settings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("server", s.serverId).putString("customDown", s.customDown).putString("customUp", s.customUp)
            .putInt("seconds", s.seconds).putInt("threads", s.threads).putInt("pings", s.pings).putInt("capMb", s.capMb)
            .putBoolean("doPing", s.doPing).putBoolean("doDown", s.doDown).putBoolean("doUp", s.doUp)
            .putBoolean("loaded", s.loaded).putString("unit", s.unit).putBoolean("automatic", s.automatic).apply()
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

    @Synchronized fun appendHistory(context: Context, result: SpeedEngine.Result) {
        saveHistory(context, listOf(result) + loadHistory(context))
    }

    @Synchronized fun removeHistory(context: Context, result: SpeedEngine.Result) {
        saveHistory(context, loadHistory(context).filterNot {
            it.time == result.time && it.server == result.server && it.net == result.net
        })
    }

    @Synchronized fun saveHistory(context: Context, list: List<SpeedEngine.Result>) {
        val a = JSONArray()
        list.take(MAX_HISTORY).forEach { r ->
            a.put(JSONObject().put("t", r.time).put("srv", r.server).put("net", r.net).put("ping", r.ping).put("jit", r.jitter)
                .put("loss", r.loss).put("lp", r.loadedPing).put("down", r.down).put("up", r.up).put("dp", r.downPeak).put("upk", r.upPeak)
                .put("bd", r.bytesDown).put("bu", r.bytesUp).put("err", r.error))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("history", a.toString()).apply()
    }
}
