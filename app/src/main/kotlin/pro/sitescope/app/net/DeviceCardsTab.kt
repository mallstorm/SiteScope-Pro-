@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package pro.sitescope.app.net

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pro.sitescope.app.DeviceRegistryStore
import pro.sitescope.app.ProtocolRouter
import pro.sitescope.app.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun cardTime(time: Long) = if (time > 0) SimpleDateFormat("d MMM yyyy, HH:mm", Locale.forLanguageTag("ru")).format(Date(time)) else "Неизвестно"

private fun categoryOf(type: String, text: String): String {
    val t = (type + " " + text).lowercase()
    return when {
        "роутер" in t || "router" in t || "шлюз" in t || "gateway" in t || "igd" in t -> "router"
        "камер" in t || "camera" in t || "onvif" in t || "rtsp" in t -> "camera"
        "nas" in t || "хранил" in t || "storage" in t -> "nas"
        "принтер" in t || "printer" in t || "ipp" in t -> "printer"
        "tv" in t || "телевиз" in t || "dlna" in t -> "tv"
        "пк" in t || "компьютер" in t || "windows" in t || "rdp" in t || "smb" in t -> "pc"
        else -> "other"
    }
}

private val CATEGORIES = listOf("router" to "Роутеры", "camera" to "Камеры", "nas" to "Хранилища", "printer" to "Принтеры", "tv" to "ТВ", "pc" to "Компьютеры", "other" to "Прочее")

private fun typeIcon(type: String, text: String): androidx.compose.ui.graphics.vector.ImageVector = when (categoryOf(type, text)) {
    "router" -> Icons.Rounded.Router; "camera" -> Icons.Rounded.Videocam; "nas" -> Icons.Rounded.Storage
    "printer" -> Icons.Rounded.Print; "tv" -> Icons.Rounded.Tv; "pc" -> Icons.Rounded.Computer; else -> Icons.Rounded.Devices
}

@Composable
internal fun DevicesTab(vm: NetViewModel, onTab: (Int) -> Unit = {}) {
    var archive by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(vm) { vm.updateLan(); onPauseOrDispose { } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!archive, { archive = false }, { Text("Сейчас") })
            FilterChip(archive, { archive = true }, { Text("Архив") })
        }
        if (archive) ArchivedDevicesTab(vm, onTab) else CurrentLanTab(vm)
    }
}

@Composable
private fun CurrentLanTab(vm: NetViewModel) {
    var query by rememberSaveable { mutableStateOf("") }
    val devices = if (vm.lan != null) vm.liveAggregates() else emptyList()
    val shown = devices.filter {
        query.isBlank() || listOf(it.ip, it.title(), it.manufacturer, it.protocols.joinToString(" "))
            .any { value -> value.contains(query.trim(), ignoreCase = true) }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MetricStrip(listOf(
            Metric(Icons.Rounded.Wifi, "Подключение", vm.connectionLabel),
            Metric(Icons.Rounded.Devices, "Найдено", devices.size.toString())
        ))
        if (vm.lan == null) {
            EmptyState(Icons.Rounded.WifiOff, "Нет локальной сети",
                "Подключитесь к Wi-Fi или Ethernet. Результаты прошлых проверок доступны в «Архиве».")
            return@Column
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.discovering) OutlinedButton(vm::stopDiscovery) { Text("Остановить") }
            else Button(vm::startDiscovery) { Icon(Icons.Rounded.Radar, null); Spacer(Modifier.width(6.dp)); Text("Обновить поиск") }
            if (vm.discoveryTime > 0L) Text("Поиск: ${cardTime(vm.discoveryTime)}", style = MaterialTheme.typography.labelSmall)
        }
        if (vm.discovering) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.discoveryStatus.isNotBlank()) Text(vm.discoveryStatus, style = MaterialTheme.typography.bodySmall)
        if (vm.discoveryError.isNotBlank()) ErrorCard(vm.discoveryError)
        if (devices.isNotEmpty()) OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Фильтр устройств") }, singleLine = true
        )
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!vm.discovering && shown.isEmpty()) item {
                Text(when {
                    query.isNotBlank() -> "Совпадений нет"
                    vm.discoveryTime > 0L -> "Устройства не ответили на локальный поиск."
                    else -> "Нажмите «Обновить поиск», чтобы проверить локальную сеть."
                })
            }
            items(shown, key = { it.ip }) { device ->
                var expanded by rememberSaveable { mutableStateOf(false) }
                SoftCard(padding = 12.dp, onClick = { expanded = !expanded }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Devices, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(device.title(), style = MaterialTheme.typography.titleSmall)
                            Text(listOf(device.ip, device.manufacturer, device.model, device.protocols.joinToString(", "))
                                .filter(String::isNotBlank).distinct().joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                    }
                    if (expanded) device.services.forEach { service ->
                        Text(listOf(service.protocol, service.name, service.services).filter { !it.isNullOrBlank() }
                            .distinct().joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchivedDevicesTab(vm: NetViewModel, onTab: (Int) -> Unit) {
    var wol by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val t = tones
    var clearArchive by remember { mutableStateOf(false) }
    LaunchedEffect(vm) { vm.refreshCards() }
    var lastScan by remember { mutableStateOf<pro.sitescope.app.Models.ScanSession?>(null) }
    LaunchedEffect(vm.scan.active) {
        lastScan = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { try { pro.sitescope.app.DataStore(context.applicationContext).scans().firstOrNull() } catch (e: Exception) { null } }
    }
    var showFilters by remember { mutableStateOf(false) }
    var typeFilter by remember { mutableStateOf("") }
    fun typeOf(card: DeviceRegistryStore.Card): String {
        val o = card.observations.firstOrNull()
        val ident = try { o?.identity() } catch (e: Exception) { null }
        return categoryOf(ident?.deviceType.orEmpty().ifEmpty { try { o?.data?.optString("deviceType").orEmpty() } catch (e: Exception) { "" } }, card.title + " " + card.protocols)
    }
    val filtered = vm.cards.filter { typeFilter.isEmpty() || typeOf(it) == typeFilter }
    val present = vm.cards.map { typeOf(it) }.toSet()
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SmartSearchBar(vm.cardQuery, vm::searchCards, "Найти устройство, IP, MAC или сервис", onGo = { vm.refreshCards() }, scan = true)
        if (vm.cardsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.cardsError.isNotBlank()) ErrorCard(vm.cardsError)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Архив карточек (${vm.cardCount})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { showFilters = !showFilters }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                        Icon(Icons.Rounded.FilterList, null, Modifier.size(18.dp)); Spacer(Modifier.width(2.dp)); Text(if (typeFilter.isNotEmpty()) "Фильтры •" else "Фильтры", maxLines = 1)
                    }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.MoreVert, "Ещё", tint = MaterialTheme.colorScheme.primary) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("Поделиться списком") }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = {
                                menu = false
                                val text = vm.cards.joinToString("\n") { c -> c.title + " — " + c.addresses + " — " + c.protocols }
                                shareText(context, text.ifEmpty { "Устройств нет" }, "Устройства сети")
                            })
                            DropdownMenuItem(text = { Text("Обновить архив") }, leadingIcon = { Icon(Icons.Rounded.Refresh, null) },
                                onClick = { menu = false; vm.refreshCards() })
                            DropdownMenuItem(text = { Text("Очистить архив устройств") }, leadingIcon = { Icon(Icons.Rounded.DeleteOutline, null) },
                                enabled = !vm.discovering && !vm.scan.active, onClick = { menu = false; clearArchive = true })
                            DropdownMenuItem(text = { Text("История изменений") }, leadingIcon = { Icon(Icons.Rounded.History, null) }, onClick = { menu = false; history = true })
                            DropdownMenuItem(text = { Text("Включить по сети (WoL)") }, leadingIcon = { Icon(Icons.Rounded.PowerSettingsNew, null) }, onClick = { menu = false; wol = true })
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                }
            }
            if (showFilters) item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    MiniChip("Все", typeFilter.isEmpty()) { typeFilter = "" }
                    CATEGORIES.filter { it.first in present }.forEach { (id, title) -> MiniChip(title, typeFilter == id) { typeFilter = if (typeFilter == id) "" else id } }
                }
            }
            if (filtered.isEmpty() && vm.cards.isNotEmpty()) item { Text("Под выбранные фильтры ничего не подходит.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (vm.cards.isEmpty() && !vm.cardsLoading) item {
                EmptyState(Icons.Rounded.Devices, if (vm.cardCount == 0) "Пока пусто" else "Ничего не найдено",
                    "Здесь сохраняются результаты прошлых проверок и карточки ваших подключений.")
            }
            items(filtered, key = { it.id }) { card ->
                val obs = card.observations.firstOrNull()
                val identity = try { obs?.identity() } catch (e: Exception) { null }
                val vendor = identity?.manufacturer.orEmpty()
                val type = identity?.deviceType.orEmpty().ifEmpty { try { obs?.data?.optString("deviceType").orEmpty() } catch (e: Exception) { "" } }
                val protocols = card.protocols.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val confidence = identity?.confidenceLabel() ?: "—"
                Row(
                    Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .clickable { vm.showCard(card.id) }.padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconBadge(typeIcon(type, card.title + " " + card.protocols), MaterialTheme.colorScheme.onPrimary, MaterialTheme.colorScheme.primary, 40.dp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1.4f)) {
                        Text(card.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(card.addresses.substringBefore(',').trim(), style = MaterialTheme.typography.labelLarge, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(type.ifEmpty { "Сетевое устройство" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        if (vendor.isNotEmpty()) Pill(vendor, t.info, t.infoSoft)
                        Text(protocols.take(3).joinToString(", ").ifEmpty { "—" }, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.VerifiedUser, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(3.dp))
                            Text(confidence, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(cardTime(card.updatedAt), style = MaterialTheme.typography.labelSmall, maxLines = 2)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
                }
            }
            if (vm.cards.size >= vm.cardLimit) item { TextButton(onClick = vm::moreCards) { Text("Показать ещё") } }
            item {
                CompactTitle("Последняя проверка", action = "История ›") { history = true }
                val s = lastScan
                SoftCard(padding = 10.dp) {
                    if (s == null) Text("Сканирование портов ещё не запускалось. Откройте вкладку «Сканер».", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else {
                        val done = if (s.totalProbes > 0) (s.completedProbes * 100 / s.totalProbes).coerceIn(0, 100) else 100
                        val risky = s.openResults.count { it.port in setOf(21, 23, 135, 139, 445, 3389, 5900) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(Icons.Rounded.MyLocation, MaterialTheme.colorScheme.onPrimary, MaterialTheme.colorScheme.primary, 40.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Сканирование сети", style = MaterialTheme.typography.titleMedium)
                                Text(s.targetExpression + " • " + (if (s.cancelled) "прервано" else "Полная проверка"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                if (s.cancelled) Pill("Остановлено", t.warn, t.warnSoft) else Pill("Завершено", t.good, t.goodSoft, icon = Icons.Rounded.Check)
                                Text(whenText(s.timestamp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(progress = { done / 100f }, modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)))
                            Spacer(Modifier.width(8.dp)); Text("$done%", style = MaterialTheme.typography.labelLarge)
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            ScanStat(Icons.Rounded.Devices, "Адресов с ответом", s.openResults.map { it.ip }.distinct().size.toString(), Modifier.weight(1f))
                            ScanStat(Icons.Rounded.Layers, "Проверено портов", "%,d".format(Locale("ru"), s.completedProbes), Modifier.weight(1f))
                            ScanStat(Icons.Rounded.LockOpen, "Открытых сервисов", s.openResults.size.toString(), Modifier.weight(1f))
                            ScanStat(Icons.Rounded.WarningAmber, "Рискованных портов", risky.toString(), Modifier.weight(1f), if (risky == 0) t.good else t.bad)
                        }
                    }
                }
            }
            item {
                CompactTitle("Быстрые действия")
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NetQuick(Icons.Rounded.Checklist, "Сканер портов", "Проверка портов и сервисов", Modifier.weight(1f)) { onTab(1) }
                    NetQuick(Icons.Rounded.AddCircleOutline, "Добавить подключение", "VPN, SSH, RDP и др.", Modifier.weight(1f)) { onTab(2) }
                }
            }
            item {
                Text("Без устойчивого ID карточка относится к адресу и сервису. Одинаковые названия не доказывают, что это одно устройство.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    vm.selectedCard?.let { DeviceCardSheet(vm, it) }
    if (wol) WolSheet(vm) { wol = false }
    if (history) DeviceHistorySheet("") { history = false }
    if (clearArchive) AlertDialog(
        onDismissRequest = { clearArchive = false },
        title = { Text("Очистить архив устройств?") },
        text = { Text("Удалятся снимки локальной сети и карточки из прошлых обнаружений и проверок портов вместе с их историей. Сохранённые подключения и отчёты о портах останутся.") },
        confirmButton = { TextButton({ clearArchive = false; vm.clearDeviceArchive() }) { Text("Очистить") } },
        dismissButton = { TextButton({ clearArchive = false }) { Text("Отмена") } }
    )
}

@Composable
private fun ScanStat(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, modifier: Modifier, color: androidx.compose.ui.graphics.Color? = null) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 2, minLines = 2)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color ?: MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun NetQuick(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    SoftCard(modifier, onClick = onClick, padding = 8.dp) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
            }
        }
    }
}

@Composable
private fun DeviceCardSheet(vm: NetViewModel, card: DeviceRegistryStore.Card) {
    val activity = LocalActivity.current
    val context = LocalContext.current
    var history by remember(card.id) { mutableStateOf(false) }
    var rename by remember(card.id) { mutableStateOf(false) }
    var merging by remember(card.id) { mutableStateOf(false) }
    var splitting by remember(card.id) { mutableStateOf(false) }
    var splitName by remember(card.id) { mutableStateOf(false) }
    val selected = remember(card.id) { mutableStateListOf<String>() }
    ModalBottomSheet(onDismissRequest = vm::closeCard) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(card.title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                    IconButton(onClick = { rename = true }) { Icon(Icons.Rounded.Edit, "Переименовать") }
                }
                Text(card.addresses, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                Text("ID карточки: ${card.id}", style = MaterialTheme.typography.labelSmall)
                Text("Создана: ${cardTime(card.createdAt)}\nОбновлена: ${cardTime(card.updatedAt)}", style = MaterialTheme.typography.bodySmall)
                if (card.priorNames.isNotEmpty()) Text("Сохранённые имена: ${card.priorNames.joinToString()}", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { history = true }) { Text("История") }
                    OutlinedButton(onClick = { merging = true }) { Text("Объединить") }
                    OutlinedButton(onClick = { splitting = !splitting; selected.clear() }, enabled = card.memberCount > 1) { Text(if (splitting) "Отмена" else "Разделить") }
                }
                if (splitting) {
                    Text("Выберите записи, которые нужно перенести в отдельную карточку. Остальные останутся здесь.", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { splitName = true }, enabled = selected.isNotEmpty() && selected.size < card.memberCount) { Text("Отделить выбранные (${selected.size})") }
                }
                SectionTitle("Источники и сервисы (${card.memberCount})")
            }
            item { CardLeaseLinks(card.id, card.memberCount) }
            if (vm.cardsError.isNotBlank()) item { ErrorCard(vm.cardsError) }
            items(card.observations, key = { it.id }) { o ->
                SoftCard(padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (splitting) Checkbox(o.id in selected, { checked -> if (checked) selected.add(o.id) else selected.remove(o.id) })
                        Column(Modifier.weight(1f)) {
                            Text("${o.protocol} · ${o.host}${if (o.port > 0) ":${o.port}" else ""}", style = MaterialTheme.typography.titleSmall)
                            Text("${o.sourceLabel()} · ${cardTime(o.seenAt)}", style = MaterialTheme.typography.labelSmall)
                        }
                        if (!splitting) IconButton(onClick = {
                            try {
                                val endpoint = o.endpoint(context)
                                if (endpoint != null && activity != null) ProtocolRouter.openActionPanel(activity, endpoint)
                                else toast(context, "Подключение уже удалено")
                            } catch (e: Exception) { toast(context, "Не удалось открыть сервис") }
                        }) { Icon(Icons.AutoMirrored.Rounded.OpenInNew, "Открыть сервис") }
                    }
                    if (o.protocol == "ONVIF") TextButton(onClick = {
                        try { val endpoint = o.endpoint(context); if (endpoint != null && activity != null) ProtocolRouter.openBuiltIn(activity, endpoint) }
                        catch (e: Exception) { toast(context, "Не удалось открыть центр камеры") }
                    }) { Text("Камера: профили и видео") }
                    Text(o.scope.label, style = MaterialTheme.typography.bodySmall)
                    if (o.stableId.isNotBlank()) Text("Объявленный ID: ${o.stableId}", style = MaterialTheme.typography.labelSmall)
                    if (o.ambiguous) Text("Совпадение ID неоднозначно — требуется ручная проверка", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (o.manual) Text("Расположение задано вручную", style = MaterialTheme.typography.labelSmall)
                    val details = listOf("location", "services", "evidence", "banner").map { o.data.optString(it) }.filter { it.isNotBlank() }.joinToString("\n")
                    if (details.isNotBlank()) TechDetails(details)
                    if (o.identity().findings.isNotEmpty()) DeviceIdentityDetails(o.identity(), o.data.optString("tlsTrust", "not_applicable"))
                }
            }
            if (card.observations.size < card.memberCount) item { TextButton(onClick = vm::moreObservations) { Text("Ещё записи") } }
        }
    }
    if (history) DeviceHistorySheet(card.id) { history = false }
    if (rename) TextDialog("Название устройства", card.alias, "Например: камера у входа", onClose = { rename = false }) { vm.renameCard(card.id, it); rename = false }
    if (splitName) TextDialog("Новая карточка", "", "Название (необязательно)", onClose = { splitName = false }) { vm.splitCard(card.id, selected.toList(), it); selected.clear(); splitting = false; splitName = false }
    if (merging) MergeCardsDialog(vm, card) { merging = false }
}

@Composable
private fun MergeCardsDialog(vm: NetViewModel, card: DeviceRegistryStore.Card, close: () -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var limit by remember { mutableIntStateOf(100) }
    var options by remember { mutableStateOf<List<DeviceRegistryStore.Card>>(emptyList()) }
    var chosen by remember { mutableStateOf<DeviceRegistryStore.Card?>(null) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(query, limit) {
        try {
            options = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { DeviceRegistryStore.list(context, query, limit).filter { it.id != card.id } }; error = ""
        } catch (e: Exception) { error = "Не удалось загрузить карточки" }
    }
    AlertDialog(onDismissRequest = close, title = { Text("Объединить с «${card.title}»") }, text = {
        Column {
            Text("Выберите карточку того же устройства. Записи и их источники сохранятся. Позже их можно разделить.")
            OutlinedTextField(query, { query = it; limit = 100; chosen = null }, label = { Text("Имя или адрес") }, singleLine = true)
            if (error.isNotEmpty()) Text(error)
            LazyColumn(Modifier.heightIn(max = 260.dp)) {
                items(options, key = { it.id }) { other ->
                    Row(Modifier.fillMaxWidth().clickable { chosen = other }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(chosen?.id == other.id, { chosen = other })
                        Column { Text(other.title, maxLines = 1, overflow = TextOverflow.Ellipsis); Text(other.addresses, style = MaterialTheme.typography.labelSmall) }
                    }
                }
                if (options.size >= limit - 1) item { TextButton(onClick = { limit += 100 }) { Text("Показать ещё") } }
            }
        }
    }, confirmButton = { TextButton(onClick = { chosen?.let { vm.mergeCards(card.id, it.id) }; close() }, enabled = chosen != null) { Text("Объединить") } }, dismissButton = { TextButton(onClick = close) { Text("Отмена") } })
}
