@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package pro.sitescope.app.data

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.sitescope.app.DataStore
import pro.sitescope.app.Models
import pro.sitescope.app.ProtocolRouter
import pro.sitescope.app.net.PortRow
import pro.sitescope.app.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun dateText(time: Long): String = if (time <= 0) "" else SimpleDateFormat("d MMM yyyy, HH:mm", Locale("ru")).format(Date(time))

/** Открывает цель проекта или записи истории в нужном разделе приложения. */
private fun openTarget(context: Context, type: String, target: String) {
    val intent = when (type) {
        "IPTV" -> Intent(context, ShellActivity::class.java).putExtra("tab", "tv")
        "IP" -> Intent(context, ToolActivity::class.java).putExtra("screen", "network").putExtra("tab", 1).putExtra("targets", target)
        else -> Intent(context, ShellActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, target)
    }
    if (type != "IP") intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    context.startActivity(intent)
}

@Composable
private fun typeLook(type: String): Triple<ImageVector, Color, Color> = when (type) {
    "IP" -> Triple(Icons.Rounded.Lan, tones.net, tones.netSoft)
    "IPTV" -> Triple(Icons.Rounded.LiveTv, tones.tv, tones.tvSoft)
    else -> Triple(Icons.Rounded.Language, tones.site, tones.siteSoft)
}

private fun typeTitle(type: String) = when (type) { "IP" -> "Сеть"; "IPTV" -> "ТВ"; else -> "Сайт" }

@Composable
fun DataScreen(startTab: Int = 0) {
    var tab by remember { mutableIntStateOf(startTab) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Проекты") })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("История") })
            FilterChip(selected = tab == 2, onClick = { tab = 2 }, label = { Text("Отчёты") })
        }
        when (tab) { 0 -> ProjectsTab(); 1 -> HistoryTab(); else -> ReportsScreen() }
    }
}

// ------------------------------------------------------------------ Проекты

@Composable
private fun ProjectsTab() {
    val context = LocalContext.current
    val store = remember { DataStore(context.applicationContext) }
    var version by remember { mutableIntStateOf(0) }
    var projects by remember { mutableStateOf<List<Models.Project>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("ALL") }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Models.Project?>(null) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(version) {
        projects = withContext(Dispatchers.IO) { try { store.projects() } catch (e: Exception) { emptyList() } }
        loaded = true
    }
    val shown = projects.filter { p ->
        (if (filter == "ARCHIVED") p.archived else !p.archived && (filter == "ALL" || p.type == filter)) &&
            (query.isBlank() || (p.name + " " + p.target + " " + p.tags + " " + p.notes).contains(query.trim(), true))
    }.sortedWith(compareByDescending<Models.Project> { it.pinned }.thenByDescending { it.updatedAt })

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ALL" to "Все", "SEO" to "Сайты", "IP" to "Сеть", "IPTV" to "ТВ", "ARCHIVED" to "Архив").forEach { (id, title) ->
                    FilterChip(selected = filter == id, onClick = { filter = id }, label = { Text(title) })
                }
            }
        }
        if (projects.size > 6) item { BigField(query, { query = it }, "Поиск по названию, адресу или метке", keyboard = KeyboardType.Text) }
        if (loaded && shown.isEmpty()) item {
            EmptyState(Icons.Rounded.Folder, if (filter == "ARCHIVED") "Архив пуст" else "Проектов пока нет",
                "Проект появляется сам после первой проверки сайта, сети или плейлиста. Сайт можно добавить и заранее.") {
                if (filter != "ARCHIVED") Button(onClick = { adding = true }) { Text("Добавить сайт") }
            }
        }
        items(shown) { p ->
            val (icon, color, soft) = typeLook(p.type)
            SoftCard(padding = 14.dp, onClick = { openTarget(context, p.type, p.target) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(icon, color, soft, 46.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (p.pinned) { Icon(Icons.Rounded.PushPin, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(4.dp)) }
                            Text(p.name.ifEmpty { p.target }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(if (p.type == "IPTV") p.summary else p.target, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(typeTitle(p.type) + " · " + dateText(p.updatedAt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    if (p.score >= 0) Pill(p.score.toString(), scoreColor(p.score), scoreColor(p.score).copy(alpha = 0.15f))
                    IconButton(onClick = { editing = p }) { Icon(Icons.Rounded.MoreVert, "Ещё") }
                }
                if (p.notes.isNotEmpty()) Text(p.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (loaded && shown.isNotEmpty() && filter != "ARCHIVED") item {
            TextButton(onClick = { adding = true }) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Добавить сайт") }
        }
    }

    if (adding) TextDialog("Новый сайт", "", "example.ru", onClose = { adding = false }) { value ->
        val target = value.trim()
        if (target.isNotEmpty()) {
            val url = if (target.startsWith("http", true)) target else "https://$target"
            try { store.upsertProject("SEO", target.removePrefix("https://").removePrefix("http://").trimEnd('/'), url, -1, "Проверка ещё не запускалась") } catch (e: Exception) { }
            version++
        }
        adding = false
    }
    editing?.let { p ->
        var name by remember(p) { mutableStateOf(p.name) }
        var notes by remember(p) { mutableStateOf(p.notes) }
        var tags by remember(p) { mutableStateOf(p.tags) }
        fun save(pinned: Boolean = p.pinned, archived: Boolean = p.archived) {
            try { store.updateProjectMetadata(p.type, p.target, name.trim(), notes.trim(), tags.trim(), pinned, archived) } catch (e: Exception) { }
            version++; editing = null
        }
        ModalBottomSheet(onDismissRequest = { editing = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
                Text(p.target, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                BigField(name, { name = it }, "Название", leading = Icons.Rounded.Label, keyboard = KeyboardType.Text)
                Spacer(Modifier.height(8.dp))
                BigField(notes, { notes = it }, "Заметка", leading = Icons.Rounded.Notes, keyboard = KeyboardType.Text)
                Spacer(Modifier.height(8.dp))
                BigField(tags, { tags = it }, "Метки через запятую", leading = Icons.Rounded.Sell, keyboard = KeyboardType.Text)
                Button(onClick = { save() }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Сохранить") }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { save(pinned = !p.pinned) }, modifier = Modifier.weight(1f)) { Text(if (p.pinned) "Открепить" else "Закрепить") }
                    OutlinedButton(onClick = { save(archived = !p.archived) }, modifier = Modifier.weight(1f)) { Text(if (p.archived) "Вернуть" else "В архив") }
                }
                TextButton(onClick = { try { store.removeProject(p.type, p.target) } catch (e: Exception) { }; version++; editing = null },
                    modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = tones.bad)) { Text("Удалить проект") }
            }
        }
    }
}

// ------------------------------------------------------------------ История

private class HistoryRow(val type: String, val time: Long, val audit: Models.AuditResult? = null, val scan: Models.ScanSession? = null, val tv: Models.IptvSnapshot? = null)

@Composable
private fun HistoryTab() {
    val context = LocalContext.current
    val activity = context as? Activity
    val store = remember { DataStore(context.applicationContext) }
    var version by remember { mutableIntStateOf(0) }
    var rows by remember { mutableStateOf<List<HistoryRow>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("ALL") }
    var openScan by remember { mutableStateOf<Models.ScanSession?>(null) }
    var confirmClear by remember { mutableStateOf(false) }
    val t = tones
    LaunchedEffect(version) {
        rows = withContext(Dispatchers.IO) {
            val list = ArrayList<HistoryRow>()
            try { store.audits().forEach { list.add(HistoryRow("SEO", it.timestamp, audit = it)) } } catch (e: Exception) { }
            try { store.scans().forEach { list.add(HistoryRow("IP", it.timestamp, scan = it)) } } catch (e: Exception) { }
            try { store.iptvHistory().forEach { list.add(HistoryRow("IPTV", it.timestamp, tv = it)) } } catch (e: Exception) { }
            list.sortedByDescending { it.time }
        }
        loaded = true
    }
    val shown = rows.filter { filter == "ALL" || it.type == filter }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("ALL" to "Всё", "SEO" to "Сайты", "IP" to "Сеть", "IPTV" to "ТВ").forEach { (id, title) ->
                    FilterChip(selected = filter == id, onClick = { filter = id }, label = { Text(title) })
                }
            }
        }
        if (loaded && shown.isEmpty()) item { EmptyState(Icons.Rounded.History, "История пуста", "Здесь появятся ваши проверки сайтов, сетей и плейлистов.") }
        items(shown) { row ->
            val (icon, color, soft) = typeLook(row.type)
            val a = row.audit; val s = row.scan; val tv = row.tv
            SoftCard(padding = 14.dp, onClick = {
                when {
                    a != null -> openTarget(context, "SEO", a.target)
                    s != null -> openScan = s
                    else -> openTarget(context, "IPTV", "")
                }
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(icon, color, soft, 42.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        val title = when {
                            a != null -> a.host.ifEmpty { a.target }
                            s != null -> s.targetExpression
                            else -> "Проверка каналов"
                        }
                        val sub = when {
                            a != null -> if (a.fullCrawl) "Весь сайт · страниц: ${a.pagesCrawled}" else if (a.error.isNotEmpty()) "Сайт не открылся" else "Одна страница"
                            s != null -> "Открытых сервисов: ${s.openResults.size}" + (if (s.cancelled) " · остановлено" else "")
                            tv != null -> "Работает ${tv.playableCount} из ${tv.channelCount}"
                            else -> ""
                        }
                        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(dateText(row.time), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    if (a != null && a.error.isEmpty()) Pill(a.score.toString(), scoreColor(a.score), scoreColor(a.score).copy(alpha = 0.15f))
                }
            }
        }
        if (shown.isNotEmpty()) item {
            TextButton(onClick = { confirmClear = true }, colors = ButtonDefaults.textButtonColors(contentColor = t.bad)) { Text(if (filter == "ALL") "Очистить всю историю" else "Очистить этот раздел") }
        }
    }

    openScan?.let { s ->
        ModalBottomSheet(onDismissRequest = { openScan = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
                Text(s.targetExpression, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(dateText(s.timestamp) + " · проверок: ${s.completedProbes} · ${s.durationMs / 1000} с", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                if (s.openResults.isEmpty()) Text("Открытых сервисов не найдено.", style = MaterialTheme.typography.bodyMedium)
                s.openResults.take(300).forEach { r ->
                    Text(r.ip.ifEmpty { r.host }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 4.dp))
                    PortRow(r) { if (activity != null) try { ProtocolRouter.open(activity, r) } catch (e: Exception) { toast(context, "Не удалось открыть") } }
                }
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openScan = null; openTarget(context, "IP", s.targetExpression) }, modifier = Modifier.weight(1f)) { Text("Проверить снова") }
                    OutlinedButton(onClick = { shareText(context, s.openResults.joinToString("\n") { r -> r.ip.ifEmpty { r.host } + ":" + r.port + "  " + r.service }, s.targetExpression) }) { Text("Поделиться") }
                }
            }
        }
    }
    if (confirmClear) ConfirmDialog("Очистить историю?", "Записи будут удалены без возможности восстановления. Проекты останутся.", "Очистить", { confirmClear = false }) {
        try {
            if (filter == "ALL" || filter == "SEO") store.clearHistory()
            if (filter == "ALL" || filter == "IP") store.clearScans()
            if (filter == "ALL" || filter == "IPTV") store.clearIptvHistory()
        } catch (e: Exception) { }
        confirmClear = false; version++
    }
}
