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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        if (report == null) Start(vm) else ReportView(vm, report, state)
    }
}

@Composable
private fun Start(vm: SiteViewModel) {
    ScreenHeader("Проверка сайта", "Понятный отчёт: скорость, поиск, защита и домен")
    Column(Modifier.padding(horizontal = 16.dp)) {
        SoftCard {
            BigField(vm.input, { vm.input = it }, "Адрес сайта, например example.com", leading = Icons.Rounded.Language, onGo = { vm.run() })
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !vm.fullCrawl, onClick = { vm.fullCrawl = false }, label = { Text("Одна страница") }, leadingIcon = { Icon(Icons.Rounded.Bolt, null, Modifier.size(18.dp)) })
                FilterChip(selected = vm.fullCrawl, onClick = { vm.fullCrawl = true }, label = { Text("Весь сайт") }, leadingIcon = { Icon(Icons.Rounded.AccountTree, null, Modifier.size(18.dp)) })
            }
            AnimatedVisibility(vm.fullCrawl) {
                Column {
                    Text("Обойти до ${vm.maxPages} страниц", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    Slider(value = vm.maxPages.toFloat(), onValueChange = { vm.maxPages = it.toInt() }, valueRange = 5f..200f)
                    Text("Ищем дубли, битые страницы и «сирот». Обход идёт бережно и учитывает robots.txt.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = { vm.run() }, enabled = !vm.running && vm.input.isNotBlank(), modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Rounded.Search, null); Spacer(Modifier.width(8.dp)); Text("Проверить")
            }
        }
        if (vm.running) { Spacer(Modifier.height(12.dp)); LoadingCard(vm.stage, vm.stageDetail, onCancel = { vm.cancel() }) }

        if (vm.history.isNotEmpty()) {
            SectionTitle("Вы проверяли")
            SoftCard(padding = 6.dp) {
                vm.history.forEachIndexed { index, item ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(enabled = !vm.running) { vm.fullCrawl = item.full; vm.run(item.target) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        ScoreRing(item.score, size = 44.dp, stroke = 5.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(item.host, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(SimpleDateFormat("d MMM yyyy, HH:mm", Locale("ru")).format(Date(item.time)) + if (item.full) " · весь сайт" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (vm.monitored.contains(item.target)) Icon(Icons.Rounded.NotificationsActive, "Под наблюдением", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    }
                    if (index < vm.history.size - 1) HorizontalDivider(Modifier.padding(start = 66.dp, end = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
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
            context.contentResolver.openOutputStream(uri)?.use { it.write(SiteReportBuilder.toHtml(r).toByteArray()) }
            toast(context, "Отчёт сохранён")
        } catch (e: Exception) { toast(context, "Не удалось сохранить") }
    }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { vm.clear() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
        Text(r.host.ifEmpty { "Отчёт" }, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        IconButton(onClick = { vm.run(r.target) }) { Icon(Icons.Rounded.Refresh, "Проверить снова") }
        IconButton(onClick = { shareText(context, SiteReportBuilder.toText(r), "Отчёт: ${r.host}") }) { Icon(Icons.Rounded.Share, "Поделиться") }
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        // Итог
        val color = scoreColor(r.score)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.72f)))).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScoreRing(r.score, size = 112.dp, stroke = 11.dp, caption = "из 100", onColor = Color.White)
                Spacer(Modifier.width(18.dp))
                Column(Modifier.weight(1f)) {
                    Text(r.headline, color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    Text("Оценка ${r.grade}", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.titleSmall)
                    r.delta?.let { d ->
                        if (d != 0) Text((if (d > 0) "▲ +$d" else "▼ $d") + " с прошлой проверки", color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                        else Text("Без изменений с прошлой проверки", color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
        if (vm.running) { Spacer(Modifier.height(12.dp)); LoadingCard(vm.stage, vm.stageDetail, onCancel = { vm.cancel() }) }

        // Разделы-плитки
        val scored = r.sections.filter { it.weight > 0 && it.checks.isNotEmpty() }
        if (scored.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in scored) Pill("${s.title} ${s.score}", scoreColor(s.score), MaterialTheme.colorScheme.surfaceContainerLowest, icon = sectionIcon(s.key))
            }
        }

        // План действий
        val tasks = r.tasks
        if (tasks.isNotEmpty()) {
            SectionTitle("Что сделать в первую очередь")
            SoftCard {
                tasks.take(5).forEachIndexed { index, (section, check) ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
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
                if (tasks.size > 5) Text("И ещё ${tasks.size - 5} — в разделах ниже", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        } else if (r.reachable) {
            Spacer(Modifier.height(12.dp))
            SoftCard(color = tones.goodSoft) { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.Verified, null, tint = tones.good); Spacer(Modifier.width(10.dp)); Text("Серьёзных замечаний нет. Так держать!") } }
        }

        SectionTitle("Подробно по разделам")
        for (s in r.sections) if (s.checks.isNotEmpty()) { SectionCard(s, startOpen = s.key == "availability" && !r.reachable); Spacer(Modifier.height(10.dp)) }

        if (r.facts.isNotEmpty()) {
            SectionTitle("Коротко о сайте")
            SoftCard { for ((k, v) in r.facts) InfoRow(k, v) }
        }

        SectionTitle("Дальше")
        SoftCard(padding = 6.dp) {
            val monitored = vm.monitored.contains(r.target)
            ActionRow(if (monitored) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsNone,
                if (monitored) "Наблюдение включено" else "Следить за сайтом",
                if (monitored) "Сообщим, если сайт упадёт или сертификат начнёт истекать. Нажмите, чтобы выключить." else "Проверять дважды в сутки и сообщать о проблемах") {
                if (!monitored && Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                vm.toggleMonitor(r.target)
            }
            ActionRow(Icons.Rounded.Download, "Сохранить отчёт", "Файл HTML — можно отправить клиенту или коллеге") { saveHtml.launch("sitescope-${r.host}.html") }
            if (r.host.isNotEmpty()) ActionRow(Icons.Rounded.Public, "Где размещён сайт", "Провайдер, страна и сеть сервера") { state.ipRequest = r.host; state.tab = Tab.IP }
            ActionRow(Icons.Rounded.QueryStats, "Метрики поиска", "Скорость по Google, показы и клики, ИКС Яндекса") { open(context, ToolActivity::class.java) { putExtra("screen", "metrics"); putExtra("targets", r.target) } }
            ActionRow(Icons.Rounded.OpenInNew, "Открыть сайт", r.target) {
                try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SiteExtras.normalize(r.target)))) } catch (e: Exception) { }
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
private fun SectionCard(s: Section, startOpen: Boolean) {
    var open by remember(s) { mutableStateOf(startOpen) }
    SoftCard(padding = 0.dp) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            val color = if (s.weight > 0) scoreColor(s.score) else tones.info
            val soft = if (s.weight == 0) tones.infoSoft else if (s.score >= 80) tones.goodSoft else if (s.score >= 55) tones.warnSoft else tones.badSoft
            IconBadge(sectionIcon(s.key), color, soft)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(s.title, style = MaterialTheme.typography.titleMedium)
                Text(if (s.weight > 0) s.verdict else "Найдено: ${s.checks.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s.weight > 0) LinearProgressIndicator(progress = { s.score / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(RoundedCornerShape(3.dp)), color = color, trackColor = MaterialTheme.colorScheme.surfaceContainerHigh)
            }
            Spacer(Modifier.width(10.dp))
            if (s.weight > 0) Text("${s.score}", style = MaterialTheme.typography.titleLarge, color = color)
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.outline)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
                val order = s.checks.sortedBy { when (it.level) { Level.BAD -> 0; Level.WARN -> 1; Level.GOOD -> 2; Level.INFO -> 3 } }
                for (c in order) CheckRow(c)
            }
        }
    }
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
