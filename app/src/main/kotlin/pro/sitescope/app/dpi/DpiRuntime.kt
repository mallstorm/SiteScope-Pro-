package pro.sitescope.app.dpi

import android.content.Context
import android.content.Intent
import android.net.VpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class DpiPhase { STOPPED, STARTING, RUNNING, ERROR }

data class DpiState(
    val phase: DpiPhase = DpiPhase.STOPPED,
    val mode: String = "",
    val message: String = "",
    val port: Int = 0,
    val since: Long = 0,
    val restarts: Int = 0,
    val log: List<String> = emptyList(),
    /** Сколько раз наблюдатель сам менял стратегию с момента запуска. */
    val switches: Int = 0,
    /** Активная строка стратегии (после возможного отката или автопереключения). */
    val line: String = "",
    /** Итог последней проверки наблюдателя: сколько контрольных сайтов открылось. */
    val lastCheck: String = "",
)

/**
 * Общий для сервисов движок: запускает ciadpi по настройкам, следит за процессом и перезапускает его при падении.
 * Один экземпляр на приложение — «основной» SOCKS5-порт, который используют режим прокси, VPN, браузер и цепочка Xray.
 *
 * Устойчивость:
 *  • строка стратегии, которую движок не принял, заменяется базовой [DpiConfig.SAFE_LINE] — обход всё равно запускается;
 *  • процесс, упавший не более 5 раз в минуту, перезапускается;
 *  • наблюдатель раз в ~75 секунд открывает через обход заблокированные сайты последнего подбора и, если они перестали
 *    открываться (при этом интернет жив), переключается на следующую лучшую стратегию из сохранённых результатов.
 */
object DpiRuntime {
    private val _state = MutableStateFlow(DpiState())
    val state: StateFlow<DpiState> = _state.asStateFlow()

    @Volatile private var engine: DpiEngine? = null
    @Volatile private var supervisor: Thread? = null
    @Volatile private var watcher: Thread? = null
    @Volatile private var stopping = false
    @Volatile private var planned = false
    @Volatile private var owner: String = ""
    @Volatile private var activeConfig: DpiConfig? = null
    private const val WATCH_INTERVAL_MS = 75_000L

    val running: Boolean get() = _state.value.phase == DpiPhase.RUNNING
    val port: Int get() = if (running) _state.value.port else 0

    @Synchronized
    fun start(context: Context, config: DpiConfig, who: String): Boolean {
        if (running) { if (owner.isEmpty()) owner = who; return true }
        stopLocked()
        stopping = false
        planned = false
        owner = who
        val app = context.applicationContext
        val wanted = if (DpiEngine.portFree(config.port)) config.port else DpiEngine.freePort()
        _state.value = DpiState(DpiPhase.STARTING, config.mode, "Запуск byedpi…", wanted, line = config.effectiveLine())
        var cfg = config
        var e = DpiEngine(app)
        var err = e.start(cfg.args(wanted))
        var note = ""
        if (err != null && err.startsWith("byedpi завершился") && cfg.effectiveLine() != DpiConfig.SAFE_LINE) {
            // Движок не принял строку (неверный ключ): откат на базовую стратегию, чтобы обход всё равно заработал.
            val safe = cfg.copy(line = DpiConfig.SAFE_LINE, useSimple = false, hostsOnly = false)
            val e2 = DpiEngine(app)
            val err2 = e2.start(safe.args(wanted))
            if (err2 == null) { e = e2; err = null; cfg = safe; note = " · строка не принята byedpi, включена базовая" }
        }
        if (err != null) {
            _state.value = DpiState(DpiPhase.ERROR, config.mode, err, wanted, log = e.logLines, line = config.effectiveLine())
            owner = ""
            return false
        }
        engine = e
        activeConfig = cfg
        val portNote = if (wanted != cfg.port) "Порт ${cfg.port} занят, использован $wanted" else "Работает"
        _state.value = DpiState(DpiPhase.RUNNING, cfg.mode, portNote + note, wanted, System.currentTimeMillis(), log = e.logLines, line = cfg.effectiveLine())
        supervise(app, wanted)
        watch(app, wanted)
        return true
    }

    private fun supervise(app: Context, wanted: Int) {
        val t = Thread({
            var restarts = 0
            val times = ArrayDeque<Long>()
            while (!stopping) {
                val e = engine ?: break
                e.waitExit()
                if (stopping) break
                val config = activeConfig ?: break
                if (planned) {
                    // Плановая смена стратегии: не считаем падением.
                    planned = false
                } else {
                    val now = System.currentTimeMillis()
                    times.addLast(now); while (times.isNotEmpty() && now - times.first() > 60_000) times.removeFirst()
                    if (times.size > 5) {
                        _state.value = _state.value.copy(phase = DpiPhase.ERROR, message = "byedpi постоянно падает: " + e.logLines.takeLast(3).joinToString(" | "), log = e.logLines)
                        break
                    }
                    restarts++
                }
                _state.value = _state.value.copy(phase = DpiPhase.STARTING, message = "Перезапуск byedpi…", restarts = restarts, log = e.logLines)
                Thread.sleep(500)
                val fresh = DpiEngine(app)
                val err = fresh.start(config.args(wanted))
                if (err != null) { _state.value = _state.value.copy(phase = DpiPhase.ERROR, message = err, log = fresh.logLines); break }
                engine = fresh
                val cur = _state.value
                _state.value = cur.copy(phase = DpiPhase.RUNNING, message = if (cur.switches > 0 && restarts == 0) "Работает (стратегия сменена автоматически: ${cur.switches})" else "Работает (перезапусков: $restarts)",
                    since = System.currentTimeMillis(), restarts = restarts, log = fresh.logLines, line = config.effectiveLine())
            }
        }, "dpi-supervisor")
        t.isDaemon = true
        supervisor = t
        t.start()
    }

    /** Наблюдатель: проверяет работу обхода и при необходимости подменяет стратегию. */
    private fun watch(app: Context, wanted: Int) {
        val t = Thread({
            var bad = 0
            val tried = mutableSetOf<String>()
            while (!stopping) {
                try { Thread.sleep(WATCH_INTERVAL_MS) } catch (_: InterruptedException) { break }
                if (stopping || !running) continue
                val cfg = activeConfig ?: continue
                if (!cfg.watchdog) continue
                val sites = DpiSites.parse(cfg.searchSites)
                if (sites.isEmpty()) continue
                val probe = DpiScoring.sample(sites, 3)
                val results = probe.map { DpiProbe.https(it, wanted, 6000) }
                val control = DpiProbe.https(DpiSites.control.first(), wanted, 6000)
                val opened = results.count { it.ok }
                _state.value = _state.value.copy(lastCheck = "$opened из ${probe.size} заблокированных открылось" + if (control.ok) "" else ", контрольный сайт не открылся")
                if (opened > 0) { bad = 0; tried.clear(); continue }
                if (!control.ok) continue // нет сети вообще — стратегия ни при чём
                bad++
                if (bad < 2) continue
                bad = 0
                tried += cfg.line.trim()
                val next = DpiSearch.loadSaved(app).filter { it.full && it.controlOk && it.ok > 0 && it.line.trim() !in tried }.firstOrNull() ?: continue
                switchLine(app, next.line)
            }
        }, "dpi-watchdog")
        t.isDaemon = true
        watcher = t
        t.start()
    }

    /** Подхватывает сохранённые настройки на ходу: перезапускает процесс (без учёта в падениях). [countAsSwitch] — смена сделана автоматически. */
    @Synchronized
    fun reload(app: Context, countAsSwitch: Boolean = false) {
        val old = activeConfig ?: return
        val fresh = DpiStore.load(app).copy(mode = old.mode, port = old.port)
        activeConfig = fresh
        planned = true
        val cur = _state.value
        _state.value = cur.copy(switches = cur.switches + if (countAsSwitch) 1 else 0, line = fresh.effectiveLine())
        engine?.stop()
    }

    /** Автоматическая смена стратегии наблюдателем. */
    private fun switchLine(app: Context, line: String) {
        DpiStore.applyLine(app, line)
        reload(app, true)
    }

    fun refreshLog() { engine?.let { _state.value = _state.value.copy(log = it.logLines) } }

    @Synchronized
    fun stop() { stopLocked(); _state.value = DpiState(); owner = "" }

    /** Останавливает только если запускал [who] (цепочка Xray не должна гасить сервис пользователя). */
    @Synchronized
    fun stopIfOwner(who: String) { if (owner == who) { stopLocked(); _state.value = DpiState(); owner = "" } }

    private fun stopLocked() {
        stopping = true
        engine?.stop(); engine = null
        supervisor?.interrupt(); supervisor = null
        watcher?.interrupt(); watcher = null
        activeConfig = null
    }

    /** Занят ли VPN-слот приложением SiteScope (Hosts/Xray). */
    fun siteScopeVpnBusy(context: Context): Boolean {
        val s = context.getSharedPreferences("hosts_manager_state", Context.MODE_PRIVATE).getString("lifecycleState", "OFF")
        return s == "ACTIVE" || s == "STARTING"
    }

    fun startService(context: Context, config: DpiConfig) {
        val cls = if (config.isVpn) DpiVpnService::class.java else DpiService::class.java
        val intent = Intent(context, cls).setAction(DpiService.ACTION_START)
        context.startForegroundService(intent)
    }

    fun stopService(context: Context) {
        context.startService(Intent(context, DpiService::class.java).setAction(DpiService.ACTION_STOP))
        context.startService(Intent(context, DpiVpnService::class.java).setAction(DpiService.ACTION_STOP))
    }

    /** Нужно ли системное разрешение VPN (null — не нужно или уже выдано). */
    fun vpnConsent(context: Context): Intent? = VpnService.prepare(context)
}

/** Цепочка внутри VPN SiteScope: Xray ходит наружу через byedpi. */
object DpiChain {
    private const val WHO = "xray-chain"

    /** Порт локального SOCKS5 byedpi или 0, если цепочка выключена/не запустилась. */
    @JvmStatic
    fun ensure(context: Context): Int {
        val cfg = DpiStore.load(context)
        if (!cfg.chainXray || !DpiEngine.available(context)) return 0
        if (DpiRuntime.running) return DpiRuntime.port
        return if (DpiRuntime.start(context, cfg.copy(mode = "chain"), WHO)) DpiRuntime.port else 0
    }

    @JvmStatic
    fun release() { DpiRuntime.stopIfOwner(WHO) }
}
