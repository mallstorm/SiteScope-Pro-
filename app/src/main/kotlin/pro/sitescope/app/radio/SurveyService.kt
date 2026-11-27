package pro.sitescope.app.radio

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import pro.sitescope.app.ui.ToolActivity
import java.util.Calendar

class SurveyService:Service() {
    companion object {
        val active=MutableStateFlow<Long?>(null)
        val last=MutableStateFlow<RadioFrame?>(null)
        val failure=MutableStateFlow("")
        const val STOP="pro.sitescope.app.radio.STOP"
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var job:Job?=null
    private var session:Long?=null
    private var currentSpeedNotice:String?=null
    private var previousConditions=emptySet<String>()
    private val mgr get()=getSystemService(NotificationManager::class.java)
    private val prefs get()=getSharedPreferences("radio-options",0)
    private fun page():PendingIntent=PendingIntent.getActivity(this,901,Intent(this,ToolActivity::class.java).putExtra("screen","radio"),PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    override fun onBind(intent:Intent?):IBinder?=null
    override fun onCreate() {
        super.onCreate()
        mgr.createNotificationChannel(NotificationChannel("survey-session","Запись радиоизмерений",NotificationManager.IMPORTANCE_LOW))
        mgr.createNotificationChannel(NotificationChannel("survey-alert","События связи",NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun notice(text:String)=Notification.Builder(this,"survey-session")
        .setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle("SiteScope · запись маршрута")
        .setContentText(text).setContentIntent(page()).setOnlyAlertOnce(true).setOngoing(true)
        .addAction(Notification.Action.Builder(null,"Остановить",PendingIntent.getService(this,902,Intent(this,SurveyService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE)).build()).build()

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP){stopSelf();return START_NOT_STICKY}
        if(job!=null) return START_NOT_STICKY
        if(!allowed(Manifest.permission.ACCESS_FINE_LOCATION)){failure.value="Для записи маршрута нужна точная геопозиция";stopSelf();return START_NOT_STICKY}
        try { startForeground(8791,notice("Начало измерения")) }
        catch(e:RuntimeException) { failure.value="Не удалось начать запись: ${e.localizedMessage}";stopSelf();return START_NOT_STICKY }
        job=scope.launch {
            try {
                val store=SurveyStore.get(this@SurveyService)
                // Allocate before the first suspension so an immediate Stop can seal the session.
                val id=store.begin(System.currentTimeMillis())
                session=id;active.value=id;failure.value=""
                while(isActive) {
                    val fix=async {currentFix(this@SurveyService)}
                    val frame=readRadio(this@SurveyService)
                    val location=fix.await()
                    withContext(Dispatchers.IO){store.append(id,frame,location)}
                    last.value=frame
                    RadioWidget.publish(this@SurveyService,frame)
                    val summary=frame.cells.firstOrNull {it.registered && it.dbm!=null}?.let{"${it.sim} ${it.rat} ${it.dbm} dBm"} ?: frame.wifi?.let{"Wi-Fi ${it.dbm} dBm"} ?: "Ожидание данных сети"
                    mgr.notify(8791,notice(summary))
                    alert(frame)
                    delay(prefs.getInt("interval",15).coerceIn(5,300)*1000L)
                }
            } catch(e:CancellationException){throw e}
            catch(e:Exception){failure.value="Запись завершена: ${e.localizedMessage}";stopSelf()}
        }
        return START_NOT_STICKY
    }
    private fun alert(frame:RadioFrame) {
        val current=buildSet {
            if(prefs.getBoolean("weak",false)) frame.cells.filter {it.registered && it.dbm!=null && it.dbm < prefs.getInt("threshold",-110) &&
                it.measured>0 && frame.time-it.measured in 0..60_000 }.forEach {add("${it.sim}: слабый сигнал")}
            if(prefs.getBoolean("internet",false) && !frame.internet) add("Нет подтверждённого доступа в интернет")
            frame.sims.forEach { sim->
                if(prefs.getBoolean("roaming",false) && sim.roaming) add("${sim.name}: роуминг")
                if(prefs.getBoolean("service",false) && sim.service==ServiceState.STATE_OUT_OF_SERVICE) add("${sim.name}: нет обслуживания")
                if(prefs.getBoolean("data",false) && sim.data==TelephonyManager.DATA_DISCONNECTED) add("${sim.name}: передача данных отключена")
            }
        }
        if (prefs.getBoolean("slow",false)) {
            val lastTest=pro.sitescope.app.net.SpeedStore.loadHistory(this).firstOrNull()
            if(lastTest!=null && lastTest.time >= (frame.time-60000) && lastTest.down>=0 && lastTest.down<prefs.getInt("speedMin",5))
                currentSpeedNotice = "Скорость загрузки ниже порога"
            else currentSpeedNotice = null
        } else currentSpeedNotice = null
        val conditions=current + listOfNotNull(currentSpeedNotice)
        val hour=Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val start=prefs.getInt("quietStart",22); val end=prefs.getInt("quietEnd",8)
        val quiet=prefs.getBoolean("quiet",false) && if(start<=end) hour in start until end else hour>=start || hour<end
        if(quiet){previousConditions=emptySet();return}
        val fresh=conditions-previousConditions;previousConditions=conditions
        if(fresh.isNotEmpty()) mgr.notify(8792,Notification.Builder(this,"survey-alert").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("SiteScope · событие связи").setContentText(fresh.joinToString(" · ")).setContentIntent(page()).setAutoCancel(true).build())
    }
    override fun onDestroy() {
        scope.cancel()
        session?.let {SurveyStore.get(this).finish(it)}
        active.value=null;last.value=null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

