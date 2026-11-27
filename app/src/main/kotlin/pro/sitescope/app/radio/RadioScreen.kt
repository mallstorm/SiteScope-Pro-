package pro.sitescope.app.radio

import android.Manifest
import android.content.Intent
import android.os.Build
import android.telephony.ServiceState
import android.telephony.TelephonyManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import pro.sitescope.app.wifi.WifiScreen
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
    val view=androidx.compose.ui.platform.LocalView.current
    val prefs=remember{ctx.getSharedPreferences("radio-options",0)}
    var keepAwake by remember {mutableStateOf(prefs.getBoolean("keepAwake",false))}
    DisposableEffect(prefs) {
        val listener=android.content.SharedPreferences.OnSharedPreferenceChangeListener {p,key->if(key=="keepAwake")keepAwake=p.getBoolean(key,false)}
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {prefs.unregisterOnSharedPreferenceChangeListener(listener)}
    }
    DisposableEffect(view,keepAwake) {
        val previous=view.keepScreenOn
        if(keepAwake)view.keepScreenOn=true
        onDispose {if(keepAwake)view.keepScreenOn=previous}
    }
    // External widget / shortcut tab numbers from alpha3 retain their destinations.
    var page by rememberSaveable(startTab) {mutableIntStateOf(when(startTab){1->2;2->6;3->5;else->0})}
    var refresh by remember{mutableIntStateOf(0)}
    var frame by remember{mutableStateOf<RadioFrame?>(null)}
    var series by remember{mutableStateOf<Map<String,List<Pair<Long,Int>>>>(emptyMap())}
    val active by SurveyService.active.collectAsState()
    val running by SurveyService.running.collectAsState()
    val live by SurveyService.last.collectAsState()
    val position by SurveyService.position.collectAsState()
    val error by SurveyService.failure.collectAsState()
    var startAfterGrant by rememberSaveable {mutableStateOf(false)}
    fun start(){runCatching{ctx.startForegroundService(Intent(ctx,SurveyService::class.java))}.onFailure{pro.sitescope.app.ui.toast(ctx,"Не удалось начать запись")}}
    val perms=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){
        refresh++
        if(startAfterGrant && ctx.allowed(Manifest.permission.ACCESS_FINE_LOCATION))start()
        startAfterGrant=false
    }
    val permissions=locationPermissions.toList()+Manifest.permission.READ_PHONE_STATE+
        (if(Build.VERSION.SDK_INT>=33)listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
    LaunchedEffect(page,refresh,running,owner) {
        if(page !in listOf(0,1,3) || running)return@LaunchedEffect
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED){while(isActive){
            val f=readRadio(ctx);frame=f;RadioWidget.publish(ctx,f);delay(10_000)
        }}
    }
    val current=if(running)live else frame
    LaunchedEffect(current?.time) {
        current?.let {f->
            val points=f.cells.filter{it.registered && it.dbm!=null && it.measured>0}.map{Triple(it.sim+" · "+it.rat,it.measured,it.dbm!!)}+
                listOfNotNull(f.wifi?.let{Triple("Wi-Fi",f.time,it.dbm)})
            val next=series.toMutableMap()
            points.forEach{(key,time,value)->val old=next[key].orEmpty();if(old.lastOrNull()?.first!=time)next[key]=(old+(time to value)).takeLast(90)}
            series=next
        }
    }
    val record:()->Unit={
        if(active!=null)ctx.stopService(Intent(ctx,SurveyService::class.java))
        else if(ctx.allowed(Manifest.permission.ACCESS_FINE_LOCATION))start()
        else{startAfterGrant=true;perms.launch(permissions.toTypedArray())}
    }
    val grant:()->Unit={perms.launch(permissions.toTypedArray())}
    var more by remember {mutableStateOf(false)}
    val labels=listOf("Сигнал","Соты","Журнал","Карта","Wi-Fi","Ещё")
    val icons=listOf(Icons.Rounded.SignalCellularAlt,Icons.Rounded.CellTower,Icons.Rounded.History,Icons.Rounded.Map,Icons.Rounded.Wifi,Icons.Rounded.MoreHoriz)
    Column(Modifier.fillMaxSize()) {
        if(error.isNotBlank())Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(horizontal=12.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when(page) {
                0->SignalDashboard(current,series,active!=null,record,{refresh++},grant)
                1->CellDashboard(current,series,grant)
                2->Column(Modifier.padding(horizontal=12.dp)) {
                    SectionHeading("Журнал измерений","Маршруты, соты и экспорт CSV / KML")
                    SurveyHistory(active,live?.time)
                }
                3->SurveyMapScreen(current,position,active,live?.time,record,grant,{page=6})
                4->WifiScreen()
                5->Column(Modifier.padding(horizontal=12.dp)) {RadioOptions(prefs)}
                6->Column(Modifier.padding(horizontal=12.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        TextButton({page=3}){Icon(Icons.Rounded.ArrowBack,"К карте")}
                        Text("База станций",style=MaterialTheme.typography.titleMedium)
                    }
                    TowerScreen()
                }
            }
        }
        NavigationBar(windowInsets=WindowInsets(0,0,0,0)) {
            labels.forEachIndexed {i,label->
                NavigationBarItem(selected=page==i || (i==5 && page==6),onClick={if(i==5)more=true else page=i},
                    icon={Box {
                        Icon(icons[i],label)
                        if(i==5)DropdownMenu(expanded=more,onDismissRequest={more=false}) {
                            DropdownMenuItem(text={Text("Тесты, оповещения и настройки")},onClick={more=false;page=5})
                            DropdownMenuItem(text={Text("База станций CLF")},onClick={more=false;page=6})
                        }
                    }},label={Text(label,fontSize=11.sp,maxLines=1)})
            }
        }
    }
}

@Composable
private fun SectionHeading(title:String,subtitle:String) {
    Column(Modifier.padding(vertical=12.dp)) {
        Text(title,style=MaterialTheme.typography.titleMedium)
        Text(subtitle,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun SignalDashboard(f:RadioFrame?,series:Map<String,List<Pair<Long,Int>>>,recording:Boolean,record:()->Unit,refresh:()->Unit,grant:()->Unit) {
    val ctx=LocalContext.current
    var mode by rememberSaveable {mutableStateOf("Прибор")}
    var chosen by rememberSaveable {mutableStateOf("")}
    val sources=(f?.sims.orEmpty().map{it.name}+f?.cells.orEmpty().filter{it.registered}.map{it.sim}).distinct()+"Wi-Fi"
    val source=chosen.takeIf{it in sources} ?: sources.first()
    val wifi=source=="Wi-Fi"
    val cell=f?.cells?.firstOrNull{it.sim==source && it.registered}
    val sim=f?.sims?.firstOrNull{it.name==source}
    val value=if(wifi)f?.wifi?.dbm else cell?.dbm
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=12.dp),contentPadding=PaddingValues(bottom=16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item {Chips(listOf("Прибор","Сеть","График"),mode){mode=it}}
        item {
            if(!ctx.allowed(Manifest.permission.ACCESS_FINE_LOCATION) || !ctx.allowed(Manifest.permission.READ_PHONE_STATE))
                OutlinedButton(grant,Modifier.fillMaxWidth()){Text("Разрешить измерения SIM и сот")}
            if(f==null)LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(if(wifi)f?.wifi?.ssid?.ifBlank{"Wi-Fi"} ?: "Wi-Fi не подключён" else sim?.operator?.ifBlank{source} ?: source,
                    style=MaterialTheme.typography.titleMedium,modifier=Modifier.weight(1f))
                IconButton(refresh){Icon(Icons.Rounded.Refresh,"Обновить измерения")}
            }
            Text(f?.let{"${clock(it.time)} · ${if(it.internet)"Интернет доступен" else "Интернет не подтверждён"}"} ?: "Ожидание данных Android",style=MaterialTheme.typography.labelSmall)
        }
        if(mode=="Прибор")item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                SignalGauge(value,wifi,if(wifi)"Wi-Fi · RSSI" else "$source · ${cell?.rat ?: "—"}")
                Row(Modifier.fillMaxWidth().padding(horizontal=12.dp).horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    sources.forEach{s->FilterChip(source==s,{chosen=s},{Text(s)},leadingIcon={Icon(if(s=="Wi-Fi")Icons.Rounded.Wifi else Icons.Rounded.SimCard,null,Modifier.size(18.dp))})}
                }
                HorizontalDivider(Modifier.padding(horizontal=12.dp,vertical=8.dp))
                Column(Modifier.padding(start=14.dp,end=14.dp,bottom=14.dp)) {
                    if(wifi)MetricGrid(listOf("Частота" to (f?.wifi?.frequency?.let{"$it МГц"} ?: "—"),"Канал" to wifiChannel(f?.wifi?.frequency),"Линк" to (f?.wifi?.link?.let{"$it Мбит/с"} ?: "—")))
                    else MetricGrid(listOf("Сеть" to (cell?.rat ?: "—"),"PCI / PSC" to (cell?.pci?.toString() ?: "—"),"Канал" to (cell?.channel?.toString() ?: "—")))
                }
            }
        }
        if(mode!="Прибор")item {Chips(sources,source){chosen=it}}
        if(mode=="График")item {
            ElevatedCard {Column(Modifier.padding(12.dp)) {
                Text("Сравнение SIM и Wi-Fi",style=MaterialTheme.typography.titleSmall)
                SignalChart(series,Modifier.height(300.dp))
                Text("dBm · до 90 замеров на источник. Время модема учитывается отдельно для каждой соты.",style=MaterialTheme.typography.bodySmall)
            }}
        }
        if(mode=="Сеть")item {
            ElevatedCard {Column(Modifier.padding(14.dp)) {
                if(wifi) {
                    DetailRow("SSID",f?.wifi?.ssid.orEmpty())
                    DetailRow("Частота",f?.wifi?.frequency?.let{"$it МГц"}.orEmpty())
                    DetailRow("Канал",wifiChannel(f?.wifi?.frequency))
                    DetailRow("Скорость линка",f?.wifi?.link?.let{"$it Мбит/с"}.orEmpty())
                    DetailRow("RSSI",value?.let{"$it dBm"}.orEmpty())
                    Text("Скорость линка сообщает адаптер; скорость интернета измеряется в разделе тестов.",style=MaterialTheme.typography.bodySmall)
                } else {
                    DetailRow("Оператор",sim?.operator.orEmpty())
                    DetailRow("MCC / MNC",cell?.let{"${it.mcc} / ${it.mnc}"}.orEmpty())
                    DetailRow("Технология",cell?.rat.orEmpty())
                    DetailRow("Обслуживание",when(sim?.service){ServiceState.STATE_IN_SERVICE->"В сети";ServiceState.STATE_OUT_OF_SERVICE->"Вне сети";ServiceState.STATE_EMERGENCY_ONLY->"Экстренные вызовы";ServiceState.STATE_POWER_OFF->"Радиомодуль выключен";else->"—"})
                    DetailRow("Передача данных",when(sim?.data){TelephonyManager.DATA_CONNECTED->"Подключена";TelephonyManager.DATA_DISCONNECTED->"Отключена";TelephonyManager.DATA_CONNECTING->"Подключение";TelephonyManager.DATA_SUSPENDED->"Приостановлена";else->"—"})
                    DetailRow("Роуминг",sim?.let{if(it.roaming)"Да" else "Нет"}.orEmpty())
                    DetailRow("TAC / LAC",cell?.area?.toString().orEmpty())
                    DetailRow("CID",cell?.cid?.toString().orEmpty())
                    DetailRow("PCI / PSC",cell?.pci?.toString().orEmpty())
                    DetailRow("Канал",cell?.channel?.toString().orEmpty())
                    cell?.details?.split(" · ")?.filter(String::isNotBlank)?.forEach {Text(it,style=MaterialTheme.typography.bodySmall)}
                    DetailRow("Замер модема",clock(cell?.measured ?: 0))
                    if(cell==null && !sim?.strength.isNullOrBlank())Text(sim!!.strength,style=MaterialTheme.typography.bodySmall)
                }
            }}
        }
        item {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(record,Modifier.fillMaxWidth()) {
                    Icon(if(recording)Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,null,Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp));Text(if(recording)"Остановить запись маршрута" else "Записать маршрут")
                }
            }
        }
        if(!f?.note.isNullOrBlank())item {
            Text(f!!.note,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

internal fun wifiChannel(frequency:Int?):String=when {
    frequency==2484->"14"
    frequency==5935->"2"
    frequency!=null && frequency in 2412..2472->((frequency-2407)/5).toString()
    frequency!=null && frequency in 5160..5885->((frequency-5000)/5).toString()
    frequency!=null && frequency in 5955..7115->((frequency-5950)/5).toString()
    else->"—"
}

@Composable
private fun CellDashboard(f:RadioFrame?,series:Map<String,List<Pair<Long,Int>>>,grant:()->Unit) {
    val ctx=LocalContext.current
    var filter by rememberSaveable {mutableStateOf("Все")}
    var neighbors by rememberSaveable {mutableStateOf(true)}
    var hex by rememberSaveable {mutableStateOf(false)}
    var gNodeBits by remember {mutableIntStateOf(ctx.getSharedPreferences("radio-options",0).getInt("gnbBits",22).coerceIn(22,32))}
    val sources=(f?.sims.orEmpty().map{it.name}+f?.cells.orEmpty().map{it.sim}).distinct()
    val selected=filter.takeIf{it=="Все" || it in sources} ?: "Все"
    val groups=f?.cells.orEmpty().filter{(selected=="Все" || it.sim==selected) && (neighbors || it.registered)}.groupBy{it.sim}
    fun num(value:Long?)=value?.let{if(hex)"0x"+it.toString(16).uppercase() else it.toString()} ?: "—"
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp),contentPadding=PaddingValues(bottom=16.dp)) {
        item {
            Chips(listOf("Все")+sources,selected){filter=it}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(neighbors,{neighbors=!neighbors},{Text("Соседние")})
                FilterChip(hex,{hex=!hex},{Text("HEX")})
                Text("${f?.cells?.size ?: 0} сот",Modifier.align(Alignment.CenterVertically),style=MaterialTheme.typography.labelMedium)
            }
            if(!ctx.allowed(Manifest.permission.ACCESS_FINE_LOCATION))OutlinedButton(grant){Text("Разрешить доступ к сотам")}
            if(groups.isEmpty())Text("Android пока не вернул соты. Проверьте разрешения и геолокацию.")
        }
        groups.forEach{(sim,rows)->
            item {
                Text("$sim · ${f?.sims?.firstOrNull{it.name==sim}?.operator.orEmpty()}",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=10.dp))
            }
            items(rows.sortedByDescending{it.registered}){c->
                if(!c.registered) {
                    var expanded by rememberSaveable(c.sim,c.rat,c.cid,c.pci,c.channel) {mutableStateOf(false)}
                    Surface(onClick={expanded=!expanded},Modifier.fillMaxWidth(),shape=MaterialTheme.shapes.small,color=MaterialTheme.colorScheme.surface) {
                        Column(Modifier.padding(horizontal=10.dp,vertical=8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {Text("${c.pci ?: "—"}",style=MaterialTheme.typography.titleSmall);Text("${c.rat} · PCI / PSC",style=MaterialTheme.typography.labelSmall)}
                                Column(Modifier.weight(1f)) {Text("${c.channel ?: "—"}",style=MaterialTheme.typography.titleSmall);Text("Канал",style=MaterialTheme.typography.labelSmall)}
                                Column(Modifier.width(72.dp)) {
                                    Text("${c.dbm ?: "—"} dBm",color=signalColor(c.dbm),style=MaterialTheme.typography.labelMedium)
                                    LinearProgressIndicator(progress={((c.dbm?.plus(140) ?: 0)/97f).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(3.dp),color=signalColor(c.dbm))
                                }
                                Icon(if(expanded)Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,if(expanded)"Свернуть параметры" else "Подробности соты",Modifier.size(18.dp))
                            }
                            if(expanded) {
                                Text("CID ${num(c.cid)} · TAC / LAC ${num(c.area)} · MCC / MNC ${c.mcc}/${c.mnc}",style=MaterialTheme.typography.bodySmall)
                                if(c.details.isNotBlank())Text(c.details,style=MaterialTheme.typography.bodySmall)
                                Text("Замер модема: ${clock(c.measured)}",style=MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                } else Surface(Modifier.fillMaxWidth(),color=MaterialTheme.colorScheme.secondaryContainer,shape=MaterialTheme.shapes.medium) {
                    Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            Text("${c.rat} · ${if(c.registered)"обслуживает" else "соседняя"}",Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
                            Text("${c.dbm ?: "—"} dBm",color=signalColor(c.dbm),style=MaterialTheme.typography.titleSmall)
                        }
                        if(c.registered) {
                            SignalPlot(series[sim+" · "+c.rat].orEmpty().map{it.second})
                        }
                        MetricGrid(listOf("TAC / LAC" to num(c.area),"CID" to num(c.cid),"PCI / PSC" to (c.pci?.toString() ?: "—"),"Канал" to (c.channel?.toString() ?: "—"),"MCC / MNC" to "${c.mcc}/${c.mnc}","Уровень" to (c.dbm?.toString() ?: "—")))
                        if(c.details.isNotBlank())Text(c.details,style=MaterialTheme.typography.bodySmall)
                        if(c.rat=="LTE" && c.cid!=null)Text("eNB ${c.cid shr 8} · сектор ${c.cid and 255}",style=MaterialTheme.typography.labelMedium)
                        if(c.rat=="NR" && c.cid!=null) {
                            Text("gNB ${c.cid shr (36-gNodeBits)} · сектор ${c.cid and ((1L shl (36-gNodeBits))-1)}",style=MaterialTheme.typography.labelMedium)
                            TextButton({gNodeBits=if(gNodeBits==32)22 else gNodeBits+1;ctx.getSharedPreferences("radio-options",0).edit().putInt("gnbBits",gNodeBits).apply()}){Text("gNodeB ID: $gNodeBits бит")}
                        }
                        Text("Замер модема: ${clock(c.measured)}",style=MaterialTheme.typography.labelSmall)
                    }
                }
            }
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

