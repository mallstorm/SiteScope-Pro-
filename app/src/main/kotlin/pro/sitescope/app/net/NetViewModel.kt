package pro.sitescope.app.net

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.sitescope.app.ConnectionProfileStore
import pro.sitescope.app.DeviceInventoryStore
import pro.sitescope.app.EndpointProbe
import pro.sitescope.app.Models
import pro.sitescope.app.NetworkDiscoveryEngine
import pro.sitescope.app.ScanService
import pro.sitescope.app.ScannerEngine
import pro.sitescope.app.ServiceEndpoint
import pro.sitescope.app.SettingsStore
import pro.sitescope.app.ip.IpTools
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/** Готовые наборы портов для сканера — чтобы не вспоминать номера. */
class PortPreset(val title: String, val ports: String)

val PORT_PRESETS = listOf(
    PortPreset("Популярные", "21,22,23,25,53,80,110,139,143,443,445,554,1883,1900,3389,5900,8000,8080,8443"),
    PortPreset("Сайты", "80,81,443,3000,5000,8000,8008,8080,8081,8088,8443,8888,9000"),
    PortPreset("Камеры и ТВ", "80,443,554,1935,4022,8000,8080,8554,8899,34567,37777"),
    PortPreset("Удалённый доступ", "22,23,3389,5900,5901,5938,8291"),
    PortPreset("Все до 1024", "1-1024")
)

/** Протоколы, для которых в приложении есть свой клиент. */
class Proto(val id: String, val title: String, val port: Int)

val PROTOCOLS = listOf(
    Proto("SSH", "SSH — терминал", 22), Proto("SFTP", "SFTP — файлы", 22), Proto("FTP", "FTP — файлы", 21), Proto("FTPS", "FTPS — файлы", 990),
    Proto("WEBDAV", "WebDAV — файлы", 80), Proto("HTTP", "Веб-страница (HTTP)", 80), Proto("HTTPS", "Веб-страница (HTTPS)", 443),
    Proto("RDP", "RDP — рабочий стол Windows", 3389), Proto("VNC", "VNC — удалённый экран", 5900), Proto("TELNET", "Telnet — консоль оборудования", 23), Proto("RTSP", "RTSP — видеопоток", 554), Proto("ONVIF", "ONVIF — камера", 80),
    Proto("SNMP", "SNMP — оборудование", 161), Proto("MQTT", "MQTT — умный дом", 1883)
)

class WolEntry(val name: String, val mac: String, val ip: String = "")

class NetViewModel(app: Application) : AndroidViewModel(app) {
    private val context: Context get() = getApplication()
    private val main = Handler(Looper.getMainLooper())

    // ---------- Устройства рядом ----------
    var discovering by mutableStateOf(false); private set
    var discoveryStatus by mutableStateOf(""); private set
    var discoveryError by mutableStateOf(""); private set
    val live = mutableStateListOf<Models.DiscoveredDevice>()
    var latest by mutableStateOf<DeviceInventoryStore.Snapshot?>(null); private set
    var previous by mutableStateOf<DeviceInventoryStore.Snapshot?>(null); private set
    var snapshotCount by mutableStateOf(0); private set
    var aliasVersion by mutableStateOf(0); private set
    private var task: NetworkDiscoveryEngine.Task? = null

    fun loadSnapshots() {
        val list = try { DeviceInventoryStore.list(context) } catch (e: Exception) { emptyList() }
        latest = list.getOrNull(0); previous = list.getOrNull(1); snapshotCount = list.size
    }

    fun startDiscovery() {
        if (discovering) return
        discovering = true; discoveryError = ""; discoveryStatus = "Ищем устройства в вашей сети…"; live.clear()
        task = NetworkDiscoveryEngine.start(context, object : NetworkDiscoveryEngine.Listener {
            override fun onStatus(status: String?) { main.post { discoveryStatus = status ?: "" } }
            override fun onDevice(device: Models.DiscoveredDevice?) { if (device != null) main.post { live.add(device) } }
            override fun onComplete(devices: MutableList<Models.DiscoveredDevice>?, cancelled: Boolean, error: String?) {
                main.post {
                    discovering = false
                    discoveryError = error ?: ""
                    if (!devices.isNullOrEmpty()) try { DeviceInventoryStore.saveSnapshot(context, devices, "LAN Discovery") } catch (e: Exception) { }
                    live.clear(); loadSnapshots()
                    discoveryStatus = if (cancelled) "Поиск остановлен" else ""
                }
            }
        })
    }

    fun stopDiscovery() { task?.cancel() }

    fun aggregates(snapshot: DeviceInventoryStore.Snapshot?): List<DeviceInventoryStore.Aggregate> =
        try { DeviceInventoryStore.aggregate(context, snapshot) } catch (e: Exception) { emptyList() }

    fun liveAggregates(): List<DeviceInventoryStore.Aggregate> {
        val snap = DeviceInventoryStore.Snapshot(); snap.devices.addAll(live)
        return aggregates(snap)
    }

    fun rename(ip: String, alias: String) { DeviceInventoryStore.setAlias(context, ip, alias.trim()); aliasVersion++ }
    fun clearSnapshots() { DeviceInventoryStore.clear(context); loadSnapshots() }

    // ---------- Сканер ----------
    var targets by mutableStateOf("")
    var ports by mutableStateOf(PORT_PRESETS[0].ports)
    var extended by mutableStateOf(false)
    var scan by mutableStateOf(ScanService.snapshot()); private set
    private val observer = ScanService.Observer { s -> main.post { scan = s } }

    override fun onCleared() { ScanService.unregister(observer); task?.cancel(); super.onCleared() }

    /** Подсеть, к которой сейчас подключён телефон (не шире /24, чтобы проверка была быстрой). */
    fun localSubnet(): String {
        try {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return ""
            val props = cm.getLinkProperties(cm.activeNetwork ?: return "") ?: return ""
            for (la in props.linkAddresses) {
                val a = la.address
                if (a is Inet4Address && !a.isLoopbackAddress) {
                    val b = a.address
                    return "${b[0].toInt() and 255}.${b[1].toInt() and 255}.${b[2].toInt() and 255}.0/24"
                }
            }
        } catch (e: Exception) { }
        return ""
    }

    class Plan(val hosts: Int, val ports: Int, val probes: Long, val needsConfirm: Boolean)

    /** Проверяет задание так же, как прежний сканер; бросает исключение с понятным текстом. */
    fun plan(): Plan {
        val hosts = ScannerEngine.parseTargets(targets, if (extended) ScannerEngine.EXTENDED_MAX_HOSTS else ScannerEngine.STANDARD_MAX_HOSTS)
        val parsed = ScannerEngine.parsePorts(ports, if (extended) ScannerEngine.EXTENDED_MAX_PORTS else ScannerEngine.STANDARD_MAX_PORTS)
        val probes = hosts.size.toLong() * parsed.size.toLong()
        val max = (if (extended) ScannerEngine.EXTENDED_MAX_PROBES else ScannerEngine.STANDARD_MAX_PROBES).toLong()
        if (probes > max) throw IllegalArgumentException("Слишком большое задание: $probes проверок при пределе $max. Уменьшите число адресов или портов.")
        if (extended && !ScannerEngine.allTargetsPrivateOrLocal(hosts)) throw IllegalArgumentException("Расширенный режим работает только для адресов вашей локальной сети.")
        return Plan(hosts.size, parsed.size, probes, SettingsStore.confirmLargeTasks(context) && probes > 250000)
    }

    fun startScan() {
        val i = Intent(context, ScanService::class.java).setAction(ScanService.ACTION_START)
            .putExtra(ScanService.EXTRA_TARGETS, targets.trim()).putExtra(ScanService.EXTRA_PORTS, ports.trim())
            .putExtra(ScanService.EXTRA_TIMEOUT, SettingsStore.scannerTimeoutMs(context))
            .putExtra(ScanService.EXTRA_CONCURRENCY, SettingsStore.scannerConcurrency(context))
            .putExtra(ScanService.EXTRA_DETECT, SettingsStore.scannerDetectServices(context))
            .putExtra(ScanService.EXTRA_EXTENDED_PRIVATE, extended)
        context.startForegroundService(i)
    }

    fun scanCommand(action: String) { context.startService(Intent(context, ScanService::class.java).setAction(action)) }

    // ---------- Подключения ----------
    var profiles by mutableStateOf<List<ConnectionProfileStore.Profile>>(emptyList()); private set
    val checking = mutableStateListOf<String>()

    fun loadProfiles() { profiles = try { ConnectionProfileStore.list(context) } catch (e: Exception) { emptyList() } }

    fun addProfile(proto: Proto, host: String, port: Int, name: String) {
        val e = ServiceEndpoint()
        e.protocol = proto.id; e.host = host.trim(); e.port = port; e.service = proto.id
        e.displayName = name.trim(); e.resourceType = proto.title
        e.uri = try { ServiceEndpoint.buildUri(proto.id, e.host, port, "") ?: "" } catch (ex: Exception) { "" }
        ConnectionProfileStore.save(context, e); loadProfiles()
    }

    fun updateProfile(p: ConnectionProfileStore.Profile, name: String, notes: String, tags: String, pinned: Boolean) {
        ConnectionProfileStore.updateMetadata(context, p.id, name.trim(), notes.trim(), tags.trim(), pinned); loadProfiles()
    }

    fun removeProfile(p: ConnectionProfileStore.Profile) { ConnectionProfileStore.remove(context, p.id); NetMonitor.reschedule(context); loadProfiles() }

    fun toggleMonitored(p: ConnectionProfileStore.Profile) {
        ConnectionProfileStore.setMonitored(context, p.id, !p.monitored)
        NetMonitor.reschedule(context)
        loadProfiles()
    }

    fun check(p: ConnectionProfileStore.Profile) {
        if (checking.contains(p.id)) return
        checking.add(p.id)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try { ConnectionProfileStore.updateHealth(context, p.id, EndpointProbe.probe(p.endpoint(), 2500)) } catch (e: Exception) { }
            }
            checking.remove(p.id); loadProfiles()
        }
    }

    fun checkAll() { profiles.forEach { check(it) } }

    // ---------- Скорость интернета ----------
    var speedRunning by mutableStateOf(false)
    var speedStage by mutableStateOf("")
    var speedDown by mutableStateOf<SpeedTest.Result?>(null)
    var speedUp by mutableStateOf<SpeedTest.Result?>(null)

    fun runSpeedTest() {
        if (speedRunning) return
        speedRunning = true; speedDown = null; speedUp = null
        viewModelScope.launch(Dispatchers.IO) {
            speedStage = "Скачиваем тестовые данные…"
            val down = SpeedTest.download()
            speedDown = down
            speedStage = "Отправляем тестовые данные…"
            val up = SpeedTest.upload()
            speedUp = up
            speedStage = ""; speedRunning = false
        }
    }

    // ---------- Включение по сети (Wake-on-LAN) ----------
    var wol by mutableStateOf<List<WolEntry>>(emptyList()); private set
    private val wolPrefs get() = context.getSharedPreferences("wake_on_lan", Context.MODE_PRIVATE)

    // Kotlin выполняет инициализаторы полей и init сверху вниз. Загрузка данных
    // должна идти после создания всех State, в которые она записывает значения.
    init {
        loadSnapshots(); loadProfiles(); loadWol()
        if (scan.targetExpression.isNotEmpty()) { targets = scan.targetExpression; ports = scan.portExpression }
        else targets = localSubnet()
        ScanService.register(observer)
    }

    private fun loadWol() {
        wol = (wolPrefs.getString("items", "") ?: "").split('\n').mapNotNull {
            val parts = it.split('\t'); if (parts.size >= 2 && parts[1].isNotEmpty()) WolEntry(parts[0], parts[1], parts.getOrElse(2) { "" }) else null
        }
    }

    private fun saveWol(list: List<WolEntry>) {
        wolPrefs.edit().putString("items", list.joinToString("\n") { it.name.replace('\t', ' ').replace('\n', ' ') + "\t" + it.mac + "\t" + it.ip }).apply(); wol = list
    }

    fun addWol(name: String, mac: String, ip: String = ""): Boolean {
        val clean = normalizeMac(mac) ?: return false
        saveWol(wol.filter { it.mac != clean } + WolEntry(name.trim().ifEmpty { clean }, clean, ip.trim())); return true
    }

    fun removeWol(entry: WolEntry) = saveWol(wol.filter { it.mac != entry.mac })

    /** Отправляет magic-пакет и, если указан IP, ждёт до 25 с ответа устройства, чтобы подтвердить пробуждение. */
    fun wake(entry: WolEntry, onStatus: (String) -> Unit) {
        viewModelScope.launch {
            val sent = withContext(Dispatchers.IO) { sendMagic(entry.mac, localSubnet()) }
            if (!sent) { onStatus("Не удалось отправить сигнал: проверьте Wi-Fi"); return@launch }
            if (entry.ip.isBlank()) { onStatus("Сигнал отправлен"); return@launch }
            onStatus("Сигнал отправлен, ждём, когда устройство поднимется…")
            val woke = withContext(Dispatchers.IO) {
                val deadline = System.currentTimeMillis() + 25_000
                var ok = false
                while (System.currentTimeMillis() < deadline && !ok) {
                    ok = IpTools.tcpPing(entry.ip, 1, 1200).ok
                    if (!ok) Thread.sleep(1500)
                }
                ok
            }
            onStatus(if (woke) "Устройство ответило — проснулось" else "Сигнал отправлен, но устройство пока не отвечает")
        }
    }

    companion object {
        fun normalizeMac(raw: String): String? {
            val hex = raw.filter { it.isLetterOrDigit() }.uppercase()
            if (hex.length != 12 || hex.any { it !in "0123456789ABCDEF" }) return null
            return hex.chunked(2).joinToString(":")
        }

        /** «Волшебный пакет»: 6 байт FF и 16 повторов MAC-адреса, на широковещательные адреса. */
        fun magicPacket(mac: String): ByteArray {
            val m = mac.split(":").map { it.toInt(16).toByte() }
            val data = ByteArray(6 + 16 * 6) { 0xFF.toByte() }
            for (i in 0 until 16) for (j in 0 until 6) data[6 + i * 6 + j] = m[j]
            return data
        }

        fun sendMagic(mac: String, subnet: String): Boolean = try {
            val data = magicPacket(mac)
            val targets = ArrayList<String>(); targets.add("255.255.255.255")
            if (subnet.endsWith(".0/24")) targets.add(subnet.removeSuffix("0/24") + "255")
            DatagramSocket().use { socket ->
                socket.broadcast = true
                for (t in targets) for (port in intArrayOf(9, 7)) socket.send(DatagramPacket(data, data.size, InetAddress.getByName(t), port))
            }
            true
        } catch (e: Exception) { false }
    }
}
