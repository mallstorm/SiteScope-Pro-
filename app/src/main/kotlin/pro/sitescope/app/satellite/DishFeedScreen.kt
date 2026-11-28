package pro.sitescope.app.satellite

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.sitescope.app.radio.Chips
import pro.sitescope.app.radio.clock
import java.net.URI

@Composable
internal fun DishFeedScreen(
    site: SitePoint?,
    imported: List<DishEntry>,
    onChoose: (Broadcast) -> Unit,
    onClose: () -> Unit,
    initialQuery: String = "",
    initialMode: String = "Каналы",
) {
    BackHandler(onBack = onClose)
    val context = LocalContext.current
    val work = remember(context) { WorkManager.getInstance(context) }
    val manual by remember(work) { work.getWorkInfosForUniqueWorkFlow(DishFeeds.MANUAL) }.collectAsState(emptyList())
    val periodic by remember(work) { work.getWorkInfosForUniqueWorkFlow(DishFeeds.PERIODIC) }.collectAsState(emptyList())
    val running = (manual + periodic).any { it.state == WorkInfo.State.RUNNING }
    val queued = manual.any { it.state == WorkInfo.State.ENQUEUED }
    var state by remember { mutableStateOf(DishFeedState()) }
    var loaded by remember { mutableStateOf(false) }
    var revision by remember { mutableIntStateOf(0) }
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    var onlyVisible by rememberSaveable { mutableStateOf(false) }
    var auto by remember { mutableStateOf(DishFeeds.auto(context)) }
    var source by rememberSaveable { mutableStateOf("") }
    var message by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(manual, periodic, revision) {
        state = withContext(Dispatchers.IO) { DishFeeds.state(context) }
        loaded = true
    }
    LaunchedEffect(loaded) {
        if (loaded && state.feeds.isNotEmpty() && state.feeds.all { it.updated == 0L && it.error.isBlank() }) {
            DishFeeds.refresh(context)
        }
    }
    var own by remember { mutableStateOf(DishFeeds.custom(context)) }
    var editIndex by rememberSaveable { mutableIntStateOf(-1) }
    val edit = own.getOrNull(editIndex)
    var editor by rememberSaveable { mutableStateOf(false) }
    if (editor) TransponderEditor(edit, onSave = { row ->
        own = (own.filterNot { it == edit } + row).distinct()
        DishFeeds.saveCustom(context, own)
        editor = false
    }, onDelete = {
        own = own.filterNot { it == edit }
        DishFeeds.saveCustom(context, own)
        editor = false
    }, onClose = { editor = false })
    val localEntries = remember(imported) {
        imported.map { DishFeedEntry(it.asBroadcast(), "", 0) }
    }
    val rotation = remember(site) { mutableMapOf<Double, DishAngles>() }
    val results = remember(state.entries, localEntries, own, query, mode, onlyVisible, site) {
        (own.map { DishFeedEntry(it, "", -1) } + state.entries + localEntries).asSequence().filter { entry ->
            val row = entry.row
            val matchesMode = when (mode) {
                "Пакеты" -> row.channel.isNotBlank() && row.network.isNotBlank()
                "Транспондеры" -> row.frequency.isNotBlank()
                else -> row.channel.isNotBlank()
            }
            matchesMode && row.matches(query) && (!onlyVisible || site == null ||
                rotation.getOrPut(row.orbit) { dishAngles(site, row.orbit) }.elevation > 0)
        }.distinctBy { entry ->
            val row = entry.row
            when (mode) {
                "Пакеты" -> listOf(row.orbit, searchKey(row.network))
                "Транспондеры" -> listOf(row.orbit, row.frequency, row.polarization, row.rate, row.note)
                else -> listOf(row.orbit, searchKey(row.channel), row.frequency, row.polarization, row.rate, row.note)
            }
        }.sortedWith(compareBy<DishFeedEntry> { it.row.orbit }.thenBy { it.row.channel }).toList()
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Данные эфира", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            IconButton({ DishFeeds.refresh(context) }, enabled = !running && !queued) {
                Icon(Icons.Rounded.Refresh, "Обновить источники")
            }
            IconButton(onClose) { Icon(Icons.Rounded.Close, "Назад в каталог") }
        }
        Chips(listOf("Каналы", "Пакеты", "Транспондеры", "Изменения", "Источники", "Своё"), mode) { mode = it }
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (queued && !running) Text("Обновление ожидает подключения", style = MaterialTheme.typography.labelSmall)
        when (mode) {
            "Источники" -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Проверять ежедневно при наличии сети", Modifier.weight(1f))
                        Switch(auto, { auto = it; DishFeeds.setAuto(context, it) })
                    }
                    Text("Поддерживаются страницы спутников LyngSat, satellites.xml, lamedb 4 и CSV.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(source, { source = it }, Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("HTTPS-адрес источника") })
                    TextButton({
                        runCatching { DishFeeds.setSources(context, DishFeeds.sources(context) + source.trim()) }
                            .onSuccess {
                                source = ""
                                message = "Источник добавлен"
                                revision++
                                DishFeeds.refresh(context)
                            }.onFailure { message = it.localizedMessage ?: "Проверьте адрес" }
                    }, enabled = source.isNotBlank()) { Text("Добавить источник") }
                    if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
                }
                items(state.feeds) { feed ->
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(feed.url, style = MaterialTheme.typography.bodySmall)
                            Text(if (feed.updated > 0) "${clock(feed.updated)} · ${feed.count} записей" else "Данные ещё не получены",
                                style = MaterialTheme.typography.labelSmall)
                            if (feed.error.isNotBlank()) Text(feed.error, color = MaterialTheme.colorScheme.error)
                            TextButton({
                                DishFeeds.setSources(context, DishFeeds.sources(context) - feed.url)
                                revision++
                            }) { Text("Убрать из обновлений") }
                        }
                    }
                }
            }
            "Своё" -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                item {
                    FilledTonalButton({ editIndex = -1; editor = true }, enabled = own.size < 500) { Text("Добавить транспондер") }
                    if (own.isEmpty()) Text("Записи доступны без интернета и участвуют в общем поиске.")
                }
                items(own) { row ->
                    ElevatedCard(onClick = { editIndex = own.indexOf(row); editor = true }, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(row.channel.ifBlank { row.satellite }, style = MaterialTheme.typography.titleSmall)
                            Text("${orbitalLabel(row.orbit)} · ${row.satellite} · ${row.tuning}")
                        }
                    }
                }
            }
            "Изменения" -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.events.isEmpty()) item {
                    Text("Изменения появятся после сравнения двух успешных загрузок. Первая загрузка создаёт исходный список.")
                }
                items(state.events) { event ->
                    val row = event.change.after ?: event.change.before ?: return@items
                    ElevatedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(event.change.kind, color = MaterialTheme.colorScheme.primary)
                            Text("${row.channel} · ${orbitalLabel(row.orbit)} ${row.satellite}", style = MaterialTheme.typography.titleSmall)
                            event.change.before?.let { Text("Было · ${it.tuning} · ${it.network} ${it.access}") }
                            event.change.after?.let { Text("Стало · ${it.tuning} · ${it.network} ${it.access}") }
                            Text("${clock(event.time)} · ${URI(event.source).host}", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            else -> {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Канал, спутник или пакет") }, placeholder = { Text("ТНТ, Русский пакет, 90° E") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(onlyVisible, { onlyVisible = !onlyVisible }, { Text("Над горизонтом") }, enabled = site != null)
                    Text("${results.size} совпадений", style = MaterialTheme.typography.labelSmall)
                }
                if (state.feeds.any { it.error.isNotBlank() }) TextButton({ mode = "Источники" }) {
                    Text("Часть источников недоступна")
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                    if (loaded && results.isEmpty()) item {
                        if (state.entries.isEmpty()) {
                            Text("Загрузите справочники, чтобы искать эфир по всем подключённым спутникам.")
                            Button({ DishFeeds.refresh(context) }, enabled = !running && !queued) { Text("Загрузить справочники") }
                        } else Text("Совпадений в загруженных источниках нет.")
                    }
                    items(results) { entry ->
                        val row = entry.row
                        val angles = site?.let { rotation.getOrPut(row.orbit) { dishAngles(it, row.orbit) } }
                        ElevatedCard(onClick = {
                            if (mode == "Пакеты") { query = row.network; mode = "Каналы" }
                            else onChoose(row)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(when (mode) {
                                    "Пакеты" -> row.network
                                    "Транспондеры" -> row.tuning
                                    else -> row.channel
                                }, style = MaterialTheme.typography.titleSmall)
                                Text("${orbitalLabel(row.orbit)} · ${row.satellite}", color = MaterialTheme.colorScheme.primary)
                                if (mode == "Каналы") Text(row.tuning.ifBlank { "Параметры в источнике не указаны" })
                                if (mode != "Пакеты") {
                                    val info = listOf(row.network, row.access, row.note.takeUnless { it == row.tuning }.orEmpty())
                                        .filter(String::isNotBlank).joinToString(" · ")
                                    if (info.isNotEmpty()) Text(info, style = MaterialTheme.typography.bodySmall)
                                }
                                if (angles != null) Text(if (angles.elevation > 0)
                                    "От вашей точки · азимут ${degrees(angles.azimuth)} · подъём ${degrees(angles.elevation)}"
                                else "Ниже горизонта", style = MaterialTheme.typography.labelSmall)
                                Text(when {
                                    entry.updated == -1L -> "Свой транспондер"
                                    entry.url.isBlank() -> "Из файла"
                                    else -> "${URI(entry.url).host} · загружено ${clock(entry.updated)}"
                                },
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TransponderEditor(
    initial: Broadcast?,
    onSave: (Broadcast) -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
) {
    var satellite by rememberSaveable { mutableStateOf(initial?.satellite.orEmpty()) }
    var position by rememberSaveable { mutableStateOf(initial?.orbit?.toString().orEmpty()) }
    var channel by rememberSaveable { mutableStateOf(initial?.channel.orEmpty()) }
    var provider by rememberSaveable { mutableStateOf(initial?.network.orEmpty()) }
    var frequency by rememberSaveable { mutableStateOf(initial?.frequency.orEmpty()) }
    var rate by rememberSaveable { mutableStateOf(initial?.rate.orEmpty()) }
    var pol by rememberSaveable { mutableStateOf(initial?.polarization ?: "H") }
    var system by rememberSaveable { mutableStateOf(initial?.standard ?: "DVB-S2") }
    var russian by rememberSaveable { mutableStateOf(initial?.language == "ru") }
    val fields = listOf(
        Triple("Спутник", satellite, { value: String -> satellite = value.take(100) }),
        Triple("Позиция в градусах, E (+) / W (−)", position, { value: String -> position = value.take(12) }),
        Triple("Частота, МГц", frequency, { value: String -> frequency = value.take(12) }),
        Triple("Символьная скорость, ксимв/с", rate, { value: String -> rate = value.take(10) }),
        Triple("Канал (можно оставить пустым)", channel, { value: String -> channel = value.take(100) }),
        Triple("Пакет или провайдер", provider, { value: String -> provider = value.take(100) }),
    )
    val orbit = position.replace(',', '.').toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
    val freq = frequency.replace(',', '.').toDoubleOrNull()?.takeIf { it in 1000.0..31_000.0 }
    val sr = rate.toIntOrNull()?.takeIf { it in 100..100_000 }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (initial == null) "Свой транспондер" else "Правка транспондера") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                fields.forEach { (label, value, edit) ->
                    OutlinedTextField(value, edit, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true)
                }
                Chips(listOf("H", "V", "L", "R"), pol) { pol = it }
                Chips(listOf("DVB-S", "DVB-S2", "DVB-S2X"), system) { system = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Русскоязычный канал", Modifier.weight(1f))
                    Switch(russian, { russian = it })
                }
                if (initial != null) TextButton(onDelete) { Text("Удалить запись") }
            }
        },
        confirmButton = {
            TextButton({
                val row = Broadcast(satellite.trim(), requireNotNull(orbit), channel.trim(), provider.trim(),
                    java.math.BigDecimal.valueOf(requireNotNull(freq)).stripTrailingZeros().toPlainString(),
                    pol, requireNotNull(sr).toString(), system, language = if (russian) "ru" else "")
                onSave(row)
            }, enabled = satellite.isNotBlank() && orbit != null && freq != null && sr != null) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClose) { Text("Отмена") } },
    )
}
