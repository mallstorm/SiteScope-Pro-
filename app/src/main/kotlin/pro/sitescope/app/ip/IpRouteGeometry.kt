package pro.sitescope.app.ip

import org.osmdroid.util.GeoPoint
import pro.sitescope.app.radio.FieldMapArc
import pro.sitescope.app.radio.Pin
import kotlin.math.*

internal data class IpMapLocation(val lat: Double, val lon: Double, val place: String) {
    companion object {
        fun of(lat: Double, lon: Double, city: String, country: String): IpMapLocation? =
            if (lat in -90.0..90.0 && lon in -180.0..180.0 && (lat != 0.0 || lon != 0.0))
                IpMapLocation(lat, lon, listOf(city, country).filter { it.isNotBlank() }.distinct().joinToString(", "))
            else null
    }
}

internal object IpRouteGeometry {
    data class Node(val ttl: Int, val pin: Pin)
    data class Scene(val nodes: List<Node>, val arcs: List<FieldMapArc>)
    data class Viewport(val center: Pin, val zoom: Double)
    private data class XY(val x: Double, val y: Double)
    private val palette = listOf(0xff26c8e6, 0xff78aaff, 0xffbe8dff, 0xffee84c1, 0xfff5af60, 0xff70d9a5).map { it.toInt() }

    fun scene(position: Pin?, info: IpInfo, hops: List<IpTools.Hop>, locations: Map<String, IpMapLocation>, vpn: Boolean): Scene {
        val nodes = buildList {
            position?.let { add(Node(0, it)) }
            val reached = hops.lastOrNull { it.reached }
            val destination = locations[info.ip]
            for (hop in hops) {
                val location = locations[hop.address] ?: if (hop.reached) destination else null
                if (location != null) {
                    val title = "${hop.ttl} · ${hop.address}" + location.place.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
                    add(Node(hop.ttl, Pin(location.lat, location.lon, title, markerLabel = "${hop.ttl}")))
                }
            }
            if (reached == null && destination != null) {
                val ttl = (hops.lastOrNull()?.ttl ?: 0) + 2
                add(Node(ttl, Pin(destination.lat, destination.lon, "Назначение · ${info.ip}", markerLabel = "Ф")))
            }
        }
        val arcs = nodes.zipWithNext().mapNotNull { (from, to) ->
            val points = arc(from.pin, to.pin)
            if (points.isEmpty()) null else FieldMapArc(
                points, palette[(to.ttl - 1).coerceAtLeast(0) % palette.size],
                to.ttl != from.ttl + 1 || (from.ttl == 0 && vpn),
            )
        }
        return Scene(nodes, arcs)
    }

    fun viewport(scene: Scene, widthDp: Double, heightDp: Double): Viewport {
        val pins = scene.nodes.map { it.pin }
        if (pins.isEmpty()) return Viewport(Pin(18.0, 0.0, ""), 2.0)
        if (pins.size == 1) return Viewport(pins.first(), 8.0)
        val points = pins.map { project(it.lat, it.lon) } +
            scene.arcs.flatMap { arc -> arc.points.map { project(it.latitude, it.longitude) } }
        val xs = points.map { it.x }.sorted()
        val gapIndex = xs.indices.maxBy { index -> if (index == xs.lastIndex) xs.first() + 1 - xs.last() else xs[index + 1] - xs[index] }
        val startX = xs[(gapIndex + 1) % xs.size]
        val endX = xs[gapIndex].let { if (it < startX) it + 1 else it }
        val minY = points.minOf { it.y }
        val maxY = points.maxOf { it.y }
        val margin = 0.74
        val zoom = min(
            log2(widthDp.coerceAtLeast(100.0) * margin / (256 * (endX - startX).coerceAtLeast(0.000001))),
            log2(heightDp.coerceAtLeast(100.0) * margin / (256 * (maxY - minY).coerceAtLeast(0.000001))),
        ).coerceIn(1.0, 13.0)
        val center = geo(XY((startX + endX) / 2, (minY + maxY) / 2))
        return Viewport(Pin(center.latitude, center.longitude, ""), zoom)
    }

    private fun arc(from: Pin, to: Pin): List<GeoPoint> {
        val a = project(from.lat, from.lon)
        val b = project(to.lat, to.lon)
        val dx = (b.x - a.x + 1.5) % 1.0 - 0.5
        val dy = b.y - a.y
        val length = hypot(dx, dy)
        if (length < 0.0000001) return emptyList()
        val bend = min(length * 0.12, 0.035)
        return (0..24).map { step ->
            val t = step / 24.0
            val offset = 4 * t * (1 - t) * bend / length
            geo(XY(a.x + dx * t - dy * offset, a.y + dy * t + dx * offset))
        }
    }

    private fun project(lat: Double, lon: Double): XY {
        val angle = lat.coerceIn(-85.05112878, 85.05112878) * PI / 180
        return XY((lon + 180) / 360, (1 - asinh(tan(angle)) / PI) / 2)
    }

    private fun geo(point: XY): GeoPoint {
        val lon = (point.x - floor(point.x)) * 360 - 180
        return GeoPoint(atan(sinh(PI * (1 - 2 * point.y.coerceIn(0.0, 1.0)))) * 180 / PI, lon)
    }
}
