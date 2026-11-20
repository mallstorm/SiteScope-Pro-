@file:OptIn(ExperimentalLayoutApi::class)

package pro.sitescope.app.data

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.sitescope.app.DataStore
import pro.sitescope.app.Models
import pro.sitescope.app.ui.*

private class Hist(val type: String, val time: Long, val title: String, val sub: String, val status: String, val ok: Boolean, val value: String, val target: String)

private fun defaultTags(type: String): List<String> = when (type) {
    "IP" -> listOf("LAN", "Сканирование"); "IPTV" -> listOf("IPTV", "HLS / M3U"); else -> listOf("SEO-аудит", "HTTP/HTTPS")
}

private fun sizeText(b: Long): String = when {
    b >= 1_048_576 -> "%.1f МБ".format(java.util.Locale("ru"), b / 1_048_576.0)
    b >= 1024 -> "${b / 1024} КБ"
    else -> "$b Б"
}

/** Сводка вкладки «Проекты»: проекты, последние проверки и отчёты на одном экране, внизу — быстрые действия. */
@Composable
internal fun DataOverview(onTab: (Int) -> Unit, onAllProjects: () -> Unit) {
    val context = LocalContext.current
    val store = remember { DataStore(context.applicationContext) }
    var version by remember { mutableIntStateOf(0) }
    var projects by remember { mutableStateOf<List<Models.Project>>(emptyList()) }
    var history by remember { mutableStateOf<List<Hist>>(emptyList()) }
    var reports by remember { mutableStateOf<List<ReportLog.Entry>>(emptyList()) }
    var adding by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(version) {
        withContext(Dispatchers.IO) {
            projects = try { store.projects().sortedWith(compareByDescending<Models.Project> { it.pinned }.thenByDescending { it.updatedAt }) } catch (e: Exception) { emptyList() }
            val list = ArrayList<Hist>()
            try { store.audits().forEach { a -> list.add(Hist("SEO", a.timestamp, (a.host.ifEmpty { a.target }) + " — проверка", whenText(a.timestamp) + " • " + (if (a.fullCrawl) "Весь сайт" else "Доступность, SEO"),
                if (a.error.isEmpty()) "Успешно" else "Ошибка", a.error.isEmpty(), if (a.error.isEmpty() && a.averageResponseMs > 0) "${a.averageResponseMs} мс" else "", a.target)) } } catch (e: Exception) { }
            try { store.scans().forEach { s -> list.add(Hist("IP", s.timestamp, s.targetExpression + " — сканирование", whenText(s.timestamp) + " • Поиск устройств, портов",
                if (s.cancelled) "Остановлено" else "Завершено", !s.cancelled, "${s.openResults.size} откр.", s.targetExpression)) } } catch (e: Exception) { }
            try { store.iptvHistory().forEach { t -> list.add(Hist("IPTV", t.timestamp, "Плейлист — проверка потоков", whenText(t.timestamp) + " • IPTV, доступность",
                "Проверено", true, "${t.channelCount} потоков", "")) } } catch (e: Exception) { }
            history = list.sortedByDescending { it.time }
            reports = ReportLog.list(context)
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            CompactTitle("Проекты", action = "Все проекты ›") { onAllProjects() }
            SoftCard(padding = 4.dp) {
                if (projects.isEmpty()) Text("Проект появляется сам после первой проверки сайта, сети или плейлиста.", Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                projects.take(4).forEachIndexed { i, p ->
                    val (icon, color, soft) = typeLook(p.type)
                    Row(Modifier.fillMaxWidth().clickableRow { openTarget(context, p.type, p.target) }.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(icon, color, soft, 44.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1.4f)) {
                            Text(p.name.ifEmpty { p.target }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(if (p.type == "IPTV") p.summary.ifEmpty { "Потоки, плейлисты, доступность" } else if (p.type == "IP") "Устройства, порты, доступность" else "Мониторинг сайта, SEO, доступность",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val tags = p.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { defaultTags(p.type) }
                            Row(Modifier.padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) { tags.take(2).forEach { Pill(it, tones.info, tones.infoSoft) } }
                        }
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(if (p.archived) Icons.Rounded.Inventory2 else if (p.pinned) Icons.Rounded.PushPin else Icons.Rounded.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(if (p.archived) "В архиве" else if (p.pinned) "Закреплён" else "Проект", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            }
                            Text("Активность:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(whenText(p.updatedAt).lowercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (i < minOf(projects.size, 4) - 1) HorizontalDivider(Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
        item {
            CompactTitle("История", action = "Вся история ›") { onTab(1) }
            SoftCard(padding = 4.dp) {
                if (history.isEmpty()) Text("Здесь появятся ваши проверки сайтов, сетей и плейлистов.", Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                history.take(3).forEachIndexed { i, h ->
                    val (icon, _, _) = typeLook(h.type)
                    Row(Modifier.fillMaxWidth().clickableRow { if (h.type == "IP") onTab(1) else openTarget(context, h.type, h.target) }.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(h.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(h.sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(6.dp))
                        Pill(h.status, if (h.ok) tones.good else tones.bad, if (h.ok) tones.goodSoft else tones.badSoft, icon = if (h.ok) Icons.Rounded.Check else Icons.Rounded.Close)
                        if (h.value.isNotEmpty()) Text(h.value, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                    if (i < minOf(history.size, 3) - 1) HorizontalDivider(Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
        item {
            CompactTitle("Отчёты", action = "Все отчёты ›") { onTab(2) }
            SoftCard(padding = 4.dp) {
                if (reports.isEmpty()) Text("Сохранённых отчётов пока нет. Выгрузите PDF/HTML после проверки сайта или нажмите «Сформировать отчёт».", Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                reports.take(3).forEachIndexed { i, r ->
                    val fmt = r.format.uppercase()
                    val (c, soft) = when (fmt) { "PDF" -> tones.bad to tones.badSoft; "CSV" -> tones.good to tones.goodSoft; else -> tones.info to tones.infoSoft }
                    Row(Modifier.fillMaxWidth().clickableRow { openReport(context, r) }.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(when (fmt) { "PDF" -> Icons.Rounded.PictureAsPdf; "CSV" -> Icons.Rounded.TableChart; else -> Icons.Rounded.Code }, c, soft, 36.dp)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(r.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(whenText(r.time) + " • " + r.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Pill(fmt, c, soft)
                        Text(sizeText(r.size), Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                        Icon(if (r.uri.isNotEmpty()) Icons.Rounded.OpenInNew else Icons.Rounded.Description, "Открыть", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 6.dp).size(20.dp))
                    }
                    if (i < minOf(reports.size, 3) - 1) HorizontalDivider(Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ adding = true }, Modifier.weight(1f).height(44.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Icon(Icons.Rounded.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Новый проект", maxLines = 1) }
                OutlinedButton({ confirmClear = true }, Modifier.weight(1f).height(44.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Icon(Icons.Rounded.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Очистить историю", maxLines = 1, style = MaterialTheme.typography.labelLarge) }
                OutlinedButton({ onTab(3) }, Modifier.weight(1f).height(44.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Icon(Icons.Rounded.Description, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Сформировать отчёт", maxLines = 2, style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
    if (adding) TextDialog("Новый проект (сайт)", "", "example.ru", onClose = { adding = false }) { value ->
        val target = value.trim()
        if (target.isNotEmpty()) {
            val url = if (target.startsWith("http", true)) target else "https://$target"
            try { store.upsertProject("SEO", target.removePrefix("https://").removePrefix("http://").trimEnd('/'), url, -1, "Проверка ещё не запускалась") } catch (e: Exception) { }
            version++
        }
        adding = false
    }
    if (confirmClear) ConfirmDialog("Очистить историю?", "Записи проверок будут удалены без возможности восстановления. Проекты и отчёты останутся.", "Очистить", { confirmClear = false }) {
        try { store.clearHistory(); store.clearScans(); store.clearIptvHistory() } catch (e: Exception) { }
        confirmClear = false; version++
    }
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier = this.then(Modifier.clickable(onClick = onClick))

internal fun openReport(context: Context, r: ReportLog.Entry) {
    if (r.uri.isEmpty()) { toast(context, "Файл сохранён через общий экспорт — найдите его в выбранной папке"); return }
    val mime = when (r.format.uppercase()) { "PDF" -> "application/pdf"; "HTML" -> "text/html"; "CSV" -> "text/csv"; "JSON" -> "application/json"; else -> "*/*" }
    try {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(android.net.Uri.parse(r.uri), mime).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) { toast(context, "Не удалось открыть файл — возможно, он перемещён или удалён") }
}


/** Все сохранённые отчёты: открыть, поделиться ссылкой на файл, убрать запись из журнала. */
@Composable
internal fun SavedReports(onNew: () -> Unit) {
    val context = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val list = remember(version) { ReportLog.list(context) }
    var filter by remember { mutableStateOf("ALL") }
    val shown = list.filter { filter == "ALL" || it.format.uppercase() == filter }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf("ALL" to "Все", "PDF" to "PDF", "HTML" to "HTML", "CSV" to "CSV", "JSON" to "JSON").forEach { (id, title) -> MiniChip(title, filter == id) { filter = id } }
            }
        }
        if (shown.isEmpty()) item {
            EmptyState(Icons.Rounded.Description, "Сохранённых отчётов нет", "Выгрузите PDF или HTML после проверки сайта либо соберите сводный отчёт по журналу приложения.") {
                Button(onClick = onNew) { Text("Сформировать отчёт") }
            }
        }
        items(shown.size) { i ->
            val r = shown[i]
            val fmt = r.format.uppercase()
            val (c, soft) = when (fmt) { "PDF" -> tones.bad to tones.badSoft; "CSV" -> tones.good to tones.goodSoft; else -> tones.info to tones.infoSoft }
            SoftCard(padding = 8.dp, onClick = { openReport(context, r) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(when (fmt) { "PDF" -> Icons.Rounded.PictureAsPdf; "CSV" -> Icons.Rounded.TableChart; else -> Icons.Rounded.Code }, c, soft, 40.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(whenText(r.time) + " • " + r.source, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 3.dp)) { Pill(fmt, c, soft); Text(sizeText(r.size), style = MaterialTheme.typography.labelMedium) }
                    }
                    if (r.uri.isNotEmpty()) IconButton(onClick = {
                        try { context.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("*/*").putExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri.parse(r.uri)).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), r.name)) }
                        catch (e: Exception) { toast(context, "Не удалось поделиться") }
                    }) { Icon(Icons.Rounded.Share, "Поделиться") }
                    IconButton(onClick = { ReportLog.remove(context, r.time); version++ }) { Icon(Icons.Rounded.DeleteOutline, "Убрать из списка", tint = tones.bad) }
                }
            }
        }
        if (list.isNotEmpty()) item { Text("Удаление убирает запись из списка; сам файл остаётся в папке, куда вы его сохранили.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}
