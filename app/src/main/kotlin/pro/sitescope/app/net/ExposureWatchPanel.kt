package pro.sitescope.app.net

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.sitescope.app.ui.SectionTitle
import pro.sitescope.app.ui.SoftCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExposureWatchPanel(vm: ExposureViewModel, openChecks: () -> Unit) {
    val targets = vm.targets
    val runs = vm.runs
    val timeline by produceState<ExposureWatch.Timeline?>(null, targets, runs) {
        value = withContext(Dispatchers.Default) { ExposureWatch.summarize(targets, runs) }
    }
    var query by rememberSaveable { mutableStateOf("") }
    var showEvents by rememberSaveable { mutableStateOf(true) }
    var measurementId by rememberSaveable { mutableStateOf<String?>(null) }
    val filtered by produceState<ExposureWatch.Timeline?>(null, timeline, query) {
        val data = timeline
        value = if (data == null || query.isBlank()) data else withContext(Dispatchers.Default) {
            val terms = query.trim().split(Regex("\\s+"))
            ExposureWatch.Timeline(
                data.latest.filter { it.matches(terms) },
                data.events.filter { it.current.matches(terms) },
            )
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SoftCard {
                SectionTitle("Наблюдение за сервисами")
                Text(
                    "По сохранённым TCP-проверкам ваших целей. Первый замер задаёт исходное состояние. Таймаут не считается закрытием порта.",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = openChecks) { Text("Перейти к проверке") }
                if (targets.isNotEmpty()) {
                    OutlinedTextField(
                        query, { query = it }, Modifier.fillMaxWidth(),
                        label = { Text("Цель, IP или порт") },
                        placeholder = { Text("Например, port:443") },
                        singleLine = true,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(showEvents, { showEvents = true }, label = { Text("Изменения") })
                        FilterChip(!showEvents, { showEvents = false }, label = { Text("Последние замеры") })
                    }
                }
            }
        }
        val data = filtered
        when {
            targets.isEmpty() -> item { Text("Сохраните цель во вкладке «Проверка».") }
            data == null -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            showEvents -> {
                item { SectionTitle("Изменения (${data.events.size})") }
                if (data.events.isEmpty()) item {
                    Text(if (query.isNotBlank()) "По запросу ничего не найдено." else "Изменения появятся после повторных замеров с той же точки.")
                }
                items(data.events) { event ->
                    WatchObservation(event.current, event.previous) { measurementId = event.current.run.optString("id") }
                }
            }
            else -> {
                item { SectionTitle("Последние замеры (${data.latest.size})") }
                if (data.latest.isEmpty()) item { Text(if (query.isBlank()) "Сохранённых замеров пока нет." else "По запросу ничего не найдено.") }
                items(data.latest, key = { it.endpoint.toString() }) { observation ->
                    WatchObservation(observation) { measurementId = observation.run.optString("id") }
                }
            }
        }
    }

    runs.firstOrNull { it.optString("id") == measurementId }?.let { run ->
        ModalBottomSheet(onDismissRequest = { measurementId = null }) {
            LazyColumn(contentPadding = PaddingValues(16.dp)) { item { Measurement(run) } }
        }
    }
}

@Composable
private fun WatchObservation(
    observation: ExposureWatch.Observation,
    previous: ExposureWatch.Observation? = null,
    openMeasurement: () -> Unit,
) {
    val endpoint = observation.endpoint
    val address = if (':' in endpoint.address) "[${endpoint.address}]" else endpoint.address
    val state = when (observation.state) {
        ExposureWatch.State.Open -> if (previous == null) "TCP доступен" else "Порт начал принимать TCP"
        ExposureWatch.State.Refused -> if (previous == null) "TCP-соединение отклонено" else "Порт перестал принимать TCP"
        ExposureWatch.State.NoResponse -> "Состояние не определено"
    }
    SoftCard {
        Text(state, style = MaterialTheme.typography.titleSmall)
        Text("${observation.title} · $address:${endpoint.port}", style = MaterialTheme.typography.bodyMedium)
        Text("${endpoint.source} · ${exposureDate(observation.time)}", style = MaterialTheme.typography.bodySmall)
        if (endpoint.external) Text("Внешняя точка измерения", style = MaterialTheme.typography.labelSmall)
        if (previous != null) {
            Text("Предыдущий подтверждённый замер · ${exposureDate(previous.time)}", style = MaterialTheme.typography.labelSmall)
        }
        TextButton(onClick = openMeasurement) { Text("Открыть измерение") }
    }
}
