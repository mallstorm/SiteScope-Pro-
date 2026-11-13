@file:OptIn(ExperimentalMaterial3Api::class)

package pro.sitescope.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import pro.sitescope.app.BackupManager
import pro.sitescope.app.ScannerEngine
import pro.sitescope.app.SettingsStore
import kotlin.math.roundToInt

/** Выбор оформления: 0 — как в системе, 1 — светлое, 2 — тёмное. */
object ThemePrefs {
    var mode by mutableIntStateOf(0)
        private set

    fun load(context: Context) { mode = context.getSharedPreferences(SettingsStore.PREFS, Context.MODE_PRIVATE).getInt("theme_mode", 0) }
    fun set(context: Context, value: Int) {
        mode = value
        context.getSharedPreferences(SettingsStore.PREFS, Context.MODE_PRIVATE).edit().putInt("theme_mode", value).apply()
    }
}

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var timeout by remember { mutableFloatStateOf(SettingsStore.scannerTimeoutMs(context).toFloat()) }
    var detect by remember { mutableStateOf(SettingsStore.scannerDetectServices(context)) }
    var confirmLarge by remember { mutableStateOf(SettingsStore.confirmLargeTasks(context)) }
    var summary by remember { mutableStateOf("") }
    var pending by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun saveScanner() = SettingsStore.saveScanner(context, timeout.roundToInt(), SettingsStore.scannerConcurrency(context), detect, confirmLarge)
    fun refresh() = scope.launch { summary = withContext(Dispatchers.IO) { try { BackupManager.currentSummary(context) } catch (e: Exception) { "" } } }
    LaunchedEffect(Unit) { refresh() }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri: Uri? ->
        if (uri != null) scope.launch {
            busy = true
            val ok = withContext(Dispatchers.IO) { try { BackupManager.exportTo(context, uri); true } catch (e: Exception) { false } }
            busy = false; toast(context, if (ok) "Копия сохранена" else "Не удалось сохранить копию")
        }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) scope.launch {
            val backup = withContext(Dispatchers.IO) { try { BackupManager.readBackup(context, uri) } catch (e: Exception) { null } }
            if (backup == null) toast(context, "Это не резервная копия SiteScope") else pending = backup
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
        SectionTitle("Оформление")
        SoftCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Как в системе", "Светлое", "Тёмное").forEachIndexed { i, title ->
                    FilterChip(selected = ThemePrefs.mode == i, onClick = { ThemePrefs.set(context, i) }, label = { Text(title) })
                }
            }
        }

        SectionTitle("Проверка сети")
        SoftCard {
            Text("Сколько ждать ответа: ${timeout.roundToInt()} мс", style = MaterialTheme.typography.bodyMedium)
            Text("Больше — надёжнее на медленных сетях, но дольше", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AppSlider(value = timeout, onValueChange = { timeout = it }, onValueChangeFinished = { saveScanner() },
                valueRange = ScannerEngine.MIN_TIMEOUT_MS.toFloat()..ScannerEngine.MAX_TIMEOUT_MS.toFloat())
            ScanTuningControls()
            SwitchRow("Определять, что за сервис", "По безопасным признакам в ответе", detect) { detect = it; saveScanner() }
            SwitchRow("Спрашивать перед большой проверкой", "Если адресов и портов очень много", confirmLarge) { confirmLarge = it; saveScanner() }
        }

        SectionTitle("Резервная копия")
        Text("Копия включает ТВ-каталог и избранное. Пароли и токены из ссылок удаляются: после переноса потребуется повторный вход.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SoftCard {
            if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
            Text("В копию попадают проекты, история, плейлисты, подключения и настройки. Пароли и ключи доступа в неё не входят.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { export.launch(BackupManager.suggestedFileName()) }, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Сохранить") }
                OutlinedButton(onClick = { restore.launch(arrayOf("application/json", "*/*")) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Восстановить") }
            }
        }

        SectionTitle("Прочее")
        SoftCard {
            TextButton(onClick = {
                context.getSharedPreferences("protocol_router_preferences", Context.MODE_PRIVATE).edit().clear().apply()
                toast(context, "Выбор приложений сброшен")
            }) { Text("Сбросить выбор «чем открывать» для устройств") }
            TextButton(onClick = {
                try { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))) } catch (e: Exception) { }
            }) { Text("Разрешения и уведомления в системе") }
        }
        val version = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (e: Exception) { "" }
        Text("SiteScope Pro $version · формат копии ${BackupManager.SCHEMA_VERSION}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }

    pending?.let { backup ->
        val about = remember(backup) { try { BackupManager.describe(backup) } catch (e: Exception) { "" } }
        ConfirmDialog("Восстановить из копии?", "$about\n\nТекущие данные будут заменены.", "Восстановить", { pending = null }) {
            pending = null
            scope.launch {
                busy = true
                val ok = withContext(Dispatchers.IO) { try { BackupManager.restore(context, backup); true } catch (e: Exception) { false } }
                busy = false; ThemePrefs.load(context); refresh()
                toast(context, if (ok) "Данные восстановлены" else "Не удалось восстановить данные")
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
