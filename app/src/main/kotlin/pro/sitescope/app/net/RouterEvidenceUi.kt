package pro.sitescope.app.net

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject
import pro.sitescope.app.*
import pro.sitescope.app.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal fun routerTime(time: Long): String = SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale.forLanguageTag("ru")).format(Date(time))
private fun snapshotTitle(s: JSONObject) = "${s.optString("name").ifBlank { s.optString("host") }} · ${routerTime(s.optLong("time"))}"

@Composable
internal fun RouterComparePanel(snapshots: List<JSONObject>) {
    var beforeId by remember { mutableStateOf("") }
    var afterId by remember { mutableStateOf("") }
    var picker by remember { mutableIntStateOf(0) }
    var limit by remember(beforeId, afterId) { mutableIntStateOf(30) }
    var result by remember { mutableStateOf<RouterComparison.Result?>(null) }
    var error by remember { mutableStateOf("") }
    val before = snapshots.find { it.optString("id") == beforeId }
    val after = snapshots.find { it.optString("id") == afterId }
    LaunchedEffect(before, after) {
        result = null; error = ""
        if (before != null && after != null) {
            try { result = withContext(Dispatchers.Default) { RouterComparison.compare(before, after) } }
            catch (e: Exception) { error = if (e is IllegalArgumentException) e.message ?: "Не удалось сравнить" else "Не удалось сравнить снимки" }
        }
    }
    SoftCard {
        Text("Сравнение снимков", style = MaterialTheme.typography.titleMedium)
        Text("Один адрес API, адаптер и сеть. Сравниваются записи DHCP, интерфейсы и известные сведения системы. Изменение записи не доказывает включение или выключение устройства.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { picker = 1 }) { Text(before?.let(::snapshotTitle) ?: "Выбрать ранний снимок") }
        OutlinedButton(enabled = before != null && RouterComparison.origin(before).isNotEmpty(), onClick = { picker = 2 }) { Text(after?.let(::snapshotTitle) ?: "Выбрать поздний снимок") }
        if (before != null && RouterComparison.origin(before).isEmpty()) Text("У снимка нет надёжной области сети. Выполните новый опрос; снимки beta20 доступны для просмотра.")
        if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        if (before != null && after != null) result?.let { diff ->
            Text("${routerTime(before.getLong("time"))} → ${routerTime(after.getLong("time"))}", style = MaterialTheme.typography.labelSmall)
            Text(after.getString("source"), style = MaterialTheme.typography.bodySmall)
            if (diff.partial) Text("Снимок частичный или источник DHCP изменился. Появление и исчезновение записей не определяются; показаны изменения измеренных полей.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            if (diff.ambiguous > 0 || diff.unkeyed > 0) Text("Исключено неоднозначных ключей: ${diff.ambiguous}; записей без ключа: ${diff.unkeyed}", style = MaterialTheme.typography.bodySmall)
            Text("Различий: ${diff.changes.size}")
            diff.changes.take(limit).forEach { change ->
                val kind = when (change.kind) { "lease" -> "DHCP"; "interface" -> "Интерфейс"; else -> "Система" }
                val action = when (change.type) { "added" -> "Появилась запись"; "removed" -> "Нет записи в позднем снимке"; else -> change.field }
                Text("$kind · ${change.key} · $action\n${change.before.ifBlank { "—" }} → ${change.after.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
            }
            if (limit < diff.changes.size) TextButton(onClick = { limit += 30 }) { Text("Ещё различия · $limit из ${diff.changes.size}") }
        }
    }
    if (picker != 0) {
        val options = if (picker == 1) snapshots else snapshots.filter { before != null && it.optString("id") != beforeId && it.optLong("time") >= before.optLong("time") && RouterComparison.origin(it) == RouterComparison.origin(before) }
        AlertDialog(onDismissRequest = { picker = 0 }, title = { Text(if (picker == 1) "Ранний снимок" else "Поздний снимок той же сети") }, text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                if (options.isEmpty()) item { Text("Подходящих снимков нет. Выполните ещё один опрос того же роутера в той же сети.") }
                items(options, key = { it.optString("id") }) { s -> TextButton(onClick = { if (picker == 1) { beforeId = s.getString("id"); afterId = "" } else afterId = s.getString("id"); picker = 0 }) { Column { Text(snapshotTitle(s)); Text(s.optString("source"), style = MaterialTheme.typography.labelSmall) } } }
            }
        }, confirmButton = { TextButton(onClick = { picker = 0 }) { Text("Закрыть") } })
    }
}

@Composable
internal fun RouterLeaseLinkDialog(snapshot: JSONObject, mac: String, close: () -> Unit, saved: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var limit by remember { mutableIntStateOf(50) }
    var cards by remember { mutableStateOf<List<DeviceRegistryStore.Card>>(emptyList()) }
    var selected by remember { mutableStateOf<DeviceRegistryStore.Card?>(null) }
    var lease by remember { mutableStateOf<JSONObject?>(null) }
    var existing by remember { mutableStateOf<JSONObject?>(null) }
    var existingTitle by remember { mutableStateOf("") }
    var confirmed by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    LaunchedEffect(snapshot, mac) {
        try {
            val data = withContext(Dispatchers.IO) {
                val row = RouterComparison.uniqueLease(snapshot, mac)
                val link = RouterLeaseStore.forOrigin(context, RouterComparison.origin(snapshot)).find { it.optString("mac") == RouterComparison.mac(mac) }
                val title = link?.let { DeviceRegistryStore.get(context, it.getString("cardId"), 1)?.title } ?: ""
                Triple(row, link, title)
            }
            lease = data.first; existing = data.second; existingTitle = data.third; ready = true
        } catch (e: Exception) { error = if (e is IllegalArgumentException) e.message ?: "Недоступна привязка" else "Не удалось загрузить связь" }
    }
    LaunchedEffect(query, limit) {
        try { cards = withContext(Dispatchers.IO) { DeviceRegistryStore.list(context, query, limit) } }
        catch (e: Exception) { error = "Не удалось загрузить карточки" }
    }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("Связать DHCP-клиента с карточкой") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${lease?.optString("name") ?: ""} · ${lease?.optString("address") ?: ""}\n${RouterComparison.mac(mac)}")
            Text("${snapshot.optString("source")} · ${routerTime(snapshot.optLong("time"))}", style = MaterialTheme.typography.labelSmall)
            if (existing != null) Text("Текущая карточка: ${existingTitle.ifBlank { existing!!.optString("cardId") }}")
            Text("Выберите существующую карточку. MAC и DHCP-аренда — признаки для ручной проверки, а не доказательство личности или доступности устройства.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(query, { query = it; selected = null; confirmed = false; limit = 50 }, enabled = !busy, label = { Text("Найти карточку по имени или адресу") }, singleLine = true)
            LazyColumn(Modifier.heightIn(max = 190.dp)) {
                items(cards, key = { it.id }) { card ->
                    Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { selected = card; confirmed = false }) {
                        RadioButton(selected?.id == card.id, { selected = card; confirmed = false }, enabled = !busy)
                        Column { Text(card.title); Text(card.addresses, style = MaterialTheme.typography.labelSmall) }
                    }
                }
                if (cards.size >= limit) item { TextButton(enabled = !busy, onClick = { limit += 50 }) { Text("Ещё карточки") } }
                if (cards.isEmpty()) item { Text("Карточки не найдены. Сначала найдите устройство или сохраните подключение.") }
            }
            selected?.let { card -> Text("Выбрано: ${card.title}\nID: ${card.id}", style = MaterialTheme.typography.labelSmall) }
            Row { Checkbox(confirmed, { confirmed = it }, enabled = !busy && selected != null && ready); Text("Я проверил сведения и подтверждаю эту связь") }
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = { TextButton(enabled = confirmed && selected != null && ready && !busy, onClick = {
        val target = selected!!.id; val revision = existing?.optString("revision") ?: ""
        busy = true
        scope.launch {
            try { withContext(Dispatchers.IO) { RouterLeaseStore.link(context, snapshot.getString("id"), mac, target, revision) }; saved(); close() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { ready = false; error = if (e is IllegalArgumentException) e.message ?: "Не удалось сохранить" else "Не удалось сохранить связь" }
            finally { busy = false }
        }
    }) { Text(if (existing != null) "Подтвердить новую привязку" else "Связать") } }, dismissButton = { TextButton(enabled = !busy, onClick = close) { Text("Отмена") } })
}

@Composable
internal fun CardLeaseLinks(cardId: String, members: Int) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var links by remember(cardId) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var version by remember(cardId) { mutableIntStateOf(0) }
    var limit by remember(cardId) { mutableIntStateOf(8) }
    var error by remember(cardId) { mutableStateOf("") }
    LaunchedEffect(cardId, members, version) {
        try { links = withContext(Dispatchers.IO) { RouterLeaseStore.forCard(context, cardId) } }
        catch (e: Exception) { error = "Не удалось загрузить связи DHCP" }
    }
    if (links.isNotEmpty()) {
        SectionTitle("DHCP: подтверждённые связи (${links.size})")
        Text("Это сведения из сохранённых снимков. Доступность клиента не проверялась. При разделении карточки связи остаются на исходной карточке.", style = MaterialTheme.typography.bodySmall)
        links.take(limit).forEach { link ->
            SoftCard {
                Text("${link.optString("leaseName")} · ${link.optString("address")} · ${link.optString("mac")}")
                Text("${link.optString("source")} · ${routerTime(link.optLong("observedAt"))}", style = MaterialTheme.typography.labelSmall)
                Text("Подтверждено владельцем: ${routerTime(link.optLong("time"))}", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = { scope.launch {
                    try { withContext(Dispatchers.IO) { RouterLeaseStore.unlink(context, link.getString("id"), link.getString("revision")) }; version++ }
                    catch (e: Exception) { error = "Связь изменилась или не была удалена. Откройте карточку заново." }
                } }) { Text("Отменить связь") }
            }
        }
        if (limit < links.size) TextButton(onClick = { limit += 32 }) { Text("Ещё связи") }
    }
    if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
}
