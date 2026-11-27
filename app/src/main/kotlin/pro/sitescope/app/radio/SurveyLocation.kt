package pro.sitescope.app.radio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.*
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

fun Context.allowed(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
val locationPermissions = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

@SuppressLint("MissingPermission")
suspend fun currentFix(ctx: Context): Location? = withTimeoutOrNull(16_000) {
    if (!locationPermissions.any(ctx::allowed)) return@withTimeoutOrNull null
    val manager = ctx.getSystemService(LocationManager::class.java) ?: return@withTimeoutOrNull null
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
    val cached = providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
        .filter { (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) in 0..30_000_000_000L }
        .minByOrNull { if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE }
    if (cached != null || providers.isEmpty()) return@withTimeoutOrNull cached
    suspendCancellableCoroutine { cont ->
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                manager.removeUpdates(this)
                if (cont.isActive) cont.resume(location)
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Legacy callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        var registered = false
        providers.forEach {
            if (runCatching { manager.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) }.isSuccess) registered = true
        }
        cont.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
        if (!registered && cont.isActive) cont.resume(null)
    }
}

