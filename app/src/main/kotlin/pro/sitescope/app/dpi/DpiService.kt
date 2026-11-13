package pro.sitescope.app.dpi

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import pro.sitescope.app.R
import pro.sitescope.app.ui.ToolActivity

internal fun dpiNotification(context: Context, text: String): Notification {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (nm.getNotificationChannel("sitescope_dpi") == null)
        nm.createNotificationChannel(NotificationChannel("sitescope_dpi", "Обход DPI", NotificationManager.IMPORTANCE_LOW))
    val open = PendingIntent.getActivity(context, 0, Intent(context, ToolActivity::class.java).putExtra("screen", "dpi"), PendingIntent.FLAG_IMMUTABLE)
    val stop = PendingIntent.getService(context, 1, Intent(context, DpiService::class.java).setAction(DpiService.ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
    return Notification.Builder(context, "sitescope_dpi").setSmallIcon(R.drawable.ic_launcher).setContentTitle("Обход DPI").setContentText(text)
        .setContentIntent(open).setOngoing(true).addAction(@Suppress("DEPRECATION") Notification.Action.Builder(0, "Остановить", stop).build()).build()
}

/** Режим «Прокси»: byedpi работает постоянно как SOCKS5 127.0.0.1:порт, VPN не занимается. */
class DpiService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            DpiRuntime.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, dpiNotification(this, "Запуск…"))
        val cfg = DpiStore.load(this).let { if (it.isVpn) it.copy(mode = "proxy") else it }
        Thread({
            if (DpiRuntime.start(this, cfg, "service")) {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIFICATION_ID, dpiNotification(this, "SOCKS5 127.0.0.1:${DpiRuntime.port}"))
            } else { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }, "dpi-start").start()
        return START_STICKY
    }

    override fun onDestroy() { DpiRuntime.stopIfOwner("service"); super.onDestroy() }

    companion object {
        const val ACTION_START = "pro.sitescope.app.dpi.START"
        const val ACTION_STOP = "pro.sitescope.app.dpi.STOP"
        const val NOTIFICATION_ID = 32050
    }
}
