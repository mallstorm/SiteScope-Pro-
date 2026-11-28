package pro.sitescope.app.radio

import android.Manifest
import android.hardware.GeomagneticField
import android.location.Location
import android.os.SystemClock
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.sitescope.app.SecureStore
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
    val store = remember(ctx) { SurveyStore.get(ctx) }
    var layer by rememberSaveable {
        mutableStateOf(FieldMapLayer.entries.firstOrNull { it.name == prefs.getString("mapLayer", "") } ?: FieldMapLayer.Streets)
    }
    var followHeading by rememberSaveable { mutableStateOf(false) }
    var recenter by remember { mutableIntStateOf(0) }
    var fix by remember {mutableStateOf<Location?>(null)}
    var samples by remember {mutableStateOf<List<Sample>>(emptyList())}
    var nearby by remember { mutableStateOf<List<Tower>>(emptyList()) }
    var local by remember { mutableStateOf<Map<StationKey, List<Tower>>>(emptyMap()) }
    var loadedKeys by remember { mutableStateOf<Set<StationKey>?>(null) }
    val online = remember { mutableStateMapOf<StationKey, StationPosition>() }
    val requested = remember { LinkedHashMap<StationKey, Long>() }
    val pending = remember { mutableStateMapOf<StationKey, Boolean>() }
    var onlineArea by remember { mutableStateOf<List<StationPosition>>(emptyList()) }
    var areaBusy by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    var auto by remember { mutableStateOf(prefs.getBoolean("autoStationLookup", false)) }
    var onlineNotice by remember { mutableStateOf("") }
    var showStations by remember { mutableStateOf(false) }
    var showOnline by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf<StationOnMap?>(null) }
    var focus by remember { mutableStateOf<Pin?>(null) }
    var source by rememberSaveable {mutableStateOf("Все")}
    var message by remember {mutableStateOf("")}
    var locating by remember { mutableStateOf(false) }
    val location=position ?: fix
    val gps = remember(location) { location?.let { Pin(it.latitude, it.longitude, "Моё местоположение", isLocation = true) } }
    val correction = remember(location?.latitude, location?.longitude) {
        location?.let { GeomagneticField(it.latitude.toFloat(), it.longitude.toFloat(), it.altitude.toFloat(), System.currentTimeMillis()).declination.toDouble() } ?: 0.0
    }
    val heading = if (followHeading) attitude(correction, smooth = true)?.heading else null
    fun locate() {
        focus = null
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
    LaunchedEffect(active,tick) {samples=withContext(Dispatchers.IO){active?.let(store::latest).orEmpty()}}
    LaunchedEffect(ctx) { token = withContext(Dispatchers.IO) { SecureStore(ctx).get(STATION_KEY) } }
    LaunchedEffect(gps?.lat?.let { (it * 200).toInt() }, gps?.lon?.let { (it * 200).toInt() }) {
        nearby = withContext(Dispatchers.IO) { gps?.let { store.nearby(it, "") }.orEmpty() }
    }
    val detected = remember(frame) { currentStationCells(frame) }
    val keys = remember(detected) { detected.mapNotNull(RadioCell::stationKey).toSet() }
    LaunchedEffect(keys) {
        local = withContext(Dispatchers.IO) { store.stationsFor(keys) }
        loadedKeys = keys
    }
    val now by produceState(System.currentTimeMillis(), frame?.time) {
        value = System.currentTimeMillis()
        while (true) { delay(10_000); value = System.currentTimeMillis() }
    }
    val radioRows = detected.map { cell ->
        val key = cell.stationKey()
        val position = key?.let { localStation(it, cell.pci, local[it].orEmpty())?.let { tower -> StationPosition(tower) } ?: online[it] }
        stationOnMap(cell, position, now)
    }
    val onlineTargets = radioRows.filter { it.fresh && it.cell?.link?.serving == true }
        .mapNotNull { it.cell?.stationKey() }.distinct().take(4)

    suspend fun fetchStation(key: StationKey, apiKey: String, force: Boolean) {
        if (pending.containsKey(key) || apiKey.isBlank()) return
        val stamp = SystemClock.elapsedRealtime()
        if (!force && (online.containsKey(key) || requested[key]?.let { stamp - it < 600_000 } == true)) return
        if (requested.size >= 128) requested.keys.firstOrNull()?.let { old -> requested.remove(old); online.remove(old) }
        requested[key] = stamp
        pending[key] = true
        try {
            val result = withContext(Dispatchers.IO) { lookupStation(apiKey, key) }
            if (result != null) online[key] = result
            onlineNotice = if (result == null) "OpenCelliD не нашёл эту соту" else "Координаты получены из OpenCelliD"
        } catch (e: CancellationException) {
            requested.remove(key)
            throw e
        } catch (e: Exception) {
            onlineNotice = if (e is IllegalArgumentException || e is IllegalStateException) e.message.orEmpty() else "OpenCelliD недоступен"
        } finally { pending.remove(key) }
    }
    val servingResolvedLocally = loadedKeys?.containsAll(onlineTargets) == true
    LaunchedEffect(auto, token, onlineTargets, servingResolvedLocally) {
        if (auto && token.isNotBlank() && servingResolvedLocally) {
            onlineTargets.filter { id -> radioRows.none { it.cell?.stationKey() == id && it.position != null } }
                .forEach { fetchStation(it, token, force = false) }
        }
    }
    val shownPositions = radioRows.mapNotNull { it.position?.tower }.toSet()
    val catalogue = (nearby.take(80).map { StationPosition(it) } + onlineArea).filterNot { point ->
        shownPositions.any { it.stationKey() == point.tower.stationKey() && it.lat == point.tower.lat && it.lon == point.tower.lon }
    }.distinctBy { listOf(it.tower.rat, it.tower.mcc, it.tower.mnc, it.tower.area, it.tower.cid, it.tower.pci) }
        .map { p ->
            val t = p.tower
            StationOnMap("catalogue:${t.rat}:${t.mcc}:${t.mnc}:${t.area}:${t.cid}:${t.pci}", null, p, StationMarker.Catalogue)
        }
    val mapRows = radioRows + catalogue
    val primary = radioRows.firstOrNull { it.fresh && it.cell?.link?.serving == true }
        ?: radioRows.firstOrNull { it.cell?.link?.serving == true }
    val pins = (catalogue + radioRows.asReversed()).mapNotNull(::stationPin).distinctBy { it.id }
    val links = if (gps == null) emptyList() else radioRows.filter { it.canDrawLink }
        .mapNotNull { stationPin(it)?.let { point -> gps to point } }
    val route=remember(samples,source){samples.asReversed().filter{it.lat!=null && it.lon!=null && it.serving!=false && (source=="Все" || it.sim==source)}.distinctBy{it.time}.map{
        Pin(it.lat!!,it.lon!!,"${it.sim} · ${it.dbm ?: "—"} dBm",it.dbm.takeIf{_->it.measured>0 && it.time-it.measured in 0..60000})
    }}
    val center = focus ?: gps ?: route.lastOrNull() ?: primary?.let(::stationPin)
    LaunchedEffect(center != null) { if (center != null) recenter++ }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onFullscreen) {
                Icon(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                    if (fullscreen) "Выйти из полноэкранной карты" else "Развернуть карту")
            }
            Column(Modifier.weight(1f)) {
                Text(if(active!=null)"Идёт запись маршрута" else "Карта радиосети",style=MaterialTheme.typography.titleSmall)
                Text(location?.let { "%.5f, %.5f".format(it.latitude, it.longitude) +
                    if (it.hasAccuracy()) " · ±${it.accuracy.toInt()} м" else "" } ?: "Геопозиция не получена",style=MaterialTheme.typography.labelSmall)
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
        StationSummary(primary) { if (primary != null) selection = primary else showStations = true }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 3.dp)) {
            location?.let { Text("GPS ${clock(it.time)}" + (if (it.hasAltitude()) " · ${it.altitude.toInt()} м" else "") +
                (if (it.hasSpeed()) " · ${(it.speed * 3.6).toInt()} км/ч" else "") +
                (if (it.hasBearing()) " · курс ${degrees(it.bearing.toDouble())}" else ""), style = MaterialTheme.typography.labelSmall) }
            if (!fullscreen && samples.isNotEmpty()) Chips(listOf("Все") + samples.map { it.sim }.distinct(), source) { source = it }
        }
        if (message.isNotBlank()) Text(message, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall)
        FieldMap(center ?: Pin(18.0, 0.0, ""), pins + listOfNotNull(gps), route, orientation = -(heading?.toInt()?.toFloat() ?: 0f),
            modifier = Modifier.fillMaxWidth().weight(1f), layer = layer, recenter = recenter, followCenter = false,
            initialZoom = if (center == null) 2.0 else 15.0, stationLinks = links,
            onStationClick = { pin -> selection = mapRows.firstOrNull { it.id == pin.id } })
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(record,Modifier.weight(1f)){Icon(if(active!=null)Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text(if(active!=null)"Стоп" else "Запись")}
            OutlinedButton({ showStations = true },Modifier.weight(1f)){Icon(Icons.Rounded.CellTower,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Соты · ${mapRows.size}")}
        }
        if (mapRows.any { it.position?.online == true }) {
            val uri = androidx.compose.ui.platform.LocalUriHandler.current
            TextButton(onClick = { uri.openUri("https://wiki.opencellid.org/docs/api/overview") }) {
                Text("Позиции OpenCelliD · CC BY-SA 4.0", style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    val item = selection?.let { selected -> mapRows.firstOrNull { it.id == selected.id } ?: selected.copy(fresh = false, marker = StationMarker.Catalogue) }
    when {
        showOnline -> StationOnlineSheet(token, auto, areaBusy || pending.isNotEmpty(), onlineNotice, gps != null,
            onClose = { showOnline = false }, onSave = { key, automatic ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { SecureStore(ctx).put(STATION_KEY, key) }
                        if (token != key) { online.clear(); onlineArea = emptyList(); requested.clear() }
                        token = key
                        auto = automatic && key.isNotBlank()
                        prefs.edit().putBoolean("autoStationLookup", auto).apply()
                        onlineNotice = if (key.isBlank()) "Ключ удалён" else "Ключ сохранён"
                    } catch (_: Exception) { onlineNotice = "Не удалось сохранить ключ" }
                }
            }, onArea = {
                val here = gps ?: return@StationOnlineSheet
                val key = token
                if (areaBusy) return@StationOnlineSheet
                areaBusy = true
                scope.launch {
                    try {
                        onlineArea = withContext(Dispatchers.IO) { lookupStationArea(key, here) }
                        onlineNotice = "Сот поблизости в базе · ${onlineArea.size}"
                        if (onlineArea.isNotEmpty()) showOnline = false
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { onlineNotice = if (e is IllegalArgumentException || e is IllegalStateException) e.message.orEmpty() else "OpenCelliD недоступен" }
                    finally { areaBusy = false }
                }
            })
        item != null -> {
            val point = stationPin(item)
            val key = item.cell?.stationKey()
            val distance = if (gps != null && point != null) FloatArray(1).also {
                Location.distanceBetween(gps.lat, gps.lon, point.lat, point.lon, it)
            }.first().let { if (it < 1000f) "${it.toInt()} м" else "%.2f км".format(it / 1000) } else null
            StationDetails(item, frame?.activeDataSim, frame?.sims?.firstOrNull { it.name == item.cell?.sim }?.operator,
                distance, key != null && pending.containsKey(key), onlineNotice.takeIf { key != null && key in requested }.orEmpty(),
                onClose = { selection = null }, onFocus = { focus = point; recenter++; selection = null; showStations = false },
                onLookup = key?.let { id -> { if (token.isBlank()) showOnline = true else scope.launch { fetchStation(id, token, force = true) }; Unit } })
        }
        showStations -> StationsSheet(mapRows, onClose = { showStations = false }, onDetails = { selection = it },
            onOnline = { showOnline = true }, onCatalogue = stations)
    }
}

private const val STATION_KEY = "radio.opencellid.token"

private fun stationPin(row: StationOnMap): Pin? {
    val tower = row.position?.tower ?: return null
    return Pin(tower.lat ?: return null, tower.lon ?: return null, row.identity(), bearing = tower.bearing, station = row.marker, id = row.id)
}
