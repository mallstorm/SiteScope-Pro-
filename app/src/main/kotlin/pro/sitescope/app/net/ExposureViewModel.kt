package pro.sitescope.app.net

import android.app.Application
import android.net.*
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import pro.sitescope.app.*
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal data class ProbeOrigin(val label: String, val external: Boolean, val transport: String = "none")
internal object ExposurePoint {
    fun describe(selected: NetworkCapabilities?, active: NetworkCapabilities?): ProbeOrigin {
        if (selected == null) return ProbeOrigin("Сеть недоступна", false)
        val vpn = selected.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || active?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val rawCell = selected.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val cell = rawCell && !selected.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !selected.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val label = when {
            selected.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN · точка выхода не подтверждена"
            cell -> "Мобильная сеть"
            rawCell -> "Смешанная сеть · точка не подтверждена"
            selected.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi · локальная точка"
            selected.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet · локальная точка"
            else -> "Другая сеть · точка не подтверждена"
        } + if (vpn && !selected.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) " · на телефоне активен VPN" else ""
        return ProbeOrigin(label, cell && !vpn && active != null, when { selected.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"; cell -> "cellular"; rawCell -> "other"; selected.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"; selected.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"; else -> "other" })
    }
    fun confirmed(run: JSONObject, row: JSONObject): Boolean = ExposureStore.externalConfirmed(run, row)
}

class ExposureViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()
    private val cm get() = checkNotNull(context.getSystemService(ConnectivityManager::class.java)) { "Служба сети недоступна" }
    var targets by mutableStateOf<List<ExposureStore.Target>>(emptyList()); private set
    var runs by mutableStateOf<List<JSONObject>>(emptyList()); private set
    var host by mutableStateOf("")
    var name by mutableStateOf("")
    var ports by mutableStateOf("80,443")
    var mobile by mutableStateOf(true)
    var ownership by mutableStateOf(false)
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf(""); private set
    var error by mutableStateOf(""); private set
    var completed by mutableIntStateOf(0); private set
    var total by mutableIntStateOf(0); private set
    private var job: Job? = null
    private var generation = 0L
    init { reload() }
    fun reload() { viewModelScope.launch { try { val data = withContext(Dispatchers.IO) { ExposureStore.targets(context) to ExposureStore.runs(context) }; targets = data.first; runs = data.second } catch (e: Exception) { error = "Не удалось загрузить цели и измерения" } } }
    fun choose(t: ExposureStore.Target) { host = t.host; name = t.name; ports = t.ports; ownership = false }
    fun save() { val rawHost = host; val rawName = name; val rawPorts = ports; val known = targets
        viewModelScope.launch { try { withContext(Dispatchers.IO) {
        val value = ExposureStore.Target().apply { this.host = ExposureStore.host(rawHost); this.name = rawName.trim(); this.ports = rawPorts }
        value.id = known.firstOrNull { it.host == value.host && it.ports == value.ports }?.id ?: ""
        ExposureStore.save(context, value)
    }; error = ""; reload() } catch (e: Exception) { error = "Проверьте один IP/домен и список не более 64 портов" } } }
    fun remove(t: ExposureStore.Target) { viewModelScope.launch { withContext(Dispatchers.IO) { ExposureStore.remove(context, t.id) }; reload() } }
    fun clearHistory() { viewModelScope.launch { withContext(Dispatchers.IO) { ExposureStore.clearRuns(context) }; reload() } }
    fun cancel() { if (busy) status = "Отменяем проверку…"; job?.cancel() }

    private class Lease(val network: Network, val release: () -> Unit) {
        val lost = AtomicBoolean(); @Volatile var onLost: (() -> Unit)? = null
        fun close() = release()
    }
    private suspend fun cellular(): Lease = withTimeout(20000) {
        suspendCancellableCoroutine { cont ->
            val manager = cm; val lock = Any(); var registered = false; var closed = false; val delivered = AtomicBoolean(); var lease: Lease? = null
            lateinit var callback: ConnectivityManager.NetworkCallback
            fun release() = synchronized(lock) { closed = true; if (registered) { registered = false; try { manager.unregisterNetworkCallback(callback) } catch (_: Exception) { } } }
            callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { if (delivered.compareAndSet(false, true)) { val value = Lease(network, ::release); lease = value; cont.resume(value) } }
                override fun onLost(network: Network) { lease?.takeIf { it.network == network }?.let { it.lost.set(true); it.onLost?.invoke() } }
                override fun onUnavailable() { if (delivered.compareAndSet(false, true)) { cont.resumeWithException(IllegalStateException("Мобильная сеть недоступна. Включите мобильные данные.")); release() } }
            }
            cont.invokeOnCancellation { release() }
            synchronized(lock) {
                if (!closed && cont.isActive) try {
                    registered = true
                    manager.requestNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback, 19000)
                } catch (e: Exception) { release(); if (delivered.compareAndSet(false, true)) cont.resumeWithException(IllegalStateException("Не удалось запросить мобильную сеть")) }
            }
        }
    }
    private fun origin(network: Network) = ExposurePoint.describe(cm.getNetworkCapabilities(network), cm.activeNetwork?.let { cm.getNetworkCapabilities(it) })
    private suspend fun probe(network: Network, hosts: List<String>, portList: List<Int>, rows: ConcurrentLinkedQueue<Models.PortResult>, lease: Lease, version: Long, config: ScannerEngine.Config): Boolean = suspendCancellableCoroutine { cont ->
        config.boundNetwork = network
        val task = ScannerEngine.start(hosts, portList, config, object : ScannerEngine.Listener {
            override fun onStarted(hosts: Int, ports: Int, probes: Int) { viewModelScope.launch { if (generation == version) total = probes } }
            override fun onChecked(result: Models.PortResult) { rows.add(result) }
            override fun onOpen(result: Models.PortResult) { }
            override fun onProgress(done: Int, probes: Int, openCount: Int, currentTarget: String?) { viewModelScope.launch { if (generation == version) { completed = done; total = probes } } }
            override fun onComplete(results: MutableList<Models.PortResult>?, cancelled: Boolean, durationMs: Long, message: String?) {
                if (!message.isNullOrEmpty()) cont.resumeWithException(IllegalStateException("Не удалось завершить проверку")) else cont.resume(cancelled)
            }
        })
        lease.onLost = { task.cancel() }; if (lease.lost.get()) task.cancel()
        cont.invokeOnCancellation { task.cancel() }
    }
    fun start() {
        if (busy) return
        if (!ownership) { error = "Подтвердите, что проверяете собственный сервис или имеете разрешение владельца"; return }
        val address: String; val selectedPorts: List<Int>
        try { address = ExposureStore.host(host); selectedPorts = ScannerEngine.parsePorts(ports, 64) }
        catch (e: Exception) { error = "Укажите один IP/домен и не более 64 портов без URL и диапазона адресов"; return }
        val config = ScannerEngine.Config().apply { tcpOnly = true; detectServices = false; timeoutMs = SettingsStore.scannerTimeoutMs(context); concurrency = SettingsStore.scannerConcurrency(context); delayMs = SettingsStore.scannerDelayMs(context) }
        val expression = ports.trim(); val useMobile = mobile; val version = ++generation
        busy = true; error = ""; completed = 0; total = 0
        job = viewModelScope.launch {
            val rows = ConcurrentLinkedQueue<Models.PortResult>(); var lease: Lease? = null; var point = ProbeOrigin("Сеть не выбрана", false); var verified = false; var cancelled = false; var failure = ""; var planned = 0
            val started = System.currentTimeMillis()
            try {
                status = if (useMobile) "Запрашиваем мобильную сеть…" else "Выбираем текущую сеть…"
                lease = if (useMobile) cellular() else Lease(cm.activeNetwork ?: throw IllegalStateException("Нет активной сети")) { }
                point = origin(lease.network)
                if (cm.getNetworkCapabilities(lease.network) == null || useMobile && cm.getNetworkCapabilities(lease.network)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) != true) throw IllegalStateException("Выбранная сеть недоступна")
                status = point.label + " · разрешаем адрес через эту сеть…"
                val hosts = withContext(Dispatchers.IO) { lease.network.getAllByName(address).map { it.hostAddress!! }.distinct() }
                if (hosts.isEmpty() || hosts.size.toLong() * selectedPorts.size > 4096) throw IllegalStateException("Слишком большое задание: уменьшите число портов")
                if (lease.lost.get()) throw IllegalStateException("Выбранная сеть потеряна")
                planned = hosts.size * selectedPorts.size; total = planned
                status = point.label + " · TCP-проверка…"
                cancelled = probe(lease.network, hosts, selectedPorts, rows, lease, version, config)
                verified = point.external && origin(lease.network).external && !lease.lost.get() && !cancelled && rows.size == planned
                status = if (cancelled) "Проверка остановлена; результаты частичные" else "Проверка завершена · ${point.label}"
            } catch (e: TimeoutCancellationException) { failure = "Мобильная сеть не появилась за 20 секунд. Проверьте мобильные данные." }
            catch (e: CancellationException) { cancelled = true; status = "Проверка отменена; результаты частичные" }
            catch (e: java.net.UnknownHostException) { failure = "Не удалось разрешить адрес через выбранную сеть" }
            catch (e: IllegalStateException) { failure = e.message ?: "Выбранная сеть недоступна" }
            catch (e: Exception) { failure = "Ошибка выбранной сети. Проверка через другую сеть не выполнялась." }
            finally {
                lease?.close()
                withContext(NonCancellable) {
                    val result = JSONObject().put("id", UUID.randomUUID().toString()).put("time", started).put("finishedAt", System.currentTimeMillis()).put("host", address).put("ports", expression)
                        .put("source", point.label).put("transport", point.transport).put("total", planned).put("completed", rows.size).put("mode", if (useMobile) "mobile" else "current").put("externalPoint", verified).put("cancelled", cancelled).put("error", failure)
                    val data = JSONArray(); rows.sortedWith(compareBy({ it.ip.ifEmpty { it.target } }, { it.port })).forEach { r -> data.put(JSONObject().put("address", r.ip.ifEmpty { r.target }).put("port", r.port).put("state", r.probeStatus).put("latencyMs", r.latencyMs)) }; result.put("results", data)
                    try { withContext(Dispatchers.IO) { ExposureStore.saveRun(context, result) } }
                    catch (e: Exception) { if (generation == version) error = "Не удалось сохранить измерение" }
                    if (generation == version) { if (failure.isNotEmpty()) { error = failure; status = "Проверка не завершена" } else status = if (cancelled) "Проверка отменена; результаты частичные" else "Проверка завершена · ${point.label}"; busy = false; reload() }
                }
            }
        }
    }
}
