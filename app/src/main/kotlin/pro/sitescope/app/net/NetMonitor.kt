package pro.sitescope.app.net

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import pro.sitescope.app.ConnectionProfileStore
import pro.sitescope.app.EndpointProbe
import pro.sitescope.app.site.SiteMonitor
import java.util.concurrent.TimeUnit

/**
 * Наблюдение за сохранёнными подключениями: каждые 15 минут проверяет, что они отвечают,
 * и сообщает об изменении состояния. Минимальный интервал WorkManager — 15 минут, поэтому
 * это не замена живому пингу, а фоновый сторож «упало/ожило» для серверов и устройств.
 */
object NetMonitor {
    private const val WORK = "net-monitor"
    private const val PREFS = "net_monitor"

    fun reschedule(context: Context) {
        val manager = WorkManager.getInstance(context)
        if (ConnectionProfileStore.monitored(context).isEmpty()) { manager.cancelUniqueWork(WORK); return }
        val request = PeriodicWorkRequestBuilder<NetMonitorWorker>(15, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    internal fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

class NetMonitorWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        val prefs = NetMonitor.prefs(context)
        for (profile in ConnectionProfileStore.monitored(context)) {
            val probe = try { EndpointProbe.probe(profile.endpoint(), 4000) } catch (e: Exception) { null }
            val up = probe?.reachable == true
            val key = "up:${profile.id}"
            val wasUp = prefs.getBoolean(key, true)
            try { ConnectionProfileStore.updateHealth(context, profile.id, probe ?: EndpointProbe.Result()) } catch (e: Exception) { }
            if (up != wasUp) {
                prefs.edit().putBoolean(key, up).apply()
                val title = profile.name.ifEmpty { profile.host }
                if (!up) SiteMonitor.notify(context, ("net:" + profile.id).hashCode(), "$title не отвечает",
                    "Подключение ${profile.protocol} · ${profile.host}:${profile.port} перестало отвечать.", "more")
                else SiteMonitor.notify(context, ("net:" + profile.id).hashCode(), "$title снова в сети",
                    "Подключение ${profile.protocol} · ${profile.host}:${profile.port} отвечает.", "more")
            }
        }
        return Result.success()
    }
}
