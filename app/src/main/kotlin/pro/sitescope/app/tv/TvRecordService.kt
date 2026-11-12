package pro.sitescope.app.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import pro.sitescope.app.ui.ToolActivity

/** Держит запись на переднем плане, чтобы Android не остановил её, когда приложение свёрнуто. */
class TvRecordService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            TvRecorder.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        createChannel()
        startForeground(NOTIFICATION_ID, notification())
        Thread({
            // Следим за TvRecorder.recording и останавливаем сервис, когда запись сама завершилась.
            while (TvRecorder.recording) Thread.sleep(1500)
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }, "SiteScope-TvRecordWatch").start()
        return START_STICKY
    }

    private fun notification(): Notification {
        val open = Intent(this, ToolActivity::class.java).putExtra("screen", "tvlan")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentIntent = PendingIntent.getActivity(this, 21, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 22, Intent(this, TvRecordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Запись канала: ${TvRecorder.channelName}")
            .setContentText("Идёт запись в файл — остановить можно в любой момент")
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", stop)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Запись ТВ", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Идёт запись канала в файл"
        })
    }

    companion object {
        const val ACTION_STOP = "pro.sitescope.app.tv.RECORD_STOP"
        private const val CHANNEL = "sitescope_tv_record"
        private const val NOTIFICATION_ID = 7601

        fun start(context: Context) { context.startForegroundService(Intent(context, TvRecordService::class.java)) }
        fun stop(context: Context) { context.startService(Intent(context, TvRecordService::class.java).setAction(ACTION_STOP)) }
    }
}
