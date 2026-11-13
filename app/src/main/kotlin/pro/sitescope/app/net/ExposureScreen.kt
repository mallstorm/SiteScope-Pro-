package pro.sitescope.app.net

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.json.JSONObject
import pro.sitescope.app.ExposureStore
import pro.sitescope.app.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun ExposureScreen(vm: ExposureViewModel = viewModel()) {
    var clear by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, vm) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.cancel() else if (event == Lifecycle.Event.ON_RESUME) vm.reload() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); vm.cancel() }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SoftCard {
                Text("Доступность моего сервиса", style = MaterialTheme.typography.titleMedium)
                Text("Один ваш IP или домен и выбранные TCP-порты. Проверка устанавливает соединение, без входа и изменения сервиса.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(vm.name, { vm.name = it }, Modifier.fillMaxWidth(), enabled = !vm.busy, label = { Text("Название цели") }, singleLine = true)
                OutlinedTextField(vm.host, { vm.host = it; vm.ownership = false }, Modifier.fillMaxWidth(), enabled = !vm.busy, label = { Text("Ваш публичный IP или домен") }, singleLine = true)
                OutlinedTextField(vm.ports, { vm.ports = it; vm.ownership = false }, Modifier.fillMaxWidth(), enabled = !vm.busy, label = { Text("Порты: 80,443,8443") }, singleLine = true)
                TextButton(enabled = !vm.busy, onClick = vm::save) { Text("Сохранить цель") }
                ScanTuningControls(enabled = !vm.busy)
                Text("Точка измерения", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(vm.mobile, { vm.mobile = true }, enabled = !vm.busy, label = { Text("Мобильная сеть") })
                    FilterChip(!vm.mobile, { vm.mobile = false }, enabled = !vm.busy, label = { Text("Текущая сеть") })
                }
                Text("Wi-Fi и VPN не доказывают доступность из интернета. Для проверки извне выберите мобильные данные; при активном VPN подтверждение внешней точки не выдаётся.", style = MaterialTheme.typography.bodySmall)
                Row { Checkbox(vm.ownership, { vm.ownership = it }, enabled = !vm.busy); Text("Это мой сервис или у меня есть разрешение владельца") }
                if (vm.busy) { Text(vm.status); LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Проверено ${vm.completed} из ${vm.total}"); OutlinedButton(onClick = vm::cancel) { Text("Отменить") } }
                else Button(enabled = vm.ownership, onClick = vm::start) { Text("Проверить выбранные порты") }
                if (!vm.busy && vm.status.isNotEmpty()) Text(vm.status, style = MaterialTheme.typography.bodySmall)
                if (vm.error.isNotEmpty()) Text(vm.error, color = MaterialTheme.colorScheme.error)
            }
        }
        if (vm.targets.isNotEmpty()) item { SectionTitle("Сохранённые цели") }
        items(vm.targets, key = { it.id }) { target ->
            SoftCard {
                Text(target.name.ifBlank { target.host }, style = MaterialTheme.typography.titleSmall); Text("${target.host} · ${target.ports}")
                Row { TextButton(enabled = !vm.busy, onClick = { vm.choose(target) }) { Text("Выбрать") }; TextButton(enabled = !vm.busy, onClick = { vm.remove(target) }) { Text("Удалить цель") } }
            }
        }
        item { SectionTitle("Измерения (${vm.runs.size}, сохраняются последние 100)"); Text("Положительный результат подтверждает TCP-доступ только с указанной точки и в указанное время. Таймаут не доказывает, что устройство выключено.", style = MaterialTheme.typography.bodySmall) }
        if (vm.runs.isNotEmpty()) item { TextButton(enabled = !vm.busy, onClick = { clear = true }) { Text("Очистить измерения") } }
        items(vm.runs, key = { it.optString("id") }) { run -> Measurement(run) }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Очистить историю измерений?") }, text = { Text("Сохранённые цели останутся.") }, confirmButton = { TextButton(onClick = { clear = false; vm.clearHistory() }) { Text("Очистить") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("Отмена") } })
}
@Composable
private fun Measurement(run: JSONObject) {
    var limit by remember(run.optString("id")) { mutableIntStateOf(8) }
    val results = run.optJSONArray("results")
    val confirmed = (0 until (results?.length() ?: 0)).any { ExposurePoint.confirmed(run, results!!.getJSONObject(it)) }
    SoftCard {
        Text("${run.optString("host")} · ${run.optString("ports")}", style = MaterialTheme.typography.titleSmall)
        Text(SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale.forLanguageTag("ru")).format(Date(run.optLong("time"))), style = MaterialTheme.typography.labelSmall)
        Text(run.optString("source"), style = MaterialTheme.typography.bodySmall)
        if (!confirmed) Text("Доступность из интернета этим измерением не подтверждена", style = MaterialTheme.typography.bodySmall)
        if (run.optBoolean("cancelled")) Text("Частичная проверка: остановлена")
        if (run.optString("error").isNotEmpty()) Text(run.optString("error"), color = MaterialTheme.colorScheme.error)
        val rows = run.optJSONArray("results")
        val count = rows?.length() ?: 0
        val shown = minOf(count, limit)
        for (i in 0 until shown) {
            val row = rows!!.getJSONObject(i)
            val text = when (row.optString("state")) {
                "open" -> if (ExposurePoint.confirmed(run, row)) "TCP доступен из мобильной сети" else "TCP доступен с выбранной точки"
                "refused" -> "Соединение отклонено"
                "timeout" -> "Таймаут: ответ не получен"
                "unresolved" -> "Ошибка разрешения адреса"
                else -> "Ошибка сетевой проверки"
            }
            Text("${row.optString("address")}:${row.optInt("port")} — $text · ${row.optLong("latencyMs")} мс", style = MaterialTheme.typography.bodySmall)
            if (row.optString("state") == "open" && !ExposureStore.publicAddress(row.optString("address"))) Text("Частный или служебный адрес: публичная доступность не подтверждена", style = MaterialTheme.typography.labelSmall)
        }
        if (shown < count) TextButton(onClick = { limit += 32 }) { Text("Показать ещё · $shown из $count") }
        if (shown > 8) TextButton(onClick = { limit = 8 }) { Text("Свернуть") }
    }
}
