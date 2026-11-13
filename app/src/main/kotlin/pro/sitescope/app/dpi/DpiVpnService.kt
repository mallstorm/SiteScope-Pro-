package pro.sitescope.app.dpi

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.v2ray.ang.service.TProxyService

/** Режимы VPN: byedpi + tun2socks (HEV). Трафик выбранных приложений (или всех) идёт в TUN → SOCKS5 byedpi. */
class DpiVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var bridge: TProxyService? = null
    @Volatile private var starting = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == DpiService.ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        startForeground(DpiService.NOTIFICATION_ID, dpiNotification(this, "Запуск VPN…"))
        if (starting || tun != null) return START_STICKY
        starting = true
        Thread({
            try { start() } catch (e: Exception) {
                DpiRuntime.stop()
                DpiRuntimeFail.set(e.message ?: e.javaClass.simpleName)
                shutdown()
            } finally { starting = false }
        }, "dpi-vpn-start").start()
        return START_STICKY
    }

    private fun start() {
        val cfg = DpiStore.load(this)
        if (cfg.mode == "vpn_only" && cfg.apps.none { it != packageName }) throw IllegalStateException("Режим «только выбранные»: отметьте приложения на вкладке «Приложения»")
        if (!DpiRuntime.start(this, cfg, "vpn")) throw IllegalStateException(DpiRuntime.state.value.message)
        val port = DpiRuntime.port
        val builder = Builder().setSession("SiteScope Обход DPI").setMtu(1500)
            .addAddress("10.10.14.1", 30).addRoute("0.0.0.0", 0).addDnsServer(cfg.dns.ifBlank { "1.1.1.1" })
        if (cfg.ipv6) builder.addAddress("fc00::10:10:14:1", 126).addRoute("::", 0).addDnsServer("2606:4700:4700::1111")
        if (android.os.Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        when (cfg.mode) {
            "vpn_only" -> cfg.apps.filter { it != packageName }.forEach { try { builder.addAllowedApplication(it) } catch (_: Exception) { } }
            "vpn_except" -> { try { builder.addDisallowedApplication(packageName) } catch (_: Exception) { }; cfg.apps.forEach { try { builder.addDisallowedApplication(it) } catch (_: Exception) { } } }
            else -> try { builder.addDisallowedApplication(packageName) } catch (_: Exception) { }
        }
        val fd = builder.establish() ?: throw IllegalStateException("Android не создал VPN-интерфейс (нет разрешения?)")
        tun = fd
        val b = TProxyService(this, fd, port)
        b.startTun2Socks()
        bridge = b
        getSystemService(Context.NOTIFICATION_SERVICE).let { (it as android.app.NotificationManager).notify(DpiService.NOTIFICATION_ID, dpiNotification(this, "VPN активен · " + modeTitle(cfg))) }
    }

    private fun modeTitle(cfg: DpiConfig) = when (cfg.mode) {
        "vpn_only" -> "${cfg.apps.size} прил."
        "vpn_except" -> "все, кроме ${cfg.apps.size}"
        else -> "все приложения"
    }

    private fun shutdown() {
        try { bridge?.stopTun2Socks() } catch (_: Throwable) { }
        bridge = null
        try { tun?.close() } catch (_: Exception) { }
        tun = null
        DpiRuntime.stopIfOwner("vpn")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() { shutdown() }
    override fun onDestroy() { shutdown(); super.onDestroy() }
}

/** Последняя причина сбоя запуска VPN — для показа на экране. */
object DpiRuntimeFail {
    @Volatile var last: String = ""
    fun set(text: String) { last = text }
}
