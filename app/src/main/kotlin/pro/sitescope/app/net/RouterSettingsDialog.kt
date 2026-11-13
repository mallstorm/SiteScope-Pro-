package pro.sitescope.app.net

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
import pro.sitescope.app.*
import org.json.JSONObject

private suspend fun <T> settingsRequest(client: RouterApiClient, action: () -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { Thread({ client.close() }, "Settings-cancel").apply { isDaemon = true }.start() }
    Thread({ try { continuation.resumeWith(Result.success(action())) } catch (e: Exception) { continuation.resumeWith(Result.failure(e)) } finally { client.close() } }, "Router-settings").apply { isDaemon = true }.start()
}

@Composable
internal fun RouterSettingsDialog(config: RouterStore.Config, user: String, password: String, close: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val coroutine = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val network = remember { CameraClient.selectNetwork(context, ServiceEndpoint().also { it.host = java.net.URI(config.base).host.removeSurrounding("[", "]") }) }
    val scope = remember { NetworkScope.capture(context, network) }
    val guard = remember {
        RouterSettings.Guard {
            if (network == null || !scope.reliable || context.getSystemService(android.net.ConnectivityManager::class.java)?.getNetworkCapabilities(network) == null || NetworkScope.capture(context, network).id != scope.id) throw IllegalArgumentException("Сеть изменилась или не определена: откройте настройки заново")
        }
    }
    var name by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<RouterSettings.Plan?>(null) }
    var restoring by remember { mutableStateOf<JSONObject?>(null) }
    var confirmed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var revision by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableLongStateOf(0) }
    var clear by remember { mutableStateOf(false) }
    var journalLimit by remember { mutableIntStateOf(20) }
    fun cancel() { generation++; job?.cancel(); job = null; busy = false; plan = null; confirmed = false; revision++; status = "Операция остановлена. Если запись началась, результат неизвестен: проверьте журнал и роутер. В OpenWrt неподтверждённое применение откатывается rpcd через 60 с." }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { if (busy) cancel() else { plan = null; confirmed = false } } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); job?.cancel() }
    }
    LaunchedEffect(revision) { try { entries = withContext(Dispatchers.IO) { RouterChangeJournal.list(context) } } catch (e: Exception) { status = "Не удалось прочитать журнал. Запись не начинайте." } }
    fun preview(entry: JSONObject? = null) {
        if (busy) return
        plan = null; confirmed = false; restoring = entry
        val target = entry?.getString("before") ?: name
        val stamp = ++generation; busy = true; status = "Чтение текущего имени…"
        job = coroutine.launch {
            try {
                val client = RouterApiClient(network)
                val p = settingsRequest(client) { RouterSettings.preview(client, config, user, password, scope.id, target, entry != null, guard) }
                if (entry != null && p.before != entry.getString("after")) throw IllegalArgumentException("Имя уже изменено другим действием. Автоматический возврат заблокирован")
                if (generation == stamp) { plan = p; status = "Предпросмотр готов. Для применения нужны права записи владельца." }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == stamp) status = if (e is IllegalArgumentException || e is RouterApiClient.Failure) e.message ?: "Ошибка API" else "Не удалось подготовить изменение. Запись не выполнялась." }
            finally { if (generation == stamp) busy = false }
        }
    }
    fun apply() {
        val p = plan ?: return
        if (busy || !confirmed) return
        val stamp = ++generation; busy = true; confirmed = false; plan = null; status = "Проверка, запись и подтверждение…"
        job = coroutine.launch {
            try {
                val client = RouterApiClient(network)
                settingsRequest(client) { RouterSettings.apply(client, config, user, password, scope.id, p, guard, RouterChangeJournal.writer(context, scope.label)) }
                if (generation == stamp) { status = "Имя сохранено и проверено через API. Для возврата выберите запись журнала."; revision++ }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == stamp) { status = (if (e is IllegalArgumentException || e is RouterApiClient.Failure) e.message ?: "Ошибка API" else "Операция не подтверждена") + ". Проверьте журнал: после начала записи результат может быть неизвестен. OpenWrt: ожидайте до 60 с для неподтверждённого отката."; revision++ } }
            finally { if (generation == stamp) busy = false }
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) close() }, title = { Text("Имя моего роутера") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${config.kind} · ${config.base}\n${scope.label}")
            Text("Меняется только имя RouterOS identity или OpenWrt system.hostname. Нужны права записи. У OpenWrt проверяется сохранённая настройка UCI; отображаемое системой имя может обновиться позже.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(name, { name = it; plan = null; confirmed = false; restoring = null }, enabled = !busy, label = { Text("Новое имя: home-router") }, singleLine = true)
            OutlinedButton(enabled = !busy, onClick = { preview() }) { Text("Прочитать и показать изменение") }
            plan?.let { p ->
                Text("${p.endpoint}\nБыло: ${p.before}\nСтанет: ${p.after}", style = MaterialTheme.typography.titleSmall)
                Text(if (restoring == null) "Прежнее значение будет сохранено в локальном журнале до отправки записи." else "Это обратная операция. Она не перезаписывает чужое новое имя: перед отправкой значение проверяется заново.", style = MaterialTheme.typography.bodySmall)
                Row { Checkbox(confirmed, { confirmed = it }, enabled = !busy); Text("Я владелец или уполномочен и подтверждаю показанное изменение") }
                Button(enabled = confirmed && !busy, onClick = ::apply) { Text("Применить показанное изменение") }
            }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); TextButton(onClick = ::cancel) { Text("Остановить ожидание") } }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
            Text("Журнал на этом устройстве (${entries.size}/100)", style = MaterialTheme.typography.titleSmall)
            Text("Неподтверждённая запись могла выполниться. После потери связи проверьте имя; автоматических повторов и восстановления при запуске приложения нет. Не редактируйте роутер одновременно из других клиентов.", style = MaterialTheme.typography.bodySmall)
            entries.take(journalLimit).forEach { entry ->
                val matching = entry.optString("endpoint") == config.base && entry.optString("kind") == config.kind && entry.optString("scope") == scope.id
                HorizontalDivider()
                Text("${entry.optString("kind")} · ${entry.optString("endpoint")} · ${entry.optString("source")}\n${routerTime(entry.optLong("time"))} · ${if (entry.optString("state") == "applied") "Проверено после записи" else "Результат неизвестен"}\n${entry.optString("before")} → ${entry.optString("after")}", style = MaterialTheme.typography.bodySmall)
                TextButton(enabled = !busy && matching, onClick = { preview(entry) }) { Text("Подготовить возврат прежнего имени") }
            }
            if (journalLimit < entries.size) TextButton(onClick = { journalLimit += 20 }) { Text("Ещё записи журнала") }
            Text("Возврат доступен для выбранного API и той же области сети. После смены сеанса/сети прежнее значение остаётся здесь для ручной проверки.", style = MaterialTheme.typography.bodySmall)
            if (entries.isNotEmpty()) TextButton(enabled = !busy, onClick = { clear = true }) { Text("Очистить весь журнал…") }
            Text("Журнал не входит в резервную копию и отчёты. Очистка данных/удаление приложения удалит прежние значения. RouterOS не предоставляет автоматический откат при обрыве связи; восстановление — отдельное подтверждённое действие.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = close) { Text("Закрыть") } })
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Удалить журнал всех роутеров?") }, text = { Text("Прежние значения для восстановления будут удалены. Настройки роутеров останутся без изменений.") }, confirmButton = { TextButton(onClick = { try { RouterChangeJournal.clear(context); revision++; plan = null; confirmed = false } catch (e: Exception) { status = "Не удалось очистить журнал" }; clear = false }) { Text("Удалить журнал") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("Отмена") } })
}
