@file:OptIn(ExperimentalLayoutApi::class)

package pro.sitescope.app.anon

import android.content.Intent
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
    val state by AnonNet.state.collectAsState()
    var settings by remember { mutableStateOf(AnonNet.settings()) }
    var address by remember { mutableStateOf("") }
    var torCheck by remember { mutableStateOf("") }
    var i2pCheck by remember { mutableStateOf("") }
    fun change(value: AnonSettings) { settings = value; AnonNet.save(value) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SoftCard {
            Text("Открыть адрес", style = MaterialTheme.typography.titleMedium)
            Text("Адреса .onion идут через Tor, .i2p — через I2P. Остальное открывается как обычно.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("xxxx.onion или site.i2p") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onGo = { open(context, address) }))
            Button({ open(context, address) }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = address.isNotBlank()) { Text("Открыть") }
            Text("Примеры", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onionExamples.forEach { (title, url) -> AssistChip(onClick = { address = url }, label = { Text("$title (.onion)") }) }
                i2pExamples.forEach { AssistChip(onClick = { address = it }, label = { Text(it) }) }
            }
        }

        SoftCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tor (.onion)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (settings.embeddedTor) {
                    val (label, color, soft) = when (state.tor) {
                        TorPhase.ON -> Triple("Работает", tones.good, tones.goodSoft)
                        TorPhase.STARTING -> Triple("Запускается", tones.warn, tones.warnSoft)
                        TorPhase.ERROR -> Triple("Ошибка", tones.bad, tones.badSoft)
                        TorPhase.OFF -> Triple("Выключен", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh)
                    }
                    Pill(label, color, soft)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilterChip(settings.embeddedTor, { change(settings.copy(embeddedTor = true)) }, label = { Text("Встроенный Tor") })
                FilterChip(!settings.embeddedTor, { change(settings.copy(embeddedTor = false)) }, label = { Text("Внешний (Orbot)") })
            }
            if (settings.embeddedTor) {
                Text(state.torNote.ifBlank { "Tor запускается автоматически при открытии .onion. Первый запуск занимает от 10 секунд до пары минут." },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.tor == TorPhase.OFF || state.tor == TorPhase.ERROR) Button({ AnonNet.startTor() }) { Text("Запустить Tor") }
                    else OutlinedButton({ AnonNet.stopTor() }) { Text("Остановить Tor") }
                }
            } else {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(settings.torHost, { change(settings.copy(torHost = it.trim())) }, Modifier.weight(1f), label = { Text("SOCKS-адрес") }, singleLine = true)
                    OutlinedTextField(settings.torPort.toString(), { v -> v.toIntOrNull()?.let { if (it in 1..65535) change(settings.copy(torPort = it)) } },
                        Modifier.width(100.dp), label = { Text("Порт") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number))
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

        SoftCard {
            Text("I2P (.i2p)", style = MaterialTheme.typography.titleMedium)
            Text("Встроить роутер I2P в приложение нельзя: для Android нет готовой библиотеки. Установите I2P или i2pd и включите его HTTP-прокси — адрес по умолчанию 127.0.0.1:4444 (SOCKS — 4447).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(settings.i2pHost, { change(settings.copy(i2pHost = it.trim())) }, Modifier.weight(1f), label = { Text("Адрес") }, singleLine = true)
                OutlinedTextField(settings.i2pPort.toString(), { v -> v.toIntOrNull()?.let { if (it in 1..65535) change(settings.copy(i2pPort = it)) } },
                    Modifier.width(100.dp), label = { Text("Порт") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number))
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

        SoftCard(color = tones.warnSoft) {
            Text("Что важно знать", style = MaterialTheme.typography.titleSmall)
            Text("• Анонимность даёт только Tor и I2P; обычный WebView не скрывает отпечаток браузера, как Tor Browser. Для чувствительных задач отключайте JavaScript (меню браузера).\n" +
                "• Если провайдер блокирует Tor, цепочка не построится — используйте Orbot с мостами.\n" +
                "• Прокси слушает только 127.0.0.1 и работает, пока открыт браузер.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
        }
    }
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
