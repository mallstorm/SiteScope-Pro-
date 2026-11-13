package pro.sitescope.app.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlin.math.roundToInt
import pro.sitescope.app.ScannerEngine
import pro.sitescope.app.SettingsStore

/** Shared settings for future network scans and IPTV gateway discovery. */
@Composable
fun ScanTuningControls(enabled: Boolean = true) {
    val context = LocalContext.current
    var workers by remember { mutableIntStateOf(SettingsStore.scannerConcurrency(context)) }
    var delay by remember { mutableIntStateOf(SettingsStore.scannerDelayMs(context)) }
    DisposableEffect(context) {
        val prefs = context.getSharedPreferences(SettingsStore.PREFS, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            workers = SettingsStore.scannerConcurrency(context)
            delay = SettingsStore.scannerDelayMs(context)
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    fun save() = SettingsStore.saveScanTuning(context, workers, delay)
    Text("Параллельных потоков: $workers", style = MaterialTheme.typography.bodyMedium)
    Slider(value = workers.toFloat(), onValueChange = { workers = it.roundToInt() }, onValueChangeFinished = { save() },
        valueRange = ScannerEngine.MIN_CONCURRENCY.toFloat()..ScannerEngine.MAX_CONCURRENCY.toFloat(), steps = 247, enabled = enabled)
    Text("Задержка между стартами проверок: $delay мс", style = MaterialTheme.typography.bodyMedium)
    Slider(value = delay.toFloat(), onValueChange = { delay = (it / 50).roundToInt() * 50 }, onValueChangeFinished = { save() },
        valueRange = 0f..ScannerEngine.MAX_DELAY_MS.toFloat(), steps = 99, enabled = enabled)
    Text("Общие настройки сканера и IPTV. Изменения применяются к новым запускам.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
