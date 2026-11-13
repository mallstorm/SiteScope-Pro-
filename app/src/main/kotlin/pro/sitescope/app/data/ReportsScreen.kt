@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package pro.sitescope.app.data

import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import pro.sitescope.app.ReportExporter
import pro.sitescope.app.ui.*
import java.io.File
import java.util.UUID

private data class PreparedReport(val file: File, val format: String, val result: ReportExporter.Result)
private val sectionNames = mapOf("devices" to "Карточки", "services" to "Сервисы", "changes" to "Изменения", "exposure" to "Доступ извне", "routers" to "Роутеры API", "router_links" to "Связи DHCP")
private val fieldNames = mapOf("section" to "Раздел", "time" to "Время", "source" to "Источник", "device" to "Название", "address" to "Адрес", "port" to "Порт", "protocol" to "Протокол", "manufacturer" to "Производитель", "model" to "Модель", "confidence" to "Уверенность", "field" to "Тип сведений", "before" to "Было", "after" to "Стало", "status" to "Статус", "point" to "Точка проверки", "latency_ms" to "Задержка, мс", "external_tcp_confirmed" to "Подтверждение TCP извне", "run_state" to "Состояние опроса", "mac" to "MAC", "software" to "Версия ПО", "uptime" to "Время работы", "interface_type" to "Тип интерфейса", "card_id" to "ID карточки", "observed_at" to "Время DHCP-снимка", "snapshot_id" to "ID снимка", "router_address" to "Адрес роутера")

@Composable
internal fun ReportsScreen() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var sections by remember { mutableStateOf(ReportExporter.SECTIONS.toSet()) }
    var fields by remember { mutableStateOf(ReportExporter.FIELDS.filter { it != "mac" }.toSet()) }
    var format by remember { mutableStateOf("json") }
    var limit by remember { mutableIntStateOf(10000) }
    var prepared by remember { mutableStateOf<PreparedReport?>(null) }
    var busy by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var generation by remember { mutableLongStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    var signal by remember { mutableStateOf<CancellationSignal?>(null) }
    fun invalidate() { generation++; signal?.cancel(); job?.cancel(); job = null; busy = false; prepared?.file?.delete(); prepared = null; status = "" }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP && busy) { invalidate(); status = "Формирование отменено" } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); signal?.cancel(); job?.cancel(); prepared?.file?.delete() }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(when (format) { "csv" -> "text/csv"; "html" -> "text/html"; else -> "application/json" })) { uri ->
        picker = false
        val artifact = prepared
        if (uri != null && artifact != null) {
            saving = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri, "wt")?.use { output -> artifact.file.inputStream().use { it.copyTo(output) } } ?: error("output") }
                    status = "Отчёт сохранён · ${artifact.result.rows} строк"
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { status = "Не удалось записать документ. В выбранном месте мог остаться неполный файл; повторите сохранение." }
                finally { saving = false }
            }
        }
    }
    fun generate() {
        invalidate(); val stamp = generation
        val options = ReportExporter.Options(sections, fields, format, limit)
        val cancellation = CancellationSignal(); signal = cancellation; busy = true; status = "Формирование отчёта…"
        job = scope.launch {
            var file: File? = null
            try {
                val result = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "network-reports").apply { mkdirs() }
                    // Recover temporary files left by process death, without touching other caches.
                    dir.listFiles()?.filter { it.isFile && it.lastModified() < System.currentTimeMillis() - 86400000L }?.forEach { it.delete() }
                    file = File(dir, UUID.randomUUID().toString() + "." + options.format)
                    file!!.outputStream().use { ReportExporter.write(context, it, options, cancellation) }
                }
                ensureActive()
                if (generation == stamp) { prepared = PreparedReport(file!!, options.format, result); file = null; status = "Готово: ${result.rows} строк; ограничено: ${if (result.truncated) "да" else "нет"}; сокращено значений: ${result.shortenedValues}" }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == stamp) status = "Не удалось сформировать отчёт" }
            finally { file?.delete(); if (generation == stamp) busy = false }
        }
    }
    val locked = busy || saving || picker
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SoftCard {
            Text("Отчёт по сохранённым данным", style = MaterialTheme.typography.titleMedium)
            Text("CSV для таблиц, JSON для обработки, HTML для просмотра. Каждый результат содержит время и источник. Пароли, токены, баннеры и сырые ответы API не экспортируются.", style = MaterialTheme.typography.bodySmall)
            Text("Разделы", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ReportExporter.SECTIONS.forEach { s -> FilterChip(s in sections, { invalidate(); sections = if (s in sections) sections - s else sections + s }, enabled = !locked, label = { Text(sectionNames[s] ?: s) }) } }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("csv", "json", "html").forEach { f -> FilterChip(format == f, { invalidate(); format = f }, enabled = !locked, label = { Text(f.uppercase()) }) } }
            Text("Число строк", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(1000, 10000, 50000, 0).forEach { n -> FilterChip(limit == n, { invalidate(); limit = n }, enabled = !locked, label = { Text(if (n == 0) "Все" else n.toString()) }) } }
            Text("Поля (раздел, время и источник обязательны)", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { ReportExporter.FIELDS.forEach { f -> FilterChip(f in fields, { invalidate(); fields = if (f in fields) fields - f else fields + f }, enabled = !locked && f !in setOf("section", "time", "source"), label = { Text(fieldNames[f] ?: f) }) } }
            Button(enabled = !locked && sections.isNotEmpty(), onClick = ::generate) { Text("Сформировать") }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); OutlinedButton(onClick = { invalidate(); status = "Формирование отменено" }) { Text("Отменить") } }
            if (saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.isNotEmpty()) Text(status)
            prepared?.let { report ->
                Text("Размер: ${report.file.length() / 1024} КиБ", style = MaterialTheme.typography.bodySmall)
                Button(enabled = !locked, onClick = { picker = true; launcher.launch("SiteScopePro-report-${report.result.startedAt}.${report.format}") }) { Text("Сохранить файл…") }
            }
            Text("Время в файле — Unix milliseconds UTC. Сервис мог изменить состояние после измерения. «Все» снимает лимит строк, но значения длиннее 4096 символов сокращаются с отметкой. Отчёт формируется последовательно и не заменяет резервную копию.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
