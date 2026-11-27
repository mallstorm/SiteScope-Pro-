package pro.sitescope.app.satellite

import kotlin.math.*

data class DishAngles(val azimuth: Double?, val elevation: Double, val skew: Double?)
data class SitePoint(val lat: Double, val lon: Double)

fun wrapped(angle: Double) = ((angle + 180) % 360 + 360) % 360 - 180
fun point(lat: String, lon: String): SitePoint? {
    val a = lat.trim().replace(',', '.').toDoubleOrNull() ?: return null
    val b = lon.trim().replace(',', '.').toDoubleOrNull() ?: return null
    return if (a in -90.0..90.0 && b in -180.0..180.0) SitePoint(a, b) else null
}

/** WGS84 surface observer, nominal circular geostationary orbit; ENU in kilometres. */
fun dishAngles(site: SitePoint, orbital: Double): DishAngles {
    require(site.lat in -90.0..90.0 && site.lon in -180.0..180.0 && orbital in -180.0..180.0)
    val lat = Math.toRadians(site.lat)
    val delta = Math.toRadians(wrapped(orbital - site.lon))
    val e2 = 6.69437999014e-3
    val radius = 6378.137 / sqrt(1 - e2 * sin(lat).pow(2))
    val x = 42164.0 * cos(delta) - radius * cos(lat)
    val east = 42164.0 * sin(delta)
    val z = -radius * (1 - e2) * sin(lat)
    val north = -x * sin(lat) + z * cos(lat)
    val up = x * cos(lat) + z * sin(lat)
    val flat = hypot(east, north)
    val az = if (flat < 0.000001 || abs(site.lat) == 90.0) null else (Math.toDegrees(atan2(east, north)) + 360) % 360
    val skew = if (hypot(sin(delta) * cos(lat), sin(lat)) < 1e-10) null else
        ((Math.toDegrees(atan2(sin(delta) * cos(lat), sin(lat))) + 270) % 180) - 90
    return DishAngles(az, Math.toDegrees(atan2(up, flat)), skew)
}

