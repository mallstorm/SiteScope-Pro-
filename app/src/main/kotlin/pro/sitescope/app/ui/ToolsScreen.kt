@file:OptIn(ExperimentalMaterial3Api::class)

package pro.sitescope.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.sitescope.app.DeviceRegistryStore
import pro.sitescope.app.HostsVpnService
import pro.sitescope.app.core.TaskCenter
import pro.sitescope.app.dpi.DpiPhase
import pro.sitescope.app.dpi.DpiRuntime
import pro.sitescope.app.tv.TvStore

/** Живая подпись и бейдж инструмента: «активно», «N задач», «новое» — только по реальному состоянию. */
private class ToolLive(val subtitle: String?, val badge: String?, val badgeKind: BadgeKind)

@Composable
fun ToolsScreen(state: AppState) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf(ToolCategory.ALL) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var devices by remember { mutableStateOf(-1) }
    var channels by remember { mutableStateOf(-1) }
    var playlists by remember { mutableStateOf(-1) }
    val vpn by rememberVpnLifecycle()
    val dpi by DpiRuntime.state.collectAsState()
    val services by rememberRunningServices()
    val tasks by TaskCenter.tasks.collectAsState()
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try { devices = DeviceRegistryStore.count(context) } catch (_: Exception) { }
            try { TvStore.load(context); channels = TvStore.snapshot().size; playlists = TvStore.sources.size } catch (_: Exception) { }
        }
    }

    fun live(tool: Tool): ToolLive {
        val running = tasks.filter { it.active }
        val crawl = running.count { it.kind == TaskCenter.Kind.CRAWL }
        val batch = running.count { it.kind == TaskCenter.Kind.BATCH }
        val check = running.count { it.kind == TaskCenter.Kind.TV_CHECK }
        val audit = running.count { it.kind == TaskCenter.Kind.SITE_AUDIT }
        fun tasksBadge(n: Int) = if (n > 0) ToolLive(null, "$n ${taskWord(n)}", BadgeKind.INFO) else null
        return when (tool.id) {
            "audit" -> tasksBadge(audit)
            "crawl" -> tasksBadge(crawl)
            "batch" -> tasksBadge(batch)
            "streamcheck" -> tasksBadge(check)
            "vpn" -> if (vpn == HostsVpnService.STATE_ACTIVE) ToolLive(null, "активно", BadgeKind.GOOD) else null
            "dpi" -> if (dpi.phase == DpiPhase.RUNNING) ToolLive(null, "активно", BadgeKind.GOOD) else null
            "anon" -> if (services.any { it.key == "tor" || it.key == "i2p" }) ToolLive(null, "активно", BadgeKind.GOOD) else null
            "scanner" -> if (services.any { it.key == "scan" }) ToolLive(null, "активно", BadgeKind.GOOD) else null
            "devices" -> if (devices > 0) ToolLive("Найдено: $devices", null, BadgeKind.GOOD) else null
            "channels" -> if (playlists >= 0) ToolLive("Плейлистов: $playlists", null, BadgeKind.GOOD) else null
            "streamcheck" -> if (channels > 0) ToolLive("Потоков: $channels", null, BadgeKind.GOOD) else null
            else -> null
        } ?: ToolLive(null, if (tool.isNew) "новое" else null, BadgeKind.NEW)
    }

    val needle = query.trim()
    val groups = ToolRegistry.groups.mapNotNull { group ->
        if (category != ToolCategory.ALL && category !in group.categories) return@mapNotNull null
        val tools = if (needle.isEmpty()) group.tools else group.tools.filter { (it.title + " " + it.subtitle).contains(needle, ignoreCase = true) }
        if (tools.isEmpty()) null else ToolGroup(group.id, group.title, group.subtitle, group.icon, group.categories, tools)
    }
    fun launch(tool: Tool) { tool.prepare?.invoke(state); state.openRoute(tool.route, tool.tab) }

    Column(Modifier.fillMaxSize()) {
        AppHeader("Инструменты", "Все рабочие модули SiteScope Pro", logo = true,
            actions = { HeaderMenu(listOf("Настройки и резервная копия" to { state.openRoute("settings") })) }
        ) { StatusPills(state) }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val avail = maxHeight
            val top = 12.dp + 56.dp + 8.dp + 40.dp + 8.dp
            val need = groups.fold(0.dp) { sum, g -> sum + groupHeight(g.tools.size) + 8.dp }
            val allFit = top + need <= avail
            val activeId = expandedId?.takeIf { id -> groups.any { it.id == id } } ?: groups.firstOrNull()?.id
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SmartSearchBar(query, { query = it }, "Найти инструмент",
                    onGo = { groups.firstOrNull()?.tools?.firstOrNull()?.let { launch(it) } })
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in ToolCategory.entries) FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c.title) })
                }
                if (groups.isEmpty()) {
                    Text("Ничего не найдено. Попробуйте другое слово или категорию.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
                }
                for (group in groups) {
                    val expanded = allFit || group.id == activeId
                    ToolGroupCard(group, expanded, onToggle = { if (!allFit) expandedId = group.id }, live = { live(it) }, onOpen = { launch(it) })
                }
            }
        }
    }
}

private fun taskWord(n: Int): String = when {
    n % 100 in 11..14 -> "задач"
    n % 10 == 1 -> "задача"
    n % 10 in 2..4 -> "задачи"
    else -> "задач"
}

/** Расчётная высота карточки группы: заголовок и строки по две плитки. */
private fun groupHeight(toolCount: Int): Dp = 64.dp + 52.dp * ((toolCount + 1) / 2) + 6.dp * (((toolCount + 1) / 2).coerceAtLeast(1) - 1) + 8.dp

@Composable
private fun ToolGroupCard(group: ToolGroup, expanded: Boolean, onToggle: () -> Unit, live: (Tool) -> ToolLive, onOpen: (Tool) -> Unit) {
    Column(
        Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolIcon(group.icon, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(group.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(group.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Свернуть" else "Развернуть", tint = MaterialTheme.colorScheme.outline)
        }
        if (expanded) Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (pair in group.tools.chunked(2)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (tool in pair) Box(Modifier.weight(1f)) { ToolTile(tool, live(tool)) { onOpen(tool) } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ToolTile(tool: Tool, live: ToolLive, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolIcon(tool.icon, size = 34.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(tool.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = MaterialTheme.typography.labelLarge.lineHeight)
            if (live.subtitle != null) Text(live.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (live.badge != null) { Spacer(Modifier.width(4.dp)); StatusBadge(live.badge, live.badgeKind) }
        else Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
    }
}
