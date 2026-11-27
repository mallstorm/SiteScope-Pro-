package pro.sitescope.app.satellite

import android.hardware.*
import android.content.Context
import android.view.Surface
import android.view.WindowManager
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlin.math.*

data class Attitude(val heading: Double?, val lensHeading: Double?, val lensElevation: Double, val accuracy: Int, val matrix:FloatArray, val rotation:Int, val correction:Double)

@Suppress("DEPRECATION")
@Composable
fun attitude(offset: Double, smooth: Boolean, filter: Float = 0.3f): Attitude? {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var reading by remember { mutableStateOf<Attitude?>(null) }
    DisposableEffect(owner, offset, smooth, filter) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val vector = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        var gravity: FloatArray? = null
        var magnetic: FloatArray? = null
        var last = 0L
        var previous: Attitude? = null
        val listener = object : SensorEventListener {
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            override fun onSensorChanged(event: SensorEvent) {
                when(event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> gravity = event.values.clone()
                    Sensor.TYPE_MAGNETIC_FIELD -> magnetic = event.values.clone()
                }
                if (event.timestamp-last < 60_000_000L) return
                val m = FloatArray(9)
                if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) SensorManager.getRotationMatrixFromVector(m,event.values)
                else if (!SensorManager.getRotationMatrix(m,null,gravity ?: return,magnetic ?: return)) return
                last = event.timestamp
                val rotation = (ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
                val (east,north) = when(rotation) {
                    Surface.ROTATION_90 -> -m[0] to -m[3]
                    Surface.ROTATION_180 -> -m[1] to -m[4]
                    Surface.ROTATION_270 -> m[0] to m[3]
                    else -> m[1] to m[4]
                }
                fun heading(e: Float,n: Float,old: Double?): Double? {
                    if (hypot(e,n) < 0.02f) return null
                    val value = Math.toDegrees(atan2(e,n).toDouble())+offset
                    val filtered = if(smooth && old != null) old + filter.coerceIn(0.1f,1f)*wrapped(value-old) else value
                    return (filtered % 360 + 360) % 360
                }
                val elevation = Math.toDegrees(asin((-m[8]).coerceIn(-1f,1f).toDouble()))
                reading = Attitude(heading(east,north,previous?.heading), heading(-m[2],-m[5],previous?.lensHeading),elevation,event.accuracy,m.clone(),rotation,offset)
                previous = reading
            }
        }
        fun start() {
            if(vector != null) sm.registerListener(listener,vector,SensorManager.SENSOR_DELAY_UI)
            else if(acc != null && mag != null) { sm.registerListener(listener,acc,SensorManager.SENSOR_DELAY_UI); sm.registerListener(listener,mag,SensorManager.SENSOR_DELAY_UI) }
        }
        val observer = LifecycleEventObserver { _, e ->
            if(e == Lifecycle.Event.ON_RESUME) start()
            if(e == Lifecycle.Event.ON_PAUSE) { sm.unregisterListener(listener); previous = null; reading = null }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); sm.unregisterListener(listener); reading = null }
    }
    return reading
}

@Composable
fun Sight(target: DishAngles, reading: Attitude?, camera: Boolean, modifier: Modifier = Modifier.fillMaxWidth().height(if(camera) 290.dp else 150.dp)) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val bearing = if(camera) reading?.lensHeading else reading?.heading
    val delta = bearing?.let { b -> target.azimuth?.let { wrapped(it-b) } }
    val lens=remember(ctx) {
        runCatching {
            val cm=ctx.getSystemService(android.hardware.camera2.CameraManager::class.java)
            val c=cm.cameraIdList.map(cm::getCameraCharacteristics).first { it.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)==android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK }
            val size=c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)!!
            val focal=c.get(android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)!!.first()
            val orientation=c.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
            Triple(size.width/focal,size.height/focal,orientation)
        }.getOrNull()
    }
    var cameraError by remember { mutableStateOf("") }
    Box(modifier.background(Color(0xff152630)),contentAlignment=Alignment.Center) {
        if(camera) {
            val view = remember(ctx) { PreviewView(ctx).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
            AndroidView(factory = { view },modifier=Modifier.fillMaxSize())
            DisposableEffect(owner,view) {
                var disposed = false
                var active: ProcessCameraProvider? = null
                val preview = Preview.Builder().build()
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    if (!disposed) runCatching {
                        active = future.get()
                        preview.surfaceProvider = view.surfaceProvider
                        active!!.bindToLifecycle(owner,CameraSelector.DEFAULT_BACK_CAMERA,preview)
                    }.onFailure { cameraError = "Задняя камера недоступна" }
                },ContextCompat.getMainExecutor(ctx))
                onDispose { disposed=true; active?.unbind(preview) }
            }
        }
        Canvas(Modifier.fillMaxSize()) {
            val c=center
            if(camera) {
                // ENU target projected into the rear camera axes, including handset roll.
                if(reading!=null && target.azimuth!=null && lens!=null) {
                    val az=Math.toRadians(target.azimuth-reading.correction)
                    val el=Math.toRadians(target.elevation)
                    val east=cos(el)*sin(az);val north=cos(el)*cos(az);val up=sin(el)
                    fun axis(i:Int)=reading.matrix[i]*east+reading.matrix[i+3]*north+reading.matrix[i+6]*up
                    val x=axis(0);val y=axis(1);val forward= -axis(2)
                    val (right,top)=when(reading.rotation){
                        Surface.ROTATION_90 -> y to -x
                        Surface.ROTATION_180 -> -x to -y
                        Surface.ROTATION_270 -> -y to x
                        else -> x to y
                    }
                    val swapped=(lens.third-reading.rotation*90+360)%180==90
                    val scale=max(size.width/(if(swapped) lens.second else lens.first),size.height/(if(swapped) lens.first else lens.second))
                    if(forward>0.001){
                        val dot=c+Offset((scale*right/forward).toFloat(),(-scale*top/forward).toFloat())
                        if(dot.x in 0f..size.width && dot.y in 0f..size.height){
                            drawCircle(Color(0xffffc857),9.dp.toPx(),dot,style=Stroke(3.dp.toPx()))
                        }
                    }
                }
                drawCircle(Color(0xffff6060),12.dp.toPx(),c,style=Stroke(2.dp.toPx()))
                drawLine(Color(0xffff6060),Offset(0f,c.y),Offset(size.width,c.y),1.dp.toPx())
                drawLine(Color(0xffff6060),Offset(c.x,0f),Offset(c.x,size.height),1.dp.toPx())
            } else {
                val r=size.minDimension*0.36f
                drawCircle(Color.Gray,r,c,style=Stroke(2f))
                drawLine(Color.White,c,c+Offset(0f,-r),3.dp.toPx())
                delta?.let {
                    val a=Math.toRadians(it)
                    drawLine(Color(0xff58d8bd),c,c+Offset((r*sin(a)).toFloat(),(-r*cos(a)).toFloat()),4.dp.toPx())
                }
            }
        }
        if(camera) Column(Modifier.align(Alignment.TopStart).padding(10.dp).background(Color.Black.copy(alpha=.65f)).padding(8.dp)) {
            Text("Цель · азимут ${degrees(target.azimuth)} · подъём ${degrees(target.elevation)}",color=Color.White,style=MaterialTheme.typography.labelMedium)
            Text("Камера · азимут ${degrees(reading?.lensHeading)} · подъём ${degrees(reading?.lensElevation)}",color=Color.White,style=MaterialTheme.typography.labelSmall)
        }
        if(cameraError.isNotBlank() && camera) Text(cameraError,color=Color.White)
    }
    when {
        reading == null -> Text("Нет данных о направлении. Используйте карту или внешний компас.")
        delta != null -> {
            val turn = if (abs(delta) < 1.0) "По азимуту совмещено" else (if(delta > 0) "Вправо " else "Влево ") + degrees(abs(delta))
            val tilt = target.elevation-reading.lensElevation
            Text(if(camera) "$turn · ${if(tilt>0) "выше" else "ниже"} ${degrees(abs(tilt))}" else "Курс ${degrees(bearing)} · $turn")
        }
        else -> Text("Направление в этом положении неопределённо")
    }
    if(reading?.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) Text("Низкая точность компаса. Отойдите от металла и откалибруйте датчик.")
}

