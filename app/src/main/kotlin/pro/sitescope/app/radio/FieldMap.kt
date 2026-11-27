package pro.sitescope.app.radio

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.*

data class Pin(val lat: Double, val lon: Double, val title: String)

@Composable
fun FieldMap(center: Pin, pins: List<Pin>, line: List<Pin> = emptyList(), onPick: ((Pin) -> Unit)? = null, orientation: Float = 0f) {
    val ctx = LocalContext.current
    val pick by rememberUpdatedState(onPick)
    val map = remember(ctx) {
        Configuration.getInstance().apply {
            userAgentValue = "SiteScopePro/2.0 (github.com/mallstorm/SiteScope-Pro-)"
            osmdroidBasePath = java.io.File(ctx.cacheDir,"field-map")
            osmdroidTileCache = java.io.File(osmdroidBasePath,"tiles")
        }
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            controller.setZoom(15.0)
        }
    }
    DisposableEffect(map) { map.onResume(); onDispose { map.onPause(); map.onDetach() } }
    LaunchedEffect(center.lat, center.lon) { map.controller.setCenter(GeoPoint(center.lat,center.lon)) }
    LaunchedEffect(pins, line, onPick != null) {
        map.overlays.clear()
        if (onPick != null) map.overlays.add(MapEventsOverlay(object: MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint) = false
            override fun longPressHelper(p: GeoPoint): Boolean { pick?.invoke(Pin(p.latitude,p.longitude,"Точка установки")); return true }
        }))
        if (line.size >= 2) map.overlays.add(Polyline(map).apply { setPoints(line.map { GeoPoint(it.lat,it.lon) }) })
        pins.forEach { p -> map.overlays.add(Marker(map).apply {
            position = GeoPoint(p.lat,p.lon); title = p.title; setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM)
        }) }
        map.invalidate()
    }
    AndroidView(factory = { map }, update = { if (it.mapOrientation != orientation) it.mapOrientation = orientation }, modifier = Modifier.fillMaxWidth().height(300.dp))
    Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall)
}

