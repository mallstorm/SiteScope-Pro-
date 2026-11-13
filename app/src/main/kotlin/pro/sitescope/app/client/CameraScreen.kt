@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package pro.sitescope.app.client

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import pro.sitescope.app.ui.*

private suspend fun <T> request(client: CameraClient, action: () -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { Thread({ client.close() }, "Camera-cancel").apply { isDaemon = true }.start() }
    Thread({
        try { continuation.resumeWith(Result.success(action())) }
        catch (e: Exception) { continuation.resumeWith(Result.failure(e)) }
        finally { client.close() }
    }, "Camera-ONVIF").apply { isDaemon = true }.start()
}

@Composable
fun OnvifScreen(ep: ServiceEndpoint) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var base by remember(ep) { mutableStateOf(CameraClient.defaultUrl(ep.host, ep.port, ep.location)) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Устройство обнаружено по адресу; доступ ONVIF и видео ещё не проверены.") }
    var error by remember { mutableStateOf("") }
    var device by remember { mutableStateOf<CameraClient.Device?>(null) }
    var credentials by remember { mutableStateOf("" to "") }
    var generation by remember { mutableLongStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    fun cancel() { generation++; job?.cancel(); job = null; busy = false; status = "Запрос отменён" }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP && busy) cancel() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); job?.cancel() }
    }
    fun run(action: suspend (CameraClient, CameraClient.Progress) -> Unit) {
        if (busy) return
        busy = true; error = ""; val version = ++generation
        val network = CameraClient.selectNetwork(context, ep)
        if (network == null) { busy = false; error = "Нет доступной сети для подключения к камере"; return }
        val client = CameraClient(network)
        val progress = CameraClient.Progress { label -> scope.launch { if (generation == version) status = label } }
        job = scope.launch {
            try { action(client, progress) }
            catch (e: CancellationException) { throw e }
            catch (e: CameraClient.Failure) { if (generation == version) { error = e.message ?: "Ошибка ONVIF"; toast(context, error); status = "ONVIF: запрос не завершён" } }
            catch (e: Exception) { if (generation == version) { error = "Не удалось выполнить запрос камеры"; toast(context, error); status = "ONVIF: запрос не завершён" } }
            finally { if (generation == version) busy = false }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { LoginCard(ep, "Получить сведения и профили", busy, error, userLabel = "Пользователь камеры (если нужен)", extra = {
            OutlinedTextField(base, { base = it }, Modifier.fillMaxWidth().padding(top = 8.dp), enabled = !busy,
                label = { Text("Адрес ONVIF device service") }, singleLine = true)
        }) { user, password ->
            val address = base.trim(); val login = user to password
            device = null
            run { client, progress ->
                val result = request(client) { client.inspect(CameraClient.deviceAddress(ep.host, address), login.first, login.second, progress) }
                credentials = login; device = result
                status = if (result.profiles.isEmpty()) "ONVIF ответил. Доступных видеопрофилей нет." else "ONVIF ответил · профилей: ${result.profiles.size}. Видеопоток ещё не проверен."
            }
        }
        }
        item { Text(status, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 10.dp)) }
        if (busy) item { OutlinedButton(onClick = ::cancel) { Text("Отменить запрос") } }
        device?.let { d ->
            item { SoftCard(padding = 12.dp) {
                d.info.forEach { (name, value) -> InfoRow(name, value) }
                DeviceIdentityDetails(d.identity)
                Text(if (d.mediaNamespace == CameraClient.MEDIA2) "ONVIF Media2" else "ONVIF Media1", style = MaterialTheme.typography.labelSmall)
            }
            }
            item { SectionTitle("Видеопрофили: ${d.profiles.size}") }
            items(d.profiles, key = { it.token }) { profile ->
                SoftCard(padding = 12.dp, modifier = Modifier.padding(bottom = 8.dp)) {
                    Text(profile.name, style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(enabled = !busy, onClick = {
                            val login = credentials
                            run { client, progress ->
                                val uri = request(client) { client.stream(d, profile, login.first, login.second, progress) }
                                copyText(context, uri, "Адрес без добавленного логина и пароля скопирован")
                                status = "Адрес RTSP получен. Воспроизведение ещё не проверено."
                            }
                        }) { Text("Адрес") }
                        Button(enabled = !busy && activity != null, onClick = {
                            val login = credentials
                            run { client, progress ->
                                val uri = request(client) { client.stream(d, profile, login.first, login.second, progress) }
                                status = "Адрес получен. Подключение к видео проверяет плеер."
                                CameraPlayback.open(activity!!, d.base, uri, login.first, login.second, profile.name, d.network)
                            }
                        }) { Text("Смотреть") }
                    }
                }
            }
        }
    }
}
