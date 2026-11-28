package pro.sitescope.app.vpn

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.sitescope.app.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun minutesText(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(0)
    return if (m >= 60) "${m / 60} ч ${m % 60} мин" else "$m мин"
}
private fun intervalLabel(min: Int) = if (min >= 60) "${min / 60} ч" else "$min мин"

// ------------------------------------------------------------------ Ротация

@Composable
internal fun RotationTab(vm: VpnViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = tones
    var enabled by remember { mutableStateOf(VpnRotation.enabled(context)) }
    var interval by remember { mutableIntStateOf(VpnRotation.intervalMin(context)) }
    var pool by remember { mutableStateOf(VpnRotation.pool(context)) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }
    val stats = remember(tick) { VpnRotation.stats(context) }
    val events = remember(tick) { VpnRotation.events(context) }
    val nextAt = remember(tick) { VpnRotation.nextAt(context) }
    val now = remember(tick) { System.currentTimeMillis() }
    val current = vm.serverList.firstOrNull { it.id == vm.runningProfileId }

    SectionTitle("Ротация серверов")
    SoftCard(padding = 12.dp, color = if (enabled) t.goodSoft else MaterialTheme.colorScheme.surfaceContainerLowest) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (enabled) "Ротация включена" else "Ротация выключена", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        !enabled -> "Пул из 2–10 серверов, смена раз в 30 мин, 1, 2 или 6 часов"
                        !vm.serverActive -> "Ждёт включения VPN через сервер"
                        else -> (current?.let { Countries.flag(Countries.of(it.name, it.endpoint)) + " " + it.name.ifEmpty { it.endpoint } } ?: "Сервер") +
                            (if (nextAt > now) " · смена через ${minutesText(nextAt - now)}" else " · смена при ближайшей проверке")
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Switch(checked = enabled, onCheckedChange = {
                if (it && pool.size < 2) { toast(context, "Отметьте минимум 2 сервера в пуле"); return@Switch }
                enabled = it; VpnRotation.setEnabled(context, it)
            })
        }
    }

    Spacer(Modifier.height(8.dp))
    Text("Как часто менять сервер", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        VpnRotation.INTERVALS.forEach { m -> MiniChip(intervalLabel(m), interval == m) { interval = m; VpnRotation.setInterval(context, m) } }
    }
    Text("Проверка отклика каждые ~15 минут. Если текущий сервер ухудшился или пропал, смена произойдёт раньше срока.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            busy = true; scope.launch { note = withContext(Dispatchers.IO) { runCatching { VpnRotation.tick(context) }.getOrElse { it.message ?: "Ошибка" } }; busy = false }
        }, enabled = !busy && pool.size >= 2, modifier = Modifier.weight(1f)) {
            if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Icon(Icons.Rounded.NetworkPing, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp)); Text("Проверить")
        }
        Button(onClick = {
            busy = true; scope.launch { note = withContext(Dispatchers.IO) { runCatching { VpnRotation.tick(context, force = true) }.getOrElse { it.message ?: "Ошибка" } }; busy = false }
        }, enabled = !busy && pool.size >= 2 && vm.serverActive, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Сменить сейчас") }
    }
    if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = t.info, modifier = Modifier.padding(top = 6.dp))

    SectionTitle("Пул серверов · ${pool.size}/${VpnRotation.MAX_POOL}")
    if (vm.serverList.isEmpty()) SoftCard { Text("Сначала добавьте серверы на вкладке «Серверы».", style = MaterialTheme.typography.bodyMedium) }
    vm.serverList.forEach { s ->
        val inPool = s.id in pool
        val st = stats[s.id]
        val running = vm.serverActive && vm.runningProfileId == s.id
        val code = Countries.of(s.name, s.endpoint)
        SoftCard(padding = 10.dp, modifier = Modifier.padding(bottom = 6.dp), onClick = {
            val next = if (inPool) pool - s.id else if (pool.size < VpnRotation.MAX_POOL) pool + s.id else { toast(context, "В пуле не больше ${VpnRotation.MAX_POOL} серверов"); pool }
            pool = next; VpnRotation.setPool(context, next)
        }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = inPool, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(Countries.flag(code), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name.ifEmpty { s.endpoint }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (st != null && st.checkedAt > 0) "проверен ${minutesText(now - st.checkedAt)} назад" else s.type.uppercase() + " · " + s.endpoint,
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (running) Pill("Сейчас", t.good, t.goodSoft)
                else if (inPool && st != null) {
                    if (st.dropped) Pill("Исключён", t.bad, t.badSoft)
                    else if (st.ema >= 0) Pill("${st.ema.toInt()} мс", if (st.ema <= 150) t.good else t.warn, if (st.ema <= 150) t.goodSoft else t.warnSoft)
                    else Pill("нет данных", t.warn, t.warnSoft)
                }
            }
        }
    }

    if (events.isNotEmpty()) {
        SectionTitle("Журнал ротации")
        val fmt = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
        SoftCard(padding = 10.dp) {
            events.take(10).forEach { e ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(fmt.format(Date(e.time)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                    Text(e.text, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    Text("Смена горячая: туннель телефона не пересоздаётся, но соединения, открытые через старый сервер, переустанавливаются приложениями автоматически. Страна меняется, когда в пуле есть серверы разных стран.",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))
}

// ------------------------------------------------------------------ Раздача

private fun openHotspotSettings(c: Context) {
    val intents = listOf(Intent("android.settings.TETHER_SETTINGS"), Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS))
    for (i in intents) { try { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (e: Exception) { } }
}

@Composable
internal fun ShareTab(vm: VpnViewModel) {
    val context = LocalContext.current
    val t = tones
    var enabled by remember { mutableStateOf(VpnShare.enabled(context)) }
    var limit by remember { mutableIntStateOf(VpnShare.limit(context)) }
    var devices by remember { mutableStateOf(VpnShare.devices(context)) }
    var addresses by remember { mutableStateOf(VpnShare.addresses()) }
    var adding by remember { mutableStateOf<String?>(null) }
    var shown by remember { mutableStateOf<VpnShare.Device?>(null) }
    var removing by remember { mutableStateOf<VpnShare.Device?>(null) }
    LaunchedEffect(Unit) { while (true) { delay(3000); addresses = VpnShare.addresses() } }
    val host = addresses.firstOrNull()?.ip

    SectionTitle("Раздача VPN")
    SoftCard(padding = 12.dp, color = if (enabled) t.goodSoft else MaterialTheme.colorScheme.surfaceContainerLowest) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (enabled) "Раздача включена" else "Раздача выключена", style = MaterialTheme.typography.titleSmall)
                Text(
                    when {
                        !enabled -> "Устройства подключаются к телефону по логину и паролю и выходят в интернет через ваш VPN"
                        !vm.serverActive -> "Включите VPN через сервер: сейчас устройства получат обычный интернет телефона"
                        host == null -> "Включите точку доступа или подключитесь к Wi-Fi"
                        else -> "Адрес для устройств: $host · SOCKS ${VpnShare.socksPort(context)} · HTTP ${VpnShare.httpPort(context)}"
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = enabled, onCheckedChange = {
                if (it && devices.isEmpty()) toast(context, "Добавьте хотя бы одно устройство")
                enabled = it; VpnShare.setEnabled(context, it)
            })
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openHotspotSettings(context) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 10.dp)) { Icon(Icons.Rounded.WifiTethering, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Точка доступа") }
            OutlinedButton(onClick = { VpnShare.reload(context); toast(context, "Применено") }, enabled = enabled && vm.active, contentPadding = PaddingValues(horizontal = 10.dp)) { Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Применить") }
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Максимум устройств: $limit", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text("занято ${devices.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
    }
    AppSlider(value = limit.toFloat(), onValueChange = { limit = it.toInt().coerceAtLeast(maxOf(1, devices.size)) }, onValueChangeFinished = { VpnShare.setLimit(context, limit) }, valueRange = 1f..VpnShare.MAX_LIMIT.toFloat())

    SectionTitle("Устройства")
    if (devices.isEmpty()) SoftCard { Text("Устройств пока нет. Добавьте телефон — появится QR-код для сканирования, или ноутбук — получите инструкцию для Windows 10.", style = MaterialTheme.typography.bodyMedium) }
    devices.forEach { d ->
        SoftCard(padding = 10.dp, modifier = Modifier.padding(bottom = 6.dp), onClick = { shown = d }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(if (d.laptop) Icons.Rounded.Laptop else Icons.Rounded.PhoneAndroid, t.info, MaterialTheme.colorScheme.surfaceContainerLow, 36.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(d.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (d.laptop) "Ноутбук · логин ${d.user}" else "Телефон · логин ${d.user}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { shown = d }) { Icon(Icons.Rounded.QrCode2, "QR") }
                IconButton(onClick = { removing = d }) { Icon(Icons.Rounded.Delete, "Отключить и удалить", tint = t.bad) }
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { adding = "mobile" }, modifier = Modifier.weight(1f), enabled = devices.size < limit) { Icon(Icons.Rounded.PhoneAndroid, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Телефон") }
        Button(onClick = { adding = "laptop" }, modifier = Modifier.weight(1f), enabled = devices.size < limit) { Icon(Icons.Rounded.Laptop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Ноутбук") }
    }
    Text("У каждого устройства свой пароль: удаление отрезает только его. Раздача идёт без root через прокси внутри приложения, поэтому подключённое устройство должно уметь работать через прокси или VPN-клиент (v2rayNG, Hiddify, v2rayN на Windows). Интернет самой точки доступа Android в туннель не попадает — используйте адрес и порт выше.",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 8.dp))

    adding?.let { kind ->
        var name by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { adding = null }, title = { Text(if (kind == "laptop") "Новый ноутбук" else "Новый телефон") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true) },
            confirmButton = { TextButton(onClick = {
                val d = VpnShare.addDevice(context, name.trim(), kind)
                if (d == null) toast(context, "Достигнут лимит устройств") else { devices = VpnShare.devices(context); shown = d; if (!enabled) { enabled = true; VpnShare.setEnabled(context, true) } }
                adding = null
            }) { Text("Создать") } },
            dismissButton = { TextButton(onClick = { adding = null }) { Text("Отмена") } })
    }
    removing?.let { d ->
        AlertDialog(onDismissRequest = { removing = null }, title = { Text("Удалить «${d.name}»?") }, text = { Text("Устройство потеряет доступ сразу после применения.") },
            confirmButton = { TextButton(onClick = { VpnShare.removeDevice(context, d.id); devices = VpnShare.devices(context); removing = null }) { Text("Удалить", color = tones.bad) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Отмена") } })
    }
    shown?.let { d -> DeviceSheet(d, addresses, enabled && host != null) { shown = null } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceSheet(d: VpnShare.Device, addresses: List<VpnShare.Address>, ready: Boolean, onClose: () -> Unit) {
    val context = LocalContext.current
    var ip by remember { mutableStateOf(addresses.firstOrNull()?.ip ?: "192.168.43.1") }
    val link = VpnShare.socksLink(context, d, ip)
    val bmp = remember(link) { VpnShare.qr(link) }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(d.name, style = MaterialTheme.typography.titleLarge)
            Text(if (d.laptop) "Откройте ссылку на ноутбуке или скопируйте инструкцию Windows" else "Отсканируйте в v2rayNG / Hiddify: «Импорт из QR»", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Image(bmp.asImageBitmap(), "QR", Modifier.size(220.dp).clip(RoundedCornerShape(12.dp)))
            if (addresses.size > 1) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { addresses.forEach { a -> MiniChip(a.ip, ip == a.ip) { ip = a.ip } } }
            if (addresses.isEmpty()) Text("Адрес появится, когда включится точка доступа или Wi-Fi", style = MaterialTheme.typography.bodySmall, color = tones.warn, modifier = Modifier.padding(top = 8.dp))
            else if (!ready) Text("Включите раздачу выше, чтобы ссылка заработала", style = MaterialTheme.typography.bodySmall, color = tones.warn, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(8.dp))
            Text("$ip · SOCKS ${VpnShare.socksPort(context)} / HTTP ${VpnShare.httpPort(context)}\nлогин ${d.user} · пароль ${d.pass}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { copyText(context, link, "Ссылка скопирована") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Ссылка") }
                OutlinedButton(onClick = { copyText(context, VpnShare.httpUrl(context, d, ip), "HTTP-прокси скопирован") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("HTTP-прокси") }
                if (d.laptop) Button(onClick = { shareText(context, VpnShare.windowsGuide(context, d, ip), "VPN для Windows") }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Windows") }
            }
        }
    }
}

// ------------------------------------------------------------------ Главное и «Ещё»

@Composable
private fun BigRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit, trailing: @Composable () -> Unit = { Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline) }) {
    SoftCard(padding = 12.dp, onClick = onClick, modifier = Modifier.padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tones.info, MaterialTheme.colorScheme.surfaceContainerLow, 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Главное: четыре понятных действия вместо восьми вкладок. */
@Composable
internal fun VpnMainTab(vm: VpnViewModel, onTab: (Int) -> Unit, onKeys: () -> Unit) {
    val context = LocalContext.current
    var rot by remember { mutableStateOf(VpnRotation.enabled(context)) }
    var share by remember { mutableStateOf(VpnShare.enabled(context)) }
    val server = vm.serverList.firstOrNull { it.id == (if (vm.serverActive) vm.runningProfileId else vm.selectedServer) }
    val ping = server?.let { vm.serverPings[it.id] }
    Spacer(Modifier.height(6.dp))
    BigRow(Icons.Rounded.Dns, "Сервер",
        if (server == null) "Сервера нет. Добавьте ключ или найдите бесплатные" else Countries.flag(Countries.of(server.name, server.endpoint)) + " " + server.name.ifEmpty { server.endpoint } + (if (ping != null && ping >= 0) " · $ping мс" else ""),
        { onTab(1) })
    BigRow(Icons.Rounded.Autorenew, "Менять сервер сама",
        if (rot) "Включено: смена каждые ${VpnRotation.intervalMin(context).let { if (it >= 60) "${it / 60} ч" else "$it мин" }}" else "Страна будет меняться по расписанию",
        { onTab(6) }) {
        Switch(checked = rot, onCheckedChange = {
            if (it && VpnRotation.pool(context).size < 2) { toast(context, "Выберите 2 и больше серверов"); onTab(6) } else { rot = it; VpnRotation.setEnabled(context, it) }
        })
    }
    BigRow(Icons.Rounded.WifiTethering, "Раздать другим устройствам",
        if (share) "Включено: устройств ${VpnShare.devices(context).size} из ${VpnShare.limit(context)}" else "Телефоны и ноутбук через ваш VPN",
        { onTab(7) }) {
        Switch(checked = share, onCheckedChange = {
            if (it && VpnShare.devices(context).isEmpty()) { toast(context, "Сначала добавьте устройство"); onTab(7) } else { share = it; VpnShare.setEnabled(context, it) }
        })
    }
    BigRow(Icons.Rounded.VpnKey, "Найти бесплатные ключи", "Поиск по сайтам, проверка отклика, сортировка по странам", onKeys)
    BigRow(Icons.Rounded.MoreHoriz, "Ещё", "Свои адреса, DNS, приложения, запись трафика, журнал", { onTab(9) })
}

@Composable
internal fun MoreTab(onTab: (Int) -> Unit) {
    Spacer(Modifier.height(6.dp))
    BigRow(Icons.Rounded.Link, "Свои адреса сайтов", "Подменить IP для сайта (файл Hosts)", { onTab(0) })
    BigRow(Icons.Rounded.AccountTree, "Какие приложения идут через VPN", "Все или только выбранные", { onTab(3) })
    BigRow(Icons.Rounded.Dns, "DNS-серверы", "Какими серверами пользоваться для имён сайтов", { onTab(2) })
    BigRow(Icons.Rounded.RadioButtonChecked, "Запись трафика", "Диагностика: сохранить обмен данными в файл", { onTab(4) })
    BigRow(Icons.Rounded.Description, "Журнал событий", "Что делал VPN и почему не подключился", { onTab(5) })
}
