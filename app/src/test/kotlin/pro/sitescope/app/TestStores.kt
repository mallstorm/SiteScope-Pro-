package pro.sitescope.app

import pro.sitescope.app.tv.TvStore

/** Robolectric resets Android services, but application singleton fields survive between methods. */
object TestStores {
    fun reset() {
        AppDatabase::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
            (get(null) as? AppDatabase)?.close()
            set(null, null)
        }
        TvStore.channels.clear(); TvStore.sources.clear(); TvStore.epgUrls.clear()
        TvStore.javaClass.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(TvStore, false)
        TvStore.udpxyBase = ""; TvStore.threads = 8; TvStore.timeoutMs = 8000
        TvStore.autoCheck = false; TvStore.lastCheckAt = 0
    }
}
