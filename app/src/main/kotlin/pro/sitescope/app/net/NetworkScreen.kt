@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package pro.sitescope.app.net

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import pro.sitescope.app.ConnectionProfileStore
import pro.sitescope.app.DeviceInventoryStore
import pro.sitescope.app.Models
import pro.sitescope.app.ProtocolRouter
import pro.sitescope.app.ScanService
import pro.sitescope.app.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun dateText(time: Long): String = if (time <= 0) "" else SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(time))

@Composable
fun NetworkScreen(startTab: Int = 0, startTargets: String? = null, vm: NetViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(startTab) }
    LifecycleResumeEffect(vm) { vm.loadProfiles(); onPauseOrDispose { } }
    LaunchedEffect(startTargets) { if (!startTargets.isNullOrBlank()) vm.targets = startTargets }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Устройства", "Сканер", "Подключения", "Скорость", "Доступ извне").forEachIndexed { i, title ->
                FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(title) })
            }
        }
        when (tab) {
            0 -> DevicesTab(vm)
            1 -> ScannerTab(vm)
            2 -> ConnectionsTab(vm)
            3 -> SpeedTab(vm)
            else -> ExposureScreen()
        }
    }
}

// ------------------------------------------------------------------ Устройства

@Composable
internal fun WolSheet(vm: NetViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("") }
    var ip by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Text("Включить по сети", style = MaterialTheme.typography.titleLarge)
            Text("Будит компьютер или сервер, если в нём включена функция Wake-on-LAN. Нужен MAC-адрес сетевой карты — он указан в настройках устройства или роутера.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
            vm.wol.forEach { entry ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.name, style = MaterialTheme.typography.titleSmall)
                        Text(entry.mac + (if (entry.ip.isNotEmpty()) " · " + entry.ip else ""), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { vm.removeWol(entry) }) { Icon(Icons.Rounded.DeleteOutline, "Удалить") }
                    Button(onClick = { vm.wake(entry) { status -> toast(context, status) } }) { Text("Включить") }
                }
            }
            if (vm.wol.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 10.dp))
            BigField(name, { name = it }, "Название (например, домашний ПК)", leading = Icons.Rounded.Computer, keyboard = KeyboardType.Text)
            Spacer(Modifier.height(8.dp))
            BigField(mac, { mac = it }, "MAC-адрес: AA:BB:CC:DD:EE:FF", leading = Icons.Rounded.Memory, keyboard = KeyboardType.Ascii, mono = true)
            Spacer(Modifier.height(8.dp))
            BigField(ip, { ip = it }, "IP для проверки пробуждения (необязательно)", leading = Icons.Rounded.Dns, keyboard = KeyboardType.Ascii, mono = true)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { if (vm.addWol(name, mac, ip)) { name = ""; mac = ""; ip = "" } else toast(context, "MAC-адрес — это 12 знаков: цифры и буквы A–F") }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить устройство") }
        }
    }
}

// ------------------------------------------------------------------ Скорость

@Composable
private fun SpeedTab(vm: NetViewModel) {
    val t = tones
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SoftCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Скорость интернета", style = MaterialTheme.typography.titleMedium)
                    Text("Скачивание и отдача через открытый сервис Cloudflare", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (!vm.speedRunning) Button(onClick = { vm.runSpeedTest() }) { Text("Измерить") }
            }
            if (vm.speedRunning) { Spacer(Modifier.height(10.dp)); LoadingCard(vm.speedStage) }
            vm.speedDown?.let { d ->
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Download, t.good, t.goodSoft, 40.dp); Spacer(Modifier.width(12.dp))
                    Column { Text("Скачивание", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (d.error.isEmpty()) SpeedTest.fmt(d.kbps) else d.error, style = MaterialTheme.typography.titleMedium) }
                }
            }
            vm.speedUp?.let { u ->
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Upload, t.net, t.netSoft, 40.dp); Spacer(Modifier.width(12.dp))
                    Column { Text("Отдача", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(if (u.error.isEmpty()) SpeedTest.fmt(u.kbps) else u.error, style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
        if (vm.speedDown == null && !vm.speedRunning) {
            Spacer(Modifier.height(8.dp))
            EmptyState(Icons.Rounded.Speed, "Проверим скорость", "Загрузим и отправим немного тестовых данных, чтобы оценить реальную скорость вашего интернета.")
        }
    }
}

// ------------------------------------------------------------------ Сканер

@Composable
private fun ScannerTab(vm: NetViewModel) {
    val activity = LocalActivity.current
    val context = LocalContext.current
    val s = vm.scan
    var error by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<NetViewModel.Plan?>(null) }
    var agreed by remember { mutableStateOf(false) }
    val t = tones
    val open = remember(s) { s.results.filter { it.open }.sortedWith(compareBy({ it.ip.ifEmpty { it.host } }, { it.port })) }
    val byHost = remember(open) { open.groupBy { it.ip.ifEmpty { it.host } } }

    fun launch() {
        try { val plan = vm.plan(); if (plan.needsConfirm) confirm = plan else vm.startScan() }
        catch (e: Exception) { error = e.message ?: "Проверьте адреса и порты" }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (!s.active) item {
            SoftCard {
                Text("Что проверить", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                BigField(vm.targets, { vm.targets = it }, "192.168.1.0/24, 10.0.0.5 или мой-сервер.ru", leading = Icons.Rounded.Lan, mono = true)
                val local = remember { vm.localSubnet() }
                if (local.isNotEmpty() && vm.targets != local) TextButton(onClick = { vm.targets = local }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Подставить мою сеть: $local") }
                Spacer(Modifier.height(10.dp))
                Text("Какие порты", style = MaterialTheme.typography.titleSmall)
                FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PORT_PRESETS.forEach { p -> FilterChip(selected = vm.ports == p.ports, onClick = { vm.ports = p.ports }, label = { Text(p.title) }) }
                }
                BigField(vm.ports, { vm.ports = it }, "22,80,443,8000-8100", leading = Icons.Rounded.Tag, keyboard = KeyboardType.Ascii, mono = true)
                Spacer(Modifier.height(12.dp))
                ScanTuningControls()
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Большая локальная сеть", style = MaterialTheme.typography.bodyMedium)
                        Text("До 65 536 адресов, только для домашних и офисных сетей", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = vm.extended, onCheckedChange = { vm.extended = it })
                }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { agreed = !agreed }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = agreed, onCheckedChange = { agreed = it })
                    Text("Это моя сеть или у меня есть разрешение владельца", style = MaterialTheme.typography.bodyMedium)
                }
                Button(onClick = { launch() }, enabled = agreed && vm.targets.isNotBlank() && vm.ports.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Начать проверку")
                }
            }
        }
        if (s.active || s.completed || s.cancelled || s.totalProbes > 0) item {
            SoftCard(color = if (s.active) t.netSoft else MaterialTheme.colorScheme.surfaceContainerLowest) {
                Text("Потоков: ${s.concurrency} · задержка: ${s.delayMs} мс", style = MaterialTheme.typography.bodySmall)
                val title = when { s.active && s.paused -> "Пауза"; s.active -> "Идёт проверка"; s.cancelled -> "Остановлено"; s.completed -> "Готово"; else -> "Последняя проверка" }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text("Открытых сервисов: ${open.size} · узлов: ${byHost.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (s.active) {
                        IconButton(onClick = { vm.scanCommand(if (s.paused) ScanService.ACTION_RESUME else ScanService.ACTION_PAUSE) }) { Icon(if (s.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, "Пауза") }
                        IconButton(onClick = { vm.scanCommand(ScanService.ACTION_STOP) }) { Icon(Icons.Rounded.Stop, "Стоп") }
                    } else if (open.isNotEmpty()) IconButton(onClick = {
                        shareText(context, open.joinToString("\n") { r -> r.ip.ifEmpty { r.host } + ":" + r.port + "  " + r.service }, "Результаты проверки сети")
                    }) { Icon(Icons.Rounded.Share, "Поделиться") }
                }
                if (s.totalProbes > 0) {
                    LinearProgressIndicator(progress = { (s.completedProbes.toFloat() / s.totalProbes).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                    Text("${s.completedProbes} из ${s.totalProbes}" + (if (s.active && s.currentTarget.isNotEmpty()) " · " + s.currentTarget else ""),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (s.error.isNotEmpty()) Text(s.error, color = t.bad, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (!s.active && s.completed && open.isEmpty()) item {
            EmptyState(Icons.Rounded.VerifiedUser, "Открытых сервисов нет", "На проверенных адресах ничего не отвечает на выбранных портах.")
        }
        byHost.forEach { (host, results) ->
            item(key = "h$host") {
                SoftCard(padding = 14.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(Icons.Rounded.Dns, t.net, t.netSoft, 40.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(results[0].hostname.ifEmpty { host }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(host + " · " + results[0].resourceType, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Pill(results.size.toString(), t.good, t.goodSoft)
                    }
                    results.forEach { r -> PortRow(r) { if (activity != null) try { ProtocolRouter.open(activity, r) } catch (e: Exception) { toast(context, "Не удалось открыть") } } }
                }
            }
        }
    }
    error?.let { ConfirmDialog("Не получится запустить", it, "Понятно", null) { error = null } }
    confirm?.let { p ->
        ConfirmDialog("Большая проверка", "Адресов: ${p.hosts}\nПортов: ${p.ports}\nВсего проверок: ${p.probes}\n\nЭто может занять много времени. Проверка продолжится в фоне.", "Запустить", { confirm = null }) { confirm = null; vm.startScan() }
    }
}

@Composable
fun PortRow(r: Models.PortResult, onClick: () -> Unit) {
    var showIdentity by remember(r.target, r.port) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(r.port.toString(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(58.dp))
        Column(Modifier.weight(1f)) {
            Text(r.service, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (r.identity.isKnown || r.identity.conflicting) Text(r.identity.summary(), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val extra = r.description.ifEmpty { r.banner }
            if (extra.isNotEmpty()) Text(extra, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (r.latencyMs > 0) Text("${r.latencyMs} мс", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        IconButton(onClick = { showIdentity = true }) { Icon(Icons.Rounded.Info, "Как определено устройство", tint = MaterialTheme.colorScheme.outline) }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
    }
    if (showIdentity) ModalBottomSheet(onDismissRequest = { showIdentity = false }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("${r.ip.ifEmpty { r.host }}:${r.port} · ${r.service}", style = MaterialTheme.typography.titleMedium)
            val confidence = when (r.confidence) { "high" -> "Высокая"; "medium" -> "Средняя"; else -> "Низкая" }
            InfoRow("Уверенность в протоколе", confidence)
            DeviceIdentityDetails(r.identity, r.tlsTrust)
        }
    }
}

// ------------------------------------------------------------------ Подключения

@Composable
private fun ConnectionsTab(vm: NetViewModel) {
    val activity = LocalActivity.current
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ConnectionProfileStore.Profile?>(null) }
    val t = tones
    LaunchedEffect(Unit) { vm.loadProfiles() }
    val shown = vm.profiles.filter { p -> query.isBlank() || (p.name + " " + p.protocol + " " + p.host + " " + p.tags + " " + p.notes).contains(query.trim(), true) }
        .sortedWith(compareByDescending<ConnectionProfileStore.Profile> { it.pinned }.thenByDescending { maxOf(it.lastOpenedAt, it.savedAt) })

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { adding = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Новое подключение") }
                if (vm.profiles.isNotEmpty()) OutlinedButton(onClick = { vm.checkAll() }) { Text("Проверить все") }
            }
        }
        if (vm.profiles.size > 5) item { BigField(query, { query = it }, "Поиск по названию, адресу или метке", keyboard = KeyboardType.Text) }
        if (vm.profiles.isEmpty()) item {
            EmptyState(Icons.Rounded.Terminal, "Нет сохранённых подключений", "Добавьте сервер, камеру или роутер вручную — или откройте устройство из поиска, и оно сохранится здесь.")
        }
        items(shown, key = { it.id }) { p ->
            val busy = vm.checking.contains(p.id)
            SoftCard(padding = 14.dp, onClick = { if (activity != null) try { ProtocolRouter.dispatch(activity, p.endpoint()) } catch (e: Exception) { toast(context, "Не удалось открыть") } }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(p.protocol, t.net, t.netSoft)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (p.pinned) { Icon(Icons.Rounded.PushPin, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(4.dp)) }
                            if (p.monitored) { Icon(Icons.Rounded.NotificationsActive, null, Modifier.size(14.dp), tint = t.good); Spacer(Modifier.width(4.dp)) }
                            Text(p.name.ifEmpty { p.host }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(p.host + ":" + p.port, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else when (p.lastStatus) {
                        "ONLINE" -> Pill(if (p.lastLatencyMs > 0) "${p.lastLatencyMs} мс" else "В сети", t.good, t.goodSoft)
                        "", "MANUAL", "UNKNOWN" -> {}
                        else -> Pill("Не отвечает", t.bad, t.badSoft)
                    }
                    IconButton(onClick = { editing = p }) { Icon(Icons.Rounded.MoreVert, "Ещё") }
                }
                if (p.notes.isNotEmpty()) Text(p.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    if (adding) AddConnectionSheet(vm) { adding = false }
    editing?.let { p -> EditConnectionSheet(vm, p) { editing = null } }
}

@Composable
private fun AddConnectionSheet(vm: NetViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    var proto by remember { mutableStateOf(PROTOCOLS[0]) }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(PROTOCOLS[0].port.toString()) }
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Text("Новое подключение", style = MaterialTheme.typography.titleLarge)
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PROTOCOLS.forEach { p -> FilterChip(selected = proto.id == p.id, onClick = { proto = p; port = p.port.toString() }, label = { Text(p.id) }) }
            }
            Text(proto.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
            BigField(host, { host = it }, "Адрес: 192.168.1.10 или server.ru", leading = Icons.Rounded.Dns)
            Spacer(Modifier.height(8.dp))
            BigField(port, { v -> port = v.filter { it.isDigit() }.take(5) }, "Порт", leading = Icons.Rounded.Tag, keyboard = KeyboardType.Number)
            Spacer(Modifier.height(8.dp))
            BigField(name, { name = it }, "Название (необязательно)", leading = Icons.Rounded.Label, keyboard = KeyboardType.Text)
            Spacer(Modifier.height(14.dp))
            Button(onClick = {
                val number = port.toIntOrNull() ?: 0
                if (host.isBlank() || number !in 1..65535) toast(context, "Укажите адрес и порт от 1 до 65535")
                else { vm.addProfile(proto, host, number, name); onClose() }
            }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить") }
        }
    }
}

@Composable
private fun EditConnectionSheet(vm: NetViewModel, p: ConnectionProfileStore.Profile, onClose: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(p.name) }
    var notes by remember { mutableStateOf(p.notes) }
    var tags by remember { mutableStateOf(p.tags) }
    var pinned by remember { mutableStateOf(p.pinned) }
    var monitored by remember { mutableStateOf(p.monitored) }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Text(p.protocol + " · " + p.host + ":" + p.port, style = MaterialTheme.typography.titleMedium)
            if (p.lastMessage.isNotEmpty()) Text(p.lastMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            BigField(name, { name = it }, "Название", leading = Icons.Rounded.Label, keyboard = KeyboardType.Text)
            Spacer(Modifier.height(8.dp))
            BigField(notes, { notes = it }, "Заметка", leading = Icons.Rounded.Notes, keyboard = KeyboardType.Text)
            Spacer(Modifier.height(8.dp))
            BigField(tags, { tags = it }, "Метки через запятую", leading = Icons.Rounded.Sell, keyboard = KeyboardType.Text)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Закрепить вверху", Modifier.weight(1f)); Switch(checked = pinned, onCheckedChange = { pinned = it })
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Следить", style = MaterialTheme.typography.bodyMedium)
                    Text("Проверять каждые 15 минут и сообщить, если пропадёт или оживёт", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = monitored, onCheckedChange = { monitored = it; vm.toggleMonitored(p) })
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.check(p); onClose() }, modifier = Modifier.weight(1f)) { Text("Проверить") }
                OutlinedButton(onClick = { copyText(context, p.uri.ifEmpty { p.host + ":" + p.port }) }, modifier = Modifier.weight(1f)) { Text("Копировать") }
            }
            Button(onClick = { vm.updateProfile(p, name, notes, tags, pinned); onClose() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Сохранить") }
            TextButton(onClick = { vm.removeProfile(p); onClose() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = tones.bad)) { Text("Удалить подключение") }
        }
    }
}
