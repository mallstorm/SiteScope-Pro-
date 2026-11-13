package pro.sitescope.app.anon

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.purplei2p.i2pd.I2PD_JNI
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Обмен между процессом :i2pcore и основным процессом. */
object I2pContract {
    const val ACTION_START = "pro.sitescope.app.anon.I2P_START"
    const val ACTION_STOP = "pro.sitescope.app.anon.I2P_STOP"
    const val ACTION_QUERY = "pro.sitescope.app.anon.I2P_QUERY"
    const val ACTION_STATUS = "pro.sitescope.app.anon.I2P_STATUS"
    const val X_HTTP = "http_port"
    const val X_SOCKS = "socks_port"
    const val X_RESEED_PROXY = "reseed_proxy"
    const val X_NTCP_PROXY = "ntcp_proxy"
    const val X_RESEED_FILE = "reseed_file"
    const val X_ACTUAL = "actual"
    const val NOTIFICATION_ID = 32061

    fun toIntent(context: Context, s: I2pSnapshot): Intent = Intent(ACTION_STATUS).setPackage(context.packageName)
        .putExtra("phase", s.phase.name).putExtra("progress", s.progress).putExtra(X_HTTP, s.httpPort).putExtra(X_SOCKS, s.socksPort)
        .putExtra("message", s.message).putExtra("error", s.error).putExtra("inbound", s.inbound).putExtra("outbound", s.outbound)
        .putExtra(X_ACTUAL, s.actual.name).putExtra("failure", s.failure.name).putExtra("bypass", s.bypassHelps)

    fun fromIntent(i: Intent) = I2pSnapshot(
        RunPhase.from(i.getStringExtra("phase")), i.getIntExtra("progress", 0), i.getIntExtra(X_HTTP, 0), i.getIntExtra(X_SOCKS, 0),
        i.getStringExtra("message") ?: "", i.getStringExtra("error"), i.getIntExtra("inbound", 0), i.getIntExtra("outbound", 0),
        AnonTransport.from(i.getStringExtra(X_ACTUAL)), AnonFailure.from(i.getStringExtra("failure")), i.getBooleanExtra("bypass", false),
    )
}

/**
 * Встроенный I2P: официальная JNI-библиотека i2pd (PurpleI2P) в отдельном процессе :i2pcore — падение нативного кода не роняет приложение.
 * После остановки процесс завершается, чтобы следующий запуск начинался с чистого состояния.
 */
class I2pService : Service() {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "anon-i2p-control").apply { isDaemon = true } }
    private val started = AtomicBoolean(false)
    @Volatile private var stopping = false
    @Volatile private var snap = I2pSnapshot()
    @Volatile private var actual = AnonTransport.DIRECT
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            I2pContract.ACTION_START -> {
                val http = intent.getIntExtra(I2pContract.X_HTTP, I2pConf.DEFAULT_HTTP)
                val socks = intent.getIntExtra(I2pContract.X_SOCKS, I2pConf.DEFAULT_SOCKS)
                actual = AnonTransport.from(intent.getStringExtra(I2pContract.X_ACTUAL))
                val options = I2pOptions(dataDir().absolutePath, http, socks, intent.getStringExtra(I2pContract.X_RESEED_PROXY),
                    intent.getStringExtra(I2pContract.X_NTCP_PROXY), intent.getStringExtra(I2pContract.X_RESEED_FILE))
                foreground("I2P запускается", 0)
                worker.execute { startI2p(options) }
            }
            I2pContract.ACTION_STOP -> worker.execute { stopI2p("I2P выключен") }
            else -> { publish(snap); if (!snap.phase.active) stopSelf(startId) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        if (started.get()) runCatching { I2PD_JNI.stopDaemon() }
        started.set(false)
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun foreground(text: String, progress: Int) {
        startForeground(I2pContract.NOTIFICATION_ID, anonNotification(this, "I2P", text, progress, I2pService::class.java, I2pContract.ACTION_STOP, 2))
    }

    private fun publish(value: I2pSnapshot) {
        snap = value
        if (value.phase.active) (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(I2pContract.NOTIFICATION_ID, anonNotification(this, "I2P", value.message, value.progress, I2pService::class.java, I2pContract.ACTION_STOP, 2))
        sendBroadcast(I2pContract.toIntent(this, value))
    }

    private fun startI2p(o: I2pOptions) {
        if (!started.compareAndSet(false, true)) { publish(snap); return }
        stopping = false
        registerNetworkCallback()
        publish(I2pSnapshot(RunPhase.STARTING, 5, o.httpPort, o.socksPort, "Подготовка I2P", actual = actual))
        try {
            prepare(o)
            I2PD_JNI.loadLibraries()
            log("JNI загружен, ABI=${runCatching { I2PD_JNI.getABICompiledWith() }.getOrDefault("?")} транспорт=$actual")
            rotateLog()
            I2PD_JNI.setDataDir(o.dataDir)
            I2PD_JNI.setLanguage("ru")
            notifyNetwork()
            publish(I2pSnapshot(RunPhase.BOOTSTRAPPING, 20, o.httpPort, o.socksPort, "Запуск маршрутизатора I2P", actual = actual))
            val result = I2PD_JNI.startDaemon().orEmpty()
            log("startDaemon: ${result.ifBlank { "empty" }}")
            if (result.isNotBlank() && !result.equals("ok", true)) throw IllegalStateException(result)
            waitReady(o)
        } catch (t: Throwable) {
            fail(t.message ?: t.javaClass.simpleName, o)
        }
    }

    private fun waitReady(o: I2pOptions) {
        val t0 = System.currentTimeMillis()
        var last = I2pSnapshot()
        while (!stopping && started.get()) {
            val elapsed = System.currentTimeMillis() - t0
            val http = canConnect(o.httpPort)
            val socks = canConnect(o.socksPort)
            val tail = readLogTail()
            val ls = I2pLog.parse(tail)
            if (http && ls.ready) {
                publish(I2pSnapshot(RunPhase.READY, 100, o.httpPort, o.socksPort, "I2P готов · туннели построены", null, ls.inbound, ls.outbound, actual))
                log("READY http=$http socks=$socks in=${ls.inbound} out=${ls.outbound}")
                return
            }
            if (elapsed > 300_000L) {
                val d = AnonDiagnostics.i2p(tail)
                throw IllegalStateException(if (d.kind == AnonFailure.RESEED) d.message else "I2P не построил входящий и исходящий туннели за 300 секунд.")
            }
            val diag = if (elapsed > 45_000L && !ls.ready) AnonDiagnostics.i2p(tail) else AnonDiagnostics.Diagnosis(AnonFailure.NONE, "")
            val progress = when {
                !http && !socks -> if (elapsed > 30_000L) 30 else 20
                ls.inbound == 0 && ls.outbound == 0 -> if (elapsed > 120_000L) 55 else 42
                ls.inbound == 0 || ls.outbound == 0 -> 75
                else -> 88
            }
            val message = when {
                diag.kind == AnonFailure.RESEED -> "Не удаётся получить список узлов (reseed)"
                !http && !socks -> "Запуск локальных I2P-прокси"
                ls.peersUnavailable && !ls.ready -> "I2P ищет узлы и строит туннели"
                ls.inbound == 0 && ls.outbound == 0 -> "Прокси открыт · ожидание туннелей"
                ls.inbound == 0 -> "Ожидание входящего туннеля"
                ls.outbound == 0 -> "Ожидание исходящего туннеля"
                else -> "Проверка готовности сети I2P"
            }
            val next = I2pSnapshot(RunPhase.BOOTSTRAPPING, progress, o.httpPort, o.socksPort, message, null, ls.inbound, ls.outbound, actual, diag.kind, diag.bypassHelps)
            if (next != last) { last = next; publish(next) }
            try { Thread.sleep(1_000L) } catch (_: InterruptedException) { return }
        }
    }

    private fun prepare(o: I2pOptions) {
        val data = File(o.dataDir).apply { mkdirs() }
        installTree("i2pd/certificates", File(data, "certificates"))
        installTree("i2pd/addressbook", File(data, "addressbook"))
        copyAsset("i2pd/tunnels.conf", File(data, "tunnels.conf"))
        File(data, "i2pd.conf").writeText(I2pConf.build(o))
    }

    private fun stopI2p(reason: String) {
        stopping = true
        publish(snap.copy(phase = RunPhase.STOPPING, message = "Остановка I2P"))
        if (started.get()) { runCatching { I2PD_JNI.stopDaemon() }.onFailure { log("stopDaemon: ${it.message}") }; started.set(false) }
        publish(I2pSnapshot(message = reason))
        finishProcess()
    }

    private fun fail(message: String, o: I2pOptions) {
        log("FAILED: $message")
        val tail = readLogTail()
        val d = AnonDiagnostics.i2p(tail)
        if (started.get()) runCatching { I2PD_JNI.stopDaemon() }
        started.set(false)
        publish(I2pSnapshot(RunPhase.FAILED, snap.progress, o.httpPort, o.socksPort, "I2P не запущен",
            AnonDiagnostics.sanitize(if (d.kind == AnonFailure.RESEED) d.message else message), snap.inbound, snap.outbound, actual,
            if (d.kind == AnonFailure.RESEED) AnonFailure.RESEED else AnonFailure.UNKNOWN, d.bypassHelps))
        finishProcess()
    }

    /** После остановки процесс завершается: состояние i2pd (глобальные синглтоны) не рассчитано на повторный старт. */
    private fun finishProcess() {
        unregisterNetworkCallback()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        Handler(Looper.getMainLooper()).postDelayed({ Process.killProcess(Process.myPid()) }, 800)
    }

    private fun registerNetworkCallback() {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = notifyNetwork()
            override fun onLost(network: Network) = notifyNetwork()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = notifyNetwork()
        }
        networkCallback = cb
        runCatching { getSystemService(ConnectivityManager::class.java).registerNetworkCallback(NetworkRequest.Builder().build(), cb) }
    }

    private fun unregisterNetworkCallback() {
        networkCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkCallback = null
    }

    private fun notifyNetwork() {
        if (!started.get()) return
        val available = getSystemService(ConnectivityManager::class.java).activeNetwork != null
        runCatching { I2PD_JNI.onNetworkStateChanged(available) }
    }

    private fun root() = File(noBackupFilesDir, "anon/i2p").apply { mkdirs() }
    private fun dataDir() = File(root(), "data").apply { mkdirs() }
    private fun nativeLog() = File(dataDir(), "i2pd.log")

    private fun rotateLog() {
        runCatching {
            val cur = nativeLog()
            if (cur.exists()) { cur.copyTo(File(cur.parentFile, "i2pd.log.previous"), overwrite = true); cur.delete() }
        }
    }

    private fun readLogTail(): String = runCatching {
        val f = nativeLog()
        if (!f.isFile) return@runCatching ""
        RandomAccessFile(f, "r").use { r ->
            val len = r.length()
            val size = minOf(len, 128_000L).toInt()
            r.seek(len - size)
            ByteArray(size).also(r::readFully).toString(Charsets.UTF_8)
        }
    }.getOrDefault("")

    private fun log(line: String) {
        runCatching {
            val f = File(root(), "runtime.log")
            if (f.length() > 400_000L) { f.copyTo(File(f.parentFile, "runtime.log.1"), overwrite = true); f.writeText("") }
            f.appendText("${System.currentTimeMillis()} ${AnonDiagnostics.sanitize(line)}\n")
        }
    }

    private fun copyAsset(asset: String, target: File) {
        if (target.isFile && target.length() > 0L) return
        target.parentFile?.mkdirs()
        assets.open(asset).use { input -> FileOutputStream(target).use(input::copyTo) }
    }

    private fun installTree(asset: String, target: File) {
        val children = assets.list(asset).orEmpty()
        if (children.isEmpty()) { copyAsset(asset, target); return }
        target.mkdirs()
        children.forEach { installTree("$asset/$it", File(target, it)) }
    }

    private fun canConnect(port: Int) = runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 350) } }.isSuccess

    companion object {
        fun start(c: Context, extras: Intent.() -> Unit) =
            ContextCompat.startForegroundService(c, Intent(c, I2pService::class.java).setAction(I2pContract.ACTION_START).apply(extras))
        fun stop(c: Context) { runCatching { c.startService(Intent(c, I2pService::class.java).setAction(I2pContract.ACTION_STOP)) } }
        fun query(c: Context) { runCatching { c.startService(Intent(c, I2pService::class.java).setAction(I2pContract.ACTION_QUERY)) } }
        fun readLog(c: Context): String {
            val root = File(c.noBackupFilesDir, "anon/i2p")
            val runtime = File(root, "runtime.log").takeIf(File::isFile)?.readText().orEmpty()
            val native = File(File(root, "data"), "i2pd.log").takeIf(File::isFile)?.readText().orEmpty()
            return (runtime + "\n" + native).takeLast(40_000).ifBlank { "Журнал I2P пока пуст" }
        }
    }
}
