package pro.sitescope.app.dpi

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue

/** Результат проверки одной стратегии. */
data class StrategyScore(
    val line: String,
    val ok: Int,
    val total: Int,
    val avgMs: Long,
    val controlOk: Boolean,
    val full: Boolean,
    val failed: List<String> = emptyList(),
    val note: String = "",
) {
    val rate: Double get() = if (total == 0) 0.0 else ok.toDouble() / total
}

/** Правила ранжирования — отдельно от сети, чтобы проверять тестами. */
object DpiScoring {
    /** Сначала не ломающие контрольные сайты, затем по доле открывшихся, затем полные проверки, затем по скорости. */
    val comparator: Comparator<StrategyScore> =
        compareByDescending<StrategyScore> { it.controlOk }.thenByDescending { it.rate }.thenByDescending { it.full }.thenBy { if (it.avgMs <= 0) Long.MAX_VALUE else it.avgMs }

    fun rank(list: List<StrategyScore>): List<StrategyScore> = list.sortedWith(comparator)

    /** Сайты для проверки: только те, что не открылись без обхода (если заблокированных нет — все). */
    fun targetSites(all: List<String>, baselineFailed: Set<String>): List<String> {
        val blocked = all.filter { it in baselineFailed }
        return if (blocked.isEmpty()) all else blocked
    }

    /** Быстрая выборка равномерно по списку, чтобы в неё попали разные сервисы. */
    fun sample(sites: List<String>, n: Int): List<String> {
        if (sites.size <= n) return sites
        return (0 until n).map { sites[(it * sites.size) / n] }
    }
}

data class SearchProgress(
    val running: Boolean = false,
    val phase: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val baselineBlocked: Int = -1,
    val baselineTotal: Int = 0,
    val message: String = "",
    val finished: Boolean = false,
)

/** Подбор стратегии: базовая проверка без обхода → быстрый прогон всех стратегий параллельно → полная проверка лучших. */
class DpiSearch(private val context: Context) {
    class Config(
        val sites: List<String>,
        val strategies: List<String>,
        val parallel: Int = 4,
        val timeoutSec: Int = 5,
        val finalists: Int = 8,
        val sni: String = "google.com",
    )

    private val _progress = MutableStateFlow(SearchProgress())
    val progress: StateFlow<SearchProgress> = _progress.asStateFlow()
    private val _results = MutableStateFlow<List<StrategyScore>>(emptyList())
    val results: StateFlow<List<StrategyScore>> = _results.asStateFlow()

    private fun post(block: (SearchProgress) -> SearchProgress) { _progress.value = block(_progress.value) }

    suspend fun run(config: Config) = coroutineScope {
        val blockedByVpn = vpnActive(context)
        if (blockedByVpn) { post { SearchProgress(message = "Выключите VPN: проверка должна идти по обычной сети, иначе результат неверный.", finished = true) }; return@coroutineScope }
        if (!DpiEngine.available(context)) { post { SearchProgress(message = "byedpi недоступен на этом устройстве (нужен arm64).", finished = true) }; return@coroutineScope }
        if (config.sites.isEmpty()) { post { SearchProgress(message = "Выберите сайты для проверки.", finished = true) }; return@coroutineScope }
        _results.value = emptyList()
        post { SearchProgress(running = true, phase = "Проверка без обхода", message = "Узнаю, какие сайты у вас действительно недоступны") }
        val timeoutMs = config.timeoutSec * 1000
        val engines = ConcurrentLinkedQueue<DpiEngine>()
        try {
            // 1. Базовая проверка: простой прокси без десинхронизации.
            val all = (config.sites + DpiSites.control).distinct()
            val base = withContext(Dispatchers.IO) { withEngine(engines, emptyList()) { port -> checkAll(all, port, timeoutMs, 12) } }
            if (base == null) { post { SearchProgress(message = "Не удалось запустить byedpi.", finished = true) }; return@coroutineScope }
            val baseFailed = base.filter { !it.ok }.map { it.host }.toSet()
            val controlBroken = DpiSites.control.all { it in baseFailed }
            if (controlBroken) { post { SearchProgress(message = "Нет интернета: даже контрольные сайты не открылись.", finished = true) }; return@coroutineScope }
            val targets = DpiScoring.targetSites(config.sites, baseFailed)
            val blockedCount = config.sites.count { it in baseFailed }
            post { it.copy(baselineBlocked = blockedCount, baselineTotal = config.sites.size) }
            if (blockedCount == 0) {
                post { it.copy(running = false, finished = true, message = "Все выбранные сайты открываются без обхода. Подбор не нужен — выберите другие сайты или проверьте сети провайдера.") }
                return@coroutineScope
            }

            // 2. Быстрый прогон: часть заблокированных сайтов + контрольные.
            val quickSites = DpiScoring.sample(targets, 6) + DpiSites.control.take(1)
            val strategies = config.strategies
            val quick = ConcurrentLinkedQueue<StrategyScore>()
            post { it.copy(phase = "Быстрый прогон", done = 0, total = strategies.size, message = "Стратегий: ${strategies.size}, параллельно: ${config.parallel}") }
            val gate = Semaphore(config.parallel.coerceIn(1, 8))
            strategies.map { line ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        if (!isActive) return@withPermit
                        val score = evaluate(engines, line, quickSites, targets.toSet(), timeoutMs, config.sni, full = false)
                        quick += score
                        post { it.copy(done = it.done + 1) }
                        _results.value = DpiScoring.rank(quick.toList())
                    }
                }
            }.awaitAll()

            // 3. Полная проверка лучших на всех целевых сайтах.
            val finalists = DpiScoring.rank(quick.toList()).filter { it.ok > 0 && it.controlOk }.take(config.finalists)
            post { it.copy(phase = "Полная проверка лучших", done = 0, total = finalists.size, message = "Проверяю ${targets.size} сайтов у каждой из ${finalists.size} стратегий") }
            val fullResults = ConcurrentLinkedQueue<StrategyScore>()
            finalists.map { f ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        if (!isActive) return@withPermit
                        val score = evaluate(engines, f.line, targets + DpiSites.control, targets.toSet(), timeoutMs, config.sni, full = true)
                        fullResults += score
                        post { it.copy(done = it.done + 1) }
                        val merged = quick.filter { q -> fullResults.none { it.line == q.line } } + fullResults
                        _results.value = DpiScoring.rank(merged)
                    }
                }
            }.awaitAll()
            val ranked = _results.value
            save(ranked)
            val best = ranked.firstOrNull { it.full }
            post { it.copy(running = false, finished = true, message = if (best == null) "Рабочих стратегий не найдено. Попробуйте полный список или другую сеть." else "Лучшая: ${best.ok} из ${best.total} сайтов") }
        } catch (e: CancellationException) {
            post { it.copy(running = false, finished = true, message = "Остановлено") }
            save(_results.value)
            throw e
        } finally {
            engines.forEach { it.stop() }
        }
    }

    private fun checkAll(hosts: List<String>, port: Int, timeoutMs: Int, concurrency: Int): List<DpiProbe.Result> {
        val pool = java.util.concurrent.Executors.newFixedThreadPool(concurrency.coerceAtMost(hosts.size).coerceAtLeast(1))
        try {
            val futures = hosts.map { h -> pool.submit<DpiProbe.Result> { DpiProbe.https(h, port, timeoutMs) } }
            return futures.map { it.get() }
        } finally { pool.shutdownNow() }
    }

    private fun evaluate(engines: ConcurrentLinkedQueue<DpiEngine>, line: String, sites: List<String>, targets: Set<String>, timeoutMs: Int, sni: String, full: Boolean): StrategyScore {
        val args = DpiArgs.build(line, 0, sni).drop(2) // порт подставит withEngine
        val results = withEngine(engines, args) { port -> checkAll(sites, port, timeoutMs, 8) }
            ?: return StrategyScore(line, 0, targets.size.coerceAtLeast(1), 0, false, full, note = "процесс не запустился")
        val tested = results.filter { it.host in targets }
        val ok = tested.count { it.ok }
        val control = results.filter { it.host in DpiSites.control }
        val controlOk = control.isEmpty() || control.any { it.ok }
        val okMs = tested.filter { it.ok }.map { it.ms }
        return StrategyScore(line, ok, tested.size, if (okMs.isEmpty()) 0 else okMs.average().toLong(), controlOk, full, tested.filter { !it.ok }.map { it.host })
    }

    private fun <T> withEngine(engines: ConcurrentLinkedQueue<DpiEngine>, extra: List<String>, block: (Int) -> T): T? {
        val engine = DpiEngine(context)
        engines += engine
        try {
            val port = DpiEngine.freePort()
            val err = engine.start(listOf("-i127.0.0.1", "-p$port") + extra, 2500)
            if (err != null) return null
            return block(port)
        } finally {
            engine.stop()
            engines -= engine
        }
    }

    private fun save(list: List<StrategyScore>) {
        val arr = JSONArray()
        list.take(30).forEach { s ->
            arr.put(JSONObject().put("line", s.line).put("ok", s.ok).put("total", s.total).put("ms", s.avgMs).put("control", s.controlOk).put("full", s.full)
                .put("failed", JSONArray(s.failed)))
        }
        context.getSharedPreferences("dpi_bypass", Context.MODE_PRIVATE).edit().putString("results", arr.toString()).putLong("resultsAt", System.currentTimeMillis()).apply()
    }

    companion object {
        fun loadSaved(context: Context): List<StrategyScore> = try {
            val a = JSONArray(context.getSharedPreferences("dpi_bypass", Context.MODE_PRIVATE).getString("results", "[]"))
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                StrategyScore(o.getString("line"), o.getInt("ok"), o.getInt("total"), o.optLong("ms"), o.optBoolean("control", true), o.optBoolean("full"),
                    o.optJSONArray("failed")?.let { f -> (0 until f.length()).map { f.getString(it) } } ?: emptyList())
            }
        } catch (e: Exception) { emptyList() }

        fun vpnActive(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
    }
}
