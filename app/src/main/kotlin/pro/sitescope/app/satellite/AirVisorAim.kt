package pro.sitescope.app.satellite

import android.hardware.SensorManager
import android.view.Surface
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

internal data class CameraAim(val right: Double, val up: Double, val forward: Double) {
    val error = Math.toDegrees(atan2(hypot(right, up), forward))
    val horizontal = Math.toDegrees(atan2(right, forward))
    val vertical = Math.toDegrees(atan2(up, hypot(right, forward)))
}

/** Transform the satellite direction from magnetic ENU into the rear camera's display axes. */
internal fun cameraAim(target: DishAngles, matrix: FloatArray, rotation: Int, correction: Double): CameraAim? {
    if (matrix.size != 9 || matrix.any { !it.isFinite() }) return null
    val azimuth = target.azimuth ?: if (abs(target.elevation - 90.0) < 1e-6) 0.0 else return null
    val az = Math.toRadians(azimuth - correction)
    val el = Math.toRadians(target.elevation)
    val east = cos(el) * sin(az)
    val north = cos(el) * cos(az)
    val up = sin(el)
    fun axis(index: Int) = matrix[index] * east + matrix[index + 3] * north + matrix[index + 6] * up
    val x = axis(0)
    val y = axis(1)
    val forward = -axis(2)
    return when (rotation) {
        Surface.ROTATION_90 -> CameraAim(y, -x, forward)
        Surface.ROTATION_180 -> CameraAim(-x, -y, forward)
        Surface.ROTATION_270 -> CameraAim(-y, x, forward)
        else -> CameraAim(x, y, forward)
    }
}

internal const val NEAR_AIM_DEGREES = 6.0
internal const val AIM_RELEASE_DEGREES = 1.8
private const val AIM_LOCK_DEGREES = 1.0
private const val AIM_HOLD_NANOS = 300_000_000L

internal data class AimLock(val sinceNanos: Long? = null, val aligned: Boolean = false)

internal fun advanceAimLock(previous: AimLock, error: Double?, reliable: Boolean, timestamp: Long): AimLock {
    if (!reliable || error == null || !error.isFinite() || error > AIM_RELEASE_DEGREES) return AimLock()
    if (previous.aligned) return previous
    if (error > AIM_LOCK_DEGREES) return AimLock()
    val since = previous.sinceNanos?.takeIf { it <= timestamp } ?: timestamp
    return AimLock(since, timestamp - since >= AIM_HOLD_NANOS)
}

internal fun reliableAim(accuracy: Int, headingError: Double?) =
    accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM && (headingError == null || headingError in 0.0..5.0)
