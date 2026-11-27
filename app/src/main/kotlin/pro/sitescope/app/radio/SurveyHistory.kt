package pro.sitescope.app.radio

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import pro.sitescope.app.data.ReportLog
import pro.sitescope.app.ui.toast
import java.io.OutputStream

internal suspend fun saveReport(ctx:Context,uri:Uri,format:String,write:(OutputStream)->Unit){
    withContext(Dispatchers.IO){
        val resolver=ctx.contentResolver
        resolver.openOutputStream(uri,"wt")?.use(write) ?: error("Файл недоступен")
        runCatching {resolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)}
        val name=resolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {if(it.moveToFirst())it.getString(0) else null} ?: "Radio.${format.lowercase()}"
        val size=resolver.openFileDescriptor(uri,"r")?.use{it.statSize} ?: -1L
        ReportLog.add(ctx,name,"Радиомонитор",format,size,uri.toString())
    }
}

@Composable
fun SurveyHistory(active:Long?,tick:Long?){
    val ctx=LocalContext.current
    val store=remember{SurveyStore.get(ctx)}
    val scope=rememberCoroutineScope()
    var sessions by remember{mutableStateOf<List<Survey>>(emptyList())}
    var chosen by rememberSaveable{mutableStateOf<Long?>(null)}
    var samples by remember{mutableStateOf<List<Sample>>(emptyList())}
    var exportId by rememberSaveable{mutableLongStateOf(0)}
    var filter by rememberSaveable{mutableStateOf("Все")}
    var map by rememberSaveable{mutableStateOf(false)}
    LaunchedEffect(active,tick){sessions=withContext(Dispatchers.IO){store.surveys()};if(chosen==null)chosen=sessions.firstOrNull()?.id}
    LaunchedEffect(chosen,tick){samples=withContext(Dispatchers.IO){chosen?.let(store::latest).orEmpty()}}
    fun output(uri:Uri?,kml:Boolean){if(uri!=null){val id=exportId;scope.launch {runCatching{
        saveReport(ctx,uri,if(kml)"KML" else "CSV"){store.export(id,kml,it)}
    }.onSuccess{toast(ctx,"Файл сохранён в отчётах")}.onFailure{toast(ctx,"Экспорт: ${it.localizedMessage}")}}}}
    val csv=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){output(it,false)}
    val kml=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.google-earth.kml+xml")){output(it,true)}
    val visible=samples.filter { filter=="Все" || it.sim==filter }
    val pins=remember(visible){visible.asReversed().filter {it.lat!=null && it.lon!=null}.distinctBy { it.time }.map {
        Pin(it.lat!!,it.lon!!,"${it.sim} · ${it.rat} · ${it.dbm ?: "—"} dBm · ${clock(it.time)}")
    }}
    LazyColumn(contentPadding=PaddingValues(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        if(sessions.isEmpty())item{Text("Сессий пока нет. На вкладке «Сигнал» нажмите «Записать маршрут».")}
        items(sessions){s->TextButton({chosen=s.id;filter="Все"}){Text("${if(chosen==s.id)"● " else ""}${clock(s.start)} · ${s.count} измерений${if(s.id==active)" · идёт запись" else ""}")}}
        if(chosen!=null)item{
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)){
                FilterChip(map,{map=!map},{Text("Карта")})
                TextButton({exportId=chosen!!;csv.launch("Radio-$exportId.csv")}){Text("CSV")}
                TextButton({exportId=chosen!!;kml.launch("Radio-$exportId.kml")},enabled=pins.isNotEmpty()){Text("KML")}
            }
            Chips(listOf("Все")+samples.map{it.sim}.filter{it.isNotBlank()}.distinct(),filter){filter=it}
            Text("Последние ${samples.size} записей. Экспорт включает всю сессию.",style=MaterialTheme.typography.bodySmall)
        }
        if(map)item{if(pins.isEmpty())Text("Для этой выборки точек с координатами нет.") else FieldMap(pins.last(),pins.filterIndexed{i,_->i%((pins.size/40).coerceAtLeast(1))==0},pins)}
        items(visible){s->Text("${clock(s.time)} · ${s.sim} ${s.rat} · ${s.dbm ?: "—"} dBm${s.cid?.let{" · CID $it"}.orEmpty()}",style=MaterialTheme.typography.bodySmall)}
    }
}

@Composable
fun TowerScreen(){
    val ctx=LocalContext.current
    val store=remember{SurveyStore.get(ctx)}
    val scope=rememberCoroutineScope()
    var query by rememberSaveable{mutableStateOf("")}
    var revision by remember{mutableIntStateOf(0)}
    var rows by remember{mutableStateOf<List<Tower>>(emptyList())}
    var msg by remember{mutableStateOf("")}
    var edit by rememberSaveable{mutableStateOf<String?>(null)}
    var map by rememberSaveable{mutableStateOf(false)}
    var nearby by remember{mutableStateOf<Pin?>(null)}
    var online by rememberSaveable{mutableStateOf(false)}
    var source by rememberSaveable{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)}
    LaunchedEffect(query,revision,nearby){delay(150);rows=withContext(Dispatchers.IO){if(nearby!=null) store.nearby(nearby!!,query) else store.towers(query)}}
    fun import(open:()->java.io.InputStream){
        if(busy)return
        busy=true;scope.launch{
            runCatching{withContext(Dispatchers.IO){open().use(store::importTowers)}}.onSuccess{(ok,skip)->msg="Обработано $ok, пропущено $skip";revision++}.onFailure{msg="Импорт: ${it.localizedMessage}"}
            busy=false
        }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri->if(uri!=null)import{requireNotNull(ctx.contentResolver.openInputStream(uri))}}
    val export=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")){uri->
        if(uri!=null)scope.launch{runCatching{saveReport(ctx,uri,"CLF",store::exportTowers)}.onSuccess{msg="Справочник экспортирован"}.onFailure{msg="Экспорт: ${it.localizedMessage}"}}
    }
    val pins=remember(rows){rows.mapNotNull{t->if(t.lat==null || t.lon==null)null else Pin(t.lat,t.lon,t.name.ifBlank{"CID ${t.cid}"})}}
    LazyColumn(contentPadding=PaddingValues(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        item {
            OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("CID, оператор MCC/MNC или название")},singleLine=true)
            Row(Modifier.horizontalScroll(rememberScrollState())){
                TextButton({picker.launch(arrayOf("*/*"))},enabled=!busy){Text("Импорт CLF")}
                TextButton({export.launch("SiteScope-stations.clf")}){Text("Экспорт")}
                TextButton({edit=""}){Text("Добавить")}
                FilterChip(map,{map=!map},{Text("Карта")})
            }
            OutlinedTextField(source,{source=it},Modifier.fillMaxWidth(),label={Text("HTTPS-адрес файла CLF")},singleLine=true)
            TextButton({import {
                val uri=java.net.URI(source.trim());require(uri.scheme=="https" && !uri.host.isNullOrBlank()){"Нужен HTTPS-адрес"}
                uri.toURL().openConnection().apply{connectTimeout=8000;readTimeout=12000}.getInputStream()
            }},enabled=source.isNotBlank() && !busy){Text("Загрузить справочник")}
            FilterChip(nearby!=null,{if(nearby!=null)nearby=null else scope.launch{val l=currentFix(ctx);if(l!=null)nearby=Pin(l.latitude,l.longitude,"Я") else msg="Для поиска рядом нужна геопозиция"}},{Text("В радиусе 20 км")})
            FilterChip(online,{online=!online},{Text("Онлайн-поиск станций")})
            if(online) OnlineCells()
            if(msg.isNotEmpty()) Text(msg)
            if(map && pins.isNotEmpty())FieldMap(nearby ?: pins.first(),pins)
            if(rows.isEmpty())Text("Совпадений нет. Можно импортировать справочник или добавить станцию вручную.")
        }
        items(rows){t->TextButton({edit=t.clf()}){Text("${t.name.ifBlank{"Станция"}} · ${t.mcc}-${t.mnc} · CID ${t.cid} · ${if(t.lat==null)"координаты неизвестны" else "${t.lat}, ${t.lon}"}")}}
    }
    edit?.let{
        AlertDialog(onDismissRequest={edit=null},title={Text("Запись CLF")},text={Column{
            Text("Тип;MCC;MNC;LAC/TAC;CID;PCI;канал;широта;долгота;точность;название;азимут")
            OutlinedTextField(it,{s->edit=s},label={Text("Запись станции")})
        }},confirmButton={TextButton({val tower=parseTower(it);if(tower==null)toast(ctx,"Проверьте поля CLF") else scope.launch{withContext(Dispatchers.IO){store.saveTower(tower)};edit=null;revision++}}){Text("Сохранить")}},dismissButton={TextButton({edit=null}){Text("Отмена")}})
    }
}

