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

data class Pin(val lat: Double, val lon: Double, val title: String, val dbm: Int? = null, val bearing: Int? = null)

@Composable
fun FieldMap(center: Pin, pins: List<Pin>, line: List<Pin> = emptyList(), onPick: ((Pin) -> Unit)? = null, orientation: Float = 0f, modifier: Modifier = Modifier.fillMaxWidth().height(320.dp)) {
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
        fun signalColor(value: Int?): Int = when {
            value == null -> android.graphics.Color.rgb(45, 125, 210)
            value >= -85 -> android.graphics.Color.rgb(30, 170, 100)
            value >= -105 -> android.graphics.Color.rgb(235, 165, 35)
            else -> android.graphics.Color.rgb(220, 65, 65)
        }
        if (line.size >= 2) {
            val stride = (line.size / 200).coerceAtLeast(1)
            val route = line.filterIndexed { index, _ -> index % stride == 0 || index == line.lastIndex }
            route.zipWithNext().forEach { (a, b) ->
                map.overlays.add(Polyline(map).apply {
                    setPoints(listOf(GeoPoint(a.lat, a.lon), GeoPoint(b.lat, b.lon)))
                    outlinePaint.color = signalColor(a.dbm)
                    outlinePaint.strokeWidth = 7f
                })
            }
        }
        pins.forEach { p ->
            p.bearing?.let { bearing ->
                val origin = GeoPoint(p.lat, p.lon)
                val outline = listOf(origin) + (bearing - 30..bearing + 30 step 5).map { origin.destinationPoint(500.0, it.toDouble()) } + origin
                map.overlays.add(Polygon(map).apply {
                    points = outline
                    fillPaint.color = 0x304B83BD
                    outlinePaint.color = 0x804B83BD.toInt()
                })
            }
            map.overlays.add(Marker(map).apply {
            position = GeoPoint(p.lat,p.lon); title = p.title
            if (p.dbm != null) {
                val bitmap = android.graphics.Bitmap.createBitmap(28, 28, android.graphics.Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = signalColor(p.dbm) }
                canvas.drawCircle(14f, 14f, 12f, paint)
                icon = android.graphics.drawable.BitmapDrawable(ctx.resources, bitmap)
            }
            setAnchor(Marker.ANCHOR_CENTER,Marker.ANCHOR_BOTTOM)
        }) }
        map.invalidate()
    }
    Column(modifier) {
    AndroidView(factory = { map }, update = { if (it.mapOrientation != orientation) it.mapOrientation = orientation }, modifier = Modifier.fillMaxWidth().weight(1f))
    if (line.any { it.dbm != null }) Text("Сигнал dBm · зелёный ≥ −85 · жёлтый ≥ −105 · красный < −105", style = MaterialTheme.typography.labelSmall)
    if (pins.any { it.bearing != null }) Text("Секторы схематичные, угол 60°", style = MaterialTheme.typography.labelSmall)
    Text("© OpenStreetMap contributors", style = MaterialTheme.typography.labelSmall)
    }
}

