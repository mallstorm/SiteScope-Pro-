package pro.sitescope.app

import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import androidx.activity.ComponentActivity

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(LegacyInsets)
        pro.sitescope.app.ui.ThemePrefs.load(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(NotificationChannel(CHANNEL_ALERTS, "Оповещения SiteScope", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Результаты проверок сайтов и каналов"
        })
    }

    companion object { const val CHANNEL_ALERTS = "sitescope_alerts" }
}

/**
 * На Android 15+ окно рисуется под системными панелями. Прежние экраны (на обычных View) об этом не знают,
 * поэтому им автоматически добавляются отступы.
 */
private object LegacyInsets : Application.ActivityLifecycleCallbacks {
    override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (Build.VERSION.SDK_INT < 35 || activity is ComponentActivity) return
        val content = activity.findViewById<View>(android.R.id.content) ?: return
        content.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        content.requestApplyInsets()
    }
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {}
}
