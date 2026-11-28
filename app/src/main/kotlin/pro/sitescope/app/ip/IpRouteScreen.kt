@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package pro.sitescope.app.ip

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import pro.sitescope.app.ExposureStore
import pro.sitescope.app.radio.FieldMap
import pro.sitescope.app.radio.FieldMapLayer
import pro.sitescope.app.radio.FieldMapLayers
import pro.sitescope.app.radio.Pin
import pro.sitescope.app.radio.allowed
import pro.sitescope.app.radio.currentFix
import pro.sitescope.app.radio.locationPermissions

@Composable
internal fun IpRouteScreen(vm: IpViewModel, info: IpInfo, dismiss: () -> Unit) {
    val context = LocalContext.current
    var layer by rememberSaveable { mutableStateOf(FieldMapLayer.Streets) }
    var location by remember { mutableStateOf<Pin?>(null) }
    var locate by remember { mutableIntStateOf(0) }
    var locating by remember { mutableStateOf(false) }
    var locationFailed by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) locate++ else locationFailed = true
    }
    LaunchedEffect(locate) {
        if (!locationPermissions.any(context::allowed)) return@LaunchedEffect
        locating = true
        val fix = currentFix(context)
        location = fix?.let { Pin(it.latitude, it.longitude, "Моё положение", isLocation = true) }
        locating = false
        locationFailed = fix == null
    }
    val online = remember(info.ip) { mutableStateMapOf<String, IpMapLocation>() }
    val destination = remember(info) {
        if (info.hasGeo) IpMapLocation.of(info.lat, info.lon, info.city, info.country) else null
    }
    LaunchedEffect(info.ip) {
        val attempted = mutableSetOf<String>()
        snapshotFlow {
            val present = vm.hopLocations
            val addresses = vm.hops.map { it.address }.filter { it.isNotBlank() }.toMutableList()
            if (destination == null) addresses.add(0, info.ip)
            addresses.distinct().filter { it !in present }
        }.collect { addresses ->
            addresses.filter { ExposureStore.publicAddress(it) && attempted.add(it) }.chunked(4).forEach { batch ->
                val geo = coroutineScope { batch.map { ip -> async { ip to vm.locateHop(ip) } }.awaitAll() }
                geo.forEach { (ip, item) -> if (item != null) online[ip] = item }
            }
        }
    }
    val geo = online.toMap() + vm.hopLocations + listOfNotNull(destination?.let { info.ip to it }).toMap()
    val scene = remember(location, info, vm.hops, geo, vm.traceOrigin?.vpn) {
        IpRouteGeometry.scene(location, info, vm.hops, geo, vm.traceOrigin?.vpn == true)
    }
    var fit by remember { mutableIntStateOf(0) }

    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = dismiss) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
                    Column(Modifier.weight(1f)) {
                        Text("Карта маршрута", style = MaterialTheme.typography.titleMedium)
                        Text(info.ip, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                    IconButton(
                        onClick = {
                            if (locationPermissions.any(context::allowed)) locate++ else permission.launch(locationPermissions)
                        },
                        enabled = !locating,
                    ) { Icon(Icons.Rounded.MyLocation, "Моё положение") }
                }
                FieldMapLayers(layer) { layer = it }
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    val viewport = remember(scene, maxWidth, maxHeight) {
                        IpRouteGeometry.viewport(scene, maxWidth.value.toDouble(), maxHeight.value.toDouble())
                    }
                    FieldMap(
                        center = viewport.center, pins = scene.nodes.map { it.pin }, arcs = scene.arcs,
                        modifier = Modifier.fillMaxSize(), layer = layer, recenter = fit, initialZoom = viewport.zoom,
                    )
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (locationFailed) "Моё положение недоступно" else if (locating) "Определяем положение…" else "Геопозиция узлов по IP приблизительная",
                        Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    TextButton(onClick = { fit++ }) { Text("Весь маршрут") }
                }
                if (scene.arcs.any { it.estimated }) Text(
                    "Пунктир соединяет известные точки через участок без измеренных координат.",
                    Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.labelSmall,
                )
                HorizontalDivider(Modifier.padding(top = 4.dp))
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 194.dp), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Переходы", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            TextButton(onClick = vm::runTrace) { Text(if (vm.traceRunning) "Остановить" else "Повторить") }
                        }
                        TraceStatus(vm)
                    }
                    items(vm.hops, key = { it.ttl }) { hop -> TraceHopRow(hop, geo[hop.address]) }
                }
            }
        }
    }
}

@Composable
internal fun TraceStatus(vm: IpViewModel) {
    val origin = vm.traceOrigin
    if (origin != null) {
        val local = origin.local.takeIf { it.isNotEmpty() }?.let { "IP устройства $it" }
        val gateway = origin.gateway.takeIf { it.isNotEmpty() }?.let { "Шлюз $it" }
        if (local != null || gateway != null) Text(listOfNotNull(local, gateway).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        if (origin.tunnel.isNotEmpty()) Text("IP в туннеле ${origin.tunnel}", style = MaterialTheme.typography.bodySmall)
        if (origin.vpn) Text("Маршрут через VPN. Туннель может скрывать промежуточные узлы.", style = MaterialTheme.typography.bodySmall)
    }
    if (vm.traceNote.isNotBlank()) Text(vm.traceNote, style = MaterialTheme.typography.bodySmall)
    if (vm.traceRunning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
}

@Composable
internal fun TraceHopRow(hop: IpTools.Hop, geo: IpMapLocation?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text("${hop.ttl}", Modifier.width(28.dp), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        Column(Modifier.weight(1f)) {
            Text(hop.address.ifBlank { "Нет ответа" }, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            val place = geo?.place.orEmpty()
            val details = listOfNotNull("Назначение".takeIf { hop.reached }, place.takeIf { it.isNotBlank() })
            if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
        }
        if (hop.ms > 0) Text("%.1f мс".format(hop.ms), style = MaterialTheme.typography.labelSmall)
    }
}
