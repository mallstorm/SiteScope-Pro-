@file:OptIn(ExperimentalLayoutApi::class)

package pro.sitescope.app.site

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pro.sitescope.app.SearchMetricsEngine
import pro.sitescope.app.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Первый экран отчёта по макету: оценка и показатели, что исправить, метрики Core Web Vitals, технологии,
 * мониторинг и последний отчёт с выгрузкой в PDF. Подробные разделы (доступность, скорость, SEO…) открываются по «Все рекомендации».
 */
@Composable
fun ColumnScope.SiteDashboard(
    vm: SiteViewModel, r: Report, state: AppState,
    onDetails: (String?) -> Unit, onSavePdf: () -> Unit, onSaveHtml: () -> Unit
) {
    val context = LocalContext.current
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(r.target) { vm.loadWatchChanges(r.target) }
    LaunchedEffect(r.target, r.timestamp) { vm.loadSpeed(r) }
    AppHeader("Сайты и SEO", "Аудит, метрики и мониторинг", onBack = { vm.clear() }, actions = {
        IconButton(onClick = { vm.run(r.target) }) { Icon(Icons.Rounded.Refresh, "Проверить снова", tint = tones.onHeader) }
        IconButton(onClick = { shareText(context, SiteReportBuilder.toText(r), "Отчёт: ${r.host}") }) { Icon(Icons.Rounded.Share, "Поделиться", tint = tones.onHeader) }
    })
    Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigField(vm.input, { vm.input = it }, "Адрес сайта", modifier = Modifier.weight(1f), leading = Icons.Rounded.Language, onGo = { vm.run() })
            Button(onClick = { vm.run() }, enabled = !vm.running && vm.input.isNotBlank(), contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(40.dp)) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(2.dp)); Text("Проверить")
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniChip("Быстрый аудит", !vm.fullCrawl) { vm.fullCrawl = false }
            MiniChip("Полный обход", vm.fullCrawl) { vm.fullCrawl = true }
            MiniChip("Метрики", false) { state.openRoute("metrics", targets = r.target) }
            MiniChip("Пакетная проверка", false) { state.openRoute("batch") }
        }
        if (vm.running) LoadingCard(vm.stage, vm.stageDetail, onCancel = { vm.cancel() })
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 10.dp)) {
            item { Summary(r, vm.psi, vm.psiLoading) }
            item { Trend(vm, r) }
            item {
                CompactTitle("Что исправить в первую очередь", action = "Все рекомендации ›") { onDetails(null) }
                Tasks(r, onDetails)
            }
            item {
                CompactTitle("Метрики", action = "Подробнее ›") { state.openRoute("metrics", 0, r.target) }
                Vitals(vm.psi, vm.psiLoading) { vm.loadSpeed(r, force = true) }
            }
            item {
                CompactTitle("Технологии сайта", action = "Подробнее ›") { onDetails("tech") }
                Tech(r.info)
            }
            item { Monitoring(vm, r) { if (Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) } }
            item {
                CompactTitle("Последний отчёт", action = "Все отчёты ›") { state.openRoute("data", 2) }
                LastReport(r, onSavePdf, onSaveHtml) { onDetails(null) }
            }
        }
    }
}

@Composable
private fun Summary(r: Report, psi: SearchMetricsEngine.PageSpeed?, loading: Boolean) {
    val info = r.info
    SoftCard(padding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreRing(r.score, size = 92.dp, stroke = 9.dp, caption = "Оценка сайта")
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Language, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(6.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.host.ifEmpty { r.target }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("Проверено " + whenText(r.timestamp).lowercase(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    StatusBadge("SEO-аудит", BadgeKind.INFO)
                }
                HorizontalDivider(Modifier.padding(vertical = 5.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
                val mobile = when { psi != null && psi.available && psi.performance >= 0 -> "${psi.performance}/100"; loading -> "…"; else -> if (info.viewport) "адаптивный" else "нет данных" }
                Row {
                    Fact(Icons.Rounded.Bolt, "Ответ сервера", if (info.responseMs > 0) SiteReportBuilder.fmtMs(info.responseMs) else "—", Modifier.weight(1f))
                    Fact(Icons.Rounded.Lock, "SSL", info.sslText, Modifier.weight(1f), if (info.sslOk == false) tones.bad else null)
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    Fact(Icons.Rounded.Layers, "Индексируемость", when (info.indexable) { true -> "Да"; false -> "Нет"; null -> "—" }, Modifier.weight(1f), if (info.indexable == false) tones.bad else null)
                    Fact(Icons.Rounded.PhoneAndroid, "Моб. оценка", mobile, Modifier.weight(1f))
                }
            }
        }
        val tasks = r.tasks
        val seo = r.sections.firstOrNull { it.key == "seo" }
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (seo != null) Pill(if (seo.score >= 80) "SEO: хорошо" else if (seo.score >= 55) "SEO: средне" else "SEO: слабо", scoreColor(seo.score), softOf(seo.score), icon = if (seo.score >= 80) Icons.Rounded.Check else Icons.Rounded.WarningAmber)
            Pill(if (r.reachable) "Доступен" else "Не открылся", if (r.reachable) tones.good else tones.bad, if (r.reachable) tones.goodSoft else tones.badSoft, icon = if (r.reachable) Icons.Rounded.Check else Icons.Rounded.Close)
            if (info.sslOk != null) Pill(if (info.sslOk) "SSL: активно" else "SSL: проблема", if (info.sslOk) tones.good else tones.bad, if (info.sslOk) tones.goodSoft else tones.badSoft, icon = if (info.sslOk) Icons.Rounded.Check else Icons.Rounded.Close)
            if (tasks.isNotEmpty()) Pill("Есть замечания", tones.warn, tones.warnSoft, icon = Icons.Rounded.WarningAmber)
        }
    }
}

@Composable
private fun softOf(score: Int): Color = if (score >= 80) tones.goodSoft else if (score >= 55) tones.warnSoft else tones.badSoft

@Composable
private fun Fact(icon: ImageVector, label: String, value: String, modifier: Modifier, color: Color? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(5.dp))
        Column {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, style = MaterialTheme.typography.titleSmall, color = color ?: MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Tasks(r: Report, onDetails: (String?) -> Unit) {
    val tasks = r.tasks.take(4)
    SoftCard(padding = 4.dp) {
        if (tasks.isEmpty()) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Verified, null, tint = tones.good); Spacer(Modifier.width(10.dp))
                Text(if (r.reachable) "Серьёзных замечаний нет. Так держать!" else "Сайт не открылся — проверьте адрес и доступность.", style = MaterialTheme.typography.bodyMedium)
            }
        }
        tasks.forEachIndexed { i, (section, check) ->
            val bad = check.level == Level.BAD
            val important = bad || section.key in setOf("availability", "security", "speed", "links")
            val c = if (bad) tones.bad else if (important) tones.warn else tones.info
            val soft = if (bad) tones.badSoft else if (important) tones.warnSoft else tones.infoSoft
            Row(Modifier.fillMaxWidth().clickable { onDetails(section.key) }.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(8.dp)).background(soft), contentAlignment = Alignment.Center) { Text("${i + 1}", color = c, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge) }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(check.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(check.text.ifEmpty { check.fix }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(6.dp))
                Pill(if (bad) "Критично" else if (important) "Важно" else "Рекомендовано", c, soft)
            }
            if (i < tasks.size - 1) HorizontalDivider(Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

@Composable
private fun Vitals(psi: SearchMetricsEngine.PageSpeed?, loading: Boolean, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        VitalCard(Icons.Rounded.WebAsset, "LCP", psi?.takeIf { it.available }?.lcpMs ?: -1.0, 2500.0, 4000.0, true, loading, Modifier.weight(1f), retry)
        VitalCard(Icons.Rounded.TouchApp, "INP", psi?.takeIf { it.available }?.inpMs ?: -1.0, 200.0, 500.0, true, loading, Modifier.weight(1f), retry)
        VitalCard(Icons.Rounded.GridView, "CLS", psi?.takeIf { it.available }?.cls ?: -1.0, 0.1, 0.25, false, loading, Modifier.weight(1f), retry)
    }
}

@Composable
private fun VitalCard(icon: ImageVector, name: String, value: Double, good: Double, bad: Double, millis: Boolean, loading: Boolean, modifier: Modifier, retry: () -> Unit) {
    val has = value >= 0
    val c = when { !has -> MaterialTheme.colorScheme.onSurfaceVariant; value <= good -> tones.good; value <= bad -> tones.warn; else -> tones.bad }
    val soft = when { !has -> MaterialTheme.colorScheme.surfaceContainerHigh; value <= good -> tones.goodSoft; value <= bad -> tones.warnSoft; else -> tones.badSoft }
    val text = when {
        !has -> if (loading) "…" else "—"
        !millis -> String.format(Locale.US, "%.2f", value)
        value >= 1000 -> String.format(Locale.US, "%.1f с", value / 1000)
        else -> "${value.toInt()} мс"
    }
    SoftCard(modifier, padding = 8.dp, onClick = if (!has && !loading) retry else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(6.dp))
            Column {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.titleMedium)
            }
        }
        Pill(when { !has -> if (loading) "Измеряем" else "Нет данных"; value <= good -> "Хорошо"; value <= bad -> "Средне"; else -> "Плохо" }, c, soft, Modifier.padding(top = 4.dp).fillMaxWidth(), icon = if (has && value <= good) Icons.Rounded.Check else null)
    }
}

@Composable
private fun Tech(info: SiteInfo) {
    SoftCard(padding = 8.dp) {
        Row(Modifier.fillMaxWidth()) {
            TechCell(Icons.Rounded.Layers, "CMS", info.cms, Modifier.weight(1f))
            TechCell(Icons.Rounded.Dns, "Сервер", info.server, Modifier.weight(1f))
            TechCell(Icons.Rounded.Cloud, "CDN", info.cdn, Modifier.weight(1f))
            TechCell(Icons.Rounded.Storage, "DNS", info.dns, Modifier.weight(1f))
        }
    }
}

@Composable
private fun TechCell(icon: ImageVector, label: String, value: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value.ifBlank { "не найдено" }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (value.isBlank()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun Monitoring(vm: SiteViewModel, r: Report, askNotifications: () -> Unit) {
    val context = LocalContext.current
    val monitored = vm.monitored.contains(r.target)
    var interval by remember(r.target) { mutableIntStateOf(SiteMonitor.interval(context, r.target)) }
    val history = remember(r.target, monitored) { SiteMonitor.history(context, r.target) }
    SoftCard(padding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Мониторинг", style = MaterialTheme.typography.titleMedium)
                Text("Проверка доступности, скорости и SSL", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = monitored, onCheckedChange = { if (!monitored) askNotifications(); vm.toggleMonitor(r.target) })
        }
        if (monitored) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Интервал:", style = MaterialTheme.typography.bodySmall)
                for ((minutes, label) in SiteMonitor.INTERVALS) MiniChip(label, interval == minutes) { interval = minutes; SiteMonitor.setInterval(context, r.target, minutes) }
            }
        }
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Pill(if (monitored) "Статус: активен" else "Статус: выключен", if (monitored) tones.good else MaterialTheme.colorScheme.onSurfaceVariant, if (monitored) tones.goodSoft else MaterialTheme.colorScheme.surfaceContainerHigh, icon = Icons.Rounded.MonitorHeart)
            Pill(if (monitored) "Уведомления: включены" else "Уведомления: нет", if (monitored) tones.good else MaterialTheme.colorScheme.onSurfaceVariant, if (monitored) tones.goodSoft else MaterialTheme.colorScheme.surfaceContainerHigh, icon = Icons.Rounded.NotificationsActive)
            Pill("Проверок: ${history.size}", tones.info, tones.infoSoft, icon = Icons.Rounded.BarChart)
        }
    }
}

@Composable
private fun LastReport(r: Report, onPdf: () -> Unit, onHtml: () -> Unit, onOpen: () -> Unit) {
    val bad = r.sections.sumOf { it.bad }; val warn = r.sections.sumOf { it.warn }
    val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(r.timestamp))
    SoftCard(padding = 10.dp, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Description, MaterialTheme.colorScheme.onPrimary, MaterialTheme.colorScheme.primary, 44.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Аудит — ${r.host}", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(whenText(r.timestamp) + " ($time) • ${r.score}/100", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                Text("Найдено: $bad ${plural(bad, "проблема", "проблемы", "проблем")}, $warn ${plural(warn, "предупреждение", "предупреждения", "предупреждений")}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onPdf, Modifier.weight(1f).height(38.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Icon(Icons.Rounded.PictureAsPdf, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Экспорт PDF", maxLines = 1) }
            OutlinedButton(onHtml, Modifier.weight(1f).height(38.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("HTML", maxLines = 1) }
        }
    }
}

private fun plural(n: Int, one: String, few: String, many: String): String {
    val m100 = n % 100; val m10 = n % 10
    return if (m100 in 11..14) many else when (m10) { 1 -> one; in 2..4 -> few; else -> many }
}


/** Динамика оценки по прошлым проверкам этого сайта (из журнала). */
@Composable
private fun Trend(vm: SiteViewModel, r: Report) {
    val context = LocalContext.current
    val points by produceState(listOf<Int>(), r.host, r.timestamp) {
        value = withContext(kotlinx.coroutines.Dispatchers.IO) {
            try { pro.sitescope.app.DataStore(context.applicationContext).audits().filter { it.host == r.host && it.error.isEmpty() }.sortedBy { it.timestamp }.takeLast(12).map { it.score } } catch (e: Exception) { emptyList() }
        }
    }
    if (points.size < 2) return
    val delta = points.last() - points[points.size - 2]
    SoftCard(padding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Динамика оценки", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Pill(if (delta > 0) "▲ +$delta" else if (delta < 0) "▼ $delta" else "Без изменений", if (delta >= 0) tones.good else tones.bad, if (delta >= 0) tones.goodSoft else tones.badSoft)
        }
        val line = MaterialTheme.colorScheme.primary
        val grid = MaterialTheme.colorScheme.outlineVariant
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(56.dp).padding(top = 6.dp)) {
            val w = size.width; val h = size.height
            drawLine(grid, androidx.compose.ui.geometry.Offset(0f, h), androidx.compose.ui.geometry.Offset(w, h), 1f)
            val step = w / (points.size - 1)
            val path = androidx.compose.ui.graphics.Path()
            points.forEachIndexed { i, p ->
                val x = i * step; val y = h - h * p.coerceIn(0, 100) / 100f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, line, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            points.forEachIndexed { i, p -> drawCircle(line, 5f, androidx.compose.ui.geometry.Offset(i * step, h - h * p.coerceIn(0, 100) / 100f)) }
        }
        Text("Проверок: ${points.size} · от ${points.first()} до ${points.last()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
