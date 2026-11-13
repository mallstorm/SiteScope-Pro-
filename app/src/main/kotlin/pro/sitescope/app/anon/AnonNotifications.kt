package pro.sitescope.app.anon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import pro.sitescope.app.R
import pro.sitescope.app.ui.ToolActivity

internal const val ANON_CHANNEL = "sitescope_anon"

/** Уведомление фонового Tor/I2P с кнопкой остановки. Вызывается и из процесса :i2pcore. */
internal fun anonNotification(context: Context, title: String, text: String, progress: Int, stopService: Class<*>, stopAction: String, requestCode: Int): Notification {
    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (nm.getNotificationChannel(ANON_CHANNEL) == null)
        nm.createNotificationChannel(NotificationChannel(ANON_CHANNEL, "Tor и I2P", NotificationManager.IMPORTANCE_LOW))
    val open = PendingIntent.getActivity(context, requestCode, Intent(context, ToolActivity::class.java).putExtra("screen", "anon"), PendingIntent.FLAG_IMMUTABLE)
    val stop = PendingIntent.getService(context, requestCode + 10, Intent(context, stopService).setAction(stopAction), PendingIntent.FLAG_IMMUTABLE)
    return Notification.Builder(context, ANON_CHANNEL).setSmallIcon(R.drawable.ic_launcher).setContentTitle(title).setContentText(text)
        .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setProgress(100, progress.coerceIn(0, 100), progress <= 0)
        .addAction(@Suppress("DEPRECATION") Notification.Action.Builder(0, "Остановить", stop).build()).build()
}
