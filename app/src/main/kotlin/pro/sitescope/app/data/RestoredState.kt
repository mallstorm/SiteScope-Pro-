package pro.sitescope.app.data

import android.content.Context
import android.util.Log
import androidx.work.*
import pro.sitescope.app.site.SiteMonitor
import pro.sitescope.app.tv.TvCheckWorker
import pro.sitescope.app.tv.TvStore
import pro.sitescope.app.tv.TvViewModel
import pro.sitescope.app.ui.ThemePrefs
import java.util.concurrent.TimeUnit

/** Refresh process caches and reconcile persisted monitoring choices after import/startup. */
object RestoredState {
    @JvmStatic fun reload(context: Context) {
        ThemePrefs.load(context)
        TvViewModel.externalChange++
        try {
            SiteMonitor.reschedule(context)
            TvStore.load(context)
            val manager = WorkManager.getInstance(context)
            if (TvStore.autoCheck) manager.enqueueUniquePeriodicWork("tv-check", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<TvCheckWorker>(24, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build())
            else manager.cancelUniqueWork("tv-check")
        } catch (e: IllegalStateException) {
            // WorkManager may not be initialized in isolated tests; retry on the next app startup.
            Log.w("SiteScope", "Monitoring schedules will be reconciled at startup", e)
        }
    }
}
