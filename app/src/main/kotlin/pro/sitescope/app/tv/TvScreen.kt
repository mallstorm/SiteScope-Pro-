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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
    var manageSheet by rememberSaveable { mutableStateOf(false) }
    var checkFirst by rememberSaveable { mutableStateOf(false) }
    var recordsSheet by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<Channel?>(null) }
    var prefillUrl by remember { mutableStateOf("") }

    LaunchedEffect(state.playlistRequest) { state.playlistRequest?.let { state.playlistRequest = null; prefillUrl = it; addSheet = true } }
    LaunchedEffect(state.streamRequest) { state.streamRequest?.let { state.streamRequest = null; play(context, listOf(vm.adHoc(it)), 0) } }
    LaunchedEffect(state.tvSourcesRequest) {
        if (state.tvSourcesRequest) {
            state.tvSourcesRequest = false
            manageSheet = true
        }
    }
    LaunchedEffect(state.tvCheckRequest) {
        if (state.tvCheckRequest) {
            state.tvCheckRequest = false
            checkFirst = true
        }
    }
    LaunchedEffect(vm.message) { if (vm.message.isNotEmpty()) { toast(context, vm.message); vm.message = "" } }

    val tick = vm.tick
    val visible = remember(tick, vm.channels, vm.query, vm.group, vm.statusFilter) { vm.visible() }

    Column(Modifier.fillMaxSize()) {
        AppHeader("Телевидение", if (vm.channels.isEmpty()) "IPTV / M3U, udpxy, multicast, проверка и просмотр" else "Каналы, источники и диагностика · ${vm.channels.size}",
            actions = {
                IconButton(onClick = { state.openRoute("satellite") }) { Icon(Icons.Rounded.SettingsInputAntenna, "Спутниковая антенна") }
                if (vm.channels.isNotEmpty()) {
                    IconButton(onClick = { addSheet = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Add, "Добавить плейлист") }
                    IconButton(onClick = { if (vm.checking) vm.stopCheck() else { vm.startCheck(vm.channels.toList()); toast(context, "Проверяем все каналы") } }, modifier = Modifier.size(40.dp)) { Icon(if (vm.checking) Icons.Rounded.Stop else Icons.Rounded.VerifiedUser, "Проверить все каналы") }
                    IconButton(onClick = { recordsSheet = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.FiberManualRecord, "Записи") }
                    IconButton(onClick = { manageSheet = true }, modifier = Modifier.size(40.dp)) { Icon(Icons.Rounded.Tune, "Источники и настройки") }
                }
            }
        ) {
        }
        if (vm.busy.isNotEmpty()) Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { LoadingCard(vm.busy) }

        if (vm.loaded && vm.channels.isEmpty()) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                EmptyState(Icons.Rounded.LiveTv, "Добавьте свои каналы", "Вставьте ссылку на плейлист или выберите файл M3U. Мы проверим, какие каналы работают, и покажем программу передач.") {
                    Button(onClick = { addSheet = true }, modifier = Modifier.fillMaxWidth().height(44.dp), shape = RoundedCornerShape(14.dp)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("Добавить плейлист") }
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = { state.openRoute("tvlan") }) { Text("Найти IPTV в моей сети (udpxy, SAP)") }
                }
            }
        } else if (vm.channels.isNotEmpty()) {
            Column(Modifier.padding(horizontal = 12.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (checkFirst) CheckPanel(vm, visible)
                SmartSearchBar(vm.query, { vm.query = it }, "Найти канал", onGo = { }, scan = true)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MiniChip("Все", vm.group == TvViewModel.ALL) { vm.group = TvViewModel.ALL }
                    MiniChip("★ Избранное", vm.group == TvViewModel.FAVORITES) { vm.group = TvViewModel.FAVORITES }
                    MiniChip("Недавние", vm.group == TvViewModel.RECENT) { vm.group = TvViewModel.RECENT }
                    val groups = remember(vm.channels) { vm.groups() }
                    for (g in groups.take(60)) MiniChip(g, vm.group == g) { vm.group = g }
                }
                TvSummary(vm, tick)
                if (!checkFirst) CheckPanel(vm, visible)
            }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (visible.isEmpty()) item {
                    EmptyState(Icons.Rounded.SearchOff, "Ничего не нашлось", when {
                        vm.group == TvViewModel.FAVORITES -> "Удерживайте канал и нажмите «В избранное»."
                        vm.group == TvViewModel.RECENT -> "Здесь появятся каналы, которые вы смотрели."
                        else -> "Попробуйте другой запрос или фильтр."
                    })
                }
                items(visible, key = { it.id + it.sourceId + it.number }) { channel ->
                    ChannelRow(channel, tick, onPlay = { vm.markWatched(channel); play(context, visible, visible.indexOf(channel)) }, onMore = { details = channel },
                        onFavorite = { vm.toggleFavorite(channel) })
                }
                item { CompactTitle("Источники", action = "Подробнее ›") { manageSheet = true } }
                item { SourcesCard(vm) { manageSheet = true } }
                item { CompactTitle("Быстрые действия") }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        QuickTile(Icons.Rounded.LibraryAdd, "Добавить плейлист", Modifier.weight(1f)) { addSheet = true }
                        QuickTile(Icons.Rounded.VerifiedUser, "Проверить все", Modifier.weight(1f)) { if (!vm.checking) vm.startCheck(vm.channels.toList()) }
                        QuickTile(Icons.Rounded.Search, "Поиск по маске", Modifier.weight(1f)) { state.openRoute("tvmask") }
                        QuickTile(Icons.Rounded.AccountTree, "ТВ в локальной сети", Modifier.weight(1f)) { state.openRoute("tvlan") }
                    }
                }
            }
        }
    }

    if (addSheet) AddSheet(vm, prefillUrl) { addSheet = false; prefillUrl = "" }
    if (manageSheet) ManageSheet(vm) { manageSheet = false }
    if (recordsSheet) RecordsSheet { recordsSheet = false }
    details?.let { channel ->
        DetailsSheet(vm, channel, tick, onPlay = {
            val index = visible.indexOf(channel)
            vm.markWatched(channel)
            if (index >= 0) play(context, visible, index) else play(context, listOf(channel), 0)
        }) { details = null }
    }
}

@Composable
private fun CheckPanel(vm: TvViewModel, visible: List<Channel>) {
    val tick = vm.tick
    Column(Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(horizontal = 10.dp, vertical = 8.dp)) {
        if (vm.checking) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Проверяем: ${vm.checkDone} из ${vm.checkTotal}", style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(progress = { if (vm.checkTotal == 0) 0f else vm.checkDone.toFloat() / vm.checkTotal }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(5.dp).clip(RoundedCornerShape(3.dp)))
                }
                Spacer(Modifier.width(10.dp))
                FilledTonalButton(onClick = { vm.stopCheck() }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Стоп") }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Проверка каналов", style = MaterialTheme.typography.titleSmall)
                    Text(if (vm.lastCheckAt == 0L) "Узнайте, какие каналы работают" else "Последняя: " + SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(vm.lastCheckAt)),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { vm.startCheck(visible) }, enabled = visible.isNotEmpty(), contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Text(if (visible.size == vm.channels.size) "Проверить все" else "Проверить ${visible.size}")
                }
            }
        }
        val counts = remember(tick, vm.channels) { StreamStatus.entries.associateWith { s -> vm.channels.count { it.status == s } } }
        if (counts.any { it.key != StreamStatus.UNKNOWN && it.value > 0 }) {
            Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (s in listOf(StreamStatus.OK, StreamStatus.SLOW, StreamStatus.BLOCKED, StreamStatus.AUTH, StreamStatus.DEAD)) {
                    val n = counts[s] ?: 0
                    if (n > 0) MiniChip("${s.title} $n", vm.statusFilter == s, dot = statusColor(s)) { vm.statusFilter = if (vm.statusFilter == s) null else s }
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
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(3.dp))
    }
}

@Composable
private fun TvSummary(vm: TvViewModel, tick: Int) {
    val ok = remember(tick, vm.channels) { vm.channels.count { it.status == StreamStatus.OK || it.status == StreamStatus.SLOW } }
    val archive = remember(vm.channels) { vm.channels.count { ArchiveChecker.supportsArchive(it) } }
    SoftCard(padding = 8.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.LiveTv, MaterialTheme.colorScheme.onPrimary, MaterialTheme.colorScheme.primary, 38.dp)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1.3f)) {
                Text("Телевидение", style = MaterialTheme.typography.titleSmall)
                Text("Плейлисты, каналы, доступность и архив", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            SummaryStat(Icons.Rounded.Layers, "Плейлисты", vm.sources.size.toString(), null, Modifier.weight(1f))
            SummaryStat(Icons.Rounded.Tv, "Каналы", vm.channels.size.toString(), null, Modifier.weight(1f))
            SummaryStat(Icons.Rounded.CheckCircle, "Работают", if (vm.channels.any { it.checkedAt > 0 }) ok.toString() else "—", tones.good, Modifier.weight(1f))
            SummaryStat(Icons.Rounded.Inventory2, "Архив", archive.toString(), null, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SummaryStat(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, tint: Color?, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = tint ?: MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

@Composable
private fun ChannelRow(channel: Channel, tick: Int, onPlay: () -> Unit, onMore: () -> Unit, onFavorite: () -> Unit) {
    val now = remember(tick, channel.id) { Epg.now(channel) }
    val tech = listOf(listOf(channel.quality, if (channel.resolution.contains('x')) "(" + channel.resolution.substringAfter('x') + ")" else "").filter { it.isNotEmpty() }.joinToString(" "), channel.video)
        .filter { it.isNotEmpty() }.joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .combinedClickable(onClick = onMore, onLongClick = onMore).padding(start = 8.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Logo(channel, 38.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(channel.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val second = when {
                tech.isNotEmpty() -> tech
                channel.status != StreamStatus.UNKNOWN && channel.status != StreamStatus.OK -> channel.detail
                else -> ""
            }
            if (second.isNotEmpty()) Text(second, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (channel.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Избранное", tint = if (channel.favorite) tones.warn else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp).clickable(onClick = onFavorite))
                Spacer(Modifier.width(3.dp))
                Text(now?.title ?: channel.group, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (now != null) LinearProgressIndicator(progress = { now.progress(System.currentTimeMillis()) }, modifier = Modifier.fillMaxWidth(0.7f).padding(top = 2.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = tones.tv, trackColor = MaterialTheme.colorScheme.surfaceContainerHigh)
        }
        Spacer(Modifier.width(4.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(statusColor(channel.status), 8.dp); Spacer(Modifier.width(3.dp))
                if (channel.uptimePercent >= 0) Text("${channel.uptimePercent}%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                else Text("—", style = MaterialTheme.typography.labelLarge)
            }
            Text("Аптайм", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (channel.quality.isNotEmpty()) Pill(channel.quality, tones.good, tones.goodSoft)
        }
        Spacer(Modifier.width(6.dp))
        OutlinedButton(onClick = onPlay, modifier = Modifier.height(32.dp), contentPadding = PaddingValues(horizontal = 8.dp), shape = RoundedCornerShape(10.dp)) {
            Icon(Icons.Rounded.PlayArrow, null, Modifier.size(16.dp)); Spacer(Modifier.width(2.dp)); Text("Смотреть", maxLines = 1, style = MaterialTheme.typography.labelMedium)
        }
        Icon(Icons.Rounded.MoreVert, "Ещё", modifier = Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onMore).padding(4.dp))
    }
}

@Composable
private fun SourcesCard(vm: TvViewModel, onOpen: () -> Unit) {
    val fmt = remember { SimpleDateFormat("d MMM", Locale("ru")) }
    SoftCard(padding = 4.dp, onClick = onOpen) {
        for (s in vm.sources) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Description, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.url.ifEmpty { "Файл" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.padding(horizontal = 6.dp)) {
                Text("M3U", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text("Каналов: ${s.count}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Pill("Активен", tones.good, tones.goodSoft, icon = Icons.Rounded.Check)
        }
        val epgOn = vm.epgInfo.isNotEmpty() && vm.epgInfo != "Не загружена"
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CalendarMonth, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Телепрограмма (EPG)", style = MaterialTheme.typography.titleSmall)
                Text(vm.epgUrls.lineSequence().firstOrNull { it.isNotBlank() } ?: "Ссылка не задана", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.padding(horizontal = 6.dp)) {
                Text("XMLTV", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(if (epgOn) "Каналов: ${Epg.channelCount}" else "Не загружена", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (epgOn) Pill("Активен", tones.good, tones.goodSoft, icon = Icons.Rounded.Check) else Pill("Выкл.", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh)
        }
    }
}

@Composable
private fun QuickTile(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    SoftCard(modifier, onClick = onClick, padding = 8.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 3, minLines = 2)
        }
    }
}

@Composable
private fun RecordsSheet(onClose: () -> Unit) {
    val context = LocalContext.current
    var tickR by remember { mutableIntStateOf(0) }
    LaunchedEffect(TvRecorder.finished) { tickR++ }
    val recordings = remember(tickR) { TvRecorder.recordings(context) }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Text("Записи", style = MaterialTheme.typography.titleLarge)
            if (TvRecorder.recording) Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.FiberManualRecord, null, tint = tones.bad, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text("${TvRecorder.channelName} · ${fmtBytes(TvRecorder.bytesWritten)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { TvRecorder.stop(context) }) { Text("Стоп") }
            }
            if (recordings.isEmpty() && !TvRecorder.recording) Text("Пока нет записей. Откройте канал (⋮) и нажмите «Записать».", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            for (file in recordings.take(50)) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(fmtBytes(file.length()) + " · " + SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(file.lastModified())), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { TvRecorder.delete(file); tickR++ }) { Icon(Icons.Rounded.DeleteOutline, "Удалить", tint = MaterialTheme.colorScheme.error) }
            }
            Text("Папка: ${TvRecorder.outputDir(context).absolutePath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = { vm.toggleFavorite(channel) }) { key(tick) { Text(if (channel.favorite) "Убрать из избранного" else "В избранное", maxLines = 1) } }
                TextButton(onClick = { copyText(context, channel.url, "Ссылка скопирована") }) { Text("Ссылка", maxLines = 1) }
                TextButton(onClick = { vm.remove(channel); onClose() }) { Text("Удалить", maxLines = 1, color = MaterialTheme.colorScheme.error) }
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
    var url by remember(prefill) { mutableStateOf(prefill) }
    val context = LocalContext.current
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { vm.addFile(uri); onClose() }
    }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("Добавить плейлист", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            // Xtream убран (как в основной ветке); три плашки помещаются по центру, перенос — только при очень крупном шрифте.
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = true, onClick = {},
                    label = { Text("Ссылка", maxLines = 1, softWrap = false) }
                )
                FilterChip(
                    selected = false,
                    onClick = {
                        context.startActivity(Intent(context, ToolActivity::class.java).putExtra("screen", "tvmask"))
                        onClose()
                    },
                    label = { Text("По маске", maxLines = 1, softWrap = false) },
                    leadingIcon = { Icon(Icons.Rounded.ManageSearch, null, Modifier.size(18.dp)) }
                )
                FilterChip(
                    selected = false,
                    onClick = { pickFile.launch(arrayOf("*/*")) },
                    label = { Text("Файл M3U", maxLines = 1, softWrap = false) },
                    leadingIcon = { Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp)) }
                )
            }
            Spacer(Modifier.height(12.dp))
            BigField(url, { url = it }, "https://…/playlist.m3u", leading = Icons.Rounded.Link,
                onGo = { vm.addUrl(url); onClose() })
            Spacer(Modifier.height(12.dp))
            Button(onClick = { vm.addUrl(url); onClose() }, enabled = url.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(50.dp), shape = RoundedCornerShape(16.dp)) {
                Text("Загрузить")
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
                AssistChip(onClick = { vm.removeDead() }, label = { Text("Убрать нерабочие", maxLines = 1) }, leadingIcon = { Icon(Icons.Rounded.CleaningServices, null, Modifier.size(18.dp)) })
                AssistChip(onClick = { vm.dedupe() }, label = { Text("Убрать повторы", maxLines = 1) }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)) })
                AssistChip(onClick = { exportText = vm.exportM3u(true); save.launch("working.m3u") }, label = { Text("Сохранить рабочие", maxLines = 1) }, leadingIcon = { Icon(Icons.Rounded.Download, null, Modifier.size(18.dp)) })
                AssistChip(onClick = { exportText = vm.exportM3u(false); save.launch("playlist.m3u") }, label = { Text("Сохранить все", maxLines = 1) })
            }

            SectionTitle("Телепрограмма")
            Text(vm.epgInfo, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            BigField(vm.epgUrls, { vm.epgUrls = it }, "Ссылки на XMLTV, по одной в строке", singleLine = false, minLines = 2)
            TextButton(onClick = { vm.saveSettings(); vm.refreshEpg() }, enabled = !vm.epgBusy) { Text(if (vm.epgBusy) "Загружаем…" else "Обновить программу") }

            SectionTitle("Проверка")
            Text("Одновременных проверок: ${vm.threads}", style = MaterialTheme.typography.bodyMedium)
            AppSlider(value = vm.threads.toFloat(), onValueChange = { vm.threads = it.toInt() }, valueRange = 1f..64f)
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
