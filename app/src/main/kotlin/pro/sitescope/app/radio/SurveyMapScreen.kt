package pro.sitescope.app.radio

import android.Manifest
import android.hardware.GeomagneticField
import android.location.Location
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.sitescope.app.satellite.attitude
import pro.sitescope.app.satellite.degrees

@Composable
internal fun SurveyMapScreen(
    frame: RadioFrame?, position: Location?, active: Long?, tick: Long?, record: () -> Unit,
    grant: () -> Unit, stations: () -> Unit, permissionRevision: Int, fullscreen: Boolean, onFullscreen: () -> Unit
) {
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    val prefs = remember(ctx) { ctx.getSharedPreferences("radio-options", 0) }
    var layer by rememberSaveable {
        mutableStateOf(FieldMapLayer.entries.firstOrNull { it.name == prefs.getString("mapLayer", "") } ?: FieldMapLayer.Streets)
    }
    var followHeading by rememberSaveable { mutableStateOf(false) }
    var recenter by remember { mutableIntStateOf(0) }
    var fix by remember {mutableStateOf<Location?>(null)}
    var samples by remember {mutableStateOf<List<Sample>>(emptyList())}
    var towers by remember {mutableStateOf<List<Pin>>(emptyList())}
    var source by rememberSaveable {mutableStateOf("Все")}
    var message by remember {mutableStateOf("")}
    var locating by remember { mutableStateOf(false) }
    val location=position ?: fix
    val correction = remember(location?.latitude, location?.longitude) {
        location?.let { GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis()).declination.toDouble() } ?: 0.0
    }
    val heading = if (followHeading) attitude(correction, smooth = true)?.heading else null
    fun locate() {
        if (position != null) { recenter++; return }
        if (!ctx.allowed(Manifest.permission.ACCESS_FINE_LOCATION)) { grant(); return }
        if (locating) return
        recenter++
        locating = true
        scope.launch {
            try {
                message = "Определяю точку…"
                val fresh = currentFix(ctx)
                if (fresh != null) { fix = fresh; recenter++ }
                message = if (fresh == null) "Свежая геопозиция недоступна" else ""
            } finally { locating = false }
        }
    }
    LaunchedEffect(permissionRevision) { if (location == null && ctx.allowed(Manifest.permission.ACCESS_FINE_LOCATION)) locate() }
    LaunchedEffect(location != null) { if (location != null) recenter++ }
    LaunchedEffect(active,tick) {samples=withContext(Dispatchers.IO){active?.let{SurveyStore.get(ctx).latest(it)}.orEmpty()}}
    LaunchedEffect(frame?.cells,location?.latitude,location?.longitude) {
        towers=withContext(Dispatchers.IO){
            if(location==null) emptyList() else {
                val cells=frame?.cells.orEmpty().filter{it.registered}
                SurveyStore.get(ctx).nearby(Pin(location.latitude,location.longitude,"Я"),"").filter{t->cells.any{it.cid==t.cid && it.mcc==t.mcc && it.mnc==t.mnc && it.area==t.area && t.rat==when(it.rat){"LTE"->"L";"NR"->"N";"GSM"->"G";"WCDMA"->"W";"TD-SCDMA"->"T";"CDMA"->"C";else->""}}}
                    .mapNotNull{t->if(t.lat==null || t.lon==null)null else Pin(t.lat,t.lon,t.name.ifBlank{"CID ${t.cid}"},bearing=t.bearing)}
            }
        }
    }
    val route=remember(samples,source){samples.asReversed().filter{it.lat!=null && it.lon!=null && it.serving!=false && (source=="Все" || it.sim==source)}.distinctBy{it.time}.map{
        Pin(it.lat!!,it.lon!!,"${it.sim} · ${it.dbm ?: "—"} dBm",it.dbm.takeIf{_->it.measured>0 && it.time-it.measured in 0..60000})
    }}
    val center = location?.let { Pin(it.latitude, it.longitude, "Моё местоположение", isLocation = true) } ?: route.lastOrNull()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onFullscreen) {
                Icon(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                    if (fullscreen) "Выйти из полноэкранной карты" else "Развернуть карту")
            }
            Column(Modifier.weight(1f)) {
                Text(if(active!=null)"Идёт запись маршрута" else "Карта радиосети",style=MaterialTheme.typography.titleSmall)
                Text(location?.let{"%.5f, %.5f · ±%d м".format(it.latitude,it.longitude,it.accuracy.toInt())} ?: "Геопозиция не получена",style=MaterialTheme.typography.labelSmall)
                if (followHeading && heading != null) Text("Курс ${degrees(heading)}", style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = { followHeading = !followHeading }) {
                Icon(Icons.Rounded.Explore, if (followHeading) "Север сверху" else "Вращать по компасу",
                    tint = if (followHeading) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { locate() }, enabled = !locating) { Icon(Icons.Rounded.MyLocation, "Моя точка") }
        }
        FieldMapLayers(layer) {
            layer = it
            prefs.edit().putString("mapLayer", it.name).apply()
        }
        if (!fullscreen) Column(Modifier.padding(horizontal = 12.dp)) {
            location?.let { Text("GPS ${clock(it.time)}" + (if (it.hasAltitude()) " · ${it.altitude.toInt()} м" else "") +
                (if (it.hasSpeed()) " · ${(it.speed * 3.6).toInt()} км/ч" else ""), style = MaterialTheme.typography.labelSmall) }
            if (samples.isNotEmpty()) Chips(listOf("Все") + samples.map { it.sim }.distinct(), source) { source = it }
        }
        if (message.isNotBlank()) Text(message, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
        if(center==null) {
            Surface(Modifier.weight(1f).fillMaxWidth(),shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.surfaceContainer) {
                Column(Modifier.padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.LocationOn,null,Modifier.size(40.dp))
                    Text("Для карты нужна точка GPS",style=MaterialTheme.typography.titleMedium)
                    Text("Маршруты прошлых измерений доступны в журнале.",style=MaterialTheme.typography.bodySmall)
                    Button({locate()}, enabled = !locating){Text("Определить местоположение")}
                }
            }
        } else FieldMap(center, towers + center, route, orientation = -(heading?.toInt()?.toFloat() ?: 0f),
            modifier = Modifier.fillMaxWidth().weight(1f), layer = layer, recenter = recenter, followCenter = false)
        if (!fullscreen) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(record,Modifier.weight(1f)){Icon(if(active!=null)Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(if(active!=null)"Стоп" else "Запись")}
                OutlinedButton(stations,Modifier.weight(1f)){Icon(Icons.Rounded.CellTower,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Станции")}
            }
            Text("Станции из вашего справочника", Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall)
        }
    }
}
