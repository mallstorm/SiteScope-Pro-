package pro.sitescope.app.satellite

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.view.Surface
import android.view.WindowManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface as Panel
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.tan

data class Attitude(
    val heading: Double?, val lensHeading: Double?, val lensElevation: Double, val accuracy: Int,
    val matrix: FloatArray, val rotation: Int, val correction: Double, val timestamp: Long, val headingError: Double?
)

@Suppress("DEPRECATION")
@Composable
fun attitude(offset: Double, smooth: Boolean, filter: Float = 0.3f, precise: Boolean = false): Attitude? {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val currentOffset by rememberUpdatedState(offset)
    val smoothing by rememberUpdatedState(smooth)
    val smoothingFactor by rememberUpdatedState(filter)
    var reading by remember { mutableStateOf<Attitude?>(null) }
    DisposableEffect(ctx, owner, precise) {
        val manager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val windowManager = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val vector = manager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val accelerometer = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnetometer = manager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val interval = if (precise) 30_000_000L else 60_000_000L
        var gravity: FloatArray? = null
        var magnetic: FloatArray? = null
        var magneticAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE
        var last = 0L
        var previous: Attitude? = null
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (sensor?.type == Sensor.TYPE_MAGNETIC_FIELD) magneticAccuracy = accuracy
            }

            override fun onSensorChanged(event: SensorEvent) {
                val vectorReading = event.sensor.type == Sensor.TYPE_ROTATION_VECTOR
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> gravity = event.values.clone()
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        magnetic = event.values.clone()
                        magneticAccuracy = event.accuracy
                    }
                }
                if (event.timestamp - last < interval) return
                val matrix = FloatArray(9)
                if (vectorReading) SensorManager.getRotationMatrixFromVector(matrix, event.values)
                else if (!SensorManager.getRotationMatrix(matrix, null, gravity ?: return, magnetic ?: return)) return
                last = event.timestamp
                val rotation = windowManager.defaultDisplay.rotation
                val (east, north) = when (rotation) {
                    Surface.ROTATION_90 -> -matrix[0] to -matrix[3]
                    Surface.ROTATION_180 -> -matrix[1] to -matrix[4]
                    Surface.ROTATION_270 -> matrix[0] to matrix[3]
                    else -> matrix[1] to matrix[4]
                }
                val correction = currentOffset
                fun heading(e: Float, n: Float, old: Double?): Double? {
                    if (hypot(e, n) < 0.02f) return null
                    val value = Math.toDegrees(atan2(e, n).toDouble()) + correction
                    val filtered = if (smoothing && old != null) {
                        old + smoothingFactor.coerceIn(0.1f, 1f) * wrapped(value - old)
                    } else value
                    return (filtered % 360 + 360) % 360
                }
                val headingError = if (vectorReading) event.values.getOrNull(4)
                    ?.takeIf { it.isFinite() && it >= 0f }?.let { Math.toDegrees(it.toDouble()) } else null
                val next = Attitude(
                    heading(east, north, previous?.heading), heading(-matrix[2], -matrix[5], previous?.lensHeading),
                    Math.toDegrees(asin((-matrix[8]).coerceIn(-1f, 1f).toDouble())),
                    if (vectorReading) event.accuracy else magneticAccuracy,
                    matrix, rotation, correction, event.timestamp, headingError
                )
                reading = next
                previous = next
            }
        }
        fun start() {
            val rate = if (precise) SensorManager.SENSOR_DELAY_GAME else SensorManager.SENSOR_DELAY_UI
            if (vector != null && manager.registerListener(listener, vector, rate)) return
            if (accelerometer != null && magnetometer != null) {
                manager.registerListener(listener, accelerometer, rate)
                manager.registerListener(listener, magnetometer, rate)
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) start()
            if (event == Lifecycle.Event.ON_PAUSE) {
                manager.unregisterListener(listener)
                gravity = null; magnetic = null; last = 0L; previous = null; reading = null
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            manager.unregisterListener(listener)
            reading = null
        }
    }
    return reading
}

private data class CameraLens(val widthRatio: Float, val heightRatio: Float, val orientation: Int)

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
private fun lensFor(cameraInfo: CameraInfo): CameraLens? = runCatching {
    val info = Camera2CameraInfo.from(cameraInfo)
    val size = requireNotNull(info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE))
    val focal = requireNotNull(info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS))
        .first { it > 0f && it.isFinite() }
    CameraLens(size.width / focal, size.height / focal, info.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90)
}.getOrNull()

@Composable
internal fun AirVisor(target: DishAngles, reading: Attitude?, modifier: Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val haptic = LocalHapticFeedback.current
    val view = remember(ctx) {
        PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var streaming by remember { mutableStateOf(false) }
    var lens by remember { mutableStateOf<CameraLens?>(null) }
    var cameraError by remember { mutableStateOf("") }
    DisposableEffect(owner, view) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        val preview = Preview.Builder().build()
        val streamObserver = Observer<PreviewView.StreamState> { streaming = it == PreviewView.StreamState.STREAMING }
        view.previewStreamState.observe(owner, streamObserver)
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            if (!disposed) runCatching {
                val active = future.get()
                provider = active
                preview.surfaceProvider = view.surfaceProvider
                lens = lensFor(active.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview).cameraInfo)
            }.onFailure { cameraError = "Задняя камера недоступна" }
        }, ContextCompat.getMainExecutor(ctx))
        onDispose {
            disposed = true
            view.previewStreamState.removeObserver(streamObserver)
            provider?.unbind(preview)
            streaming = false
        }
    }

    val aim = reading?.let { cameraAim(target, it.matrix, it.rotation, it.correction) }
    val quality = reading?.let { reliableAim(it.accuracy, it.headingError) } == true
    var lock by remember(target) { mutableStateOf(AimLock()) }
    LaunchedEffect(target, reading?.timestamp, streaming, quality) {
        lock = advanceAimLock(lock, aim?.error, streaming && quality, reading?.timestamp ?: 0L)
    }
    val aligned = lock.aligned && streaming && quality && aim != null && aim.error <= AIM_RELEASE_DEGREES
    LaunchedEffect(aligned) { if (aligned) haptic.performHapticFeedback(HapticFeedbackType.LongPress) }

    val close = aim != null && aim.error <= NEAR_AIM_DEGREES
    val accent = when {
        aligned -> Color(0xff5af28a)
        close -> Color(0xffffcc66)
        else -> Color(0xffff6868)
    }
    Box(modifier.background(Color(0xff152630)), contentAlignment = Alignment.Center) {
        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val camera = lens
            if (streaming && aim != null && aim.forward > 0.001) {
                val focalPixels = if (camera != null && reading != null) {
                    val swapped = (camera.orientation - reading.rotation * 90 + 360) % 180 == 90
                    val width = if (swapped) camera.heightRatio else camera.widthRatio
                    val height = if (swapped) camera.widthRatio else camera.heightRatio
                    max(size.width / width, size.height / height)
                } else (size.minDimension / (2 * tan(Math.toRadians(27.5)))).toFloat()
                val dot = c + Offset((focalPixels * aim.right / aim.forward).toFloat(), (-focalPixels * aim.up / aim.forward).toFloat())
                if (dot.x in 0f..size.width && dot.y in 0f..size.height) {
                    drawCircle(Color.Black.copy(alpha = 0.74f), 8.dp.toPx(), dot, style = Stroke(4.dp.toPx()))
                    drawCircle(accent, 8.dp.toPx(), dot, style = Stroke(2.dp.toPx()))
                    drawCircle(accent, 2.3.dp.toPx(), dot)
                }
            }
            val gap = 14.dp.toPx()
            val reach = 28.dp.toPx()
            for (axis in listOf(Offset(1f, 0f), Offset(0f, 1f))) {
                for (sign in listOf(-1f, 1f)) {
                    val start = c + axis * (sign * gap)
                    val end = c + axis * (sign * reach)
                    drawLine(Color.Black.copy(alpha = 0.7f), start, end, 4.dp.toPx(), StrokeCap.Round)
                    drawLine(accent, start, end, 2.dp.toPx(), StrokeCap.Round)
                }
            }
            drawCircle(accent, 12.dp.toPx(), c, style = Stroke(2.dp.toPx()))
        }

        Panel(Modifier.align(Alignment.TopStart).padding(10.dp), color = Color.Black.copy(alpha = 0.68f),
            shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text("Цель · азимут ${degrees(target.azimuth)} · подъём ${degrees(target.elevation)}", color = Color.White,
                    style = MaterialTheme.typography.labelMedium)
                if (reading != null) Text("Камера · азимут ${degrees(reading.lensHeading)} · подъём ${degrees(reading.lensElevation)}",
                    color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }

        Panel(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
            color = Color.Black.copy(alpha = 0.79f), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                val title = when {
                    cameraError.isNotBlank() -> cameraError
                    !streaming -> "Запускаю камеру…"
                    aim == null -> "Жду данные компаса и наклона"
                    aligned -> "Направление совмещено"
                    close -> "Точная доводка · ${degrees(aim.error)}"
                    else -> "Отклонение от цели · ${degrees(aim.error)}"
                }
                Text(title, color = if (aligned || close) accent else Color.White, style = MaterialTheme.typography.titleSmall)
                if (streaming && aim != null) Text(guidance(aim, aligned, quality), color = Color.White,
                    style = MaterialTheme.typography.bodyMedium)
                if (streaming && reading != null && !quality) {
                    Text("Точность компаса снижена${reading.headingError?.let { " · ±${degrees(it)}" }.orEmpty()}. Отойдите от металла или откалибруйте датчик.",
                        color = Color(0xffffcc66), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

private fun guidance(aim: CameraAim, aligned: Boolean, quality: Boolean): String {
    if (aligned) return "Удерживайте телефон. Сигнал проверьте по ресиверу."
    if (aim.forward <= 0.0) return "Цель за камерой. Развернитесь к нужному азимуту."
    val hints = buildList {
        if (abs(aim.horizontal) >= 0.3) add("${if (aim.horizontal > 0) "вправо" else "влево"} ${degrees(abs(aim.horizontal))}")
        if (abs(aim.vertical) >= 0.3) add("${if (aim.vertical > 0) "выше" else "ниже"} ${degrees(abs(aim.vertical))}")
    }
    if (aim.error <= 1.0 && quality) return "Удерживайте телефон для подтверждения."
    return hints.joinToString(" · ").replaceFirstChar { it.uppercase() }.ifEmpty { "Удерживайте направление." }
}
