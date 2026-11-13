@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package pro.sitescope.app.net

import android.app.Application
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import pro.sitescope.app.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class SpeedViewModel(app: Application) : AndroidViewModel(app) {
    private val context = app.applicationContext
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
    private val cancel = AtomicBoolean(false)
    val running get() = phase != SpeedEngine.Phase.IDLE && phase != SpeedEngine.Phase.DONE

    fun update(s: SpeedEngine.Settings) { settings = s; SpeedStore.saveSettings(context, s) }

    fun start() {
        if (running) return
        cancel.set(false)
        current = null; live = 0.0; progress = 0f; pingNow = -1.0; pingDone = 0; downNow = -1.0; upNow = -1.0
        net = SpeedEngine.networkLabel(context)
        val s = settings
        viewModelScope.launch(Dispatchers.IO) {
            val r = SpeedEngine.run(context, s, cancel, object : SpeedEngine.Listener {
                override fun phase(p: SpeedEngine.Phase) {
                    if (p == SpeedEngine.Phase.DOWN && downNow < 0) downNow = 0.0
                    phase = p; live = 0.0; progress = 0f
                }
                override fun ping(ms: Double, done: Int, total: Int) { if (ms >= 0) pingNow = ms; pingDone = done; progress = done.toFloat() / total }
                override fun speed(p: SpeedEngine.Phase, mbps: Double, progress: Float) {
                    live = mbps; this@SpeedViewModel.progress = progress
                    if (p == SpeedEngine.Phase.DOWN) downNow = mbps else if (p == SpeedEngine.Phase.UP) upNow = mbps
                }
            })
            current = r; live = 0.0
            if (!cancel.get() && (r.down >= 0 || r.up >= 0 || r.ping >= 0)) {
                history = listOf(r) + history
                SpeedStore.saveHistory(context, history)
            }
            phase = SpeedEngine.Phase.DONE
        }
    }

    fun stop() { cancel.set(true) }
    fun clearHistory() { history = emptyList(); SpeedStore.saveHistory(context, history) }
    fun remove(r: SpeedEngine.Result) { history = history.filter { it !== r }; SpeedStore.saveHistory(context, history) }
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
    SpeedEngine.Phase.PING -> "Задержка"
    SpeedEngine.Phase.DOWN -> "Скачивание"
    SpeedEngine.Phase.UP -> "Отдача"
    else -> ""
}

@Composable
fun SpeedScreen(vm: SpeedViewModel = viewModel()) {
    var showSettings by remember { mutableStateOf(false) }
    val s = vm.settings
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        // Сервер и тип сети
        SoftCard(Modifier.clickable(enabled = !vm.running) { showSettings = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Dns, tones.net, tones.netSoft, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.server().name, style = MaterialTheme.typography.titleMedium)
                    Text(s.server().place + " · " + vm.net, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    Box(Modifier.size(150.dp).clip(CircleShape).clickable { vm.start() }
                        .then(Modifier), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            drawCircle(Brush.verticalGradient(listOf(tones.heroStart, tones.heroEnd)))
                        }
                        Text(if (done == null) "НАЧАТЬ" else "ЕЩЁ РАЗ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                    }
                }
            }
            else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(phaseTitle(vm.phase), style = MaterialTheme.typography.titleMedium, color = accent)
                if (vm.phase == SpeedEngine.Phase.PING) {
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
    val s = vm.settings
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 32.dp)) {
            Text("Настройки замера", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Text("Сервер", style = MaterialTheme.typography.titleSmall)
            SpeedEngine.servers.forEach { sv ->
                Row(Modifier.fillMaxWidth().clickable { vm.update(s.copy(serverId = sv.id)) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.serverId == sv.id, onClick = { vm.update(s.copy(serverId = sv.id)) })
                    Column { Text(sv.name); Text(sv.place, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (s.serverId == "custom") {
                OutlinedTextField(s.customDown, { vm.update(s.copy(customDown = it)) }, Modifier.fillMaxWidth(), label = { Text("Адрес скачивания (можно с %d — размер в байтах)") }, singleLine = true)
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(s.customUp, { vm.update(s.copy(customUp = it)) }, Modifier.fillMaxWidth(), label = { Text("Адрес приёма данных (необязательно)") }, singleLine = true)
            }
            Spacer(Modifier.height(14.dp))
            Text("Что измерять", style = MaterialTheme.typography.titleSmall)
            SwitchRow("Задержка, джиттер, потери", s.doPing) { vm.update(s.copy(doPing = it)) }
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
    Slider(value, onChange, valueRange = range, steps = steps)
}
