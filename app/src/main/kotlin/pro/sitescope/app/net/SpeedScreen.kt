@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package pro.sitescope.app.net

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.sitescope.app.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class SpeedViewModel(app: Application) : AndroidViewModel(app) {
    private val context = app.applicationContext
    private val main = Handler(Looper.getMainLooper())
    var settings by mutableStateOf(SpeedStore.loadSettings(context)); private set
    var history by mutableStateOf(SpeedStore.loadHistory(context)); private set
    var phase by mutableStateOf(SpeedEngine.Phase.IDLE); private set
    var live by mutableStateOf(0.0); private set
    var progress by mutableStateOf(0f); private set
    var pingNow by mutableStateOf(-1.0); private set
    var pingDone by mutableStateOf(0); private set
    var current by mutableStateOf<SpeedEngine.Result?>(null); private set
    var net by mutableStateOf(SpeedEngine.networkLabel(context)); private set
    // Промежуточные значения текущего замера (показываются до итогового результата)
    var downNow by mutableStateOf(-1.0); private set
    var upNow by mutableStateOf(-1.0); private set
    var activeServer by mutableStateOf<SpeedEngine.Server?>(null); private set
    var probing by mutableStateOf(false); private set
    val probes = mutableStateMapOf<String, SpeedEngine.ServerProbe>()
    private val cancel = AtomicBoolean(false)
    private val cancelProbe = AtomicBoolean(false)
    val running get() = phase != SpeedEngine.Phase.IDLE && phase != SpeedEngine.Phase.DONE

    fun update(s: SpeedEngine.Settings) {
        if (settings.serverId != s.serverId || settings.automatic != s.automatic) activeServer = null
        settings = s
        SpeedStore.saveSettings(context, s)
    }

    fun checkServers() {
        if (running || probing) return
        probing = true
        cancelProbe.set(false)
        net = SpeedEngine.networkLabel(context)
        probes.clear()
        val custom = settings.server().takeIf { it.id == "custom" && it.pingUrl.isNotBlank() }
        viewModelScope.launch {
            try {
                val measured = withContext(Dispatchers.IO) {
                    SpeedEngine.probeServers(SpeedServers.public + listOfNotNull(custom), cancelProbe) { probe, _, _ ->
                        main.post { if (!cancelProbe.get()) probes[probe.server.id] = probe }
                    }
                }
                if (!cancelProbe.get()) measured.forEach { probes[it.server.id] = it }
            } finally { probing = false }
        }
    }

    fun stopChecking() { cancelProbe.set(true) }

    fun start() {
        if (running || probing) return
        cancel.set(false)
        current = null; live = 0.0; progress = 0f; pingNow = -1.0; pingDone = 0; downNow = -1.0; upNow = -1.0
        net = SpeedEngine.networkLabel(context)
        val s = settings
        activeServer = if (s.automatic) null else s.server()
        if (s.automatic) probes.clear()
        phase = SpeedEngine.Phase.SELECTING
        val listener = object : SpeedEngine.Listener {
            override fun server(server: SpeedEngine.Server) {
                main.post {
                    activeServer = server
                    pingNow = -1.0; pingDone = 0; downNow = -1.0; upNow = -1.0
                }
            }
            override fun serverProbe(result: SpeedEngine.ServerProbe, done: Int, total: Int) {
                main.post {
                    probes[result.server.id] = result
                    pingDone = done
                    this@SpeedViewModel.progress = done.toFloat() / total
                }
            }
            override fun phase(p: SpeedEngine.Phase) {
                if (p == SpeedEngine.Phase.DONE) return
                main.post {
                    if (p == SpeedEngine.Phase.DOWN && downNow < 0) downNow = 0.0
                    phase = p; live = 0.0; progress = 0f
                }
            }
            override fun ping(ms: Double, done: Int, total: Int) {
                main.post { pingNow = ms; pingDone = done; progress = done.toFloat() / total }
            }
            override fun speed(p: SpeedEngine.Phase, mbps: Double, progress: Float) {
                main.post {
                    live = mbps; this@SpeedViewModel.progress = progress
                    if (p == SpeedEngine.Phase.DOWN) downNow = mbps else if (p == SpeedEngine.Phase.UP) upNow = mbps
                }
            }
        }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val measured = SpeedEngine.run(context, s, cancel, listener)
                    if (!cancel.get() && (measured.down >= 0 || measured.up >= 0 || measured.ping >= 0)) {
                        SpeedStore.appendHistory(context, measured)
                    }
                    measured
                }
                current = result
                history = SpeedStore.loadHistory(context)
            } finally {
                live = 0.0
                phase = SpeedEngine.Phase.DONE
            }
        }
    }

    fun stop() { cancel.set(true) }
    fun clearHistory() { history = emptyList(); SpeedStore.saveHistory(context, history) }
    fun remove(r: SpeedEngine.Result) { SpeedStore.removeHistory(context, r); history = SpeedStore.loadHistory(context) }

    override fun onCleared() {
        cancel.set(true)
        cancelProbe.set(true)
        super.onCleared()
    }
}

private val gaugeScale = listOf(0.0, 5.0, 10.0, 20.0, 50.0, 100.0, 250.0, 500.0, 1000.0)

/** Положение стрелки (0..1) на нелинейной шкале как в Speedtest: больше места малым скоростям. */
internal fun gaugeFraction(mbps: Double): Float {
    if (mbps <= 0) return 0f
    val top = gaugeScale.last()
    if (mbps >= top) return 1f
    var i = 0
    while (i < gaugeScale.size - 2 && mbps > gaugeScale[i + 1]) i++
    val a = gaugeScale[i]; val b = gaugeScale[i + 1]
    return ((i + (mbps - a) / (b - a)) / (gaugeScale.size - 1)).toFloat()
}

private fun phaseTitle(p: SpeedEngine.Phase) = when (p) {
    SpeedEngine.Phase.SELECTING -> "Подбор сервера"
    SpeedEngine.Phase.PING -> "Задержка"
    SpeedEngine.Phase.DOWN -> "Скачивание"
    SpeedEngine.Phase.UP -> "Отдача"
    else -> ""
}

@Composable
fun SpeedScreen(vm: SpeedViewModel = viewModel()) {
    var showSettings by remember { mutableStateOf(false) }
    val s = vm.settings
    val server = if (s.automatic) vm.activeServer else s.server()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp)) {
        // Сервер и тип сети
        SoftCard(Modifier.clickable(enabled = !vm.running) { showSettings = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Dns, tones.net, tones.netSoft, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(server?.name ?: "Автовыбор сервера", style = MaterialTheme.typography.titleMedium)
                    Text(listOfNotNull(if (s.automatic) "Авто" else "Вручную", server?.place, vm.net).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { showSettings = true }, enabled = !vm.running) { Icon(Icons.Rounded.Settings, "Настройки") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Gauge(vm)
        Spacer(Modifier.height(4.dp))
        ResultTiles(vm)
        val err = vm.current?.error.orEmpty()
        if (err.isNotEmpty()) { Spacer(Modifier.height(8.dp)); ErrorCard(err) }
        Spacer(Modifier.height(16.dp))
        HistoryBlock(vm)
    }
    if (showSettings) SpeedSettingsSheet(vm) { showSettings = false }
}

@Composable
private fun Gauge(vm: SpeedViewModel) {
    val unit = vm.settings.unit
    val running = vm.running
    val idle = !running
    val target = when (vm.phase) {
        SpeedEngine.Phase.DOWN, SpeedEngine.Phase.UP -> gaugeFraction(vm.live)
        else -> 0f
    }
    val fraction by animateFloatAsState(target, label = "gauge")
    val down = vm.phase == SpeedEngine.Phase.DOWN
    val accent = when (vm.phase) { SpeedEngine.Phase.UP -> tones.net; SpeedEngine.Phase.PING -> tones.info; else -> MaterialTheme.colorScheme.primary }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val heroA = tones.heroStart; val heroB = tones.heroEnd
    Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(280.dp)) {
            val stroke = 22.dp.toPx()
            val inset = stroke / 2 + 6.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(track, 150f, 240f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (fraction > 0.001f) {
                drawArc(Brush.sweepGradient(listOf(accent.copy(alpha = 0.45f), accent, accent.copy(alpha = 0.45f))), 150f, 240f * fraction, false,
                    topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            // Деления шкалы
            val cx = size.width / 2; val cy = size.height / 2
            val rOut = size.width / 2 - 2.dp.toPx(); val rIn = rOut - 6.dp.toPx()
            for (i in gaugeScale.indices) {
                val a = Math.toRadians((150.0 + 240.0 * i / (gaugeScale.size - 1)))
                drawLine(tickColor, Offset(cx + (rIn * Math.cos(a)).toFloat(), cy + (rIn * Math.sin(a)).toFloat()),
                    Offset(cx + (rOut * Math.cos(a)).toFloat(), cy + (rOut * Math.sin(a)).toFloat()), 2.dp.toPx())
            }
        }
        when {
            idle -> {
                val done = vm.current
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(150.dp).clip(CircleShape).clickable(enabled = !vm.probing) { vm.start() }
                        .then(Modifier), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawCircle(Brush.verticalGradient(listOf(heroA, heroB)))
                        }
                        Text(when { vm.probing -> "ПРОВЕРКА"; done == null -> "НАЧАТЬ"; else -> "ЕЩЁ РАЗ" },
                            color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    }
                }
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(phaseTitle(vm.phase), style = MaterialTheme.typography.titleMedium, color = accent)
                if (vm.phase == SpeedEngine.Phase.SELECTING) {
                    Text(if (vm.pingDone > 0) "Проверено ${vm.pingDone} из ${SpeedServers.public.size}" else "Проверяем доступность узлов",
                        style = MaterialTheme.typography.bodyMedium)
                } else if (vm.phase == SpeedEngine.Phase.PING) {
                    Text(if (vm.pingNow >= 0) "%.0f".format(vm.pingNow) else "…", fontSize = 64.sp, fontWeight = FontWeight.Bold)
                    Text("мс · ${vm.pingDone}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(SpeedEngine.value(vm.live, unit), fontSize = 64.sp, fontWeight = FontWeight.Bold)
                    Text(SpeedEngine.unitLabel(unit), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.width(140.dp))
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { vm.stop() }) { Text("Остановить") }
            }
        }
        // Подписи шкалы по краям дуги
        if (idle || down || vm.phase == SpeedEngine.Phase.UP) {
            Text(SpeedEngine.value(0.0, unit), Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 22.dp), style = MaterialTheme.typography.labelSmall, color = tickColor)
            Text(SpeedEngine.value(gaugeScale.last(), unit), Modifier.align(Alignment.BottomEnd).padding(end = 30.dp, bottom = 22.dp), style = MaterialTheme.typography.labelSmall, color = tickColor)
        }
    }
}

@Composable
private fun ResultTiles(vm: SpeedViewModel) {
    val unit = vm.settings.unit
    val r = vm.current
    val down = r?.down?.takeIf { it >= 0 } ?: vm.downNow.takeIf { it >= 0 && vm.running }
    val up = r?.up?.takeIf { it >= 0 } ?: vm.upNow.takeIf { it >= 0 && vm.running }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile("Скачивание", down?.let { SpeedEngine.value(it, unit) } ?: "—", SpeedEngine.unitLabel(unit), Icons.Rounded.Download, tones.good, Modifier.weight(1f))
        Tile("Отдача", up?.let { SpeedEngine.value(it, unit) } ?: "—", SpeedEngine.unitLabel(unit), Icons.Rounded.Upload, tones.net, Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile("Пинг", r?.ping?.takeIf { it >= 0 }?.let { "%.0f".format(it) } ?: "—", "мс", Icons.Rounded.Timer, tones.info, Modifier.weight(1f))
        Tile("Джиттер", r?.jitter?.takeIf { it >= 0 }?.let { "%.1f".format(it) } ?: "—", "мс", Icons.Rounded.Timeline, tones.warn, Modifier.weight(1f))
        Tile("Потери", r?.loss?.takeIf { it >= 0 }?.let { "%.0f".format(it) } ?: "—", "%", Icons.Rounded.SignalCellularAlt, tones.bad, Modifier.weight(1f))
    }
    if (r != null && r.loadedPing >= 0) {
        Spacer(Modifier.height(8.dp))
        SoftCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Задержка под нагрузкой", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text("%.0f мс".format(r.loadedPing), style = MaterialTheme.typography.titleMedium)
            }
            if (r.downPeak >= 0 || r.upPeak >= 0) {
                Spacer(Modifier.height(6.dp))
                Text("Пик: ↓ ${SpeedEngine.value(r.downPeak, unit)} · ↑ ${SpeedEngine.value(r.upPeak, unit)} ${SpeedEngine.unitLabel(unit)}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Tile(title: String, value: String, unit: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, modifier: Modifier) {
    SoftCard(modifier) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(unit, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun HistoryBlock(vm: SpeedViewModel) {
    val context = LocalContext.current
    val unit = vm.settings.unit
    if (vm.history.isEmpty()) return
    var confirm by remember { mutableStateOf(false) }
    SectionTitle("История", action = "Очистить", onAction = { confirm = true })
    vm.history.take(30).forEach { h ->
        SoftCard(Modifier.padding(bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(h.time)) + " · " + h.net + " · " + h.server,
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("↓ ${SpeedEngine.value(h.down, unit)}   ↑ ${SpeedEngine.value(h.up, unit)} ${SpeedEngine.unitLabel(unit)}",
                        style = MaterialTheme.typography.titleMedium)
                    Text("Пинг ${if (h.ping >= 0) "%.0f".format(h.ping) else "—"} мс · джиттер ${if (h.jitter >= 0) "%.1f".format(h.jitter) else "—"} мс · потери ${if (h.loss >= 0) "%.0f".format(h.loss) else "—"}%",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = {
                    val text = "SiteScope Pro · скорость\n↓ ${SpeedEngine.value(h.down, unit)} ↑ ${SpeedEngine.value(h.up, unit)} ${SpeedEngine.unitLabel(unit)}, пинг ${if (h.ping >= 0) "%.0f".format(h.ping) else "—"} мс (${h.server}, ${h.net})"
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Поделиться"))
                }) { Icon(Icons.Rounded.Share, "Поделиться") }
                IconButton(onClick = { vm.remove(h) }) { Icon(Icons.Rounded.Delete, "Удалить") }
            }
        }
    }
    if (confirm) ConfirmDialog("Очистить историю?", "Все сохранённые замеры будут удалены.", "Очистить", onConfirm = { vm.clearHistory(); confirm = false }, onDismiss = { confirm = false })
}

@Composable
private fun SpeedSettingsSheet(vm: SpeedViewModel, onClose: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxHeight(0.94f)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Серверы") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Параметры замера") })
            }
            if (tab == 0) SpeedServerPicker(vm) else SpeedMeasurementSettings(vm)
        }
    }
}

@Composable
private fun SpeedServerPicker(vm: SpeedViewModel) {
    val s = vm.settings
    var query by rememberSaveable { mutableStateOf("") }
    val needle = query.trim()
    val matching = SpeedEngine.servers.filter { server ->
        needle.isEmpty() || listOf(server.name, server.place, server.id, server.downUrl).any { it.contains(needle, ignoreCase = true) }
    }
    val shown = if (vm.probing) matching else matching.sortedWith(
        compareBy<SpeedEngine.Server> { if (it.id == "custom") 0 else if (it.id == s.serverId && !s.automatic) 1 else 2 }
            .thenBy { vm.probes[it.id]?.let { probe -> if (probe.available) 0 else 2 } ?: 1 }
            .thenBy { vm.probes[it.id]?.ping?.takeIf { ping -> ping >= 0 } ?: Double.POSITIVE_INFINITY })
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = s.automatic, onClick = { vm.update(s.copy(automatic = true)) }, label = { Text("Авто") })
            FilterChip(selected = !s.automatic, onClick = { vm.update(s.copy(automatic = false)) }, label = { Text("Вручную") })
        }
        Text(if (s.automatic) "Перед замером приложение подберёт доступный узел по отклику и нужным видам замера."
            else "Выберите сервер для следующего замера.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("Город или провайдер") }, leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Clear, "Очистить поиск") } })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Публичные узлы · ${SpeedServers.public.size}", style = MaterialTheme.typography.labelMedium)
            TextButton(onClick = { if (vm.probing) vm.stopChecking() else vm.checkServers() }, enabled = !vm.running) {
                Text(if (vm.probing) "Остановить" else "Проверить отклик")
            }
        }
        if (vm.probing) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 20.dp)) {
            if (shown.isEmpty()) item { Text("Серверы не найдены", Modifier.padding(vertical = 12.dp)) }
            items(shown, key = { it.id }) { server ->
                val selected = !s.automatic && s.serverId == server.id
                val custom = server.id == "custom"
                val probe = vm.probes[server.id]
                val detail = when {
                    custom -> "HTTP или HTTPS"
                    server.supportsUpload -> "${server.place} · ↓↑"
                    else -> "${server.place} · только ↓"
                }
                Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton,
                    onClick = { vm.update(s.copy(serverId = server.id, automatic = false)) })
                    .heightIn(min = 56.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected, onClick = null, modifier = Modifier.padding(end = 12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(server.name, style = MaterialTheme.typography.titleSmall)
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (probe != null) {
                        val label = if (probe.available) "${probe.ping.toInt()} мс" else probe.error.ifBlank { "Нет ответа" }
                        Text(label, modifier = Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium,
                            color = if (probe.available) tones.good else tones.bad)
                    }
                }
                if (custom && selected) Column(Modifier.padding(start = 30.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(s.customDown, { vm.update(s.copy(customDown = it)); vm.probes.remove("custom") }, Modifier.fillMaxWidth(),
                        label = { Text("Адрес скачивания") }, supportingText = { Text("%d подставляет размер в байтах") }, singleLine = true)
                    OutlinedTextField(s.customUp, { vm.update(s.copy(customUp = it)); vm.probes.remove("custom") }, Modifier.fillMaxWidth(),
                        label = { Text("Адрес приёма данных") }, singleLine = true)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f))
            }
        }
    }
}

@Composable
private fun SpeedMeasurementSettings(vm: SpeedViewModel) {
    val s = vm.settings
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text("Что измерять", style = MaterialTheme.typography.titleSmall)
            SwitchRow("HTTP-пинг, джиттер, потери", s.doPing) { vm.update(s.copy(doPing = it)) }
            SwitchRow("Скачивание", s.doDown) { vm.update(s.copy(doDown = it)) }
            SwitchRow("Отдача", s.doUp) { vm.update(s.copy(doUp = it)) }
            SwitchRow("Задержка под нагрузкой", s.loaded) { vm.update(s.copy(loaded = it)) }
            Spacer(Modifier.height(10.dp))
            Text("Единицы", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("mbps" to "Мбит/с", "mbs" to "МБ/с", "kbps" to "кбит/с").forEach { (id, label) ->
                    FilterChip(selected = s.unit == id, onClick = { vm.update(s.copy(unit = id)) }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(10.dp))
            SliderRow("Длительность фазы: ${s.seconds} с", s.seconds.toFloat(), 3f..30f, 26) { vm.update(s.copy(seconds = it.toInt())) }
            SliderRow("Параллельных соединений: ${s.threads}", s.threads.toFloat(), 1f..16f, 14) { vm.update(s.copy(threads = it.toInt())) }
            SliderRow("Замеров задержки: ${s.pings}", s.pings.toFloat(), 5f..50f, 8) { vm.update(s.copy(pings = it.toInt())) }
            SliderRow(if (s.capMb == 0) "Лимит трафика на фазу: без лимита" else "Лимит трафика на фазу: ${s.capMb} МБ", s.capMb.toFloat(), 0f..500f, 9) { vm.update(s.copy(capMb = (it / 10).toInt() * 10)) }
            Text("Лимит экономит мобильный трафик: фаза завершится, как только будет передано столько данных.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
            Button(onClick = { vm.update(SpeedEngine.Settings(unit = s.unit)) }, colors = ButtonDefaults.outlinedButtonColors()) { Text("Сбросить настройки") }
    }
}

@Composable
private fun SwitchRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f)); Switch(value, onChange)
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Text(label, style = MaterialTheme.typography.bodyMedium)
    AppSlider(value, onChange, valueRange = range, steps = steps)
}
