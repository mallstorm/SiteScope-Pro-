package pro.sitescope.app.anon

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pro.sitescope.app.dpi.DpiEngine
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Состояние и журнал встроенного Tor (общие для сервиса и экрана; сервис работает в основном процессе, сам Tor — отдельный процесс ОС). */
object TorRuntime {
    private val _state = MutableStateFlow(TorSnapshot())
    val state: StateFlow<TorSnapshot> = _state.asStateFlow()
    val current: TorSnapshot get() = _state.value
    fun publish(value: TorSnapshot) { _state.value = value }

    private val lines = ArrayDeque<String>()
    fun log(line: String) { synchronized(lines) { lines.addLast(line.take(400)); while (lines.size > 300) lines.removeFirst() } }
    fun logText(): String = synchronized(lines) { lines.joinToString("\n") }
}

/**
 * Встроенный Tor: исполняемый libtor.so (Tor 0.4.9.5) запускается отдельным процессом с собственным torrc.
 * Способ выхода в сеть: напрямую, через byedpi (обход DPI) или через заданный SOCKS5; в режиме «Авто»
 * при зависании на ранней стадии один раз переключается Direct → DPI. Падение Tor не затрагивает приложение.
 */
class TorProcessService : Service() {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "anon-tor-control").apply { isDaemon = true } }
    private val runCounter = AtomicLong(0)
    private val lock = Any()
    @Volatile private var process: Process? = null
    @Volatile private var activeRunId = 0L
    @Volatile private var stopping = false
    @Volatile private var requested = AnonTransport.AUTO
    @Volatile private var actual = AnonTransport.DIRECT
    @Volatile private var fallbackTried = false
    @Volatile private var dpiHeld = false
    @Volatile private var port = 0
    @Volatile private var lastDiag = AnonDiagnostics.Diagnosis(AnonFailure.NONE, "")
    private val tlsFailures = AtomicInteger(0)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> { foreground("Tor запускается", 0); worker.execute(::startTor) }
            ACTION_STOP -> worker.execute { stopTor("Tor выключен") }
            else -> if (process?.isAlive != true) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        val target = synchronized(lock) { activeRunId = runCounter.incrementAndGet(); stopping = true; process.also { process = null } }
        terminate(target)
        releaseDpi()
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun foreground(text: String, progress: Int) {
        startForeground(NOTIFICATION_ID, anonNotification(this, "Tor", text, progress, TorProcessService::class.java, ACTION_STOP, 1))
    }

    private fun publish(value: TorSnapshot) {
        TorRuntime.publish(value)
        if (value.phase.active) (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, anonNotification(this, "Tor", value.message, value.progress, TorProcessService::class.java, ACTION_STOP, 1))
    }

    private fun snap() = TorRuntime.current

    private fun acquireDpi(): ProxySpec? { val p = AnonDpi.acquire(this, "tor"); dpiHeld = p != null; return p }
    private fun releaseDpi() { if (dpiHeld) { dpiHeld = false; AnonDpi.release("tor") } }

    private fun startTor() {
        synchronized(lock) {
            if (process?.isAlive == true) { publish(snap()); return }
            fallbackTried = false
        }
        AnonNet.init(this)
        val s = AnonNet.settings()
        requested = s.torTransport
        val dpi = if (requested == AnonTransport.DPI) acquireDpi() else null
        val plan = AnonTransportPlanner.plan(requested, dpi, ProxySpec.parse(s.torProxy))
        log("TRANSPORT requested=$requested actual=${plan.actual} reason=${plan.reason}")
        plan.error?.let { failNoProcess(AnonDiagnostics.Diagnosis(AnonFailure.PROXY, it, plan.reason), plan.actual); return }
        launch(plan.actual, plan.proxy, if (requested == AnonTransport.AUTO) "Авто" else null)
    }

    private fun launch(transport: AnonTransport, proxy: ProxySpec?, prefix: String?) {
        val runId = runCounter.incrementAndGet()
        synchronized(lock) {
            activeRunId = runId; stopping = false; actual = transport
            lastDiag = AnonDiagnostics.Diagnosis(AnonFailure.NONE, ""); tlsFailures.set(0)
        }
        val how = when (transport) { AnonTransport.DPI -> "через обход DPI"; AnonTransport.PROXY -> "через SOCKS5"; else -> "напрямую" }
        publish(TorSnapshot(RunPhase.STARTING, 0, 0, listOfNotNull(prefix, "Подготовка Tor $how").joinToString(" · "), requested = requested, actual = transport))
        try {
            val binary = File(applicationInfo.nativeLibraryDir, "libtor.so")
            require(binary.isFile) { "Встроенный Tor не найден в приложении (нужен arm64)" }
            require(binary.canExecute() || binary.setExecutable(true, false)) { "Нет разрешения на запуск Tor" }
            val root = File(noBackupFilesDir, "anon/tor").apply { mkdirs() }
            val data = File(root, "data").apply { mkdirs() }
            val cache = File(root, "cache").apply { mkdirs() }
            port = if (DpiEngine.portFree(TorConf.DEFAULT_SOCKS)) TorConf.DEFAULT_SOCKS else DpiEngine.freePort()
            val bridges = TorConf.parseBridges(AnonNet.settings().torBridges)
            if (bridges.skipped.isNotEmpty()) log("BRIDGES пропущено ${bridges.skipped.size}: нужны только простые мосты IP:порт (obfs4/snowflake/webtunnel без клиента не работают)")
            val torrc = File(root, "torrc").apply { writeText(TorConf.torrc(data.absolutePath, cache.absolutePath, port, proxy, bridges.lines)) }
            log("START run=$runId requested=$requested actual=$transport port=$port bridges=${bridges.lines.size}")
            val launched = ProcessBuilder(binary.absolutePath, "-f", torrc.absolutePath).directory(root).redirectErrorStream(true).apply {
                environment()["HOME"] = root.absolutePath
                environment()["TMPDIR"] = cache.absolutePath
                environment()["LD_LIBRARY_PATH"] = applicationInfo.nativeLibraryDir
            }.start()
            synchronized(lock) {
                if (activeRunId != runId) { terminate(launched); return }
                process = launched
            }
            daemon("anon-tor-log-$runId") { consume(launched, runId) }
            daemon("anon-tor-watch-$runId") { watchExit(launched, runId) }
            daemon("anon-tor-dog-$runId") { watchdog(launched, runId) }
        } catch (t: Throwable) {
            fail(AnonDiagnostics.tor(t.message ?: t.javaClass.simpleName).let {
                if (it.kind == AnonFailure.UNKNOWN) it.copy(message = "Не удалось запустить Tor: " + (t.message ?: t.javaClass.simpleName)) else it
            }, runId)
        }
    }

    private fun daemon(name: String, block: () -> Unit) { Thread(block, name).apply { isDaemon = true; start() } }

    private fun isCurrent(target: Process, runId: Long) = activeRunId == runId && process === target

    private fun consume(target: Process, runId: Long) {
        runCatching {
            target.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    val clean = AnonDiagnostics.sanitize(line)
                    log(clean)
                    if (!isCurrent(target, runId) || stopping) return@forEach
                    val p = TorConf.progress(line)
                    when {
                        p != null -> {
                            val ready = p >= 100 && waitSocks(target, runId, 6_000)
                            val prev = snap()
                            publish(TorSnapshot(
                                if (ready) RunPhase.READY else RunPhase.BOOTSTRAPPING, p, if (ready) port else 0,
                                if (ready) "Tor готов" else stage(p), requested = requested, actual = actual,
                                failure = if (ready) AnonFailure.NONE else prev.failure, bypassHelps = if (ready) false else prev.bypassHelps,
                                diagnostic = if (ready) null else prev.diagnostic,
                            ))
                        }
                        line.contains("Opening Socks listener", true) -> publish(snap().copy(message = "Локальный SOCKS Tor запущен", actual = actual))
                        line.contains("warn", true) || line.contains("[err]", true) || line.contains("error", true) -> {
                            val d = AnonDiagnostics.tor(line)
                            if (d.kind != AnonFailure.UNKNOWN && d.meaningful) record(d)
                            else publish(snap().copy(message = clean.take(300), diagnostic = clean))
                        }
                    }
                }
            }
        }.onFailure { if (isCurrent(target, runId) && !stopping) fail(AnonDiagnostics.Diagnosis(AnonFailure.EXITED, "Не удалось прочитать вывод Tor: ${it.message}"), runId) }
    }

    private fun record(d: AnonDiagnostics.Diagnosis) {
        lastDiag = d
        if (d.kind == AnonFailure.TLS_BLOCKED) tlsFailures.incrementAndGet()
        publish(snap().copy(message = d.message, actual = actual, failure = d.kind, bypassHelps = d.bypassHelps, diagnostic = d.raw))
    }

    private fun watchdog(target: Process, runId: Long) {
        val start = System.currentTimeMillis()
        while (isCurrent(target, runId) && target.isAlive && !stopping) {
            val elapsed = System.currentTimeMillis() - start
            val progress = snap().progress
            if (!fallbackTried && AnonTransportPlanner.torStuck(progress, elapsed)) {
                val next = AnonTransportPlanner.fallback(requested, actual, AnonDpi.available(this))
                if (next != null) {
                    fallbackTried = true
                    log("FALLBACK $actual -> $next на $progress%")
                    worker.execute { restart(target, runId, next) }
                    return
                }
            }
            if (elapsed >= 180_000 && snap().phase != RunPhase.READY) {
                fail(AnonDiagnostics.torTimeout(progress, lastDiag.takeIf { it.meaningful }), runId)
                return
            }
            try { Thread.sleep(1_000) } catch (_: InterruptedException) { return }
        }
    }

    private fun restart(old: Process, runId: Long, next: AnonTransport) {
        synchronized(lock) {
            if (!isCurrent(old, runId)) return
            activeRunId = runCounter.incrementAndGet()
            process = null
        }
        terminate(old)
        val dpi = if (next == AnonTransport.DPI) acquireDpi() else null
        val plan = AnonTransportPlanner.plan(next, dpi, null)
        if (plan.error != null) { failNoProcess(AnonDiagnostics.Diagnosis(AnonFailure.PROXY, plan.error, plan.reason), next); return }
        launch(plan.actual, plan.proxy, "Смена способа после остановки на ${snap().progress}%")
    }

    private fun watchExit(target: Process, runId: Long) {
        val code = runCatching { target.waitFor() }.getOrDefault(-1)
        if (!isCurrent(target, runId) || stopping) return
        synchronized(lock) { if (process === target) process = null }
        fail(lastDiag.takeIf { it.meaningful } ?: AnonDiagnostics.Diagnosis(AnonFailure.EXITED, "Процесс Tor завершился с кодом $code", "exit_code=$code"), runId)
    }

    private fun stopTor(reason: String) {
        val target = synchronized(lock) {
            activeRunId = runCounter.incrementAndGet(); stopping = true
            publish(snap().copy(phase = RunPhase.STOPPING, message = "Остановка Tor"))
            process.also { process = null }
        }
        terminate(target)
        releaseDpi()
        publish(TorSnapshot(message = reason, requested = requested))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun fail(d: AnonDiagnostics.Diagnosis, runId: Long) {
        val target = synchronized(lock) {
            if (runId != activeRunId) return
            activeRunId = runCounter.incrementAndGet(); stopping = true
            process.also { process = null }
        }
        terminate(target)
        failNoProcess(d.takeIf { it.meaningful } ?: AnonDiagnostics.Diagnosis(AnonFailure.UNKNOWN, "Неизвестная ошибка Tor"), actual)
    }

    private fun failNoProcess(d: AnonDiagnostics.Diagnosis, transport: AnonTransport) {
        log("FAILED kind=${d.kind}: ${d.message}; raw=${d.raw.orEmpty()}")
        releaseDpi()
        publish(TorSnapshot(RunPhase.FAILED, snap().progress, 0, "Tor не запущен", d.message.take(500), requested, transport, d.kind, d.bypassHelps, d.raw?.take(800)))
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun log(line: String) { TorRuntime.log(line); runCatching {
        val f = File(noBackupFilesDir, "anon/tor/tor.log")
        if (f.length() > 600_000L) { f.copyTo(File(f.parentFile, "tor.log.1"), overwrite = true); f.writeText("") }
        f.appendText("${System.currentTimeMillis()} $line\n")
    } }

    private fun canConnect(p: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", p), 350) } }.isSuccess

    private fun waitSocks(target: Process, runId: Long, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && isCurrent(target, runId)) {
            if (canConnect(port)) return true
            Thread.sleep(100)
        }
        return false
    }

    private fun terminate(target: Process?) {
        if (target == null) return
        runCatching {
            target.destroy()
            if (!target.waitFor(2, TimeUnit.SECONDS)) { target.destroyForcibly(); target.waitFor(1, TimeUnit.SECONDS) }
        }
    }

    private fun stage(p: Int) = when {
        p < 10 -> "Запуск Tor · $p%"
        p < 20 -> "Подключение к каталогам Tor · $p%"
        p < 80 -> "Построение цепочки Tor · $p%"
        else -> "Проверка цепочки Tor · $p%"
    }

    companion object {
        const val ACTION_START = "pro.sitescope.app.anon.TOR_START"
        const val ACTION_STOP = "pro.sitescope.app.anon.TOR_STOP"
        const val NOTIFICATION_ID = 32060

        fun start(c: Context) = ContextCompat.startForegroundService(c, Intent(c, TorProcessService::class.java).setAction(ACTION_START))
        fun stop(c: Context) { runCatching { c.startService(Intent(c, TorProcessService::class.java).setAction(ACTION_STOP)) } }
        fun readLog(c: Context): String =
            TorRuntime.logText().ifBlank { File(c.noBackupFilesDir, "anon/tor/tor.log").takeIf(File::isFile)?.readText()?.takeLast(40_000) ?: "Журнал Tor пока пуст" }
    }
}
