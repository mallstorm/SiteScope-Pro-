@file:OptIn(ExperimentalLayoutApi::class)

package pro.sitescope.app.anon

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import pro.sitescope.app.WebClientActivity
import pro.sitescope.app.ui.*
import java.io.File

private fun browser(context: Context, configure: Intent.() -> Unit) {
    context.startActivity(Intent(context, WebClientActivity::class.java).apply(configure))
}

private fun openAddress(context: Context, input: String) {
    val url = AnonRouting.normalize(input)
    if (url.isBlank()) return
    browser(context) { putExtra("open_url", url) }
}

private fun savedTabs(context: Context): Int = try {
    JSONArray(context.getSharedPreferences("browser_tabs", Context.MODE_PRIVATE).getString("tabs", "[]")).length()
} catch (_: Exception) { 0 }

@Composable
fun AnonScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    remember { AnonNet.init(context); 0 }
    LaunchedEffect(Unit) { AnonNet.queryI2p() }
    val state by AnonNet.state.collectAsState()
    var settings by remember { mutableStateOf(AnonNet.settings()) }
    var address by remember { mutableStateOf("") }
    var torCheck by remember { mutableStateOf("") }
    var i2pCheck by remember { mutableStateOf("") }
    var dialog by remember { mutableStateOf<String?>(null) }   // "tor" | "i2p"
    var tabsCount by remember { mutableIntStateOf(savedTabs(context)) }
    LaunchedEffect(Unit) { while (true) { tabsCount = savedTabs(context); delay(2000) } }
    fun change(value: AnonSettings) { settings = value; AnonNet.save(value) }

    val pickReseed = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val path = withContext(Dispatchers.IO) {
                try {
                    val f = File(File(context.noBackupFilesDir, "anon/i2p").apply { mkdirs() }, "reseed.su3")
                    context.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
                    f.absolutePath
                } catch (e: Exception) { null }
            }
            if (path != null) change(settings.copy(i2pReseedFile = path)) else toast(context, "Не удалось прочитать файл")
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ───── адрес и вкладки ─────
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigField(address, { address = it }, "xxxx.onion или site.i2p", modifier = Modifier.weight(1f), leading = Icons.Rounded.Language, onGo = { openAddress(context, address) })
            Button({ openAddress(context, address) }, enabled = address.isNotBlank(), contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.height(40.dp)) { Text("Открыть") }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniChip("＋ Вкладка Tor", false) { browser(context) { putExtra("new_tab_kind", "tor") } }
            MiniChip("＋ Вкладка I2P", false) { browser(context) { putExtra("new_tab_kind", "i2p") } }
            MiniChip("Вкладки: $tabsCount", tabsCount > 0) { browser(context) { putExtra("restore_tabs", true) } }
        }

        // ───── Tor ─────
        val tor = state.tor
        EngineCard(
            title = "Tor", subtitle = ".onion · анонимный доступ", icon = Icons.Rounded.Public, phase = tor.phase,
            status = tor.error ?: tor.message.takeIf { tor.phase != RunPhase.OFF } ?: "Запускается сам при открытии .onion",
            failed = tor.phase == RunPhase.FAILED, progress = tor.progress.takeIf { tor.phase == RunPhase.STARTING || tor.phase == RunPhase.BOOTSTRAPPING },
            detail = if (tor.phase.active) "Выход: ${tor.actual.title}" + if (tor.ready) " · SOCKS 127.0.0.1:${tor.port}" else "" else null,
            check = torCheck, canCheck = tor.ready,
            onToggle = { if (tor.phase.active) AnonNet.stopTor() else AnonNet.startTor() },
            onCheck = { scope.launch { torCheck = "Проверяю через Tor…"; torCheck = withContext(Dispatchers.IO) { AnonNet.checkTor() } } },
            onSettings = { dialog = "tor" },
        )

        // ───── I2P ─────
        val i2p = state.i2p
        EngineCard(
            title = "I2P", subtitle = ".i2p · скрытая сеть", icon = Icons.Rounded.Hub, phase = i2p.phase,
            status = i2p.error ?: i2p.message.takeIf { i2p.phase != RunPhase.OFF } ?: "Запускается сам при открытии .i2p, первый старт — несколько минут",
            failed = i2p.phase == RunPhase.FAILED, progress = i2p.progress.takeIf { i2p.phase == RunPhase.STARTING || i2p.phase == RunPhase.BOOTSTRAPPING },
            detail = if (i2p.phase.active) "Туннели: ${i2p.inbound}/${i2p.outbound} · ${i2p.actual.title}" + if (i2p.ready) " · HTTP 127.0.0.1:${i2p.httpPort}" else "" else null,
            check = i2pCheck, canCheck = i2p.ready,
            onToggle = { if (i2p.phase.active) AnonNet.stopI2p() else AnonNet.startI2p() },
            onCheck = { scope.launch { i2pCheck = "Запрашиваю i2p-projekt.i2p (до 90 с)…"; i2pCheck = withContext(Dispatchers.IO) { AnonNet.checkI2p() } } },
            onSettings = { dialog = "i2p" },
        )

        SoftCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Весь трафик браузера через Tor", style = MaterialTheme.typography.titleSmall)
                    Text("Кроме адресов локальной сети", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(settings.torForAll, { change(settings.copy(torForAll = it)) })
            }
        }
    }

    when (dialog) {
        "tor" -> TorSettingsDialog(settings, state.tor.phase.active, ::change) { dialog = null }
        "i2p" -> I2pSettingsDialog(settings, state.i2p.phase.active, ::change, { pickReseed.launch(arrayOf("*/*")) }) { dialog = null }
    }
}

@Composable
private fun EngineCard(
    title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, phase: RunPhase, status: String, failed: Boolean,
    progress: Int?, detail: String?, check: String, canCheck: Boolean, onToggle: () -> Unit, onCheck: () -> Unit, onSettings: () -> Unit,
) {
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tones.site, tones.siteSoft)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            PhasePill(phase)
        }
        Text(status, style = MaterialTheme.typography.bodySmall, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 3, modifier = Modifier.padding(top = 8.dp))
        if (progress != null) LinearProgressIndicator(progress = { progress / 100f }, Modifier.fillMaxWidth().padding(top = 6.dp))
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        if (check.isNotBlank()) Text(check, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!phase.active) Button(onToggle, contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.height(36.dp)) { Text("Запустить") }
            else OutlinedButton(onToggle, contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.height(36.dp)) { Text("Остановить") }
            OutlinedButton(onCheck, enabled = canCheck, contentPadding = PaddingValues(horizontal = 14.dp), modifier = Modifier.height(36.dp)) { Text("Проверить") }
            Spacer(Modifier.weight(1f))
            IconButton(onSettings) { Icon(Icons.Rounded.Tune, "Настройки $title") }
        }
    }
}

@Composable
private fun TorSettingsDialog(settings: AnonSettings, running: Boolean, change: (AnonSettings) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var showLog by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    LaunchedEffect(showLog) { while (showLog) { log = withContext(Dispatchers.IO) { TorProcessService.readLog(context) }; delay(2000) } }
    AlertDialog(onDismissRequest = onClose, confirmButton = { TextButton(onClose) { Text("Готово") } }, title = { Text("Настройки Tor") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Как Tor выходит в сеть", style = MaterialTheme.typography.labelMedium)
            TransportChips(settings.torTransport, !running) { change(settings.copy(torTransport = it)) }
            Text(transportHelp(settings.torTransport, "Tor"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (settings.torTransport == AnonTransport.PROXY) OutlinedTextField(settings.torProxy, { change(settings.copy(torProxy = it.trim())) }, Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("SOCKS5 хост:порт") }, singleLine = true, isError = settings.torProxy.isNotBlank() && ProxySpec.parse(settings.torProxy) == null)
            val bridges = remember(settings.torBridges) { TorConf.parseBridges(settings.torBridges) }
            OutlinedTextField(settings.torBridges, { change(settings.copy(torBridges = it)) }, Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 2, maxLines = 5,
                label = { Text("Мосты (по одному в строке)") },
                supportingText = { Text(when {
                    settings.torBridges.isBlank() -> "Простые мосты IP:порт [отпечаток]. Без мостов — публичная сеть Tor."
                    bridges.skipped.isNotEmpty() -> "Принято: ${bridges.lines.size}. Пропущено: ${bridges.skipped.size} — obfs4, snowflake и webtunnel не поддерживаются."
                    else -> "Принято мостов: ${bridges.lines.size}"
                }) })
            TextButton({ showLog = !showLog }) { Text(if (showLog) "Скрыть журнал" else "Показать журнал") }
            if (showLog) MonoBlock(log.ifBlank { "Загрузка…" }.takeLast(4000))
        }
    })
}

@Composable
private fun I2pSettingsDialog(settings: AnonSettings, running: Boolean, change: (AnonSettings) -> Unit, pickFile: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    var showLog by remember { mutableStateOf(false) }
    var log by remember { mutableStateOf("") }
    LaunchedEffect(showLog) { while (showLog) { log = withContext(Dispatchers.IO) { I2pService.readLog(context) }; delay(2000) } }
    AlertDialog(onDismissRequest = onClose, confirmButton = { TextButton(onClose) { Text("Готово") } }, title = { Text("Настройки I2P") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Как I2P получает список узлов и выходит в сеть", style = MaterialTheme.typography.labelMedium)
            TransportChips(settings.i2pTransport, !running) { change(settings.copy(i2pTransport = it)) }
            Text(transportHelp(settings.i2pTransport, "I2P"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (settings.i2pTransport == AnonTransport.PROXY) OutlinedTextField(settings.i2pProxy, { change(settings.copy(i2pProxy = it.trim())) }, Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("SOCKS5 хост:порт") }, singleLine = true, isError = settings.i2pProxy.isNotBlank() && ProxySpec.parse(settings.i2pProxy) == null)
            OutlinedTextField(settings.i2pReseedFile, { change(settings.copy(i2pReseedFile = it.trim())) }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
                label = { Text("Reseed: файл .su3 или https-адрес") },
                supportingText = { Text("Если серверы reseed недоступны, выберите файл i2pseeds.su3 из другой сети.") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(pickFile) { Text("Выбрать файл") }
                if (settings.i2pReseedFile.isNotBlank()) OutlinedButton({ change(settings.copy(i2pReseedFile = "")) }) { Text("Сбросить") }
            }
            TextButton({ showLog = !showLog }) { Text(if (showLog) "Скрыть журнал" else "Показать журнал") }
            if (showLog) MonoBlock(log.ifBlank { "Загрузка…" }.takeLast(4000))
        }
    })
}

@Composable
private fun PhasePill(phase: RunPhase) {
    val (label, color, soft) = when (phase) {
        RunPhase.READY -> Triple("Работает", tones.good, tones.goodSoft)
        RunPhase.STARTING, RunPhase.BOOTSTRAPPING -> Triple("Запускается", tones.warn, tones.warnSoft)
        RunPhase.STOPPING -> Triple("Останавливается", tones.warn, tones.warnSoft)
        RunPhase.FAILED -> Triple("Ошибка", tones.bad, tones.badSoft)
        RunPhase.OFF -> Triple("Выключен", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh)
    }
    Pill(label, color, soft)
}

@Composable
private fun TransportChips(current: AnonTransport, enabled: Boolean, onChange: (AnonTransport) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        AnonTransport.values().forEach { t -> FilterChip(current == t, { onChange(t) }, label = { Text(t.title) }, enabled = enabled) }
    }
}

private fun transportHelp(t: AnonTransport, what: String) = when (t) {
    AnonTransport.AUTO -> "Сначала напрямую; если $what зависнет, один раз автоматически включится обход DPI."
    AnonTransport.DIRECT -> "Только прямое подключение."
    AnonTransport.DPI -> "Подключение идёт через byedpi со стратегией из раздела «DPI»."
    AnonTransport.PROXY -> "Подключение идёт через указанный SOCKS5-прокси (например, локальный порт VPN-приложения)."
}
