package pro.sitescope.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.NetworkPing
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import pro.sitescope.app.HostsVpnService
import pro.sitescope.app.anon.AnonNet
import pro.sitescope.app.anon.RunPhase
import pro.sitescope.app.dpi.DpiPhase
import pro.sitescope.app.dpi.DpiRuntime
import pro.sitescope.app.dpi.DpiViewModel
import pro.sitescope.app.vpn.VpnViewModel

/** Раздел защиты, в который ведёт карточка сводки: 1 = VPN, 2 = DPI, 3 = Onion · I2P, 4 = TLS Inspector. */
typealias ProtectGo = (section: Int, vpnTab: Int) -> Unit

/** Сводка «Защита и доступ» по макету: состояние VPN, свои адреса, серверы, режимы обхода и Onion/I2P на одном экране. */
@Composable
fun ProtectSummary(go: ProtectGo) {
    val context = LocalContext.current
    val vpn: VpnViewModel = viewModel()
    val dpi: DpiViewModel = viewModel()
    val dpiState by DpiRuntime.state.collectAsState()
    val anon by AnonNet.state.collectAsState()
    LaunchedEffect(Unit) { AnonNet.init(context) }
    var query by remember { mutableStateOf("") }
    val needle = query.trim()
    val t = tones

    val running = vpn.serverList.firstOrNull { it.id == vpn.runningProfileId } ?: vpn.serverList.firstOrNull { it.id == vpn.selectedServer }
    val pingValue = running?.let { vpn.serverPings[it.id] }
    val rules = vpn.entries.filter { needle.isEmpty() || it.address.contains(needle, true) || it.hostnames.any { h -> h.contains(needle, true) } }
    val servers = vpn.serverList.filter { needle.isEmpty() || (it.name + " " + it.endpoint).contains(needle, true) }
    val dpiOn = dpiState.phase == DpiPhase.RUNNING

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmartSearchBar(query, { query = it }, "Найти адрес, сервер или правило", onGo = { }, scan = true)
        MetricStrip(listOf(
            Metric(Icons.Rounded.Shield, "VPN", if (vpn.active) "Включён" else "Выключен"),
            Metric(Icons.Rounded.Dns, "Сервер", if (vpn.serverActive) vpn.runningProfile.ifEmpty { "—" } else running?.name?.ifEmpty { running.endpoint } ?: "—"),
            Metric(Icons.Rounded.NetworkPing, "Пинг", if (pingValue != null && pingValue >= 0) "$pingValue мс" else "—"),
            Metric(Icons.Rounded.LockOpen, "DPI", if (dpiOn) "Активен" else "Выкл")
        ))

        // Свои адреса сайтов
        SummaryCard(Icons.Rounded.Link, "Свои адреса сайтов", "Переопределение IP-адресов и локальные записи (Hosts)", "${vpn.entries.size} " + plural(vpn.entries.size, "правило", "правила", "правил")) { go(1, 0) } {
            if (rules.isEmpty()) Text(if (vpn.entries.isEmpty()) "Правил пока нет." else "Ничего не найдено.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            for (e in rules.take(3)) Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Public, null, tint = if (e.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(e.primaryHost().ifEmpty { "—" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("→", color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(horizontal = 6.dp))
                Text(e.address, style = MaterialTheme.typography.bodyMedium, maxLines = 1, modifier = Modifier.weight(1f))
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp).height(34.dp).clip(RoundedCornerShape(12.dp)).background(t.aqua.copy(alpha = 0.22f)).clickable { go(1, 0) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
            ) { Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Добавить правило", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) }
        }

        // Серверы
        SummaryCard(Icons.Rounded.Storage, "Серверы", "VPN / прокси-серверы и профили подключения", "${vpn.serverList.size} " + plural(vpn.serverList.size, "сервер", "сервера", "серверов")) { go(1, 1) } {
            if (servers.isEmpty()) Text(if (vpn.serverList.isEmpty()) "Серверов пока нет — добавьте ссылку или отсканируйте QR." else "Ничего не найдено.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            for (s in servers.take(3)) {
                val live = vpn.serverActive && vpn.runningProfileId == s.id
                val ping = vpn.serverPings[s.id]
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(if (live) t.good else MaterialTheme.colorScheme.outline))
                    Spacer(Modifier.width(8.dp))
                    Text(s.name.ifEmpty { s.endpoint }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (live) StatusBadge("Активен", BadgeKind.GOOD) else if (vpn.reserveServer == s.id) StatusBadge("Резерв", BadgeKind.INFO)
                    if (ping != null) Text(if (ping >= 0) "  $ping мс" else "  нет ответа", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // DPI / обход
        SummaryCard(Icons.Rounded.LockOpen, "DPI / обход", "Обход блокировок, фильтрация трафика и режимы туннелирования", if (dpiOn) "работает" else "выключен") { go(2, 0) } {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                ModeTile(Icons.Rounded.Hub, "Прокси", dpi.cfg.mode == "proxy", Modifier.weight(1f)) { dpi.update(dpi.cfg.copy(mode = "proxy")) }
                ModeTile(Icons.Rounded.Apps, "VPN: выбранные приложения", dpi.cfg.mode == "vpn_only", Modifier.weight(1f)) { dpi.update(dpi.cfg.copy(mode = "vpn_only")) }
                ModeTile(Icons.Rounded.Security, "VPN: все приложения", dpi.cfg.mode == "vpn_all", Modifier.weight(1f)) { dpi.update(dpi.cfg.copy(mode = "vpn_all")) }
            }
        }

        // Onion и I2P
        SummaryCard(Icons.Rounded.Language, "Onion и I2P", "Tor и I2P: анонимность и альтернативные маршруты", null) { go(3, 0) } {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AnonCell("Tor", anon.tor.phase, anon.tor.message, Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(34.dp).background(MaterialTheme.colorScheme.outlineVariant))
                AnonCell("I2P", anon.i2p.phase, anon.i2p.message, Modifier.weight(1f).padding(start = 10.dp))
            }
        }
        // TLS Inspector: быстрая ссылка
        Row(
            Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .clickable { go(4, 0) }.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically
        ) {
            ToolIcon(Icons.Rounded.Security, size = 30.dp); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("TLS Inspector", style = MaterialTheme.typography.titleMedium)
                Text("Рукопожатия, JA3, QUIC, ECH", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
        }
    }
}

private fun plural(n: Int, one: String, few: String, many: String): String {
    val m100 = n % 100; val m10 = n % 10
    return if (m100 in 11..14) many else when (m10) { 1 -> one; 2, 3, 4 -> few; else -> many }
}

@Composable
private fun SummaryCard(icon: ImageVector, title: String, subtitle: String, badge: String?, onOpen: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(10.dp)) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
            ToolIcon(icon, size = 32.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (badge != null) Text(badge, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun ModeTile(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier.height(58.dp).clip(shape).background(if (selected) tones.aqua.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainerLow).clickable(onClick = onClick).padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        Box { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp)); if (selected) Icon(Icons.Rounded.CheckCircle, null, tint = tones.good, modifier = Modifier.size(11.dp).align(Alignment.TopEnd)) }
        Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center, lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified)
    }
}

@Composable
private fun AnonCell(name: String, phase: RunPhase, message: String, modifier: Modifier) {
    val t = tones
    val (label, kind) = when (phase) {
        RunPhase.READY -> "Активен" to BadgeKind.GOOD
        RunPhase.STARTING, RunPhase.BOOTSTRAPPING -> "Запуск" to BadgeKind.WARN
        RunPhase.STOPPING -> "Остановка" to BadgeKind.WARN
        RunPhase.FAILED -> "Ошибка" to BadgeKind.BAD
        RunPhase.OFF -> "Отключён" to BadgeKind.NEUTRAL
    }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text(name, style = MaterialTheme.typography.titleSmall); Spacer(Modifier.width(6.dp)); StatusBadge(label, kind) }
        Text(if (phase == RunPhase.OFF) "Не используется" else message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
