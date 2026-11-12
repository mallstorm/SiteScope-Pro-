@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package pro.sitescope.app.tv

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import pro.sitescope.app.IptvActivity
import pro.sitescope.app.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun statusColor(status: StreamStatus): Color = when (status) {
    StreamStatus.OK -> tones.good; StreamStatus.SLOW -> tones.warn; StreamStatus.BLOCKED, StreamStatus.AUTH -> tones.info
    StreamStatus.DEAD -> tones.bad; StreamStatus.UNKNOWN -> MaterialTheme.colorScheme.outlineVariant
}

fun play(context: Context, list: List<Channel>, index: Int) {
    if (list.isEmpty()) return
    PlayerQueue.channels = list; PlayerQueue.index = index.coerceIn(0, list.size - 1)
    context.startActivity(Intent(context, TvPlayerActivity::class.java))
}

@Composable
fun TvScreen(state: AppState, vm: TvViewModel = viewModel()) {
    LaunchedEffect(TvViewModel.externalChange) { vm.syncFromStore() }
    val context = LocalContext.current
    var addSheet by remember { mutableStateOf(false) }
    var manageSheet by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<Channel?>(null) }
    var prefillUrl by remember { mutableStateOf("") }

    LaunchedEffect(state.playlistRequest) { state.playlistRequest?.let { state.playlistRequest = null; prefillUrl = it; addSheet = true } }
    LaunchedEffect(state.streamRequest) { state.streamRequest?.let { state.streamRequest = null; play(context, listOf(vm.adHoc(it)), 0) } }
    LaunchedEffect(vm.message) { if (vm.message.isNotEmpty()) { toast(context, vm.message); vm.message = "" } }

    val tick = vm.tick
    val visible = remember(tick, vm.channels, vm.query, vm.group, vm.statusFilter) { vm.visible() }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader("Телевидение", if (vm.channels.isEmpty()) "Плейлисты, проверка и просмотр" else "Каналов: ${vm.channels.size}") {
            if (vm.channels.isNotEmpty()) {
                IconButton(onClick = { addSheet = true }) { Icon(Icons.Rounded.Add, "Добавить плейлист") }
                IconButton(onClick = { manageSheet = true }) { Icon(Icons.Rounded.Tune, "Источники и настройки") }
            }
        }
        if (vm.busy.isNotEmpty()) Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) { LoadingCard(vm.busy) }

        if (vm.loaded && vm.channels.isEmpty()) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                EmptyState(Icons.Rounded.LiveTv, "Добавьте свои каналы", "Вставьте ссылку на плейлист, выберите файл M3U или войдите по данным Xtream. Мы проверим, какие каналы работают, и покажем программу передач.") {
                    Button(onClick = { addSheet = true }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Добавить плейлист") }
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { open(context, ToolActivity::class.java) { putExtra("screen", "tvlan") } }) { Text("Найти IPTV в моей сети (udpxy, SAP)") }
                }
            }
        } else if (vm.channels.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 16.dp)) {
                BigField(vm.query, { vm.query = it }, "Найти канал", keyboard = KeyboardType.Text)
                Spacer(Modifier.height(8.dp))
                CheckPanel(vm, visible)
            }
            // Группы
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GroupChip("Все", vm.group == TvViewModel.ALL) { vm.group = TvViewModel.ALL }
                GroupChip("★ Избранное", vm.group == TvViewModel.FAVORITES) { vm.group = TvViewModel.FAVORITES }
                GroupChip("Недавние", vm.group == TvViewModel.RECENT) { vm.group = TvViewModel.RECENT }
                val groups = remember(vm.channels) { vm.groups() }
                for (g in groups.take(60)) GroupChip(g, vm.group == g) { vm.group = g }
            }
            if (visible.isEmpty()) {
                EmptyState(Icons.Rounded.SearchOff, "Ничего не нашлось", when {
                    vm.group == TvViewModel.FAVORITES -> "Удерживайте канал и нажмите «В избранное»."
                    vm.group == TvViewModel.RECENT -> "Здесь появятся каналы, которые вы смотрели."
                    else -> "Попробуйте другой запрос или фильтр."
                })
            } else LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = { it.id + it.sourceId + it.number }) { channel ->
                    ChannelRow(channel, tick, onClick = { vm.markWatched(channel); play(context, visible, visible.indexOf(channel)) }, onLong = { details = channel })
                }
            }
        }
    }

    if (addSheet) AddSheet(vm, prefillUrl) { addSheet = false; prefillUrl = "" }
    if (manageSheet) ManageSheet(vm) { manageSheet = false }
    details?.let { channel -> DetailsSheet(vm, channel, tick, onPlay = { vm.markWatched(channel); play(context, visible, maxOf(0, visible.indexOf(channel))) }) { details = null } }
}

@Composable
private fun GroupChip(title: String, selected: Boolean, onClick: () -> Unit) =
    FilterChip(selected = selected, onClick = onClick, label = { Text(title, maxLines = 1) })

@Composable
private fun CheckPanel(vm: TvViewModel, visible: List<Channel>) {
    val tick = vm.tick
    SoftCard(padding = 14.dp) {
        if (vm.checking) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Проверяем: ${vm.checkDone} из ${vm.checkTotal}", style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(progress = { if (vm.checkTotal == 0) 0f else vm.checkDone.toFloat() / vm.checkTotal }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(RoundedCornerShape(3.dp)))
                }
                Spacer(Modifier.width(12.dp))
                FilledTonalButton(onClick = { vm.stopCheck() }) { Text("Стоп") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Проверка каналов", style = MaterialTheme.typography.titleSmall)
                    Text(if (vm.lastCheckAt == 0L) "Узнайте, какие каналы работают" else "Последняя: " + SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(vm.lastCheckAt)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { vm.startCheck(visible) }, enabled = visible.isNotEmpty()) { Text(if (visible.size == vm.channels.size) "Проверить все" else "Проверить ${visible.size}") }
            }
        }
        val counts = remember(tick, vm.channels) { StreamStatus.entries.associateWith { s -> vm.channels.count { it.status == s } } }
        if (counts.any { it.key != StreamStatus.UNKNOWN && it.value > 0 }) {
            FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (s in listOf(StreamStatus.OK, StreamStatus.SLOW, StreamStatus.BLOCKED, StreamStatus.AUTH, StreamStatus.DEAD)) {
                    val n = counts[s] ?: 0
                    if (n > 0) FilterChip(selected = vm.statusFilter == s, onClick = { vm.statusFilter = if (vm.statusFilter == s) null else s },
                        label = { Text("${s.title} $n") }, leadingIcon = { Dot(statusColor(s)) })
                }
            }
        }
    }
}

@Composable
private fun Logo(channel: Channel, size: androidx.compose.ui.unit.Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 3.4f)).background(tones.tvSoft), contentAlignment = Alignment.Center) {
        Text(channel.name.trim().take(1).uppercase(), color = tones.tv, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        if (channel.logo.startsWith("http")) AsyncImage(model = channel.logo, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(4.dp))
    }
}

@Composable
private fun ChannelRow(channel: Channel, tick: Int, onClick: () -> Unit, onLong: () -> Unit) {
    val now = remember(tick, channel.id) { Epg.now(channel) }
    Card(shape = MaterialTheme.shapes.medium, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).combinedClickable(onClick = onClick, onLongClick = onLong)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Logo(channel, 48.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (channel.favorite) Icon(Icons.Rounded.Star, null, tint = tones.warn, modifier = Modifier.padding(start = 4.dp).size(16.dp))
                }
                if (now != null) {
                    Text(now.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    LinearProgressIndicator(progress = { now.progress(System.currentTimeMillis()) }, modifier = Modifier.fillMaxWidth(0.7f).padding(top = 4.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                        color = tones.tv, trackColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                } else {
                    val sub = when {
                        channel.status != StreamStatus.UNKNOWN && channel.status != StreamStatus.OK -> channel.detail
                        channel.group.isNotEmpty() -> channel.group
                        else -> ""
                    }
                    if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            if (channel.quality.isNotEmpty()) { Pill(channel.quality, tones.tv, tones.tvSoft); Spacer(Modifier.width(8.dp)) }
            Dot(statusColor(channel.status), 12.dp)
        }
    }
}

@Composable
private fun DetailsSheet(vm: TvViewModel, channel: Channel, tick: Int, onPlay: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Logo(channel, 56.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(channel.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (channel.group.isNotEmpty()) Text(channel.group, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(14.dp))
            key(tick) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(statusColor(channel.status), 14.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(channel.status.title, style = MaterialTheme.typography.titleSmall)
                        if (channel.detail.isNotEmpty()) Text(channel.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { vm.checkOne(channel); toast(context, "Проверяем…") }) { Text("Проверить") }
                }
                if (channel.checkedAt > 0) {
                    Spacer(Modifier.height(8.dp))
                    InfoRow("Запуск", if (channel.startMs > 0) "%.1f с".format(channel.startMs / 1000.0) else "", copyable = false)
                    InfoRow("Качество", listOf(channel.quality, channel.resolution).filter { it.isNotEmpty() }.joinToString(" · "), copyable = false)
                    InfoRow("Поток", if (channel.bitrateKbps > 0) StreamChecker.fmtRate(channel.bitrateKbps) else "", copyable = false)
                    InfoRow("Формат", listOf(channel.container, channel.video, channel.audio).filter { it.isNotEmpty() }.joinToString(" · "), copyable = false)
                    if (channel.uptimePercent >= 0) InfoRow("Надёжность", "${channel.uptimePercent}% (проверок: ${channel.okCount + channel.failCount})", copyable = false)
                }
            }
            val programmes = remember(tick, channel.id) { val t = System.currentTimeMillis(); Epg.forChannel(channel).filter { it.stop > t }.take(5) }
            if (programmes.isNotEmpty()) {
                SectionTitle("Программа")
                val format = remember { SimpleDateFormat("HH:mm", Locale.US) }
                for (p in programmes) Row(Modifier.padding(vertical = 4.dp)) {
                    Text(format.format(Date(p.start)), style = MaterialTheme.typography.labelLarge, color = tones.tv, modifier = Modifier.width(52.dp))
                    Text(p.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(14.dp))
            Button(onClick = { onClose(); onPlay() }, modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Смотреть") }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { vm.toggleFavorite(channel) }) { key(tick) { Text(if (channel.favorite) "Убрать из избранного" else "В избранное") } }
                TextButton(onClick = { copyText(context, channel.url, "Ссылка скопирована") }) { Text("Ссылка") }
                TextButton(onClick = { vm.remove(channel); onClose() }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            }
            RecordAndArchiveRow(vm, channel)
            TechDetails(listOf(channel.url, if (channel.userAgent.isNotEmpty()) "User-Agent: ${channel.userAgent}" else "", if (channel.referrer.isNotEmpty()) "Referer: ${channel.referrer}" else "",
                if (channel.tvgId.isNotEmpty()) "tvg-id: ${channel.tvgId}" else "", if (channel.httpCode > 0) "HTTP ${channel.httpCode}" else "").filter { it.isNotEmpty() }.joinToString("\n"))
        }
    }
}

@Composable
private fun RecordAndArchiveRow(vm: TvViewModel, channel: Channel) {
    val context = LocalContext.current
    var archiveBusy by remember { mutableStateOf(false) }
    val isThis = TvRecorder.recording && TvRecorder.channelId == channel.id
    val busyOther = TvRecorder.recording && TvRecorder.channelId != channel.id
    Column {
        if (isThis) {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FiberManualRecord, null, tint = tones.bad, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text("Идёт запись · ${fmtBytes(TvRecorder.bytesWritten)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { TvRecorder.stop(context) }) { Text("Остановить", color = MaterialTheme.colorScheme.error) }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { TvRecorder.start(context, channel, vm.udpxy, 60); toast(context, "Запись начата: ${channel.name}") }, enabled = !busyOther) {
                    Icon(Icons.Rounded.FiberManualRecord, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Записать (1 ч)")
                }
                if (Playlist.catchupUrl(channel, 0, 1) != null) TextButton(onClick = {
                    archiveBusy = true
                    Thread({ val r = ArchiveChecker.check(channel); archiveBusy = false; toast(context, r.detail) }, "SiteScope-ArchiveOne").start()
                }, enabled = !archiveBusy) { Text(if (archiveBusy) "Проверяем…" else "Проверить архив") }
            }
        }
        if (TvRecorder.error.isNotEmpty() && TvRecorder.channelId == channel.id) Text(TvRecorder.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

private fun fmtBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> "%.2f ГБ".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> "%.1f МБ".format(bytes / (1024.0 * 1024))
    bytes >= 1024 -> "%.0f КБ".format(bytes / 1024.0)
    else -> "$bytes Б"
}

@Composable
private fun AddSheet(vm: TvViewModel, prefill: String, onClose: () -> Unit) {
    var mode by remember { mutableIntStateOf(0) }
    var url by remember { mutableStateOf(prefill) }
    var server by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) { vm.addFile(uri); onClose() } }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Добавить плейлист", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Ссылка") })
                FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Xtream") })
                FilterChip(selected = false, onClick = { pickFile.launch(arrayOf("*/*")) }, label = { Text("Файл M3U") }, leadingIcon = { Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp)) })
            }
            Spacer(Modifier.height(12.dp))
            if (mode == 0) {
                BigField(url, { url = it }, "https://…/playlist.m3u", leading = Icons.Rounded.Link, onGo = { vm.addUrl(url); onClose() })
                Spacer(Modifier.height(12.dp))
                Button(onClick = { vm.addUrl(url); onClose() }, enabled = url.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) { Text("Загрузить") }
            } else {
                OutlinedTextField(server, { server = it }, label = { Text("Адрес сервера") }, placeholder = { Text("http://portal.example:8080") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(user, { user = it }, label = { Text("Логин") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
                Spacer(Modifier.height(12.dp))
                Button(onClick = { vm.addXtream(server, user, password); onClose() }, enabled = server.isNotBlank() && user.isNotBlank() && password.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) { Text("Войти и загрузить") }
            }
        }
    }
}

@Composable
private fun ManageSheet(vm: TvViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var exportText by remember { mutableStateOf("") }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/x-mpegurl")) { uri -> if (uri != null) vm.write(uri, exportText) }
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var archiveChecking by remember { mutableStateOf(false) }
    var archiveDone by remember { mutableIntStateOf(0) }
    var archiveTotal by remember { mutableIntStateOf(0) }
    var archiveSummary by remember { mutableStateOf("") }
    var recordingsTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(TvRecorder.finished) { recordingsTick++ }
    val recordings = remember(recordingsTick) { TvRecorder.recordings(context) }
    ModalBottomSheet(onDismissRequest = { vm.saveSettings(); onClose() }) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Text("Источники", style = MaterialTheme.typography.titleLarge)
            for (s in vm.sources) Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("Каналов: ${s.count} · " + SimpleDateFormat("d MMM", Locale("ru")).format(Date(s.updatedAt)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (s.type == "url") IconButton(onClick = { vm.refreshSource(s) }) { Icon(Icons.Rounded.Refresh, "Обновить") }
                IconButton(onClick = { vm.removeSource(s) }) { Icon(Icons.Rounded.DeleteOutline, "Удалить", tint = MaterialTheme.colorScheme.error) }
            }

            SectionTitle("Порядок в списке")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = { vm.removeDead() }, label = { Text("Убрать нерабочие") }, leadingIcon = { Icon(Icons.Rounded.CleaningServices, null, Modifier.size(18.dp)) })
                AssistChip(onClick = { vm.dedupe() }, label = { Text("Убрать повторы") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)) })
                AssistChip(onClick = { exportText = vm.exportM3u(true); save.launch("working.m3u") }, label = { Text("Сохранить рабочие") }, leadingIcon = { Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)) })
                AssistChip(onClick = { exportText = vm.exportM3u(false); save.launch("playlist.m3u") }, label = { Text("Сохранить все") })
            }

            SectionTitle("Телепрограмма")
            Text(vm.epgInfo, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            BigField(vm.epgUrls, { vm.epgUrls = it }, "Ссылки на XMLTV, по одной в строке", singleLine = false, minLines = 2)
            TextButton(onClick = { vm.saveSettings(); vm.refreshEpg() }, enabled = !vm.epgBusy) { Text(if (vm.epgBusy) "Загружаем…" else "Обновить программу") }

            SectionTitle("Проверка")
            Text("Одновременных проверок: ${vm.threads}", style = MaterialTheme.typography.bodyMedium)
            Slider(value = vm.threads.toFloat(), onValueChange = { vm.threads = it.toInt() }, valueRange = 1f..32f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Проверять раз в сутки", style = MaterialTheme.typography.titleSmall)
                    Text("По Wi-Fi, в фоне. Сообщим, если избранные каналы перестали работать.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = vm.autoCheck, onCheckedChange = {
                    vm.autoCheck = it
                    if (it && Build.VERSION.SDK_INT >= 33) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                })
            }

            SectionTitle("Архив передач (catch-up)")
            val withArchive = remember(vm.channels) { vm.channels.count { ArchiveChecker.supportsArchive(it) } }
            if (withArchive == 0) {
                Text("В плейлисте нет каналов с архивом (catch-up/timeshift)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (archiveChecking) {
                Text("Проверяем архив: $archiveDone из $archiveTotal", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(progress = { if (archiveTotal == 0) 0f else archiveDone.toFloat() / archiveTotal }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(6.dp).clip(RoundedCornerShape(3.dp)))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Каналов с архивом: $withArchive", style = MaterialTheme.typography.bodyMedium)
                        if (archiveSummary.isNotEmpty()) Text(archiveSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = {
                        archiveChecking = true; archiveDone = 0; archiveTotal = withArchive; archiveSummary = ""
                        ArchiveChecker.checkAll(vm.channels, vm.threads, object : ArchiveChecker.Listener {
                            override fun onResult(result: ArchiveChecker.Result, done: Int, total: Int) { archiveDone = done; archiveTotal = total }
                            override fun onFinished(results: List<ArchiveChecker.Result>) {
                                archiveChecking = false
                                val ok = results.count { it.ok }
                                archiveSummary = "Открывается у $ok из ${results.size}"
                            }
                        })
                    }) { Text("Проверить") }
                }
            }

            SectionTitle("Мои записи")
            if (recordings.isEmpty() && !TvRecorder.recording) {
                Text("Пока нет записей. Откройте канал и нажмите «Записать» в его карточке.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                if (TvRecorder.recording) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FiberManualRecord, null, tint = tones.bad, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("${TvRecorder.channelName} · ${fmtBytes(TvRecorder.bytesWritten)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { TvRecorder.stop(context) }) { Text("Стоп") }
                }
                for (file in recordings.take(20)) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(fmtBytes(file.length()) + " · " + SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(file.lastModified())), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { TvRecorder.delete(file); recordingsTick++ }) { Icon(Icons.Rounded.DeleteOutline, "Удалить", tint = MaterialTheme.colorScheme.error) }
                }
                Text("Папка: ${TvRecorder.outputDir(context).absolutePath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            SectionTitle("Для локальной сети")
            OutlinedTextField(vm.udpxy, { vm.udpxy = it }, label = { Text("Адрес udpxy (необязательно)") }, placeholder = { Text("192.168.1.1:4022") }, singleLine = true, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
            Text("Нужен, если каналы провайдера идут как udp:// или rtp://, а смотреть нужно через роутер.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            TextButton(onClick = { open(context, ToolActivity::class.java) { putExtra("screen", "tvlan") } }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Поиск udpxy и анонсов SAP в сети") }
        }
    }
}
