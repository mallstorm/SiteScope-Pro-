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
import androidx.compose.ui.graphics.Color
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

    var showAll by remember { mutableStateOf(false) }
    val selectedKey = expanded ?: filtered.firstOrNull()?.let { it.timestamp.toString() + ":" + it.sourcePort + ":" + it.remote }
    val selected = filtered.firstOrNull { it.timestamp.toString() + ":" + it.sourcePort + ":" + it.remote == selectedKey }
    val lastQuic = events.lastOrNull { it.transport.contains("QUIC", true) }
    val captureBytes = CaptureHub.session?.writtenBytes ?: 0L
    fun jsonOf(list: List<TlsHandshake>): String {
        val items = JSONArray()
        list.forEach {
            items.put(JSONObject().put("timestamp", it.timestamp).put("source", it.source)
                .put("sourcePort", it.sourcePort).put("remote", it.remote).put("port", it.remotePort)
                .put("sni", it.fingerprint.serverName).put("tls", it.fingerprint.version)
                .put("alpn", JSONArray(it.fingerprint.alpn)).put("ja3", it.fingerprint.ja3)
                .put("ja3Raw", it.fingerprint.ja3Raw).put("echOffered", it.fingerprint.echOffered)
                .put("transport", it.transport).put("quicVersion", it.quicVersion))
        }
        return JSONObject().put("format", "SiteScope TLS Inspector v1").put("events", items).toString(2)
    }
    var menu by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SoftCard(padding = 8.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Circle, null, tint = if (enabled) tones.good else MaterialTheme.colorScheme.outline, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (enabled) "Наблюдение активно" else "Наблюдение выключено", style = MaterialTheme.typography.titleMedium)
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
        }
        MetricStrip(listOf(
            Metric(Icons.Rounded.Handshake, "Рукопожатия", events.size.toString()),
            Metric(Icons.Rounded.Lock, "TLS 1.3", tls13.toString()),
            Metric(Icons.Rounded.Wifi, "QUIC", quic.toString()),
            Metric(Icons.Rounded.VerifiedUser, "ECH", ech.toString())
        ))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.weight(1f)) { SmartSearchBar(filter, { filter = it.take(100) }, "Поиск по домену, SNI или IP…", onGo = { }) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "Ещё") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Скопировать JSON") }, enabled = events.isNotEmpty(), onClick = { menu = false; copyText(context, jsonOf(events), "JSON скопирован") })
                    DropdownMenuItem(text = { Text("Поделиться JSON") }, enabled = events.isNotEmpty(), onClick = { menu = false; shareText(context, jsonOf(events), "TLS Inspector") })
                    DropdownMenuItem(text = { Text("Очистить список") }, enabled = events.isNotEmpty(), onClick = { menu = false; TlsInspectorHub.clear(); events = emptyList() })
                }
            }
        }
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 10.dp)) {
            if (filtered.isEmpty()) item {
                Text((if (events.isEmpty()) "Событий пока нет. Запустите анализ и откройте HTTPS-сайт. " else "Совпадений не найдено. ") + "QUIC v1/v2: анализируются только Initial CRYPTO и ClientHello; ECH может скрывать реальный SNI.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                val shown = if (showAll) filtered else filtered.take(5)
                item {
                    SoftCard(padding = 0.dp) {
                        shown.forEachIndexed { i, item ->
                            val key = item.timestamp.toString() + ":" + item.sourcePort + ":" + item.remote
                            val fp = item.fingerprint
                            val on = key == selectedKey
                            val isQuic = item.transport.contains("QUIC", true)
                            Row(Modifier.fillMaxWidth().background(if (on) tones.infoSoft.copy(alpha = 0.5f) else Color.Transparent).clickable { expanded = key }.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                IconBadge(connIcon(fp.serverName), MaterialTheme.colorScheme.onPrimary, MaterialTheme.colorScheme.primary, 34.dp)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1.3f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Rounded.Circle, null, tint = if (fp.echOffered) tones.warn else tones.good, modifier = Modifier.size(8.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(fp.serverName.ifEmpty { item.remote }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                    Text("SNI: " + fp.serverName.ifEmpty { "—" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(item.remote + ":" + item.remotePort, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Pill(if (isQuic) "QUIC" + (if (item.quicVersion.isNotEmpty()) " " + item.quicVersion else "") else fp.version, if (isQuic) tones.info else tones.good, if (isQuic) tones.infoSoft else tones.goodSoft)
                                        Spacer(Modifier.width(4.dp))
                                        Text("ALPN: " + (fp.alpn.firstOrNull() ?: "—"), style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                    }
                                    Text("JA3: " + shortHash(fp.ja3), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                }
                                Icon(if (on) Icons.Rounded.KeyboardArrowDown else Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (i < shown.size - 1) HorizontalDivider(Modifier.padding(horizontal = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        }
                        if (filtered.size > 5) TextButton(onClick = { showAll = !showAll }, modifier = Modifier.fillMaxWidth()) { Text(if (showAll) "Свернуть" else "Все соединения (${filtered.size})") }
                    }
                }
                selected?.let { item -> item { HandshakeCard(item, probes, scope, context) } }
            }
            item {
                HttpsCard(caInstalled, mitmEnabled, recording, captureBytes,
                    onConfigure = { context.startActivity(Intent(context, ToolActivity::class.java).putExtra("screen", "vpn").putExtra("tab", 4)) },
                    onOpen = { context.startActivity(Intent(context, ToolActivity::class.java).putExtra("screen", "https-content")) })
            }
            item { QuicCard(lastQuic, mitmEnabled && quicBlocked) }
        }
    }
}

private fun shortHash(h: String): String = if (h.length > 12) h.take(4) + "…" + h.takeLast(4) else h

private fun connIcon(host: String): androidx.compose.ui.graphics.vector.ImageVector = when {
    host.startsWith("cloud") || host.contains("cdn") -> Icons.Rounded.Cloud
    host.startsWith("api") -> Icons.Rounded.Dns
    host.startsWith("static") || host.startsWith("img") || host.startsWith("images") -> Icons.Rounded.Image
    else -> Icons.Rounded.Language
}

@Composable
private fun HandshakeCard(item: TlsHandshake, probes: MutableMap<String, SiteExtras.TlsInfo?>, scope: kotlinx.coroutines.CoroutineScope, context: Context) {
    val fp = item.fingerprint
    var more by remember(item.timestamp) { mutableStateOf(false) }
    val host = fp.serverName
    val probe = probes[host]
    val verOf = when { fp.version.contains("1.3") -> "TLS 1.3 (0x0304)"; fp.version.contains("1.2") -> "TLS 1.2 (0x0303)"; else -> fp.version }
    SoftCard(padding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Handshake, MaterialTheme.colorScheme.onPrimary, MaterialTheme.colorScheme.primary, 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Подробности рукопожатия", style = MaterialTheme.typography.titleMedium)
                Text(item.remote.let { if (host.isNotEmpty()) host else it } + ":" + item.remotePort + " · " + fp.version, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(if (more) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, modifier = Modifier.clickable { more = !more })
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        Row {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Detail(Icons.Rounded.Description, "ClientHello", verOf)
                Detail(Icons.Rounded.Language, "SNI (Server Name)", host.ifEmpty { "не указан" })
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Layers, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(6.dp))
                    Column {
                        Text("ALPN (протоколы)", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (fp.alpn.isEmpty()) Text("не указан", style = MaterialTheme.typography.bodySmall)
                            fp.alpn.take(3).forEachIndexed { i, a -> Pill(a, if (i == 0) tones.good else tones.info, if (i == 0) tones.goodSoft else tones.infoSoft) }
                        }
                    }
                }
            }
            Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(120.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Detail(Icons.Rounded.Fingerprint, "JA3 (клиент)", fp.ja3.take(14) + "…", copy = fp.ja3, context = context)
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(6.dp))
                    Column {
                        Text("ECH (Encrypted CH)", style = MaterialTheme.typography.titleSmall)
                        if (fp.echOffered) Pill("Поддерживается", tones.good, tones.goodSoft, icon = Icons.Rounded.Check) else Pill("Не предложен", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh)
                    }
                }
                val sha = probe?.sha256.orEmpty()
                if (sha.isNotEmpty()) Detail(Icons.Rounded.WorkspacePremium, "Отпечаток сертификата", "SHA-256: " + sha.take(11) + "…" + sha.takeLast(5), copy = sha, context = context)
                else Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.WorkspacePremium, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(6.dp))
                    Column {
                        Text("Отпечаток сертификата", style = MaterialTheme.typography.titleSmall)
                        if (host.isNotEmpty()) {
                            if (probes.containsKey(host) && probe == null) Text("Проверяем…", style = MaterialTheme.typography.bodySmall)
                            else TextButton(onClick = {
                                probes[host] = null
                                scope.launch(kotlinx.coroutines.Dispatchers.IO) { val r = try { SiteExtras.inspectTls(host, 443) } catch (e: Exception) { SiteExtras.TlsInfo().apply { error = e.message ?: "ошибка" } }; probes[host] = r }
                            }, contentPadding = PaddingValues(0.dp)) { Text("Проверить (проба)", style = MaterialTheme.typography.labelMedium) }
                        } else Text("недоступен", style = MaterialTheme.typography.bodySmall)
                        if (probe != null && sha.isEmpty()) Text("Проба не удалась: " + probe.error.ifEmpty { "нет ответа" }, style = MaterialTheme.typography.labelSmall, color = tones.bad)
                    }
                }
            }
        }
        if (more) {
            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            InfoRow("Протокол", item.transport + (if (item.quicVersion.isNotEmpty()) " · " + item.quicVersion else ""))
            InfoRow("Источник", item.source + ":" + item.sourcePort, mono = true)
            InfoRow("JA3-строка", fp.ja3Raw, mono = true)
            InfoRow("Cipher suites", fp.cipherSuites.joinToString(", ") { "0x" + it.toString(16) }, mono = true)
            InfoRow("Extensions", fp.extensions.joinToString(", "), mono = true)
            InfoRow("Groups", fp.groups.joinToString(", "), mono = true)
            if (probe != null && probe.sha256.isNotEmpty()) {
                InfoRow("Выдан для / кем", probe.subject + " / " + probe.issuer)
                InfoRow("Действует до", SimpleDateFormat("d MMM yyyy", Locale("ru")).format(Date(probe.notAfter)) + " (дней: ${probe.daysLeft})")
                Text("Источник: активная проба ${host}:443 с этого телефона, не перехват.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else Text("Сертификат в пассивном TLS 1.3 зашифрован, поэтому берём его только активной пробой.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Detail(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, copy: String? = null, context: Context? = null) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (copy != null && context != null) Icon(Icons.Rounded.ContentCopy, "Копировать", modifier = Modifier.size(20.dp).clickable { copyText(context, copy, "Скопировано") })
    }
}

@Composable
private fun HttpsCard(caInstalled: Boolean, mitm: Boolean, recording: Boolean, bytes: Long, onConfigure: () -> Unit, onOpen: () -> Unit) {
    SoftCard(padding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Lock, MaterialTheme.colorScheme.onPrimary, MaterialTheme.colorScheme.primary, 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Расшифровка HTTPS", style = MaterialTheme.typography.titleMedium)
                Text("Анализ TLS-трафика и содержимого (MITM)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (caInstalled) Pill("Готово", tones.good, tones.goodSoft, icon = Icons.Rounded.Check) else Pill("Нужен сертификат", tones.warn, tones.warnSoft)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text("Сертификат", style = MaterialTheme.typography.titleSmall)
                if (caInstalled) Pill("Действителен", tones.good, tones.goodSoft, icon = Icons.Rounded.Check) else Pill("Не найден", tones.bad, tones.badSoft)
            }
            Column(Modifier.weight(1f)) {
                Text("MITM", style = MaterialTheme.typography.titleSmall)
                if (mitm) Pill("Активен", tones.good, tones.goodSoft) else Pill("Не активен", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh, icon = Icons.Rounded.Circle)
            }
            Column(Modifier.weight(1.2f)) {
                Text("Запись PCAPNG", style = MaterialTheme.typography.titleSmall)
                if (recording) Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Circle, null, tint = tones.good, modifier = Modifier.size(8.dp)); Spacer(Modifier.width(4.dp)); Text("Ведётся (" + fmtMb(bytes) + ")", style = MaterialTheme.typography.labelMedium) }
                else Text("Выключена", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onConfigure, Modifier.weight(1f).height(40.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Icon(Icons.Rounded.Settings, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Настроить запись", maxLines = 1) }
            Button(onOpen, Modifier.weight(1f).height(40.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Icon(Icons.Rounded.Description, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Открыть содержимое", maxLines = 1) }
        }
    }
}

private fun fmtMb(b: Long): String = if (b >= 1_048_576) "%.1f МБ".format(Locale("ru"), b / 1_048_576.0) else "${b / 1024} КБ"

@Composable
private fun QuicCard(q: TlsHandshake?, blocked: Boolean) {
    var open by remember { mutableStateOf(true) }
    SoftCard(padding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.CellTower, MaterialTheme.colorScheme.onPrimary, MaterialTheme.colorScheme.primary, 40.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("QUIC (HTTP/3)", style = MaterialTheme.typography.titleMedium)
                Text("Анализ QUIC-подключения (без расшифровки HTTP/3)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(if (open) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown, null, modifier = Modifier.clickable { open = !open })
        }
        if (open) Row(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(10.dp)) {
            Icon(Icons.Rounded.Info, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(8.dp))
            Column {
                if (q == null) Text(if (blocked) "QUIC заблокирован для перехода на HTTPS/TCP — Initial-пакетов не будет." else "Initial-пакетов пока не было. Откройте сайт с HTTP/3.", style = MaterialTheme.typography.bodySmall)
                else {
                    Text("Initial: " + q.quicVersion.ifEmpty { "—" } + " · DCID: " + q.dcid.take(8).ifEmpty { "—" } + (if (q.dcid.length > 8) "…" else ""), style = MaterialTheme.typography.bodySmall)
                    Text("ALPN: " + (q.fingerprint.alpn.firstOrNull() ?: "—") + " · SNI: " + q.fingerprint.serverName.ifEmpty { "—" } + " · JA3: " + shortHash(q.fingerprint.ja3), style = MaterialTheme.typography.bodySmall)
                    Text("Версия: " + (if (q.quicVersion.contains("2")) "2 (RFC 9369)" else "1 (RFC 9000)") + " · ECH: " + (if (q.fingerprint.echOffered) "да" else "нет"), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
