@file:OptIn(ExperimentalMaterial3Api::class)

package pro.sitescope.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.saveable.rememberSaveable
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
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf(ToolCategory.ALL) }
    var expandedId by rememberSaveable { mutableStateOf<String?>(ToolRegistry.groups.firstOrNull()?.id) }
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
        fun tasksBadge(n: Int) = if (n > 0) ToolLive(null, "$n ${taskWord(n)}", BadgeKind.INFO) else null
        fun running(kind: TaskCenter.Kind) = tasks.count { it.active && it.kind == kind }
        return when (tool.id) {
            "audit" -> tasksBadge(running(TaskCenter.Kind.SITE_AUDIT))
            "crawl" -> tasksBadge(running(TaskCenter.Kind.CRAWL))
            "batch" -> tasksBadge(running(TaskCenter.Kind.BATCH))
            "streamcheck" -> tasksBadge(running(TaskCenter.Kind.TV_CHECK))
                ?: if (channels >= 0) ToolLive("Каналов: $channels", null, BadgeKind.GOOD) else null
            "vpn" -> if (vpn == HostsVpnService.STATE_ACTIVE) ToolLive(null, "активно", BadgeKind.GOOD) else null
            "dpi" -> if (dpi.phase == DpiPhase.RUNNING) ToolLive(null, "активно", BadgeKind.GOOD) else null
            "anon" -> if (services.any { it.key == "tor" || it.key == "i2p" }) ToolLive(null, "активно", BadgeKind.GOOD) else null
            "scanner" -> if (services.any { it.key == "scan" }) ToolLive(null, "активно", BadgeKind.GOOD) else null
            "devices" -> if (devices > 0) ToolLive("Найдено: $devices", null, BadgeKind.GOOD) else null
            "channels" -> if (playlists >= 0) ToolLive("Плейлистов: $playlists", null, BadgeKind.GOOD) else null
            else -> null
        } ?: ToolLive(null, if (tool.isNew) "новое" else null, BadgeKind.NEW)
    }

    val needle = query.trim()
    val groups = remember(needle, category) { ToolRegistry.find(needle, category) }
    fun launch(tool: Tool) { tool.prepare?.invoke(state); state.openRoute(tool.route, tool.tab) }

    Column(Modifier.fillMaxSize()) {
        AppHeader("Инструменты", "", logo = true,
            actions = { HeaderMenu(listOf("Задачи" to { state.openRoute("tasks") }, "Настройки и резервная копия" to { state.openRoute("settings") })) }
        )

        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SmartSearchBar(query, { query = it.take(160) }, "Найти инструмент", scan = false,
                onGo = { groups.firstOrNull()?.tools?.firstOrNull()?.let { launch(it) } })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ToolCategory.entries.forEach { option ->
                    FilterChip(category == option, onClick = {
                        category = option
                        expandedId = ToolRegistry.find(needle, option).firstOrNull()?.id
                    }, label = { Text(option.title) })
                }
            }
            if (groups.isEmpty()) {
                Text("Ничего не найдено. Попробуйте другое слово или категорию.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
            }
            val searching = needle.isNotEmpty()
            androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(groups.size, key = { groups[it].id }) { index ->
                    val group = groups[index]
                    ToolGroupCard(group, searching || group.id == expandedId, onToggle = {
                        expandedId = if (expandedId == group.id) null else group.id
                    }, live = { live(it) }, onOpen = { launch(it) })
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

@Composable
private fun ToolGroupCard(group: ToolGroup, expanded: Boolean, onToggle: () -> Unit, live: (Tool) -> ToolLive, onOpen: (Tool) -> Unit) {
    Column(
        Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest)
    ) {
        Row(Modifier.fillMaxWidth().height(64.dp).clickable(onClick = onToggle).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            ToolIcon(group.icon, size = 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(group.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(group.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Свернуть" else "Развернуть", tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(24.dp))
        }
        if (expanded) Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (tool in group.tools) ToolRow(tool, live(tool)) { onOpen(tool) }
        }
    }
}

@Composable
private fun ToolRow(tool: Tool, live: ToolLive, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 54.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolIcon(tool.icon, size = 34.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(tool.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(live.subtitle ?: tool.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (live.badge != null) { Spacer(Modifier.width(6.dp)); StatusBadge(live.badge, live.badgeKind) }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
    }
}
