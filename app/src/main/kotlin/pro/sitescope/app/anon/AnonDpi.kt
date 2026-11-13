package pro.sitescope.app.anon

import android.content.Context
import pro.sitescope.app.dpi.DpiEngine
import pro.sitescope.app.dpi.DpiRuntime
import pro.sitescope.app.dpi.DpiStore

/** Общий byedpi для Tor и I2P: поднимается по требованию и гасится, когда им никто не пользуется (сервис пользователя не трогаем). */
object AnonDpi {
    private const val WHO = "anon"
    private val holders = mutableSetOf<String>()

    fun available(context: Context): Boolean = DpiRuntime.running || DpiEngine.available(context)

    /** Адрес SOCKS5 byedpi или null, если движок недоступен. */
    @Synchronized
    fun acquire(context: Context, holder: String): ProxySpec? {
        if (DpiRuntime.running) { holders += holder; return ProxySpec("127.0.0.1", DpiRuntime.port) }
        if (!DpiEngine.available(context)) return null
        val cfg = DpiStore.load(context).copy(mode = "chain")
        if (!DpiRuntime.start(context.applicationContext, cfg, WHO)) return null
        holders += holder
        return ProxySpec("127.0.0.1", DpiRuntime.port)
    }

    @Synchronized
    fun release(holder: String) {
        if (holders.remove(holder) && holders.isEmpty()) DpiRuntime.stopIfOwner(WHO)
    }
}
