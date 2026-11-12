package pro.sitescope.app.site

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import pro.sitescope.app.App
import java.util.concurrent.TimeUnit

/** Наблюдение за сайтами: дважды в сутки проверяет доступность и срок сертификата. */
object SiteMonitor {
    private const val PREFS = "site_monitor"
    private const val WORK = "site-monitor"

    fun targets(context: Context): Set<String> =
        HashSet(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("targets", emptySet()) ?: emptySet())

    fun toggle(context: Context, target: String): Set<String> {
        val set = HashSet(targets(context))
        if (!set.remove(target)) set.add(target)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet("targets", set).apply()
        schedule(context, set.isNotEmpty())
        return set
    }

    fun watchesChanges(context: Context, target: String): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet("watch_changes", emptySet())?.contains(target) == true

    fun setWatchesChanges(context: Context, target: String, watch: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val set = HashSet(prefs.getStringSet("watch_changes", emptySet()) ?: emptySet())
        if (watch) set.add(target) else set.remove(target)
        prefs.edit().putStringSet("watch_changes", set).apply()
    }

    private fun schedule(context: Context, enabled: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (!enabled) { manager.cancelUniqueWork(WORK); return }
        val request = PeriodicWorkRequestBuilder<SiteMonitorWorker>(12, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        manager.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun reschedule(context: Context) = schedule(context, targets(context).isNotEmpty())

    fun notify(context: Context, id: Int, title: String, text: String, tab: String) {
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent().setClassName(context, "pro.sitescope.app.ui.ShellActivity").putExtra("tab", tab)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, App.CHANNEL_ALERTS)
            .setSmallIcon(android.R.drawable.stat_notify_error).setContentTitle(title).setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(pending).setAutoCancel(true).build()
        context.getSystemService(NotificationManager::class.java)?.notify(id, notification)
    }
}

class SiteMonitorWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        val prefs = context.getSharedPreferences("site_monitor", Context.MODE_PRIVATE)
        for (target in SiteMonitor.targets(context)) {
            val data = SiteExtras.collect(target, checkLinks = false)
            val host = try { java.net.URL(SiteExtras.normalize(target)).host } catch (e: Exception) { target }
            val code = data.chain.lastOrNull()?.code ?: 0
            val down = !data.reachable || code >= 500
            val wasDown = prefs.getBoolean("down:$target", false)
            if (down != wasDown) {
                prefs.edit().putBoolean("down:$target", down).apply()
                if (down) SiteMonitor.notify(context, target.hashCode(), "$host не открывается", "Сайт не ответил при плановой проверке.", "site")
                else SiteMonitor.notify(context, target.hashCode(), "$host снова работает", "Сайт открылся при плановой проверке.", "site")
            }
            val days = data.tls?.takeIf { it.valid }?.daysLeft
            if (days != null && days <= 14) {
                val key = "cert:$target"
                val today = System.currentTimeMillis() / 86_400_000L
                if (prefs.getLong(key, 0) != today) {
                    prefs.edit().putLong(key, today).apply()
                    SiteMonitor.notify(context, target.hashCode() + 1, "Сертификат $host скоро истечёт", "Осталось дней: $days. Продлите сертификат, чтобы сайт не пометили небезопасным.", "site")
                }
            }
            if (!down && SiteMonitor.watchesChanges(context, target)) checkContentChange(context, prefs, target, host, data.html)
        }
        return Result.success()
    }

    /** Хэш видимого текста страницы (без тегов и лишних пробелов) — мало ложных срабатываний от дат и рекламы. */
    private fun contentHash(html: String): String {
        val text = html.replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun checkContentChange(context: Context, prefs: android.content.SharedPreferences, target: String, host: String, html: String) {
        if (html.isBlank()) return
        val hash = contentHash(html)
        val key = "hash:$target"
        val previous = prefs.getString(key, null)
        prefs.edit().putString(key, hash).apply()
        if (previous != null && previous != hash) {
            SiteMonitor.notify(context, target.hashCode() + 2, "$host изменился", "Содержимое страницы поменялось с прошлой проверки.", "site")
        }
    }
}
