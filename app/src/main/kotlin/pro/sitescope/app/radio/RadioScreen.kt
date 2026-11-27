package pro.sitescope.app.radio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import pro.sitescope.app.ui.ToolActivity
import pro.sitescope.app.ui.toast
import java.text.DateFormat
import java.util.Date

internal fun clock(ms:Long)=if(ms>0) DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.MEDIUM).format(Date(ms)) else "—"

@Composable
internal fun Chips(options:List<String>,selected:String,select:(String)->Unit){
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
        options.forEach{FilterChip(it==selected,{select(it)},{Text(it)})}
    }
}

@Composable
fun RadioScreen(startTab:Int=0){
    val ctx=LocalContext.current
    val owner=LocalLifecycleOwner.current
    val prefs=remember{ctx.getSharedPreferences("radio-options",0)}
    val tabs=listOf("Сигнал","Сессии и карта","База станций","Тесты и события")
    var tab by rememberSaveable {mutableIntStateOf(startTab.coerceIn(tabs.indices))}
    var refresh by remember{mutableIntStateOf(0)}
    var frame by remember{mutableStateOf<RadioFrame?>(null)}
    val active by SurveyService.active.collectAsState()
    val live by SurveyService.last.collectAsState()
    val error by SurveyService.failure.collectAsState()
    var startAfterGrant by rememberSaveable {mutableStateOf(false)}
    fun start(){runCatching {ctx.startForegroundService(Intent(ctx,SurveyService::class.java))}.onFailure{toast(ctx,"Не удалось начать запись")}}
    val perms=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){
        refresh++
        if(startAfterGrant && ctx.allowed(Manifest.permission.ACCESS_FINE_LOCATION))start()
        startAfterGrant=false
    }
    val permissions=locationPermissions.toList()+Manifest.permission.READ_PHONE_STATE+
        (if(Build.VERSION.SDK_INT>=33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
    LaunchedEffect(tab,refresh,active,owner) {
        if(tab!=0 || active!=null) return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED){
            while(isActive){
                val f=readRadio(ctx);frame=f;RadioWidget.publish(ctx,f)
                delay(10_000)
            }
        }
    }
    val current=if(active!=null) live else frame
    Column(Modifier.fillMaxSize().padding(horizontal=12.dp)){
        Chips(tabs,tabs[tab]){tab=tabs.indexOf(it)}
        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error)
        when(tab){
            0 -> LiveRadio(current,refresh={refresh++},grant={perms.launch(permissions.toTypedArray())},recording=active!=null,record={
                if(active!=null)ctx.stopService(Intent(ctx,SurveyService::class.java))
                else if(ctx.allowed(Manifest.permission.ACCESS_FINE_LOCATION))start()
                else {startAfterGrant=true;perms.launch(permissions.toTypedArray())}
            })
            1 -> SurveyHistory(active,live?.time)
            2 -> TowerScreen()
            3 -> RadioOptions(prefs)
        }
    }
}

@Composable
private fun LiveRadio(f:RadioFrame?,refresh:()->Unit,grant:()->Unit,recording:Boolean,record:()->Unit){
    val ctx=LocalContext.current
    var filter by rememberSaveable {mutableStateOf("Все")}
    var neighbors by rememberSaveable {mutableStateOf(true)}
    var hex by rememberSaveable {mutableStateOf(false)}
    var series by remember{mutableStateOf<Map<String,List<Pair<Long,Int>>>>(emptyMap())}
    LaunchedEffect(f?.time){
        if(f!=null){
            val observed=f.cells.filter {it.registered && it.dbm!=null && it.measured>0}.map { Triple(it.sim+" · "+it.rat,it.measured,it.dbm!!) }+
                listOfNotNull(f.wifi?.let {Triple("Wi-Fi",f.time,it.dbm)})
            val next=series.toMutableMap()
            observed.forEach{(key,time,v)->val samples=next[key].orEmpty();if(samples.lastOrNull()?.first!=time)next[key]=(samples+(time to v)).takeLast(90)}
            series=next
        }
    }
    fun num(v:Long?)=v?.let{if(hex)"0x"+it.toString(16).uppercase() else it.toString()} ?: "—"
    val cells=f?.cells.orEmpty().filter{(filter=="Все" || it.sim==filter) && (neighbors || it.registered)}
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp),contentPadding=PaddingValues(vertical=10.dp)){
        item {
            if(!ctx.allowed(Manifest.permission.ACCESS_FINE_LOCATION) || !ctx.allowed(Manifest.permission.READ_PHONE_STATE))
                OutlinedButton(grant){Text("Разрешить измерения SIM и сот")}
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                Button(record){Text(if(recording)"Остановить запись" else "Записать маршрут")}
                TextButton(refresh,enabled=!recording){Text("Обновить")}
            }
            Text(f?.let{"${clock(it.time)} · ${if(it.internet) "Интернет доступен" else "Интернет не подтверждён"}"} ?: "Ожидание данных Android")
            if(!f?.note.isNullOrBlank())Text(f!!.note,style=MaterialTheme.typography.bodySmall)
            f?.sims?.forEach{Text("${it.name} · ${it.operator} · ${it.strength.ifBlank{"нет уровня"}}${if(it.roaming)" · роуминг" else ""}")}
            f?.wifi?.let{Text("Wi-Fi ${it.ssid} · ${it.dbm} dBm · ${it.frequency} МГц · ${it.link} Мбит/с")}
            Chips(listOf("Все")+(f?.sims.orEmpty().map{it.name}+f?.cells.orEmpty().map{it.sim}).distinct(),filter){filter=it}
        }
        series.filterKeys{filter=="Все" || it.startsWith(filter)}.forEach{(key,points)->item{
            Text("$key · ${points.last().second} dBm",style=MaterialTheme.typography.labelLarge)
            SignalPlot(points.map { it.second })
        }}
        item {
            Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){
                FilterChip(neighbors,{neighbors=!neighbors},{Text("Соседние соты")})
                FilterChip(hex,{hex=!hex},{Text("HEX")})
            }
            if(f!=null && cells.isEmpty())Text("Android не вернул соты для этого фильтра.")
        }
        items(cells){c->
            ElevatedCard(Modifier.fillMaxWidth()){Column(Modifier.padding(12.dp)){
                Text("${c.sim} · ${c.rat} · ${if(c.registered)"обслуживает" else "соседняя"}",style=MaterialTheme.typography.titleSmall)
                Text("${c.dbm ?: "—"} dBm · MCC/MNC ${c.mcc}/${c.mnc}")
                Text("CID ${num(c.cid)} · TAC/LAC ${num(c.area)} · PCI/PSC ${c.pci ?: "—"}")
                Text("Канал ${c.channel ?: "—"}")
                if(c.rat=="LTE" && c.cid!=null)Text("eNB ${c.cid shr 8} · сектор ${c.cid and 255}")
                if(c.details.isNotBlank())Text(c.details,style=MaterialTheme.typography.bodySmall)
                Text("Измерение модема: ${clock(c.measured)}",style=MaterialTheme.typography.labelSmall)
            }}
        }
    }
}

@Composable
internal fun SignalPlot(values:List<Int>){
    val color=MaterialTheme.colorScheme.primary
    val grid=MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(62.dp)){
        listOf(0.25f,0.5f,0.75f).forEach{y->drawLine(grid,Offset(0f,size.height*y),Offset(size.width,size.height*y))}
        fun xy(i:Int)=Offset(size.width*i/(values.lastIndex.coerceAtLeast(1)),size.height*(1f-((values[i]+150)/125f).coerceIn(0f,1f)))
        for(i in 1 until values.size)drawLine(color,xy(i-1),xy(i),2.dp.toPx())
        if(values.isNotEmpty())drawCircle(color,3.dp.toPx(),xy(values.lastIndex))
    }
}

