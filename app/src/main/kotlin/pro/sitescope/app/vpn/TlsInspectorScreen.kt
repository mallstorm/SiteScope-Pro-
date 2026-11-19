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
import pro.sitescope.app.site.SiteExtras
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
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
    val tls13 = events.count { it.fingerprint.version.contains("1.3") }
    val quic = events.count { it.transport.contains("QUIC", true) }
    val ech = events.count { it.fingerprint.echOffered }
    var showMitm by remember { mutableStateOf(false) }
    // Активные пробы сертификатов: хост -> результат; источник данных подписывается в интерфейсе.
    val probes = remember { mutableStateMapOf<String, SiteExtras.TlsInfo?>() }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Состояние наблюдения
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolIcon(Icons.Rounded.Security, size = 34.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(if (enabled) "Наблюдение активно" else "Наблюдение выключено", style = MaterialTheme.typography.titleSmall, color = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                Text(when {
                    enabled -> "Перехват рукопожатий, анализ TLS/QUIC"
                    !vpnActive -> "Сначала включите VPN в режиме «Без сервера» или «Через сервер»"
                    mode == HostsVpnService.MODE_BASIC -> "Режим «Свои адреса» видит только DNS — нужен полный режим"
                    else -> "Метаданные соединений выбранных приложений, без сертификата"
                }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            Switch(checked = enabled, onCheckedChange = {
                context.startService(Intent(context, HostsVpnService::class.java).setAction(if (enabled) HostsVpnService.ACTION_INSPECTOR_STOP else HostsVpnService.ACTION_INSPECTOR_START))
            }, enabled = enabled || vpnActive && mode == HostsVpnService.MODE_XRAY)
        }
        // Счётчики
        MetricStrip(listOf(
            Metric(Icons.Rounded.Handshake, "Рукопожатия", events.size.toString()),
            Metric(Icons.Rounded.Lock, "TLS 1.3", tls13.toString()),
            Metric(Icons.Rounded.Bolt, "QUIC", quic.toString()),
            Metric(Icons.Rounded.VisibilityOff, "ECH", ech.toString())
        ))
        // Поиск и действия
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { SmartSearchBar(filter, { filter = it.take(100) }, "Поиск по SNI, IP или JA3", onGo = { }) }
            IconButton(onClick = { showMitm = !showMitm }) { Icon(Icons.Rounded.Settings, "Расшифровка HTTPS", tint = if (showMitm) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) }
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
                copyText(context, JSONObject().put("format", "SiteScope TLS Inspector v1").put("events", items).toString(2), "JSON скопирован")
            }, enabled = events.isNotEmpty()) { Icon(Icons.Rounded.ContentCopy, "Скопировать JSON") }
            IconButton(onClick = { TlsInspectorHub.clear(); events = emptyList() }, enabled = events.isNotEmpty()) { Icon(Icons.Rounded.DeleteSweep, "Очистить") }
        }
        if (showMitm) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(12.dp)) {
            Text("Расшифровка HTTPS", style = MaterialTheme.typography.titleSmall)
            Text("MITM: " + (if (mitmEnabled) "включён" else "выключен") + " · сертификат: " + (if (caInstalled) "установлен" else "не найден") + " · запись PCAPNG: " + (if (recording) "идёт" else "выключена"),
                style = MaterialTheme.typography.bodySmall)
            Text("QUIC: " + if (mitmEnabled && quicBlocked) "заблокирован для перехода на HTTPS/TCP" else "разрешён для анализа Initial", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { context.startActivity(Intent(context, ToolActivity::class.java).putExtra("screen", "vpn").putExtra("tab", 4)) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("MITM и запись", maxLines = 1) }
                OutlinedButton(onClick = { context.startActivity(Intent(context, ToolActivity::class.java).putExtra("screen", "https-content")) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Содержимое HTTPS", maxLines = 1) }
            }
            Text("Нужен MITM-сертификат, доверенный клиентом. Просмотр поддерживает TLS 1.3 и HTTP/1.1.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // Список соединений: прокручивается только он
        if (filtered.isEmpty()) Text(
            (if (events.isEmpty()) "Событий пока нет. Запустите анализ и откройте HTTPS-сайт. " else "Совпадений не найдено. ") + "QUIC v1/v2: анализируются только Initial CRYPTO и ClientHello; ECH может скрывать реальный SNI.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(filtered.size, key = { filtered[it].timestamp.toString() + ":" + filtered[it].sourcePort + ":" + filtered[it].remote }) { index ->
                val item = filtered[index]
                val key = item.timestamp.toString() + ":" + item.sourcePort + ":" + item.remote
                val fp = item.fingerprint
                val open = expanded == key
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).clickable { expanded = if (open) null else key }.padding(horizontal = 10.dp, vertical = 7.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Circle, null, tint = if (fp.echOffered) tones.warn else tones.good, modifier = Modifier.size(9.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(fp.serverName.ifEmpty { "SNI не указан" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(item.remote + ":" + item.remotePort + " · ALPN " + fp.alpn.joinToString(",").ifEmpty { "—" } + (if (fp.echOffered) " · ECH" else ""),
                                style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        StatusBadge(if (item.transport.contains("QUIC", true)) "QUIC" + (if (item.quicVersion.isNotEmpty()) " " + item.quicVersion else "") else fp.version, BadgeKind.INFO)
                    }
                    if (!open) Text("JA3 " + fp.ja3.take(12) + "…  ·  " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(item.timestamp)), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline)
                    if (open) {
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        InfoRow("Протокол", item.transport + (if (item.quicVersion.isNotEmpty()) " · " + item.quicVersion else ""))
                        InfoRow("Источник", item.source + ":" + item.sourcePort, mono = true)
                        InfoRow("ClientHello", fp.version)
                        InfoRow("SNI", fp.serverName.ifEmpty { "не указан" })
                        InfoRow("ALPN", fp.alpn.joinToString(", ").ifEmpty { "не указан" })
                        InfoRow("JA3 клиента", fp.ja3, mono = true)
                        InfoRow("JA3-строка", fp.ja3Raw, mono = true)
                        InfoRow("Cipher suites", fp.cipherSuites.joinToString(", ") { "0x" + it.toString(16) }, mono = true)
                        InfoRow("Extensions", fp.extensions.joinToString(", "), mono = true)
                        InfoRow("Groups", fp.groups.joinToString(", "), mono = true)
                        InfoRow("ECH", if (fp.echOffered) "Предложен — реальное имя может быть скрыто" else "Нет")
                        // Сертификат: при пассивном TLS 1.3 он зашифрован, поэтому берём только из активной пробы хоста.
                        val host = fp.serverName
                        if (host.isNotEmpty()) {
                            if (probes.containsKey(host)) {
                                val probe = probes[host]
                                if (probe == null) Text("Проверяем сертификат…", style = MaterialTheme.typography.bodySmall)
                                else if (probe.sha256.isNotEmpty()) {
                                    InfoRow("Сертификат SHA-256", probe.sha256, mono = true)
                                    InfoRow("Выдан для / кем", probe.subject + " / " + probe.issuer)
                                    InfoRow("Действует до", SimpleDateFormat("d MMM yyyy", Locale("ru")).format(Date(probe.notAfter)) + " (дней: ${probe.daysLeft})")
                                    Text("Источник: активная проба ${host}:443 с этого телефона, не перехват. Сервер мог отдать другой сертификат этому клиенту.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                } else Text("Проба не удалась: " + probe.error.ifEmpty { "нет ответа" }, style = MaterialTheme.typography.bodySmall, color = tones.bad)
                            } else {
                                Text("Сертификат в пассивном TLS 1.3 зашифрован и недоступен.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedButton(onClick = {
                                    probes[host] = null
                                    scope.launch(kotlinx.coroutines.Dispatchers.IO) { val r = try { SiteExtras.inspectTls(host, 443) } catch (e: Exception) { SiteExtras.TlsInfo().apply { error = e.message ?: "ошибка" } }; probes[host] = r }
                                }, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Проверить сертификат (активная проба)") }
                            }
                        }
                    }
                }
            }
        }
    }
}
