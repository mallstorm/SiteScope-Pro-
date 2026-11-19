@file:OptIn(androidx.camera.core.ExperimentalGetImage::class)

package pro.sitescope.app.scan

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import pro.sitescope.app.ui.tones
import pro.sitescope.app.ui.toast

/** Полноэкранный сканер QR-кодов: камера, фонарик и распознавание из картинки галереи. Распознавание — на устройстве (ML Kit). */
@Composable
fun QrScannerDialog(onResult: (String) -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ScannerBody(onResult, onClose)
    }
}

@Composable
private fun ScannerBody(onResult: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    var torch by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val done = remember { AtomicBoolean(false) }
    val scanner = remember { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_DATA_MATRIX, Barcode.FORMAT_AZTEC).build()) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { scanner.close(); executor.shutdown() } }

    fun deliver(value: String?) {
        val text = value?.trim().orEmpty()
        if (text.isNotEmpty() && done.compareAndSet(false, true)) onResult(text)
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok; denied = !ok }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) try {
            scanner.process(InputImage.fromFilePath(context, uri))
                .addOnSuccessListener { codes -> val hit = codes.firstOrNull { !it.rawValue.isNullOrBlank() }; if (hit != null) deliver(hit.rawValue) else toast(context, "QR-код на картинке не найден") }
                .addOnFailureListener { toast(context, "Не удалось прочитать картинку") }
        } catch (e: Exception) { toast(context, "Не удалось открыть картинку") }
    }
    LaunchedEffect(torch, camera) { try { camera?.cameraControl?.enableTorch(torch) } catch (e: Exception) { } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
                val preview = PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    try {
                        val provider = future.get()
                        val use = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
                        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                        analysis.setAnalyzer(executor) { proxy ->
                            val media = proxy.image
                            if (media == null || done.get()) { proxy.close(); return@setAnalyzer }
                            scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                                .addOnSuccessListener { codes -> codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.let { deliver(it.rawValue) } }
                                .addOnCompleteListener { proxy.close() }
                        }
                        provider.unbindAll()
                        camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, use, analysis)
                    } catch (e: Exception) { toast(ctx, "Камера недоступна") }
                }, ContextCompat.getMainExecutor(ctx))
                preview
            })
            DisposableEffect(Unit) { onDispose { try { ProcessCameraProvider.getInstance(context).get().unbindAll() } catch (e: Exception) { } } }
            val accent = tones.aqua
            Canvas(Modifier.fillMaxSize()) {
                val side = size.minDimension * 0.66f
                val left = (size.width - side) / 2; val top = (size.height - side) / 2 - size.height * 0.04f
                val arm = side * 0.16f; val w = 4.dp.toPx()
                fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                    drawLine(accent, Offset(x, y), Offset(x + dx * arm, y), w, StrokeCap.Round)
                    drawLine(accent, Offset(x, y), Offset(x, y + dy * arm), w, StrokeCap.Round)
                }
                corner(left, top, 1f, 1f); corner(left + side, top, -1f, 1f); corner(left, top + side, 1f, -1f); corner(left + side, top + side, -1f, -1f)
                drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(left, top), Size(side, side), CornerRadius(12.dp.toPx()), style = Stroke(1.dp.toPx()))
            }
        } else {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (denied) "Нет доступа к камере. Разрешите его в настройках или выберите картинку с QR-кодом." else "Нужно разрешение на камеру", color = Color.White, textAlign = TextAlign.Center)
                Button(onClick = { gallery.launch("image/*") }) { Text("Выбрать картинку") }
            }
        }
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Закрыть", tint = Color.White) }
            Text("Сканер QR", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { gallery.launch("image/*") }) { Icon(Icons.Rounded.Image, "Из картинки", tint = Color.White) }
            if (granted) IconButton(onClick = { torch = !torch }) { Icon(if (torch) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff, "Фонарик", tint = if (torch) tones.aqua else Color.White) }
        }
        Text(
            "Наведите на QR-код: ссылка, IP, плейлист или сервер vless:// · vmess:// · trojan://",
            color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 32.dp, vertical = 48.dp)
        )
    }
}
