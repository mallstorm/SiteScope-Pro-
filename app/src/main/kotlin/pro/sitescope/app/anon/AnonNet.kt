package pro.sitescope.app.anon

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pro.sitescope.app.dpi.DpiEngine
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Настройки Onion/I2P. */
data class AnonSettings(
    val embeddedTor: Boolean = true,
    val torHost: String = "127.0.0.1",
    val torPort: Int = 9050,
    val torTransport: AnonTransport = AnonTransport.AUTO,
    val torProxy: String = "",
    val torBridges: String = "",
    val embeddedI2p: Boolean = true,
    val i2pHost: String = "127.0.0.1",
    val i2pPort: Int = 4444,
    val i2pSocks: Boolean = false,
    val i2pTransport: AnonTransport = AnonTransport.AUTO,
    val i2pProxy: String = "",
    val i2pReseedFile: String = "",
    val torForAll: Boolean = false,
)

data class AnonState(
    val tor: TorSnapshot = TorSnapshot(),
    val i2p: I2pSnapshot = I2pSnapshot(),
    val proxyPort: Int = 0,
    val webViewProxyOn: Boolean = false,
)

/**
 * Tor и I2P для встроенного браузера: встроенные движки (Tor — процесс libtor.so, I2P — i2pd в процессе :i2pcore)
 * или внешние (Orbot, Tor Browser, I2P/i2pd). Локальный прокси-маршрутизатор направляет .onion в Tor, .i2p в I2P, остальное — напрямую или через byedpi.
 */
object AnonNet {
    private const val PREFS = "anon_net"
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(AnonState())
    val state: StateFlow<AnonState> = _state.asStateFlow()

    @Volatile private var settings = AnonSettings()
    private var inited = false
    private var receiverOn = false

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
        scope.launch { TorRuntime.state.collect { snap -> update { it.copy(tor = snap) } } }
    }

    fun settings(): AnonSettings = settings

    fun save(value: AnonSettings) {
        settings = value
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("embedded", value.embeddedTor).putString("torHost", value.torHost).putInt("torPort", value.torPort)
            .putString("torTransport", value.torTransport.key).putString("torProxy", value.torProxy).putString("torBridges", value.torBridges)
            .putBoolean("embeddedI2p", value.embeddedI2p).putString("i2pHost", value.i2pHost).putInt("i2pPort", value.i2pPort).putBoolean("i2pSocks", value.i2pSocks)
            .putString("i2pTransport", value.i2pTransport.key).putString("i2pProxy", value.i2pProxy).putString("i2pReseedFile", value.i2pReseedFile)
            .putBoolean("torAll", value.torForAll).apply()
    }

    private fun load(): AnonSettings {
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = AnonSettings()
        return AnonSettings(
            embeddedTor = p.getBoolean("embedded", d.embeddedTor), torHost = p.getString("torHost", d.torHost) ?: d.torHost, torPort = p.getInt("torPort", d.torPort),
            torTransport = AnonTransport.from(p.getString("torTransport", null)), torProxy = p.getString("torProxy", "") ?: "", torBridges = p.getString("torBridges", "") ?: "",
            embeddedI2p = p.getBoolean("embeddedI2p", d.embeddedI2p), i2pHost = p.getString("i2pHost", d.i2pHost) ?: d.i2pHost, i2pPort = p.getInt("i2pPort", d.i2pPort),
            i2pSocks = p.getBoolean("i2pSocks", d.i2pSocks), i2pTransport = AnonTransport.from(p.getString("i2pTransport", null)),
            i2pProxy = p.getString("i2pProxy", "") ?: "", i2pReseedFile = p.getString("i2pReseedFile", "") ?: "", torForAll = p.getBoolean("torAll", d.torForAll),
        )
    }

    private inline fun update(change: (AnonState) -> AnonState) { synchronized(this) { _state.value = change(_state.value) } }

    private fun torUpstream(): Upstream? {
        val s = settings
        if (!s.embeddedTor) return Upstream.Socks5(s.torHost, s.torPort)
        val t = _state.value.tor
        return if (t.ready) Upstream.Socks5("127.0.0.1", t.port) else null
    }

    private fun i2pUpstream(): Upstream {
        val s = settings
        if (!s.embeddedI2p) return if (s.i2pSocks) Upstream.Socks5(s.i2pHost, s.i2pPort) else Upstream.Http(s.i2pHost, s.i2pPort)
        val i = _state.value.i2p
        return Upstream.Http("127.0.0.1", if (i.ready) i.httpPort else I2pConf.DEFAULT_HTTP)
    }

    private fun currentConfig() = AnonConfig(tor = torUpstream(), i2p = i2pUpstream(), torForAll = settings.torForAll, direct = dpiUpstream())

    private fun dpiUpstream(): Upstream? =
        if (inited && pro.sitescope.app.dpi.DpiStore.browserViaDpi(app) && pro.sitescope.app.dpi.DpiRuntime.running) Upstream.Socks5("127.0.0.1", pro.sitescope.app.dpi.DpiRuntime.port) else null

    /** Обычные адреса браузера должны идти через локальный прокси, потому что включён обход DPI для браузера. */
    @JvmStatic
    fun directViaDpi(): Boolean = inited && dpiUpstream() != null

    // ───── встроенный Tor ─────

    @Synchronized
    fun startTor() {
        if (!inited) return
        if (_state.value.tor.phase.active) return
        update { it.copy(tor = TorSnapshot(RunPhase.STARTING, 0, 0, "Tor запускается…", requested = settings.torTransport)) }
        try { TorProcessService.start(app) }
        catch (e: Exception) { update { it.copy(tor = TorSnapshot(RunPhase.FAILED, message = "Tor не запущен", error = "Не удалось запустить Tor: " + (e.message ?: e.javaClass.simpleName))) } }
    }

    @Synchronized
    fun stopTor() {
        if (!inited) return
        TorProcessService.stop(app)
        if (!_state.value.tor.phase.active) update { it.copy(tor = TorSnapshot(message = "Tor выключен")) }
    }

    // ───── встроенный I2P ─────

    private var i2pRequested = AnonTransport.AUTO
    private var i2pActual = AnonTransport.DIRECT
    private var i2pFallbackTried = false
    @Volatile private var i2pRestarting = false
    private var i2pStartedAt = 0L

    private val i2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == I2pContract.ACTION_STATUS) onI2pStatus(I2pContract.fromIntent(intent))
        }
    }

    private fun ensureReceiver() {
        if (receiverOn) return
        ContextCompat.registerReceiver(app, i2pReceiver, IntentFilter(I2pContract.ACTION_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverOn = true
    }

    /** Узнать у процесса :i2pcore, работает ли I2P (после перезапуска приложения). */
    fun queryI2p() {
        if (!inited) return
        ensureReceiver()
        if (_state.value.i2p.phase.active) I2pService.query(app)
    }

    @Synchronized
    fun startI2p() {
        if (!inited) return
        val phase = _state.value.i2p.phase
        if (phase.active || phase == RunPhase.STOPPING) return
        ensureReceiver()
        i2pRequested = settings.i2pTransport
        i2pFallbackTried = false
        launchI2p(i2pRequested)
    }

    @Synchronized
    fun stopI2p() {
        if (!inited) return
        i2pRestarting = false
        AnonDpi.release("i2p")
        if (_state.value.i2p.phase.active) I2pService.stop(app) else update { it.copy(i2p = I2pSnapshot(message = "I2P выключен")) }
    }

    private fun launchI2p(transport: AnonTransport) {
        val s = settings
        val dpi = if (transport == AnonTransport.DPI) AnonDpi.acquire(app, "i2p") else null
        val plan = AnonTransportPlanner.plan(transport, dpi, ProxySpec.parse(s.i2pProxy))
        if (plan.error != null) {
            i2pRestarting = false
            update { it.copy(i2p = I2pSnapshot(RunPhase.FAILED, message = "I2P не запущен", error = plan.error, actual = plan.actual)) }
            return
        }
        i2pActual = plan.actual
        i2pStartedAt = System.currentTimeMillis()
        val http = if (DpiEngine.portFree(I2pConf.DEFAULT_HTTP)) I2pConf.DEFAULT_HTTP else DpiEngine.freePort()
        val socks = if (DpiEngine.portFree(I2pConf.DEFAULT_SOCKS)) I2pConf.DEFAULT_SOCKS else DpiEngine.freePort()
        val reseed = s.i2pReseedFile.takeIf { it.isNotBlank() && (it.startsWith("https://") || File(it).isFile) }
        update { it.copy(i2p = I2pSnapshot(RunPhase.STARTING, 2, http, socks, "I2P запускается…", actual = plan.actual)) }
        i2pRestarting = false
        try {
            I2pService.start(app) {
                putExtra(I2pContract.X_HTTP, http); putExtra(I2pContract.X_SOCKS, socks); putExtra(I2pContract.X_ACTUAL, plan.actual.name)
                if (plan.actual == AnonTransport.DPI) putExtra(I2pContract.X_RESEED_PROXY, plan.proxy?.socksUrl)
                if (plan.actual == AnonTransport.PROXY) putExtra(I2pContract.X_NTCP_PROXY, plan.proxy?.socksUrl)
                if (reseed != null) putExtra(I2pContract.X_RESEED_FILE, reseed)
            }
        } catch (e: Exception) {
            update { it.copy(i2p = I2pSnapshot(RunPhase.FAILED, message = "I2P не запущен", error = "Не удалось запустить I2P: " + (e.message ?: e.javaClass.simpleName))) }
        }
    }

    private fun onI2pStatus(snap: I2pSnapshot) {
        // Во время перезапуска (смена способа) промежуточное «выключен» не показываем.
        if (i2pRestarting && snap.phase == RunPhase.OFF) return
        val canFallback = AnonTransportPlanner.fallback(i2pRequested, i2pActual, AnonDpi.available(app)) != null
        val stuck = snap.failure == AnonFailure.RESEED && snap.bypassHelps &&
            (snap.phase == RunPhase.FAILED || (snap.phase == RunPhase.BOOTSTRAPPING && System.currentTimeMillis() - i2pStartedAt > 60_000))
        if (!i2pFallbackTried && canFallback && stuck) {
            i2pFallbackTried = true
            i2pRestarting = true
            update { it.copy(i2p = snap.copy(phase = RunPhase.BOOTSTRAPPING, message = "Список узлов недоступен напрямую — включаю обход DPI…", error = null)) }
            if (snap.phase != RunPhase.FAILED) I2pService.stop(app)
            main.postDelayed({ if (i2pRestarting) launchI2p(AnonTransport.DPI) }, 2_000)
            return
        }
        update { it.copy(i2p = snap) }
        if ((snap.phase == RunPhase.OFF || snap.phase == RunPhase.FAILED) && !i2pRestarting) AnonDpi.release("i2p")
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

    /** Через Tor запрашивает check.torproject.org: подтверждает, что трафик действительно идёт через Tor. Блокирующий вызов. */
    fun checkTor(): String {
        val up = torUpstream() as? Upstream.Socks5 ?: return "Tor не запущен."
        return try {
            Socks5.connect(up.host, up.port, "check.torproject.org", 80, 40_000).use { s ->
                s.soTimeout = 40_000
                s.getOutputStream().apply { write("GET /api/ip HTTP/1.0\r\nHost: check.torproject.org\r\nUser-Agent: curl/8\r\n\r\n".toByteArray()); flush() }
                val body = s.getInputStream().readBytes().toString(Charsets.UTF_8).substringAfter("\r\n\r\n")
                val isTor = Regex("\"IsTor\"\\s*:\\s*true").containsMatchIn(body)
                val ip = Regex("\"IP\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                if (isTor) "Tor работает: выходной узел ${ip ?: "?"}" else "Соединение есть, но check.torproject.org не видит Tor (IP ${ip ?: "?"})."
            }
        } catch (e: Exception) { "Проверка не удалась: " + (e.message ?: e.javaClass.simpleName) }
    }

    /** Запрашивает eepsite через HTTP-прокси I2P. Первый запрос может идти минуту: ищется leaseSet. Блокирующий вызов. */
    fun checkI2p(): String {
        val up = i2pUpstream() as? Upstream.Http ?: return "Прокси I2P не задан."
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(up.host, up.port), 5_000)
                s.soTimeout = 90_000
                s.getOutputStream().apply { write("GET http://i2p-projekt.i2p/ HTTP/1.1\r\nHost: i2p-projekt.i2p\r\nConnection: close\r\n\r\n".toByteArray()); flush() }
                val first = s.getInputStream().bufferedReader().readLine().orEmpty()
                if (first.startsWith("HTTP/") && (" 200 " in first || " 301 " in first || " 302 " in first)) "I2P работает: i2p-projekt.i2p ответил ($first)."
                else if (first.startsWith("HTTP/")) "Прокси отвечает, но сайт не открылся: $first. Подождите пару минут: туннели ещё строятся."
                else "Нет корректного ответа от прокси I2P."
            }
        } catch (e: Exception) { "Проверка не удалась: " + (e.message ?: e.javaClass.simpleName) }
    }

    // ───── WebView ─────

    /**
     * Готовит всё нужное, чтобы открыть [url]: поднимает локальный прокси, при необходимости Tor или I2P,
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
                    AnonRouting.Route.I2P -> waitForI2p(listener)
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

    private fun hint(error: String?, bypass: Boolean): String =
        (error ?: "неизвестная ошибка") + if (bypass) "\n\nОткройте «Ещё → Onion и I2P» и выберите способ «Обход DPI» или «SOCKS5»." else ""

    private fun waitForTor(listener: Listener) {
        val s = settings
        if (!s.embeddedTor) {
            post { listener.status("Проверяю Tor-прокси ${s.torHost}:${s.torPort}…") }
            if (!probeTorExternal()) throw IllegalStateException(
                "Tor-прокси ${s.torHost}:${s.torPort} не отвечает.\n\nЗапустите Orbot (или Tor Browser для порта 9150) либо переключитесь на встроенный Tor в разделе «Onion и I2P».")
            return
        }
        if (!_state.value.tor.phase.active) startTor()
        val started = System.currentTimeMillis()
        while (true) {
            val t = _state.value.tor
            if (t.ready && torUpstream() != null) break
            if (t.phase == RunPhase.FAILED) throw IllegalStateException(hint(t.error ?: t.message, t.bypassHelps))
            val elapsed = ((System.currentTimeMillis() - started) / 1000).toInt()
            if (elapsed > 300) throw IllegalStateException("Tor не построил цепочку за 5 минут. Возможно, сеть Tor заблокирована — откройте «Onion и I2P» и выберите «Обход DPI» или добавьте мосты.")
            post { listener.status(t.message.ifBlank { "Запуск Tor…" } + " · $elapsed с") }
            Thread.sleep(700)
        }
    }

    private fun waitForI2p(listener: Listener) {
        val s = settings
        if (!s.embeddedI2p) {
            post { listener.status("Проверяю I2P-роутер ${s.i2pHost}:${s.i2pPort}…") }
            if (!probeI2p()) throw IllegalStateException(
                "I2P-роутер не отвечает на ${s.i2pHost}:${s.i2pPort}.\n\nЗапустите приложение I2P или i2pd и включите HTTP-прокси, либо переключитесь на встроенный I2P в разделе «Onion и I2P».")
            return
        }
        if (!_state.value.i2p.phase.active && !i2pRestarting) startI2p()
        val started = System.currentTimeMillis()
        while (true) {
            val i = _state.value.i2p
            if (i.ready) break
            if (i.phase == RunPhase.FAILED && !i2pRestarting) throw IllegalStateException(hint(i.error ?: i.message, i.bypassHelps))
            val elapsed = ((System.currentTimeMillis() - started) / 1000).toInt()
            if (elapsed > 480) throw IllegalStateException("I2P не построил туннели за 8 минут. Попробуйте другую сеть или откройте «Onion и I2P» и задайте способ выхода.")
            val tunnels = if (i.inbound + i.outbound > 0) " · туннели ${i.inbound}/${i.outbound}" else ""
            post { listener.status("${i.message.ifBlank { "Запуск I2P…" }}$tunnels · ${i.progress}% · $elapsed с" + if (elapsed < 120) "\nПервый запуск I2P может занять несколько минут." else "") }
            Thread.sleep(700)
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

    /** Снимает прокси с WebView (при закрытии браузера). Tor и I2P остаются работать до остановки вручную. */
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
