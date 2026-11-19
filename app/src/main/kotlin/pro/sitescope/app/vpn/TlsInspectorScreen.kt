package pro.sitescope.app.vpn

import android.content.Context
import android.content.Intent
import pro.sitescope.app.ui.ToolActivity
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import pro.sitescope.app.HostsVpnService
import pro.sitescope.app.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TlsInspectorScreen() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(TlsInspectorHub.isEnabled()) }
    var events by remember { mutableStateOf(TlsInspectorHub.snapshot()) }
    var vpnActive by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }
    var mitmEnabled by remember { mutableStateOf(VpnSettings.mitm(context)) }
    var quicBlocked by remember { mutableStateOf(VpnSettings.blockQuic(context)) }
    var caInstalled by remember { mutableStateOf(false) }
    var recording by remember { mutableStateOf(CaptureHub.active()) }
    LaunchedEffect(Unit) {
        while (true) {
            val p = context.getSharedPreferences(HostsVpnService.PREFS, Context.MODE_PRIVATE)
            vpnActive = p.getString(HostsVpnService.KEY_STATE, "") == HostsVpnService.STATE_ACTIVE
            mode = p.getString("mode", "") ?: ""
            enabled = TlsInspectorHub.isEnabled()
            events = TlsInspectorHub.snapshot()
            recording = CaptureHub.active()
            mitmEnabled = VpnSettings.mitm(context)
            quicBlocked = VpnSettings.blockQuic(context)
            caInstalled = Mitm.caInstalled(context)
            delay(650)
        }
    }
    val filtered = remember(events, filter) {
        events.filter { filter.isBlank() || it.fingerprint.serverName.contains(filter, true) ||
            it.remote.contains(filter, true) || it.fingerprint.ja3.contains(filter, true) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp)) {
        SoftCard {
            Text("TLS Handshake", style = MaterialTheme.typography.titleLarge)
            Text("TLS 1.2/1.3 и QUIC v1/v2: обнаружение ClientHello, SNI, ALPN и отпечатка JA3.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Text(if (enabled) "● Наблюдение активно" else "○ Наблюдение выключено",
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(when {
                !vpnActive -> "Сначала включите VPN в режиме «Без сервера» или «Через сервер»."
                mode == HostsVpnService.MODE_BASIC -> "Режим «Свои адреса» видит только DNS. Переключите VPN в полный режим."
                else -> "Метаданные соединений выбранных приложений. Пассивный режим не требует сертификата."
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Button(onClick = {
                context.startService(Intent(context, HostsVpnService::class.java)
                    .setAction(if (enabled) HostsVpnService.ACTION_INSPECTOR_STOP else HostsVpnService.ACTION_INSPECTOR_START))
            }, enabled = enabled || vpnActive && mode == HostsVpnService.MODE_XRAY, modifier = Modifier.fillMaxWidth()) {
                Icon(if (enabled) Icons.Rounded.Stop else Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(if (enabled) "Остановить анализ" else "Начать анализ")
            }
        }
        Spacer(Modifier.height(10.dp))
        SoftCard {
            Text("Расшифровка HTTPS", style = MaterialTheme.typography.titleMedium)
            Text("Xray MITM записывает ключи TLS в PCAPNG. Поддерживаемые HTTP/1.1-запросы можно открыть непосредственно в приложении.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text("MITM: " + if (mitmEnabled) "включён" else "выключен", style = MaterialTheme.typography.bodySmall)
            Text("Сертификат: " + if (caInstalled) "установлен" else "не найден", style = MaterialTheme.typography.bodySmall)
            Text("Запись PCAPNG: " + if (recording) "идёт" else "выключена", style = MaterialTheme.typography.bodySmall)
            Text("QUIC: " + if (mitmEnabled && quicBlocked) "заблокирован для перехода на HTTPS/TCP" else "разрешён для анализа Initial",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = {
                context.startActivity(Intent(context, ToolActivity::class.java)
                    .putExtra("screen", "vpn").putExtra("tab", 4))
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Настроить MITM и запись HTTPS")
            }
            OutlinedButton(onClick = {
                context.startActivity(Intent(context, ToolActivity::class.java)
                    .putExtra("screen", "https-content"))
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Article, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Открыть содержимое HTTPS")
            }
            Text("Нужен MITM-сертификат, доверенный клиентом. Сейчас просмотр поддерживает TLS 1.3 и HTTP/1.1; HTTP/2 и HTTP/3 останутся зашифрованными в этом просмотрщике.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text("Рукопожатия: " + events.size, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { TlsInspectorHub.clear(); events = emptyList() }, enabled = events.isNotEmpty()) { Text("Очистить") }
            IconButton(onClick = {
                val items = JSONArray()
                events.forEach {
                    items.put(JSONObject().put("timestamp", it.timestamp).put("source", it.source)
                        .put("sourcePort", it.sourcePort).put("remote", it.remote).put("port", it.remotePort)
                        .put("sni", it.fingerprint.serverName).put("tls", it.fingerprint.version)
                        .put("alpn", JSONArray(it.fingerprint.alpn)).put("ja3", it.fingerprint.ja3)
                        .put("ja3Raw", it.fingerprint.ja3Raw).put("echOffered", it.fingerprint.echOffered)
                        .put("transport", it.transport).put("quicVersion", it.quicVersion))
                }
                val json = JSONObject().put("format", "SiteScope TLS Inspector v1").put("events", items)
                copyText(context, json.toString(2), "JSON скопирован")
            }, enabled = events.isNotEmpty()) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Скопировать JSON") }
        }
        OutlinedTextField(value = filter, onValueChange = { filter = it.take(100) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), label = { Text("Поиск по SNI, IP или JA3") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) })
        Spacer(Modifier.height(10.dp))
        if (filtered.isEmpty()) SoftCard {
            Text(if (events.isEmpty()) "Событий пока нет. Запустите анализ и откройте HTTPS-сайт."
                 else "Совпадений не найдено.")
            Text("QUIC v1/v2: анализируются только Initial CRYPTO и ClientHello. ECH может скрывать реальный SNI.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        filtered.forEach { item ->
            val key = item.timestamp.toString() + ":" + item.sourcePort + ":" + item.remote
            val fp = item.fingerprint
            SoftCard(modifier = Modifier.padding(bottom = 8.dp), padding = 12.dp) {
                Column(Modifier.fillMaxWidth().clickable { expanded = if (expanded == key) null else key }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(fp.serverName.ifEmpty { "SNI не указан" }, style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.transport + " · " + item.remote + ":" + item.remotePort, style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(fp.version, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Icon(if (expanded == key) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, contentDescription = null)
                    }
                    Text(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(item.timestamp)) +
                        " · ALPN: " + fp.alpn.joinToString(", ").ifEmpty { "—" } +
                        (if (fp.echOffered) " · ECH" else ""),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("JA3  " + fp.ja3, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
                if (expanded == key) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    InfoRow("Протокол", item.transport + (if (item.quicVersion.isNotEmpty()) " · " + item.quicVersion else ""))
                    InfoRow("Источник", item.source + ":" + item.sourcePort, mono = true)
                    InfoRow("SNI", fp.serverName.ifEmpty { "не указан" })
                    InfoRow("ALPN", fp.alpn.joinToString(", ").ifEmpty { "не указан" })
                    InfoRow("Версия", fp.version)
                    InfoRow("JA3", fp.ja3, mono = true)
                    InfoRow("JA3-строка", fp.ja3Raw, mono = true)
                    InfoRow("Cipher suites", fp.cipherSuites.joinToString(", ") { "0x" + it.toString(16) }, mono = true)
                    InfoRow("Extensions", fp.extensions.joinToString(", "), mono = true)
                    InfoRow("Groups", fp.groups.joinToString(", "), mono = true)
                    if (fp.echOffered) Text("ECH предложен: действительное имя может быть скрыто.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}
