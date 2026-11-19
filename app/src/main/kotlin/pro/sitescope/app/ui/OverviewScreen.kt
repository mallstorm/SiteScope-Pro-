package pro.sitescope.app.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.sitescope.app.DataStore
import pro.sitescope.app.DeviceRegistryStore
import pro.sitescope.app.HostsVpnService
import pro.sitescope.app.data.ReportLog
import pro.sitescope.app.dpi.DpiPhase
import pro.sitescope.app.dpi.DpiRuntime
import pro.sitescope.app.tv.StreamStatus
import pro.sitescope.app.tv.TvStore
import androidx.compose.runtime.collectAsState

/** Строка ленты «Недавние результаты». */
class RecentItem(
    val icon: ImageVector, val tag: String, val title: String, val detail: String, val time: Long,
    val statusText: String, val statusKind: BadgeKind, val route: String, val routeTab: Int, val target: String
)

/** Сводка для хаба «Обзор»; всё берётся из локальных хранилищ приложения. */
class OverviewData(
    val audits: Int = 0, val avgScore: Int = -1, val devices: Int = 0, val openPorts: Int = -1,
    val playlists: Int = 0, val channels: Int = 0, val working: Int = 0, val reports: Int = 0,
    val subnet: String = "", val recents: List<RecentItem> = emptyList()
)

fun loadOverview(context: Context): OverviewData {
    var audits = 0; var avg = -1; var devices = 0; var openPorts = -1
    var playlists = 0; var channels = 0; var working = 0
    val recents = ArrayList<RecentItem>()
    val store = DataStore(context)
    try {
        val list = store.audits()
        audits = list.size
        if (list.isNotEmpty()) avg = list.take(20).map { it.score }.average().toInt()
        list.filter { it.host.isNotEmpty() }.take(6).forEach {
            val ok = it.score >= 80
            recents.add(RecentItem(Icons.Rounded.Language, "SEO-аудит", it.host, "Оценка ${it.score}/100", it.timestamp,
                if (ok) "Успешно" else "Есть замечания", if (ok) BadgeKind.GOOD else BadgeKind.WARN, "site", 0, it.target))
        }
    } catch (_: Exception) { }
    try {
        val scans = store.scans().sortedByDescending { it.timestamp }
        if (scans.isNotEmpty()) openPorts = scans.first().openResults.size
        scans.take(4).forEach {
            val done = !it.cancelled && it.error.isEmpty()
            recents.add(RecentItem(Icons.Rounded.Lan, "Сканирование", it.targetExpression.ifEmpty { "Сеть" }, "Открытых сервисов: ${it.openResults.size}", it.timestamp,
                if (done) "Завершено" else "Прервано", if (done) BadgeKind.GOOD else BadgeKind.WARN, "network", 1, it.targetExpression))
        }
    } catch (_: Exception) { }
    try {
        store.iptvHistory().take(4).forEach {
            recents.add(RecentItem(Icons.Rounded.LiveTv, "IPTV", it.source.ifEmpty { "Плейлист" }, "Потоков: ${it.channelCount} · доступных: ${it.playableCount}", it.timestamp,
                "Проверено", BadgeKind.GOOD, "tv", 0, ""))
        }
    } catch (_: Exception) { }
    try { devices = DeviceRegistryStore.count(context) } catch (_: Exception) { }
    try {
        TvStore.load(context)
        val all = TvStore.snapshot()
        channels = all.size
        working = all.count { it.status == StreamStatus.OK || it.status == StreamStatus.SLOW }
        playlists = TvStore.sources.size
    } catch (_: Exception) { }
    val reports = try { ReportLog.count(context) } catch (_: Exception) { 0 }
    return OverviewData(audits, avg, devices, openPorts, playlists, channels, working, reports, localSubnet(context), recents.sortedByDescending { it.time })
}

/** Главная вкладка — короткая сводка: поиск, показатели, плитки состояния и свежие результаты. Каталог инструментов — на вкладке «Инструменты». */
@Composable
fun OverviewScreen(state: AppState) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var data by remember { mutableStateOf(OverviewData()) }
    var watched by remember { mutableStateOf(0) }
    val vpn by rememberVpnLifecycle()
    val dpi by DpiRuntime.state.collectAsState()
    val services by rememberRunningServices()
    val net by rememberNetLabel()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) {
        data = withContext(Dispatchers.IO) { loadOverview(context.applicationContext) }
        watched = withContext(Dispatchers.IO) { try { pro.sitescope.app.site.SiteMonitor.targets(context).size } catch (_: Exception) { 0 } }
    }
    fun go() { if (!state.open(input)) toast(context, "Введите сайт, IP, подсеть или ссылку"); else input = "" }
    val vpnOn = vpn == HostsVpnService.STATE_ACTIVE
    val dpiOn = dpi.phase == DpiPhase.RUNNING
    val anonOn = services.any { it.key == "tor" || it.key == "i2p" }
    val protect = buildList { add(if (vpnOn) "VPN вкл" else "VPN выкл"); add(if (dpiOn) "DPI вкл" else "DPI выкл"); if (anonOn) add("Tor/I2P") }.joinToString(" · ")

    Column(Modifier.fillMaxSize()) {
        AppHeader("SiteScope Pro", "Инженерный центр анализа", logo = true,
            actions = { HeaderMenu(listOf("Настройки и резервная копия" to { state.openRoute("settings") }, "Диагностика Wi-Fi" to { state.openRoute("wifi") })) }
        ) { StatusPills(state) }

        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp).padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SmartSearchBar(input, { input = it }, "Сайт, IP, сеть, M3U или поток", onGo = { go() },
                onPaste = { clipboard.getText()?.text?.let { input = it.trim().take(500) } })
            MetricStrip(listOf(
                Metric(Icons.Rounded.Wifi, "Сеть", data.subnet.ifEmpty { net.text }),
                Metric(Icons.Rounded.Layers, "Активно", rememberActiveTaskCount().toString()),
                Metric(Icons.Rounded.Description, "Отчёты", data.reports.toString())
            ))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(Icons.Rounded.Language, "Сайты и SEO", if (data.audits > 0) "${data.audits} проверок" + (if (data.avgScore >= 0) " · ср. ${data.avgScore}" else "") else "Проверок пока нет", Modifier.weight(1f)) { state.openRoute("site") }
                StatTile(Icons.Rounded.Public, "IP и подсети", "Поиск · CIDR · база", Modifier.weight(1f)) { state.openRoute("ip") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(Icons.Rounded.Lan, "Моя сеть", "Устройств: ${data.devices}" + (if (data.openPorts >= 0) " · портов: ${data.openPorts}" else ""), Modifier.weight(1f)) { state.openRoute("network") }
                StatTile(Icons.Rounded.LiveTv, "Телевидение", if (data.channels > 0) "Каналов: ${data.channels} · живых: ${data.working}" else "Плейлистов: ${data.playlists}", Modifier.weight(1f)) { state.openRoute("tv") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(Icons.Rounded.Shield, "Защита и доступ", protect, Modifier.weight(1f)) { state.openRoute("vpn") }
                StatTile(Icons.Rounded.NotificationsActive, "Мониторинг", if (watched > 0) "Сайтов под наблюдением: $watched" else "Сайты не отслеживаются", Modifier.weight(1f)) { state.openRoute("site") }
            }
            CompactTitle("Недавние результаты", action = "История") { state.openRoute("data", 1) }
            if (data.recents.isEmpty()) {
                Text("Здесь появятся ваши проверки сайтов, сканирования сети и плейлисты.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(data.recents.take(12).size) { index ->
                    val item = data.recents[index]
                    RecentRow(item) {
                        if (item.route == "site" && item.target.isNotBlank()) state.siteRequest = item.target
                        state.openRoute(item.route, item.routeTab, item.target.ifBlank { null })
                    }
                }
            }
        }
    }
}

/** Плитка состояния раздела: значок, название и одна строка живых данных. Высота 48 dp. */
@Composable
private fun StatTile(icon: ImageVector, title: String, stat: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(48.dp).shadow(1.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest).clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolIcon(icon, size = 30.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stat, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
fun RecentRow(item: RecentItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(42.dp).shadow(1.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(item.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(whenText(item.time) + " · " + item.tag + " · " + item.detail, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(6.dp))
        StatusBadge(item.statusText, item.statusKind)
    }
}
