package pro.sitescope.app.net

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import org.json.JSONObject
import pro.sitescope.app.*
import pro.sitescope.app.ui.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private suspend fun readRouter(client: RouterApiClient, action: () -> JSONObject): JSONObject = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { Thread({ client.close() }, "Router-cancel").apply { isDaemon = true }.start() }
    Thread({
        try { continuation.resumeWith(Result.success(action())) }
        catch (e: Exception) { continuation.resumeWith(Result.failure(e)) }
        finally { client.close() }
    }, "Router-read").apply { isDaemon = true }.start()
}

@Composable
internal fun RouterScreen() {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var base by remember { mutableStateOf("https://192.168.1.1") }
    var kind by remember { mutableStateOf("routeros") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var allowHttp by remember { mutableStateOf(false) }
    var keep by remember { mutableStateOf(false) }
    var owner by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var configs by remember { mutableStateOf<List<RouterStore.Config>>(emptyList()) }
    var snapshots by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var version by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    var generation by remember { mutableLongStateOf(0) }
    var clear by remember { mutableStateOf(false) }
    fun cancel() { generation++; job?.cancel(); job = null; busy = false; status = "Опрос отменён" }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP && busy) cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); job?.cancel() }
    }
    LaunchedEffect(version) {
        try { val data = withContext(Dispatchers.IO) { RouterStore.configs(context) to RouterStore.snapshots(context) }; configs = data.first; snapshots = data.second }
        catch (e: Exception) { error = "Не удалось прочитать сохранённые сведения" }
    }
    fun config(): RouterStore.Config = RouterStore.Config().also { it.id = id; it.name = name.trim(); it.base = RouterStore.address(base, allowHttp).toASCIIString(); it.kind = kind; it.allowHttp = allowHttp }
    fun save() {
        try {
            val c = config(); RouterStore.save(context, c); id = c.id
            RouterStore.saveCredentials(context, c.id, user, password, keep)
            version++; error = ""; status = if (keep) "Подключение сохранено; учётные данные зашифрованы Android Keystore" else "Подключение сохранено без учётных данных"
        } catch (e: IllegalArgumentException) { error = e.message ?: "Проверьте адрес" }
        catch (e: Exception) { error = "Не удалось сохранить подключение или зашифровать учётные данные. Открытого хранения нет."; version++ }
    }
    fun read() {
        if (busy || !owner) return
        try {
            val c = config(); val login = user to password
            val endpoint = ServiceEndpoint().also { it.host = java.net.URI(c.base).host.removeSurrounding("[", "]") }
            val network = CameraClient.selectNetwork(context, endpoint)
            if (network == null) { error = "Нет доступной сети для подключения к роутеру"; return }
            val source = "${if (kind == "routeros") "RouterOS REST" else "OpenWrt ubus"} · ${NetworkScope.capture(context, network).label}"
            val client = RouterApiClient(network); val stamp = ++generation
            busy = true; error = ""; status = "Чтение API роутера…"
            job = scope.launch {
                try {
                    val result = readRouter(client) { client.read(c, login.first, login.second, source) }
                    ensureActive()
                    withContext(Dispatchers.IO) { RouterStore.snapshot(context, result) }
                    if (generation == stamp) { version++; status = if (result.optBoolean("partial")) "Получен частичный снимок: смотрите пояснения" else "Сведения прочитаны. Конфигурация не изменялась." }
                } catch (e: CancellationException) { throw e }
                catch (e: RouterApiClient.Failure) { if (generation == stamp) error = e.message ?: "Ошибка API" }
                catch (e: Exception) { if (generation == stamp) error = "Не удалось получить или сохранить сведения роутера" }
                finally { if (generation == stamp) busy = false }
            }
        } catch (e: Exception) { error = if (e is IllegalArgumentException) e.message ?: "Проверьте адрес" else "Проверьте адрес роутера" }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { SoftCard {
            Text("API моего роутера", style = MaterialTheme.typography.titleMedium)
            Text("RouterOS 7 REST и OpenWrt ubus: система, интерфейсы и DHCP. Нужен включённый API и пользователь с правами чтения. Для OpenWrt также нужен rpcd/uhttpd-mod-ubus.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(kind == "routeros", { kind = "routeros"; id = ""; owner = false }, enabled = !busy, label = { Text("RouterOS 7") })
                FilterChip(kind == "openwrt", { kind = "openwrt"; id = ""; owner = false }, enabled = !busy, label = { Text("OpenWrt") })
            }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Название") }, singleLine = true)
            OutlinedTextField(base, { base = it; owner = false; id = "" }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("https://IP:порт или домен") }, singleLine = true)
            Row { Checkbox(allowHttp, { allowHttp = it; owner = false }, enabled = !busy); Text("Разрешить HTTP для локального числового IP") }
            if (allowHttp) Text("HTTP передаёт данные входа без шифрования. Используйте только в доверенной локальной сети.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Пользователь владельца") }, singleLine = true)
            OutlinedTextField(password, { password = it }, Modifier.fillMaxWidth(), enabled = !busy, label = { Text("Пароль") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            Row { Checkbox(keep, { keep = it }, enabled = !busy); Text("Запомнить данные входа на этом устройстве") }
            Row { Checkbox(owner, { owner = it }, enabled = !busy); Text("Это мой роутер или у меня есть разрешение владельца") }
            Row { TextButton(enabled = !busy && owner, onClick = ::save) { Text("Сохранить") }; Button(enabled = !busy && owner, onClick = ::read) { Text("Прочитать") } }
            if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); OutlinedButton(onClick = ::cancel) { Text("Отменить") } }
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
            if (error.isNotEmpty()) Text(error, color = MaterialTheme.colorScheme.error)
        } }
        items(configs, key = { it.id }) { c -> SoftCard {
            Text("${c.name.ifBlank { c.kind }} · ${c.base}")
            Row {
                TextButton(enabled = !busy, onClick = { id = c.id; name = c.name; kind = c.kind; base = c.base; allowHttp = c.allowHttp; owner = false; val login = RouterStore.credentials(context, c.id); user = login[0]; password = login[1]; keep = user.isNotEmpty(); error = "" }) { Text("Выбрать") }
                TextButton(enabled = !busy, onClick = { RouterStore.remove(context, c.id); if (id == c.id) { id = ""; user = ""; password = ""; keep = false }; version++ }) { Text("Удалить") }
            }
        } }
        item { SectionTitle("Снимки API (${snapshots.size}, последние 20)"); Text("DHCP-аренда и включённый интерфейс не доказывают доступность устройства сейчас. Снимки доступны в «Данные → Отчёты».", style = MaterialTheme.typography.bodySmall) }
        if (snapshots.isNotEmpty()) item { TextButton(enabled = !busy, onClick = { clear = true }) { Text("Очистить снимки") } }
        items(snapshots, key = { it.optString("id") }) { RouterSnapshot(it) }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Удалить снимки API?") }, text = { Text("Сохранённые подключения останутся.") }, confirmButton = { TextButton(onClick = { RouterStore.clearSnapshots(context); version++; clear = false }) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("Отмена") } })
}
@Composable
private fun RouterSnapshot(snapshot: JSONObject) {
    var limit by remember(snapshot.optString("id")) { mutableIntStateOf(8) }
    SoftCard {
        Text(snapshot.optString("name").ifBlank { snapshot.optString("host") }, style = MaterialTheme.typography.titleSmall)
        Text(SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale.forLanguageTag("ru")).format(Date(snapshot.optLong("time"))))
        Text(snapshot.optString("source"), style = MaterialTheme.typography.bodySmall)
        val warnings = snapshot.optJSONArray("warnings")
        for (i in 0 until (warnings?.length() ?: 0)) Text(warnings!!.optString(i), color = MaterialTheme.colorScheme.error)
        val rows = snapshot.getJSONArray("rows")
        for (i in 0 until minOf(rows.length(), limit)) {
            val r = rows.getJSONObject(i)
            Text(listOf("kind", "name", "model", "manufacturer", "address", "mac", "software", "uptime", "interface_type", "status").map { r.optString(it) }.filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
        }
        if (limit < rows.length()) TextButton(onClick = { limit += 32 }) { Text("Ещё · ${minOf(limit, rows.length())} из ${rows.length()}") }
    }
}
