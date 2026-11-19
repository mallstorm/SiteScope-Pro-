@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package pro.sitescope.app.site

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import pro.sitescope.app.SearchMetricsActivity
import pro.sitescope.app.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SiteScreen(state: AppState, vm: SiteViewModel = viewModel()) {
    LaunchedEffect(state.siteRequest) {
        state.siteRequest?.let { request -> state.siteRequest = null; vm.input = request; vm.run(request) }
    }
    BackHandler(enabled = vm.report != null || vm.running) { if (vm.running) vm.cancel() else vm.clear() }
    val report = vm.report
    Column(Modifier.fillMaxSize()) {
        if (report == null) Start(vm, state) else ReportView(vm, report, state)
    }
}

@Composable
private fun Start(vm: SiteViewModel, state: AppState) {
    AppHeader("Сайты и SEO", "Аудит, доступность, SEO, заголовки, скорость, индексация") { StatusPills(state) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SoftCard {
            BigField(vm.input, { vm.input = it }, "Адрес сайта, например example.com", leading = Icons.Rounded.Language, onGo = { vm.run() })
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !vm.fullCrawl, onClick = { vm.fullCrawl = false }, label = { Text("Одна страница") }, leadingIcon = { Icon(Icons.Rounded.Bolt, null, Modifier.size(18.dp)) })
                FilterChip(selected = vm.fullCrawl, onClick = { vm.fullCrawl = true }, label = { Text("Весь сайт") }, leadingIcon = { Icon(Icons.Rounded.AccountTree, null, Modifier.size(18.dp)) })
            }
            AnimatedVisibility(vm.fullCrawl) {
                Column {
                    Text("Обойти до ${vm.maxPages} страниц · учитываем robots.txt", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    AppSlider(value = vm.maxPages.toFloat(), onValueChange = { vm.maxPages = it.toInt() }, valueRange = 5f..200f)
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(onClick = { vm.run() }, enabled = !vm.running && vm.input.isNotBlank(), modifier = Modifier.fillMaxWidth().height(48.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Rounded.Search, null); Spacer(Modifier.width(8.dp)); Text("Проверить")
            }
        }
        if (vm.running) LoadingCard(vm.stage, vm.stageDetail, onCancel = { vm.cancel() })
        if (vm.history.isNotEmpty()) {
            CompactTitle("Вы проверяли")
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest),
                contentPadding = PaddingValues(6.dp)
            ) {
                itemsIndexed(vm.history) { index, item ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(enabled = !vm.running) { vm.fullCrawl = item.full; vm.run(item.target) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        ScoreRing(item.score, size = 40.dp, stroke = 5.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.host, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(whenText(item.time) + if (item.full) " · весь сайт" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (vm.monitored.contains(item.target)) Icon(Icons.Rounded.NotificationsActive, "Под наблюдением", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                    if (index < vm.history.size - 1) HorizontalDivider(Modifier.padding(start = 60.dp, end = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        } else if (!vm.running) {
            EmptyState(Icons.Rounded.TravelExplore, "Узнайте, как дела у сайта", "Введите адрес — через несколько секунд покажем оценку и что стоит поправить в первую очередь.")
        }
    }
}

@Composable
private fun ReportView(vm: SiteViewModel, r: Report, state: AppState) {
    val context = LocalContext.current
    val saveHtml = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        if (uri != null) try {
            val bytes = SiteReportBuilder.toHtml(r).toByteArray(); context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }; pro.sitescope.app.data.ReportLog.add(context, "sitescope-${r.host}.html", "SEO-аудит (${r.host})", "HTML", bytes.size.toLong())
            toast(context, "Отчёт сохранён")
        } catch (e: Exception) { toast(context, "Не удалось сохранить") }
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(r.target) { vm.loadWatchChanges(r.target) }
    val shown = r.sections.filter { it.checks.isNotEmpty() }
    val titles = listOf("Итог") + shown.map { it.title } + "Действия"
    val icons = listOf<ImageVector>(Icons.Rounded.Dashboard) + shown.map { sectionIcon(it.key) } + Icons.Rounded.Bolt
    var tab by remember(r.target, r.timestamp) { mutableIntStateOf(0) }

    AppHeader(r.host.ifEmpty { "Отчёт" }, "Оценка ${r.grade} · ${whenText(r.timestamp)}", onBack = { vm.clear() }, actions = {
        IconButton(onClick = { vm.run(r.target) }) { Icon(Icons.Rounded.Refresh, "Проверить снова", tint = Color.White) }
        IconButton(onClick = { shareText(context, SiteReportBuilder.toText(r), "Отчёт: ${r.host}") }) { Icon(Icons.Rounded.Share, "Поделиться", tint = Color.White) }
    }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 8.dp)) {
            ScoreRing(r.score, size = 60.dp, stroke = 7.dp, onColor = Color.White)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(r.headline, color = Color.White, style = MaterialTheme.typography.titleMedium)
                val d = r.delta
                Text(
                    when { d == null -> "Первая проверка"; d > 0 -> "▲ +$d с прошлой проверки"; d < 0 -> "▼ $d с прошлой проверки"; else -> "Без изменений с прошлой проверки" },
                    color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall
                )
            }
        }
        HeaderChips(titles, tab, { tab = it }, icons)
    }
    if (vm.running) Box(Modifier.padding(16.dp, 10.dp, 16.dp, 0.dp)) { LoadingCard(vm.stage, vm.stageDetail, onCancel = { vm.cancel() }) }

    val listModifier = Modifier.weight(1f).fillMaxWidth()
    val padding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
    when {
        tab == 0 -> LazyColumn(listModifier, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val scored = r.sections.filter { it.weight > 0 && it.checks.isNotEmpty() }
            if (scored.isNotEmpty()) item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in scored) Pill("${s.title} ${s.score}", scoreColor(s.score), MaterialTheme.colorScheme.surfaceContainerLowest, icon = sectionIcon(s.key))
                }
            }
            val tasks = r.tasks
            if (tasks.isNotEmpty()) {
                item { CompactTitle("Что сделать в первую очередь") }
                itemsIndexed(tasks) { index, (section, check) ->
                    SoftCard(padding = 12.dp) {
                        Row(verticalAlignment = Alignment.Top) {
                            val c = if (check.level == Level.BAD) tones.bad else tones.warn
                            Box(Modifier.size(26.dp).clip(RoundedCornerShape(9.dp)).background(if (check.level == Level.BAD) tones.badSoft else tones.warnSoft), contentAlignment = Alignment.Center) {
                                Text("${index + 1}", color = c, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(check.title, style = MaterialTheme.typography.titleSmall)
                                if (check.fix.isNotEmpty()) Text(check.fix, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(section.title, style = MaterialTheme.typography.labelSmall, color = c)
                            }
                        }
                    }
                }
            } else if (r.reachable) item {
                SoftCard(color = tones.goodSoft) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Verified, null, tint = tones.good); Spacer(Modifier.width(10.dp)); Text("Серьёзных замечаний нет. Так держать!") } }
            }
            if (r.facts.isNotEmpty()) {
                item { CompactTitle("Коротко о сайте") }
                item { SoftCard { for ((k, v) in r.facts) InfoRow(k, v) } }
            }
        }
        tab in 1..shown.size -> {
            val s = shown[tab - 1]
            val order = s.checks.sortedBy { when (it.level) { Level.BAD -> 0; Level.WARN -> 1; Level.GOOD -> 2; Level.INFO -> 3 } }
            LazyColumn(listModifier, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    val color = if (s.weight > 0) scoreColor(s.score) else tones.info
                    SoftCard(padding = 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (s.weight > 0) s.verdict else "Найдено: ${s.checks.size}", style = MaterialTheme.typography.titleSmall)
                                if (s.weight > 0) LinearProgressIndicator(progress = { s.score / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(RoundedCornerShape(3.dp)), color = color, trackColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                            }
                            if (s.weight > 0) { Spacer(Modifier.width(12.dp)); Text("${s.score}", style = MaterialTheme.typography.titleLarge, color = color) }
                        }
                    }
                }
                items(order) { c -> SoftCard(padding = 10.dp) { CheckRow(c) } }
            }
        }
        else -> LazyColumn(listModifier, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            item {
                SoftCard(padding = 6.dp) {
                    val monitored = vm.monitored.contains(r.target)
                    ActionRow(if (monitored) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsNone,
                        if (monitored) "Наблюдение включено" else "Следить за сайтом",
                        if (monitored) "Сообщим, если сайт упадёт или сертификат начнёт истекать. Нажмите, чтобы выключить." else "Проверять дважды в сутки и сообщать о проблемах") {
                        if (!monitored && Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                        vm.toggleMonitor(r.target)
                    }
                    if (monitored) ActionRow(Icons.Rounded.Rule,
                        if (vm.watchChanges) "Следим за изменениями страницы" else "Сообщать об изменениях страницы",
                        "Сравниваем текст страницы при каждой плановой проверке и сообщаем, если он поменялся") { vm.toggleWatchChanges(r.target) }
                    ActionRow(Icons.Rounded.Download, "Сохранить отчёт", "Файл HTML — можно отправить клиенту или коллеге") { saveHtml.launch("sitescope-${r.host}.html") }
                    ActionRow(Icons.Rounded.Dns, "Проверить список сайтов", "Пакетная проверка нескольких доменов сразу") { state.openRoute("batch") }
                    if (r.host.isNotEmpty()) ActionRow(Icons.Rounded.Public, "Где размещён сайт", "Провайдер, страна и сеть сервера") { state.ipRequest = r.host; state.openRoute("ip") }
                    ActionRow(Icons.Rounded.QueryStats, "Метрики поиска", "Скорость по Google, показы и клики, ИКС Яндекса") { state.openRoute("metrics", 0, r.target) }
                    ActionRow(Icons.Rounded.OpenInNew, "Открыть сайт", r.target) {
                        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SiteExtras.normalize(r.target)))) } catch (e: Exception) { }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, title: String, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer, 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun sectionIcon(key: String): ImageVector = when (key) {
    "availability" -> Icons.Rounded.CheckCircle; "speed" -> Icons.Rounded.Speed; "seo" -> Icons.Rounded.Search
    "security" -> Icons.Rounded.Lock; "domain" -> Icons.Rounded.Dns; "links" -> Icons.Rounded.Link; else -> Icons.Rounded.Category
}

@Composable
private fun CheckRow(c: Check) {
    val (icon, color) = when (c.level) {
        Level.GOOD -> Icons.Rounded.CheckCircle to tones.good
        Level.WARN -> Icons.Rounded.Warning to tones.warn
        Level.BAD -> Icons.Rounded.Cancel to tones.bad
        Level.INFO -> Icons.Rounded.Info to tones.info
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = color, modifier = Modifier.size(22.dp).padding(top = 1.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(c.title, style = MaterialTheme.typography.titleSmall)
            if (c.text.isNotEmpty()) Text(c.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (c.fix.isNotEmpty() && c.level != Level.GOOD) {
                Row(Modifier.padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Icon(Icons.Rounded.Lightbulb, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(c.fix, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            TechDetails(c.tech, Modifier.padding(top = 2.dp))
        }
    }
}
