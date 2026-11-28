package pro.sitescope.app.radio

import android.graphics.Canvas
import android.graphics.Color
import android.widget.FrameLayout
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import pro.sitescope.app.R
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.*

data class Pin(val lat: Double, val lon: Double, val title: String, val dbm: Int? = null, val bearing: Int? = null, val isLocation: Boolean = false)

enum class FieldMapLayer(val title: String) {
    Streets("Схема"), Satellite("Спутник"), Hybrid("Гибрид")
}

@Composable
internal fun FieldMapLayers(layer: FieldMapLayer, onSelect: (FieldMapLayer) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FieldMapLayer.entries.forEach { option ->
            FilterChip(selected = layer == option, onClick = { onSelect(option) }, label = { Text(option.title) })
        }
    }
}

private fun esriTiles(name: String, layer: String, extension: String) =
    object : OnlineTileSourceBase(name, 0, 19, 256, extension, arrayOf(
        "https://server.arcgisonline.com/ArcGIS/rest/services/$layer/MapServer/tile/"
    )) {
        override fun getTileURLString(tile: Long) =
            "$baseUrl${MapTileIndex.getZoom(tile)}/${MapTileIndex.getY(tile)}/${MapTileIndex.getX(tile)}"
    }

private val imagery = esriTiles("EsriWorldImagery", "World_Imagery", ".jpg")
private val roads = esriTiles("EsriWorldTransportation", "Reference/World_Transportation", ".png")
private val labels = esriTiles("EsriWorldPlaces", "Reference/World_Boundaries_and_Places", ".png")

@Composable
fun FieldMap(center: Pin, pins: List<Pin>, line: List<Pin> = emptyList(), onPick: ((Pin) -> Unit)? = null, orientation: Float = 0f, modifier: Modifier = Modifier.fillMaxWidth().height(320.dp), layer: FieldMapLayer = FieldMapLayer.Streets, recenter: Int = 0, initialZoom: Double = 15.0, followCenter: Boolean = true) {
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
            setVerticalMapRepetitionEnabled(false)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.ALWAYS)
            controller.setZoom(initialZoom)
        }
    }
    val references = remember(map) {
        listOf(roads, labels).map { source ->
            val provider = MapTileProviderBasic(ctx.applicationContext, source)
            provider.setTileRequestCompleteHandler(map.tileRequestCompleteHandler)
            TilesOverlay(provider, ctx).apply {
                loadingBackgroundColor = Color.TRANSPARENT
                loadingLineColor = Color.TRANSPARENT
                isVerticalWrapEnabled = false
            }
        }
    }
    DisposableEffect(map, references) {
        map.onResume()
        onDispose {
            references.forEach { map.overlays.remove(it); it.onDetach(map) }
            map.onPause()
            map.onDetach()
        }
    }
    LaunchedEffect(layer) {
        map.setTileSource(if (layer == FieldMapLayer.Streets) TileSourceFactory.MAPNIK else imagery)
        map.controller.setZoom(map.zoomLevelDouble.coerceIn(map.minZoomLevel, map.maxZoomLevel))
        map.invalidate()
    }
    LaunchedEffect(initialZoom) { map.controller.setZoom(initialZoom) }
    LaunchedEffect(map, recenter, center.lat.takeIf { followCenter }, center.lon.takeIf { followCenter }) {
        map.controller.setCenter(GeoPoint(center.lat, center.lon))
    }
    LaunchedEffect(pins, line, onPick != null, layer) {
        map.overlays.clear()
        if (layer == FieldMapLayer.Hybrid) map.overlays.addAll(references)
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
                position = GeoPoint(p.lat, p.lon)
                title = p.title
                if (p.isLocation) {
                    icon = ContextCompat.getDrawable(ctx, R.drawable.ic_map_location_crystal)
                } else if (p.dbm != null) {
                    val bitmap = android.graphics.Bitmap.createBitmap(28, 28, android.graphics.Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bitmap)
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = signalColor(p.dbm) }
                    canvas.drawCircle(14f, 14f, 12f, paint)
                    icon = android.graphics.drawable.BitmapDrawable(ctx.resources, bitmap)
                }
                setAnchor(Marker.ANCHOR_CENTER, if (p.dbm != null && !p.isLocation) Marker.ANCHOR_CENTER else Marker.ANCHOR_BOTTOM)
            })
        }
        map.invalidate()
    }
    Column(modifier.clipToBounds()) {
        AndroidView(
            factory = {
                // MapView rotates its drawing canvas. Clip in the fixed parent coordinate space.
                object : FrameLayout(it) {
                    override fun dispatchDraw(canvas: Canvas) {
                        val saved = canvas.save()
                        try {
                            canvas.clipRect(0, 0, width, height)
                            super.dispatchDraw(canvas)
                        } finally {
                            canvas.restoreToCount(saved)
                        }
                    }
                }.apply {
                    clipChildren = true
                    addView(map, FrameLayout.LayoutParams(-1, -1))
                }
            },
            update = { if (map.mapOrientation != orientation) map.setMapOrientation(orientation, true) },
            modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds()
        )
        if (line.any { it.dbm != null }) Text("Сигнал dBm · зелёный ≥ −85 · жёлтый ≥ −105 · красный < −105", style = MaterialTheme.typography.labelSmall)
        if (pins.any { it.bearing != null }) Text("Секторы схематичные, угол 60°", style = MaterialTheme.typography.labelSmall)
        val attribution = when (layer) {
            FieldMapLayer.Streets -> "© OpenStreetMap contributors"
            FieldMapLayer.Satellite -> "Снимки © Esri, Vantor, Earthstar Geographics и GIS User Community"
            FieldMapLayer.Hybrid -> "Снимки © Esri и поставщики · дороги и подписи © Esri, HERE и GIS User Community"
        }
        Text(attribution, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}
