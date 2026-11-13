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
)

/**
 * Общий для сервисов движок: запускает ciadpi по настройкам, следит за процессом и перезапускает его при падении.
 * Один экземпляр на приложение — «основной» SOCKS5-порт, который используют режим прокси, VPN, браузер и цепочка Xray.
 */
object DpiRuntime {
    private val _state = MutableStateFlow(DpiState())
    val state: StateFlow<DpiState> = _state.asStateFlow()

    @Volatile private var engine: DpiEngine? = null
    @Volatile private var supervisor: Thread? = null
    @Volatile private var stopping = false
    @Volatile private var owner: String = ""

    val running: Boolean get() = _state.value.phase == DpiPhase.RUNNING
    val port: Int get() = if (running) _state.value.port else 0

    @Synchronized
    fun start(context: Context, config: DpiConfig, who: String): Boolean {
        if (running) { if (owner.isEmpty()) owner = who; return true }
        stopLocked()
        stopping = false
        owner = who
        val app = context.applicationContext
        val wanted = if (DpiEngine.portFree(config.port)) config.port else DpiEngine.freePort()
        _state.value = DpiState(DpiPhase.STARTING, config.mode, "Запуск byedpi…", wanted)
        val e = DpiEngine(app)
        val err = e.start(config.args(wanted))
        if (err != null) {
            _state.value = DpiState(DpiPhase.ERROR, config.mode, err, wanted, log = e.logLines)
            owner = ""
            return false
        }
        engine = e
        _state.value = DpiState(DpiPhase.RUNNING, config.mode, if (wanted != config.port) "Порт ${config.port} занят, использован $wanted" else "Работает", wanted, System.currentTimeMillis(), log = e.logLines)
        supervise(app, config, wanted)
        return true
    }

    private fun supervise(app: Context, config: DpiConfig, wanted: Int) {
        val t = Thread({
            var restarts = 0
            val times = ArrayDeque<Long>()
            while (!stopping) {
                val e = engine ?: break
                e.waitExit()
                if (stopping) break
                val now = System.currentTimeMillis()
                times.addLast(now); while (times.isNotEmpty() && now - times.first() > 60_000) times.removeFirst()
                if (times.size > 5) {
                    _state.value = DpiState(DpiPhase.ERROR, config.mode, "byedpi постоянно падает: " + e.logLines.takeLast(3).joinToString(" | "), wanted, log = e.logLines)
                    break
                }
                restarts++
                _state.value = _state.value.copy(phase = DpiPhase.STARTING, message = "Перезапуск byedpi ($restarts)…", restarts = restarts, log = e.logLines)
                Thread.sleep(700)
                val fresh = DpiEngine(app)
                val err = fresh.start(config.args(wanted))
                if (err != null) { _state.value = DpiState(DpiPhase.ERROR, config.mode, err, wanted, log = fresh.logLines); break }
                engine = fresh
                _state.value = DpiState(DpiPhase.RUNNING, config.mode, "Работает (перезапусков: $restarts)", wanted, System.currentTimeMillis(), restarts, fresh.logLines)
            }
        }, "dpi-supervisor")
        t.isDaemon = true
        supervisor = t
        t.start()
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
