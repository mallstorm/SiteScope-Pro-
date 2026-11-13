package pro.sitescope.app.anon

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.torproject.jni.TorService
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Настройки Onion/I2P. */
data class AnonSettings(
    val embeddedTor: Boolean = true,
    val torHost: String = "127.0.0.1",
    val torPort: Int = 9050,
    val i2pHost: String = "127.0.0.1",
    val i2pPort: Int = 4444,
    val i2pSocks: Boolean = false,
    val torForAll: Boolean = false,
)

enum class TorPhase { OFF, STARTING, ON, ERROR }

data class AnonState(
    val tor: TorPhase = TorPhase.OFF,
    val torNote: String = "",
    val torPort: Int = 0,
    val proxyPort: Int = 0,
    val webViewProxyOn: Boolean = false,
)

/** Состояние и запуск Tor (встроенный Tor от Guardian Project или внешний SOCKS, например Orbot), I2P-прокси и прокси-маршрутизатора для WebView. */
object AnonNet {
    private const val PREFS = "anon_net"
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(AnonState())
    val state: StateFlow<AnonState> = _state.asStateFlow()

    @Volatile private var settings = AnonSettings()
    private var inited = false
    private var receiverOn = false
    private var torService: TorService? = null
    private var bound = false

    private val proxy = AnonProxy({ currentConfig() })

    interface Listener {
        fun status(text: String)
        fun ready()
        fun failed(text: String)
    }

    @JvmStatic @Synchronized
    fun init(context: Context) {
        if (inited) return
        app = context.applicationContext
        settings = load()
        inited = true
    }

    fun settings(): AnonSettings = settings

    fun save(value: AnonSettings) {
        settings = value
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("embedded", value.embeddedTor).putString("torHost", value.torHost).putInt("torPort", value.torPort)
            .putString("i2pHost", value.i2pHost).putInt("i2pPort", value.i2pPort).putBoolean("i2pSocks", value.i2pSocks)
            .putBoolean("torAll", value.torForAll).apply()
    }

    private fun load(): AnonSettings {
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = AnonSettings()
        return AnonSettings(
            p.getBoolean("embedded", d.embeddedTor), p.getString("torHost", d.torHost) ?: d.torHost, p.getInt("torPort", d.torPort),
            p.getString("i2pHost", d.i2pHost) ?: d.i2pHost, p.getInt("i2pPort", d.i2pPort), p.getBoolean("i2pSocks", d.i2pSocks), p.getBoolean("torAll", d.torForAll),
        )
    }

    private fun torUpstream(): Upstream? {
        val s = settings
        return if (s.embeddedTor) {
            val port = _state.value.torPort
            if (_state.value.tor == TorPhase.ON && port > 0) Upstream.Socks5("127.0.0.1", port) else null
        } else Upstream.Socks5(s.torHost, s.torPort)
    }

    private fun i2pUpstream(): Upstream {
        val s = settings
        return if (s.i2pSocks) Upstream.Socks5(s.i2pHost, s.i2pPort) else Upstream.Http(s.i2pHost, s.i2pPort)
    }

    private fun currentConfig() = AnonConfig(tor = torUpstream(), i2p = i2pUpstream(), torForAll = settings.torForAll, direct = dpiUpstream())

    private fun dpiUpstream(): Upstream? =
        if (inited && pro.sitescope.app.dpi.DpiStore.browserViaDpi(app) && pro.sitescope.app.dpi.DpiRuntime.running) Upstream.Socks5("127.0.0.1", pro.sitescope.app.dpi.DpiRuntime.port) else null

    /** Обычные адреса браузера должны идти через локальный прокси, потому что включён обход DPI для браузера. */
    @JvmStatic
    fun directViaDpi(): Boolean = inited && dpiUpstream() != null

    // ───── встроенный Tor ─────

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                TorService.ACTION_STATUS -> when (intent.getStringExtra(TorService.EXTRA_STATUS)) {
                    TorService.STATUS_STARTING -> update { it.copy(tor = TorPhase.STARTING, torNote = "Tor запускается и строит цепочку…") }
                    TorService.STATUS_ON -> update { it.copy(tor = TorPhase.ON, torNote = "Tor работает", torPort = torService?.socksPort ?: it.torPort) }
                    TorService.STATUS_STOPPING -> update { if (it.tor == TorPhase.ERROR) it else it.copy(tor = TorPhase.OFF, torNote = "Tor останавливается") }
                    TorService.STATUS_OFF -> update { if (it.tor == TorPhase.ERROR) it else it.copy(tor = TorPhase.OFF, torNote = "Tor остановлен", torPort = 0) }
                }
                TorService.ACTION_ERROR -> update { it.copy(tor = TorPhase.ERROR, torNote = "Ошибка Tor: " + (intent.getStringExtra(Intent.EXTRA_TEXT) ?: "неизвестно")) }
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            torService = (binder as? TorService.LocalBinder)?.service
            update { it.copy(torPort = torService?.socksPort?.takeIf { p -> p > 0 } ?: it.torPort) }
        }
        override fun onServiceDisconnected(name: ComponentName) { torService = null; bound = false }
    }

    private inline fun update(change: (AnonState) -> AnonState) { _state.value = change(_state.value) }

    @Synchronized
    fun startTor() {
        if (!inited) return
        if (_state.value.tor == TorPhase.STARTING || _state.value.tor == TorPhase.ON) return
        if (!receiverOn) {
            ContextCompat.registerReceiver(app, receiver, IntentFilter().apply {
                addAction(TorService.ACTION_STATUS); addAction(TorService.ACTION_ERROR)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverOn = true
        }
        update { it.copy(tor = TorPhase.STARTING, torNote = "Tor запускается и строит цепочку…", torPort = 0) }
        try {
            val intent = Intent(app, TorService::class.java)
            app.startService(intent)
            if (!bound) bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            update { it.copy(tor = TorPhase.ERROR, torNote = "Не удалось запустить Tor: " + (e.message ?: e.javaClass.simpleName)) }
        }
    }

    @Synchronized
    fun stopTor() {
        if (!inited) return
        try { if (bound) app.unbindService(connection) } catch (_: Exception) { }
        bound = false
        try { app.stopService(Intent(app, TorService::class.java)) } catch (_: Exception) { }
        torService = null
        update { it.copy(tor = TorPhase.OFF, torNote = "Tor остановлен", torPort = 0) }
    }

    // ───── проверки доступности ─────

    @JvmStatic
    fun reachable(host: String, port: Int, timeoutMs: Int = 2500): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (_: Exception) { false }

    fun probeTorExternal(): Boolean = reachable(settings.torHost, settings.torPort)
    fun probeI2p(): Boolean = reachable(settings.i2pHost, settings.i2pPort)

    /** Порты, на которых обычно слушает внешний Tor: Orbot 9050, Tor Browser 9150. */
    fun detectExternalTorPort(): Int? = listOf(9050, 9150).firstOrNull { reachable("127.0.0.1", it, 800) }

    // ───── WebView ─────

    /**
     * Готовит всё нужное, чтобы открыть [url]: поднимает локальный прокси, при необходимости Tor,
     * включает прокси в WebView. Вызывать из любого потока; ответы приходят в основном потоке.
     */
    @JvmStatic
    fun prepare(context: Context, url: String, listener: Listener) {
        init(context)
        val host = AnonRouting.hostOf(url)
        val route = AnonRouting.routeOf(host, settings.torForAll)
        if (route == AnonRouting.Route.DIRECT && !directViaDpi() && !_state.value.webViewProxyOn) { main.post { listener.ready() }; return }
        Thread({
            try {
                val port = proxy.start()
                update { it.copy(proxyPort = port) }
                when (route) {
                    AnonRouting.Route.TOR -> waitForTor(listener)
                    AnonRouting.Route.I2P -> {
                        post { listener.status("Проверяю I2P-роутер ${settings.i2pHost}:${settings.i2pPort}…") }
                        if (!probeI2p()) throw IllegalStateException(
                            "I2P-роутер не отвечает на ${settings.i2pHost}:${settings.i2pPort}.\n\nЗапустите приложение I2P или i2pd и включите HTTP-прокси " +
                                "(по умолчанию 127.0.0.1:4444), либо укажите другой адрес в разделе «Onion и I2P». Первый запуск I2P может занять несколько минут.")
                    }
                    else -> Unit
                }
                if (!_state.value.webViewProxyOn) installWebViewProxy(port)
                post { listener.ready() }
            } catch (e: Exception) {
                post { listener.failed(e.message ?: e.javaClass.simpleName) }
            }
        }, "anon-prepare").start()
    }

    private fun post(block: () -> Unit) { main.post(block) }

    private fun waitForTor(listener: Listener) {
        val s = settings
        if (!s.embeddedTor) {
            post { listener.status("Проверяю Tor-прокси ${s.torHost}:${s.torPort}…") }
            if (!probeTorExternal()) throw IllegalStateException(
                "Tor-прокси ${s.torHost}:${s.torPort} не отвечает.\n\nЗапустите Orbot (или Tor Browser для порта 9150) либо переключитесь на встроенный Tor в разделе «Onion и I2P».")
            return
        }
        if (_state.value.tor != TorPhase.ON) {
            main.post { startTor() }
            val started = System.currentTimeMillis()
            while (true) {
                val st = _state.value
                if (st.tor == TorPhase.ON && torUpstream() != null) break
                if (st.tor == TorPhase.ERROR) throw IllegalStateException(st.torNote)
                val elapsed = ((System.currentTimeMillis() - started) / 1000).toInt()
                if (elapsed > 180) throw IllegalStateException("Tor не построил цепочку за 3 минуты. Возможно, сеть Tor заблокирована у провайдера — попробуйте другую сеть или мосты (Orbot).")
                post { listener.status("Запуск Tor… $elapsed с") }
                Thread.sleep(700)
            }
        }
    }

    private fun installWebViewProxy(port: Int) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) throw IllegalStateException(
            "Эта версия Android System WebView не поддерживает прокси. Обновите WebView в магазине приложений.")
        val latch = CountDownLatch(1)
        val config = ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").build()
        ProxyController.getInstance().setProxyOverride(config, { it.run() }, { latch.countDown() })
        if (!latch.await(10, TimeUnit.SECONDS)) throw IllegalStateException("WebView не применил настройки прокси")
        update { it.copy(webViewProxyOn = true) }
    }

    /** Снимает прокси с WebView (при закрытии браузера). Tor остаётся работать до остановки вручную. */
    @JvmStatic
    fun releaseWebView() {
        if (!inited || !_state.value.webViewProxyOn) return
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) ProxyController.getInstance().clearProxyOverride({ it.run() }, { })
        } catch (_: Exception) { }
        update { it.copy(webViewProxyOn = false) }
        proxy.stop()
        update { it.copy(proxyPort = 0) }
    }
}
