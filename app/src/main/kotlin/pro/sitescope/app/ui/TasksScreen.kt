package pro.sitescope.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pro.sitescope.app.core.TaskCenter

/** Вкладка «Задачи»: живые службы и операции из TaskCenter; страница фиксирована, список прокручивается внутри. */
@Composable
fun TasksScreen(state: AppState) {
    val tasks by TaskCenter.tasks.collectAsState()
    val services by rememberRunningServices()
    var segment by remember { mutableIntStateOf(0) }
    val active = tasks.filter { it.active }
    val finished = tasks.filter { !it.active }
    Column(Modifier.fillMaxSize()) {
        AppHeader("Задачи", "Фоновые операции и работающие службы", logo = true,
            actions = { HeaderMenu(listOf("Очистить завершённые" to { TaskCenter.clearFinished() })) }
        ) { StatusPills(state) }
        SegmentedTabs(listOf("Активные (${active.size + services.size})", "Завершённые (${finished.size})"), segment, { segment = it },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (segment == 0) {
                items(services, key = { "svc-" + it.key }) { s ->
                    TaskRow(Icons.Rounded.Shield, s.title, s.detail, -1f, "работает", BadgeKind.GOOD, null) { state.openRoute(s.route, s.tab) }
                }
                items(active, key = { "task-" + it.id }) { t ->
                    TaskRow(Icons.Rounded.Checklist, t.title, listOf(t.target, t.detail).filter { it.isNotBlank() }.joinToString(" · "), t.progress, "идёт", BadgeKind.INFO,
                        if (t.cancellable) ({ TaskCenter.cancel(t.id) }) else null) { if (t.kind.route.isNotEmpty()) state.openRoute(t.kind.route) }
                }
                if (services.isEmpty() && active.isEmpty()) item { EmptyHint("Активных задач нет", "Здесь появятся проверки сайтов, обходы, сканирования, проверка потоков и работающие службы VPN, DPI, Tor и I2P.") }
            } else {
                items(finished, key = { "done-" + it.id }) { t ->
                    val (word, kind) = when (t.state) {
                        TaskCenter.State.DONE -> "готово" to BadgeKind.GOOD
                        TaskCenter.State.FAILED -> "ошибка" to BadgeKind.BAD
                        else -> "отменено" to BadgeKind.NEUTRAL
                    }
                    TaskRow(Icons.Rounded.Checklist, t.title, listOf(t.target, t.detail, whenText(t.finishedAt)).filter { it.isNotBlank() }.joinToString(" · "), -1f, word, kind, null) {
                        if (t.kind.route.isNotEmpty()) state.openRoute(t.kind.route)
                    }
                }
                if (finished.isEmpty()) item { EmptyHint("История пуста", "Завершённые за этот запуск операции будут показаны здесь.") }
            }
        }
    }
}

@Composable
private fun EmptyHint(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 24.dp, end = 24.dp))
    }
}

@Composable
private fun TaskRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, detail: String, progress: Float, status: String, kind: BadgeKind, onCancel: (() -> Unit)?, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolIcon(icon, size = 38.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(6.dp))
            StatusBadge(status, kind)
            if (onCancel != null) IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) { Icon(Icons.Rounded.Close, "Отменить", modifier = Modifier.size(20.dp)) }
            else Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(18.dp))
        }
        if (status == "идёт") {
            if (progress in 0f..1f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}
