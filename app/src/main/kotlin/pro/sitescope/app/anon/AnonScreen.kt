@file:OptIn(ExperimentalLayoutApi::class)

package pro.sitescope.app.anon

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.File
import pro.sitescope.app.WebClientActivity
import pro.sitescope.app.ui.*

private val onionExamples = listOf(
    "DuckDuckGo" to "http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion",
    "Tor Project" to "http://2gzyxa5ihm7nsggfxnu52rck2vv4rvmdlkiu3zzui5du4xyclen53wid.onion",
)
private val i2pExamples = listOf("i2p-projekt.i2p", "stats.i2p", "reg.i2p")

private fun open(context: android.content.Context, input: String) {
    val url = AnonRouting.normalize(input)
    if (url.isBlank()) return
    context.startActivity(Intent(context, WebClientActivity::class.java).putExtra("open_url", url))
}

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
    var showTorLog by remember { mutableStateOf(false) }
    var torLog by remember { mutableStateOf("") }
    var showI2pLog by remember { mutableStateOf(false) }
    var i2pLog by remember { mutableStateOf("") }
    fun change(value: AnonSettings) { settings = value; AnonNet.save(value) }

    LaunchedEffect(showTorLog) { while (showTorLog) { torLog = withContext(Dispatchers.IO) { TorProcessService.readLog(context) }; delay(2000) } }
    LaunchedEffect(showI2pLog) { while (showI2pLog) { i2pLog = withContext(Dispatchers.IO) { I2pService.readLog(context) }; delay(2000) } }

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

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SoftCard {
            Text("Открыть адрес", style = MaterialTheme.typography.titleMedium)
            Text("Адреса .onion идут через Tor, .i2p — через I2P. Остальное открывается как обычно.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("xxxx.onion или site.i2p") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { open(context, address) }))
            Button({ open(context, address) }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = address.isNotBlank()) { Text("Открыть") }
            Text("Примеры", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onionExamples.forEach { (title, url) -> AssistChip(onClick = { address = url }, label = { Text("$title (.onion)") }) }
                i2pExamples.forEach { AssistChip(onClick = { address = it }, label = { Text(it) }) }
            }
        }

        // ───── Tor ─────
        SoftCard {
            val tor = state.tor
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tor (.onion)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (settings.embeddedTor) PhasePill(tor.phase)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilterChip(settings.embeddedTor, { change(settings.copy(embeddedTor = true)) }, label = { Text("Встроенный Tor") })
                FilterChip(!settings.embeddedTor, { change(settings.copy(embeddedTor = false)) }, label = { Text("Внешний (Orbot)") })
            }
            if (settings.embeddedTor) {
                Text(tor.error ?: tor.message.takeIf { tor.phase != RunPhase.OFF } ?: "Tor запускается автоматически при открытии .onion. Первый запуск занимает от 10 секунд до пары минут.",
                    style = MaterialTheme.typography.bodySmall, color = if (tor.phase == RunPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
                if (tor.phase == RunPhase.STARTING || tor.phase == RunPhase.BOOTSTRAPPING)
                    LinearProgressIndicator(progress = { tor.progress / 100f }, Modifier.fillMaxWidth().padding(top = 8.dp))
                if (tor.phase.active) Text("Способ выхода: " + tor.actual.title + if (tor.ready) " · SOCKS 127.0.0.1:${tor.port}" else "", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!tor.phase.active) Button({ AnonNet.startTor() }) { Text("Запустить Tor") } else OutlinedButton({ AnonNet.stopTor() }) { Text("Остановить Tor") }
                    OutlinedButton({ scope.launch { torCheck = "Проверяю через Tor…"; torCheck = withContext(Dispatchers.IO) { AnonNet.checkTor() } } }, enabled = tor.ready) { Text("Проверить") }
                    OutlinedButton({ showTorLog = !showTorLog }) { Text(if (!showTorLog) "Журнал" else "Скрыть журнал") }
                }
                if (torCheck.isNotBlank()) Text(torCheck, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                if (showTorLog) MonoBlock(torLog.ifBlank { "Загрузка…" }.takeLast(6000), Modifier.padding(top = 8.dp))

                Text("Как Tor выходит в сеть", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                TransportChips(settings.torTransport, !tor.phase.active) { change(settings.copy(torTransport = it)) }
                Text(transportHelp(settings.torTransport, "Tor"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (settings.torTransport == AnonTransport.PROXY) OutlinedTextField(settings.torProxy, { change(settings.copy(torProxy = it.trim())) }, Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("SOCKS5 хост:порт") }, singleLine = true, isError = settings.torProxy.isNotBlank() && ProxySpec.parse(settings.torProxy) == null)
                val bridges = remember(settings.torBridges) { TorConf.parseBridges(settings.torBridges) }
                OutlinedTextField(settings.torBridges, { change(settings.copy(torBridges = it)) }, Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 2, maxLines = 6,
                    label = { Text("Мосты (по одному в строке)") },
                    supportingText = { Text(when {
                        settings.torBridges.isBlank() -> "Простые мосты вида IP:порт [отпечаток]. Без мостов Tor подключается к публичной сети."
                        bridges.skipped.isNotEmpty() -> "Принято: ${bridges.lines.size}. Пропущено: ${bridges.skipped.size} — obfs4, snowflake и webtunnel в сборке не поддерживаются."
                        else -> "Принято мостов: ${bridges.lines.size}"
                    }) })
            } else {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(settings.torHost, { change(settings.copy(torHost = it.trim())) }, Modifier.weight(1f), label = { Text("SOCKS-адрес") }, singleLine = true)
                    OutlinedTextField(settings.torPort.toString(), { v -> v.toIntOrNull()?.let { if (it in 1..65535) change(settings.copy(torPort = it)) } },
                        Modifier.width(100.dp), label = { Text("Порт") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({
                        scope.launch {
                            torCheck = "Проверяю…"
                            val found = withContext(Dispatchers.IO) { AnonNet.detectExternalTorPort() }
                            if (found != null) { change(settings.copy(torHost = "127.0.0.1", torPort = found)); torCheck = "Найден Tor на 127.0.0.1:$found" }
                            else torCheck = "Tor-прокси не найден (Orbot — порт 9050, Tor Browser — 9150)"
                        }
                    }) { Text("Найти Tor") }
                    LaunchAppButton("Открыть Orbot", "org.torproject.android")
                }
                if (torCheck.isNotBlank()) Text(torCheck, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
            SwitchRow("Весь трафик браузера через Tor", "Кроме адресов локальной сети. Удобно проверить: check.torproject.org", settings.torForAll) { change(settings.copy(torForAll = it)) }
        }

        // ───── I2P ─────
        SoftCard {
            val i2p = state.i2p
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("I2P (.i2p)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (settings.embeddedI2p) PhasePill(i2p.phase)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilterChip(settings.embeddedI2p, { change(settings.copy(embeddedI2p = true)) }, label = { Text("Встроенный I2P") })
                FilterChip(!settings.embeddedI2p, { change(settings.copy(embeddedI2p = false)) }, label = { Text("Внешний роутер") })
            }
            if (settings.embeddedI2p) {
                Text(i2p.error ?: i2p.message.takeIf { i2p.phase != RunPhase.OFF } ?: "I2P запускается автоматически при открытии .i2p. Первый запуск занимает до нескольких минут: роутер получает список узлов и строит туннели.",
                    style = MaterialTheme.typography.bodySmall, color = if (i2p.phase == RunPhase.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp))
                if (i2p.phase == RunPhase.STARTING || i2p.phase == RunPhase.BOOTSTRAPPING)
                    LinearProgressIndicator(progress = { i2p.progress / 100f }, Modifier.fillMaxWidth().padding(top = 8.dp))
                if (i2p.phase.active) Text("Туннели: входящих ${i2p.inbound}, исходящих ${i2p.outbound} · способ: ${i2p.actual.title}" + if (i2p.ready) " · HTTP 127.0.0.1:${i2p.httpPort}" else "",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!i2p.phase.active) Button({ AnonNet.startI2p() }) { Text("Запустить I2P") } else OutlinedButton({ AnonNet.stopI2p() }) { Text("Остановить I2P") }
                    OutlinedButton({ scope.launch { i2pCheck = "Запрашиваю i2p-projekt.i2p (до 90 с)…"; i2pCheck = withContext(Dispatchers.IO) { AnonNet.checkI2p() } } }, enabled = i2p.ready) { Text("Проверить") }
                    OutlinedButton({ showI2pLog = !showI2pLog }) { Text(if (!showI2pLog) "Журнал" else "Скрыть журнал") }
                }
                if (i2pCheck.isNotBlank()) Text(i2pCheck, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                if (showI2pLog) MonoBlock(i2pLog.ifBlank { "Загрузка…" }.takeLast(6000), Modifier.padding(top = 8.dp))

                Text("Как I2P получает список узлов и выходит в сеть", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
                TransportChips(settings.i2pTransport, !i2p.phase.active) { change(settings.copy(i2pTransport = it)) }
                Text(transportHelp(settings.i2pTransport, "I2P"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (settings.i2pTransport == AnonTransport.PROXY) OutlinedTextField(settings.i2pProxy, { change(settings.copy(i2pProxy = it.trim())) }, Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("SOCKS5 хост:порт") }, singleLine = true, isError = settings.i2pProxy.isNotBlank() && ProxySpec.parse(settings.i2pProxy) == null)
                OutlinedTextField(settings.i2pReseedFile, { change(settings.copy(i2pReseedFile = it.trim())) }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
                    label = { Text("Reseed: файл .su3 или https-адрес") },
                    supportingText = { Text("Если серверы reseed недоступны, выберите файл i2pseeds.su3, полученный из другой сети.") })
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ pickReseed.launch(arrayOf("*/*")) }) { Text("Выбрать файл") }
                    if (settings.i2pReseedFile.isNotBlank()) OutlinedButton({ change(settings.copy(i2pReseedFile = "")) }) { Text("Сбросить") }
                }
            } else {
                Text("Установите I2P или i2pd и включите его HTTP-прокси — адрес по умолчанию 127.0.0.1:4444 (SOCKS — 4447).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(settings.i2pHost, { change(settings.copy(i2pHost = it.trim())) }, Modifier.weight(1f), label = { Text("Адрес") }, singleLine = true)
                    OutlinedTextField(settings.i2pPort.toString(), { v -> v.toIntOrNull()?.let { if (it in 1..65535) change(settings.copy(i2pPort = it)) } },
                        Modifier.width(100.dp), label = { Text("Порт") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    FilterChip(!settings.i2pSocks, { change(settings.copy(i2pSocks = false, i2pPort = if (settings.i2pPort == 4447) 4444 else settings.i2pPort)) }, label = { Text("HTTP-прокси") })
                    FilterChip(settings.i2pSocks, { change(settings.copy(i2pSocks = true, i2pPort = if (settings.i2pPort == 4444) 4447 else settings.i2pPort)) }, label = { Text("SOCKS5") })
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({
                        scope.launch {
                            i2pCheck = "Проверяю…"
                            val ok = withContext(Dispatchers.IO) { AnonNet.probeI2p() }
                            i2pCheck = if (ok) "I2P-прокси отвечает на ${settings.i2pHost}:${settings.i2pPort}" else "Нет ответа на ${settings.i2pHost}:${settings.i2pPort}. Запустите I2P или i2pd."
                        }
                    }) { Text("Проверить") }
                    LaunchAppButton("Открыть I2P", "net.i2p.android", "net.i2p.android.router", "org.purplei2p.i2pd")
                }
                if (i2pCheck.isNotBlank()) Text(i2pCheck, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        }

        SoftCard(color = tones.warnSoft) {
            Text("Что важно знать", style = MaterialTheme.typography.titleSmall)
            Text("• Анонимность даёт только Tor и I2P; обычный WebView не скрывает отпечаток браузера, как Tor Browser. Для чувствительных задач отключайте JavaScript (меню браузера).\n" +
                "• Если провайдер блокирует Tor или серверы I2P, выберите способ «Обход DPI» (byedpi): он запускается автоматически, подберите стратегию в разделе «Обход блокировок».\n" +
                "• В режиме «Авто» сначала пробуется прямое подключение; при зависании приложение один раз переключится на обход DPI.\n" +
                "• Прокси слушает только 127.0.0.1. Tor и I2P работают в фоне, пока вы их не остановите.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
        AnonTransport.values().forEach { t -> FilterChip(current == t, { onChange(t) }, label = { Text(t.title) }, enabled = enabled) }
    }
}

private fun transportHelp(t: AnonTransport, what: String) = when (t) {
    AnonTransport.AUTO -> "Сначала напрямую; если $what зависнет, один раз автоматически включится обход DPI."
    AnonTransport.DIRECT -> "Только прямое подключение."
    AnonTransport.DPI -> "Подключение идёт через byedpi с текущей стратегией раздела «Обход блокировок (DPI)»."
    AnonTransport.PROXY -> "Подключение идёт через указанный SOCKS5-прокси (например, локальный порт VPN-приложения)."
}

@Composable
private fun LaunchAppButton(label: String, vararg packages: String) {
    val context = LocalContext.current
    val intent = remember { packages.firstNotNullOfOrNull { context.packageManager.getLaunchIntentForPackage(it) } }
    OutlinedButton({ intent?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }, enabled = intent != null) {
        Text(if (intent != null) label else "$label (не установлено)")
    }
}

@Composable
private fun SwitchRow(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}
