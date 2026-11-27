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
        val running = MutableStateFlow(false)
        val closing = MutableStateFlow(false)
        val active=MutableStateFlow<Long?>(null)
        val position = MutableStateFlow<android.location.Location?>(null)
        val last=MutableStateFlow<RadioFrame?>(null)
        val failure=MutableStateFlow("")
        const val STOP="pro.sitescope.app.radio.STOP"
    }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private var job:Job?=null
    private var session:Long?=null
    private var recordRequested = false
    private val cancelSpeed = java.util.concurrent.atomic.AtomicBoolean(false)
    private var nextSpeedAt = 0L
    private var nextHttpAt = 0L
    private var previousCells = emptyMap<String, Long?>()
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
        .setSmallIcon(android.R.drawable.ic_menu_compass).setContentTitle("SiteScope · радиомонитор")
        .setContentText(text).setContentIntent(page()).setOnlyAlertOnce(true).setOngoing(true)
        .addAction(Notification.Action.Builder(null,"Остановить",PendingIntent.getService(this,902,Intent(this,SurveyService::class.java).setAction(STOP),PendingIntent.FLAG_IMMUTABLE)).build()).build()

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==STOP){stopSelf();return START_NOT_STICKY}
        if(job!=null) {
            if (intent?.getBooleanExtra("record", true) != false) recordRequested = true
            return START_NOT_STICKY
        }
        if (closing.value) {
            failure.value = "Завершается предыдущая запись. Повторите запуск через несколько секунд."
            stopSelf()
            return START_NOT_STICKY
        }
        if(!allowed(Manifest.permission.ACCESS_FINE_LOCATION)){failure.value="Для записи маршрута нужна точная геопозиция";stopSelf();return START_NOT_STICKY}
        try { startForeground(8791,notice("Начало измерения")) }
        catch(e:RuntimeException) { failure.value="Не удалось начать запись: ${e.localizedMessage}";stopSelf();return START_NOT_STICKY }
        running.value = true
        recordRequested = intent?.getBooleanExtra("record", true) != false
        job=scope.launch {
            try {
                val store=SurveyStore.get(this@SurveyService)
                failure.value = ""
                while(isActive) {
                    if (recordRequested && session == null) {
                        // Assign inside IO so cancellation still leaves an ID for finally to seal.
                        withContext(Dispatchers.IO) { session = store.begin(System.currentTimeMillis()) }
                        active.value = session
                        recordRequested = false
                    }
                    val fix=async { if (session != null) currentFix(this@SurveyService) else null }
                    val frame=readRadio(this@SurveyService)
                    val location=fix.await()
                    position.value = location
                    session?.let { id -> withContext(Dispatchers.IO) { store.append(id, frame, location) } }
                    periodicSpeed()
                    periodicHttp()
                    val cells = frame.cells.filter { it.registered }.associate { it.sim to it.cid }
                    if (previousCells.isNotEmpty() && cells != previousCells) {
                        withContext(Dispatchers.IO) { store.addEvent("Смена обслуживающей соты · " + cells.entries.joinToString { "${it.key} CID ${it.value ?: "—"}" }) }
                    }
                    previousCells = cells
                    last.value=frame
                    RadioWidget.publish(this@SurveyService,frame)
                    val summary=frame.cells.firstOrNull {it.registered && it.dbm!=null}?.let{"${it.sim} ${it.rat} ${it.dbm} dBm"} ?: frame.wifi?.let{"Wi-Fi ${it.dbm} dBm"} ?: "Ожидание данных сети"
                    mgr.notify(8791,notice(summary))
                    alert(frame)
                    delay(prefs.getInt("interval",15).coerceIn(5,300)*1000L)
                }
            } catch(e:CancellationException){throw e}
            catch(e:Exception){failure.value="Запись завершена: ${e.localizedMessage}";stopSelf()}
            finally {
                withContext(NonCancellable + Dispatchers.IO) {
                    session?.let { id ->
                        runCatching { SurveyStore.get(this@SurveyService).finish(id) }
                            .onFailure { failure.value = "Не удалось завершить сессию: ${it.localizedMessage}" }
                        if (active.value == id) active.value = null
                    }
                }
            }
        }
        job?.invokeOnCompletion { closing.value = false }
        return START_NOT_STICKY
    }
    private suspend fun alert(frame:RadioFrame) {
        val current=buildSet {
            if(prefs.getBoolean("weak",false)) frame.cells.filter {it.registered && it.dbm!=null && it.dbm < prefs.getInt("threshold",-110) &&
                it.measured>0 && frame.time-it.measured in 0..60_000 }.forEach {add("${it.sim}: слабый сигнал")}
            if (prefs.getBoolean("wifiWeak", false) && frame.wifi != null && frame.wifi.dbm < prefs.getInt("wifiThreshold", -75))
                add("Wi-Fi: слабый сигнал")
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
        val fresh = conditions - previousConditions
        val recovered = previousConditions - conditions
        previousConditions = conditions
        withContext(Dispatchers.IO) {
            for (event in fresh) SurveyStore.get(this@SurveyService).addEvent(event)
            for (event in recovered) SurveyStore.get(this@SurveyService).addEvent("Завершилось · $event")
        }
        if(quiet) return
        if(fresh.isNotEmpty()) mgr.notify(8792,Notification.Builder(this,"survey-alert").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("SiteScope · событие связи").setContentText(fresh.joinToString(" · ")).setContentIntent(page()).setAutoCancel(true).build())
    }
    private suspend fun periodicHttp() {
        if (!prefs.getBoolean("autoHttp", false) || System.currentTimeMillis() < nextHttpAt) return
        nextHttpAt = System.currentTimeMillis() + prefs.getInt("httpInterval", 60).coerceIn(15, 3600) * 1000L
        val address = prefs.getString("testUrl", "https://www.google.com/generate_204").orEmpty()
        val url = runCatching { java.net.URL(address) }.getOrNull() ?: return
        if (url.protocol !in listOf("http", "https")) return
        val ping = withContext(Dispatchers.IO) {
            pro.sitescope.app.net.SpeedEngine.pingOnce(pro.sitescope.app.net.SpeedEngine.Server("monitor", "HTTP", "", address, ""))
        }
        getSharedPreferences("radio-widget", 0).edit()
            .putLong("latency-time", System.currentTimeMillis()).putString("latency", ping.takeIf { it >= 0 }?.toInt()?.toString()).apply()
        if (ping < 0) withContext(Dispatchers.IO) { SurveyStore.get(this@SurveyService).addEvent("HTTP-узел не ответил") }
    }
    private suspend fun periodicSpeed() {
        if (!prefs.getBoolean("autoSpeed", false) || System.currentTimeMillis() < nextSpeedAt) return
        nextSpeedAt = System.currentTimeMillis() + prefs.getInt("speedInterval", 15).coerceIn(5, 1440) * 60000L
        val connectivity = getSystemService(android.net.ConnectivityManager::class.java)
        if (connectivity.isActiveNetworkMetered && !prefs.getBoolean("meteredSpeed", false)) return
        val settings = pro.sitescope.app.net.SpeedStore.loadSettings(this).copy(
            seconds = 3, threads = 1, pings = 3, capMb = prefs.getInt("speedCap", 2).coerceIn(1, 20),
            doDown = true, doUp = false, doPing = true, loaded = false
        )
        val listener = object : pro.sitescope.app.net.SpeedEngine.Listener {
            override fun phase(p: pro.sitescope.app.net.SpeedEngine.Phase) {}
            override fun ping(ms: Double, done: Int, total: Int) {}
            override fun speed(p: pro.sitescope.app.net.SpeedEngine.Phase, mbps: Double, progress: Float) {}
        }
        val result = withContext(Dispatchers.IO) {
            pro.sitescope.app.net.SpeedEngine.run(this@SurveyService, settings, cancelSpeed, listener)
        }
        if (!cancelSpeed.get()) {
            if (result.down >= 0 || result.up >= 0 || result.ping >= 0)
                pro.sitescope.app.net.SpeedStore.appendHistory(this, result)
        }
    }
    override fun onDestroy() {
        cancelSpeed.set(true)
        if (job?.isCompleted == false) closing.value = true
        scope.cancel()
        active.value=null;last.value=null;position.value=null;running.value=false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

