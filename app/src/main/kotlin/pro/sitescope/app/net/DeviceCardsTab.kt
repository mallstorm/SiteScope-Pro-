@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package pro.sitescope.app.net

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
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

@Composable
internal fun DevicesTab(vm: NetViewModel) {
    var wol by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(false) }
    val t = tones
    val subnet = remember { vm.localSubnet() }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SmartSearchBar(vm.cardQuery, vm::searchCards, "Найти устройство, IP, MAC или сервис", onGo = { }, scan = true)
        MetricStrip(listOf(
            Metric(Icons.Rounded.Wifi, "Текущая сеть", subnet.ifEmpty { "—" }),
            Metric(Icons.Rounded.Devices, "Найдено устройств", vm.cardCount.toString()),
            Metric(Icons.Rounded.Radar, "Ответили сейчас", vm.live.size.toString())
        ))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Устройства (${vm.cardCount})", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { history = true }, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.History, "История изменений", tint = MaterialTheme.colorScheme.primary) }
            IconButton(onClick = { wol = true }, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.PowerSettingsNew, "Включить по сети", tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(4.dp))
            if (vm.discovering) OutlinedButton(onClick = vm::stopDiscovery, contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Стоп") }
            else Button(onClick = vm::startDiscovery, contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Rounded.Radar, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Найти") }
        }
        if (vm.discovering) Text("${vm.discoveryStatus} · ответов: ${vm.live.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (vm.discovering || vm.cardsLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (vm.discoveryError.isNotBlank()) ErrorCard(vm.discoveryError)
        if (vm.cardsError.isNotBlank()) ErrorCard(vm.cardsError)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (vm.cards.isEmpty() && !vm.cardsLoading) item {
                EmptyState(Icons.Rounded.Devices, if (vm.cardCount == 0) "Пока пусто" else "Ничего не найдено",
                    "Нажмите «Найти», проверьте порты или сохраните подключение. Карточки сохраняются между запусками.")
            }
            items(vm.cards, key = { it.id }) { card ->
                Row(
                    Modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest)
                        .clickable { vm.showCard(card.id) }.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ToolIcon(Icons.Rounded.Devices, size = 34.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(card.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(card.addresses, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${card.protocols} · записей: ${card.memberCount} · ${cardTime(card.updatedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
                }
            }
            if (vm.cards.size >= vm.cardLimit) item { TextButton(onClick = vm::moreCards) { Text("Показать ещё") } }
            item {
                Text("Без устойчивого ID карточка относится к адресу и сервису. Одинаковые названия не доказывают, что это одно устройство.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    vm.selectedCard?.let { DeviceCardSheet(vm, it) }
    if (wol) WolSheet(vm) { wol = false }
    if (history) DeviceHistorySheet("") { history = false }
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
