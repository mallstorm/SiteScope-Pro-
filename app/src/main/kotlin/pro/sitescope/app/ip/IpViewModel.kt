package pro.sitescope.app.ip

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.sitescope.app.ExposureStore
import pro.sitescope.app.IpDataStore
import pro.sitescope.app.RipeStatClient
import pro.sitescope.app.ipintel.ProviderApiClient
import java.net.InetAddress

class IpInfo {
    var query = ""; var ip = ""; var isAsn = false; var allAddresses = ""
    var kind = ""; var country = ""; var countryCode = ""; var region = ""; var city = ""
    var lat = 0.0; var lon = 0.0; var hasGeo = false; var geoSource = ""
    var asn = ""; var holder = ""; var prefix = ""; var rir = ""; var announced = false
    var reverse = ""; var networkName = ""
    var abuse: List<String> = emptyList(); var prefixes: List<String> = emptyList(); var whois: List<String> = emptyList()
    var error = ""
    val flag: String get() = if (countryCode.length == 2 && countryCode.all { it in 'A'..'Z' })
        String(Character.toChars(0x1F1E6 + (countryCode[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (countryCode[1] - 'A'))) else ""
    val place: String get() = listOf(city, region, country).filter { it.isNotBlank() }.distinct().joinToString(", ")
}

class ProviderHit(val asn: String, val name: String, val country: String, val prefixes: Int)

data class MyNetwork(
    var ipv4: String = "", var ipv6: String = "", var vpn4: String = "", var vpn6: String = "",
    val provider: String = "", val asn: String = "", var transport: String = "", var vpn: Boolean = false,
    var localAddresses: String = "", var dns: String = "", var vpnDns: String = "", var iface: String = "",
    var error: String = "",
)

class IpViewModel(app: Application) : AndroidViewModel(app) {
    var page by mutableStateOf(0)

    // Поиск
    var query by mutableStateOf("")
    var searching by mutableStateOf(false)
    var info by mutableStateOf<IpInfo?>(null)
    var hits by mutableStateOf(listOf<ProviderHit>())
    var searchError by mutableStateOf("")
    var recent by mutableStateOf(listOf<String>())
    var recentTimes by mutableStateOf(mapOf<String, Long>())

    var blacklists by mutableStateOf<List<IpTools.BlacklistEntry>?>(null)
    var blacklistRunning by mutableStateOf(false)
    var ping by mutableStateOf<IpTools.PingResult?>(null)
    var pingRunning by mutableStateOf(false)
    var hops by mutableStateOf(listOf<IpTools.Hop>())
    internal var hopLocations by mutableStateOf(mapOf<String, IpMapLocation>())
        private set
    internal var traceOrigin by mutableStateOf<TraceOrigin?>(null)
        private set
    internal var traceNote by mutableStateOf("")
        private set
    var traceRunning by mutableStateOf(false)
    var services by mutableStateOf<List<Pair<Int, String>>?>(null)
    var servicesRunning by mutableStateOf(false)

    // Мой адрес
    var mine by mutableStateOf<MyNetwork?>(null)
    var mineLoading by mutableStateOf(false)

    // Подсети
    var subnetInput by mutableStateOf("192.168.1.0/24")
    var aclInput by mutableStateOf("")

    private var traceJob: Job? = null
    private var traceGeneration = 0
    private var mineJob: Job? = null
    private val prefs = app.getSharedPreferences("ip_recent", Context.MODE_PRIVATE)

    init {
        recent = (prefs.getString("list", "") ?: "").split('\n').filter { it.isNotBlank() }
        recentTimes = (prefs.getString("times", "") ?: "").split('\n').mapNotNull { l -> val i = l.indexOf('\t'); if (i > 0) l.substring(i + 1) to (l.substring(0, i).toLongOrNull() ?: 0L) else null }.toMap()
    }

    private fun remember(q: String) {
        recent = (listOf(q) + recent.filter { it != q }).take(8)
        recentTimes = (recentTimes + (q to System.currentTimeMillis())).filterKeys { it in recent }
        prefs.edit().putString("list", recent.joinToString("\n")).putString("times", recentTimes.entries.joinToString("\n") { it.value.toString() + "\t" + it.key }).apply()
    }

    fun search(text: String = query) {
        val q = text.trim()
        if (q.isEmpty() || searching) return
        query = q; searching = true; info = null; hits = emptyList(); searchError = ""
        blacklists = null; ping = null; services = null
        stopTrace()
        hops = emptyList(); hopLocations = emptyMap(); traceOrigin = null; traceNote = ""
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val asn = Regex("^(?i)as\\s?(\\d+)$").find(q)
                when {
                    asn != null -> info = lookup("AS" + asn.groupValues[1], q)
                    Subnet.isIpv4(q) || Subnet.isIpv6(q) -> info = lookup(q, q)
                    q.contains('.') && !q.contains(' ') -> {
                        val host = q.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':')
                        val addresses = try { InetAddress.getAllByName(host).mapNotNull { it.hostAddress } } catch (e: Exception) { emptyList() }
                        if (addresses.isEmpty()) searchError = "Не удалось найти адрес для «$host». Проверьте написание."
                        else info = lookup(addresses.first(), host).also { it.allAddresses = addresses.joinToString(", ") }
                    }
                    else -> {
                        val found = ProviderApiClient.search(q).filter { it.error.isEmpty() && it.asn.isNotEmpty() }
                        if (found.isEmpty()) searchError = "Провайдер «$q» не найден. Попробуйте название латиницей или номер AS."
                        else hits = found.map { ProviderHit(it.asn, it.displayName(), it.country, it.prefixes.size) }
                    }
                }
                if (info != null) {
                    remember(q)
                    // Быстрые безобидные проверки запускаем сами: связь, чёрные списки (DNS) и маршрут. Порты — только по подтверждению.
                    withContext(Dispatchers.Main) {
                        info?.let { i -> if (!i.isAsn && i.ip.isNotEmpty()) {
                            runPing()
                            if (Subnet.isIpv4(i.ip) && i.kind.startsWith("Публичный")) runBlacklists()
                            if (ExposureStore.publicAddress(i.ip)) runTrace()
                        } }
                    }
                }
            } catch (e: Throwable) { searchError = "Не удалось выполнить поиск: " + (e.message ?: e.javaClass.simpleName) }
            searching = false
        }
    }

    private fun lookup(resource: String, original: String): IpInfo {
        val out = IpInfo(); out.query = original; out.isAsn = resource.startsWith("AS")
        if (!out.isAsn) {
            out.ip = resource
            out.kind = if (Subnet.isIpv4(resource)) Subnet.kind(Subnet.ipToLong(resource)) else try { Subnet.v6(resource).kind } catch (e: Exception) { "" }
            try {
                val db = IpDataStore(getApplication<Application>())
                try {
                db.lookupGeo(resource)?.let { g ->
                    out.country = g.country; out.countryCode = g.countryCode; out.region = g.region; out.city = g.city
                    out.lat = g.latitude; out.lon = g.longitude; out.hasGeo = g.latitude != 0.0 || g.longitude != 0.0
                    out.asn = g.asn; out.holder = g.organization; out.geoSource = "локальная база DB-IP"
                }
                if (out.asn.isEmpty()) db.lookupAsn(resource)?.let { out.asn = "AS" + it.asn; out.holder = it.organization }
                } finally { db.close() }
            } catch (e: Throwable) { }
            // Для частных адресов внешние справочники бессмысленны
            if (out.kind.isNotEmpty() && !out.kind.startsWith("Публичный") && !out.kind.startsWith("Глобальный")) {
                out.reverse = IpTools.reverseDns(resource); return out
            }
        }
        val r = RipeStatClient.lookup(resource)
        if (out.asn.isEmpty()) out.asn = r.asn
        if (r.holder.isNotEmpty()) out.holder = r.holder
        out.prefix = r.prefix; out.rir = r.rir; out.announced = r.announced; out.networkName = r.rdapName
        if (out.country.isEmpty()) {
            out.country = r.country.ifEmpty { r.rdapCountry }; out.city = r.city; out.region = r.region
            if (out.country.length == 2) out.countryCode = out.country.uppercase()
            if (r.latitude != 0.0 || r.longitude != 0.0) { out.lat = r.latitude; out.lon = r.longitude; out.hasGeo = true; out.geoSource = "RIPEstat" }
        }
        if (out.countryCode.isEmpty() && r.rdapCountry.length == 2) out.countryCode = r.rdapCountry.uppercase()
        out.reverse = r.reverseDns.ifEmpty { if (out.isAsn) "" else IpTools.reverseDns(resource) }
        out.abuse = ArrayList(r.abuseContacts); out.prefixes = ArrayList(r.announcedPrefixes); out.whois = ArrayList(r.whois)
        if (r.error.isNotEmpty() && out.asn.isEmpty() && out.holder.isEmpty()) out.error = r.error
        return out
    }

    fun runBlacklists() {
        val ip = info?.ip ?: return
        if (blacklistRunning) return
        blacklistRunning = true
        viewModelScope.launch(Dispatchers.IO) { blacklists = IpTools.blacklists(ip); blacklistRunning = false }
    }

    fun runPing() {
        val ip = info?.ip ?: return
        if (pingRunning) return
        pingRunning = true
        viewModelScope.launch(Dispatchers.IO) { ping = IpTools.ping(ip); pingRunning = false }
    }

    fun runTrace() {
        val ip = info?.ip ?: return
        if (traceRunning) { stopTrace(); traceNote = "Трассировка остановлена"; return }
        val ctx = getApplication<Application>()
        val manager = ctx.getSystemService(ConnectivityManager::class.java)
        val network = manager?.activeNetwork
        traceOrigin = IpTrace.origin(ctx, network, Subnet.isIpv4(ip))
        traceRunning = true; hops = emptyList(); traceNote = ""
        val generation = ++traceGeneration
        traceJob = viewModelScope.launch {
            try {
                val note = withContext(Dispatchers.IO) {
                    val job = currentCoroutineContext()[Job]
                    fun networkChanged() = manager?.activeNetwork != network
                    val db = runCatching { IpDataStore(ctx) }.getOrNull()
                    try {
                        val end = IpTrace.run(ip, network, onHop = { hop ->
                            val geo = if (hop.address.isBlank()) null else runCatching {
                                db?.lookupGeo(hop.address)?.let { IpMapLocation.of(it.latitude, it.longitude, it.city, it.country) }
                            }.getOrNull()
                            viewModelScope.launch hopUpdate@{
                                if (generation != traceGeneration) return@hopUpdate
                                hops = hops + hop
                                if (geo != null) hopLocations = hopLocations + (hop.address to geo)
                            }
                        }, cancelled = { job?.isActive != true || networkChanged() })
                        if (networkChanged()) "Сеть сменилась во время трассировки" else end
                    } finally { db?.close() }
                }
                if (generation == traceGeneration) traceNote = note
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (generation == traceGeneration) traceNote = "Не удалось выполнить трассировку"
            } finally {
                if (generation == traceGeneration) traceRunning = false
            }
        }
    }

    private fun stopTrace() {
        traceGeneration++
        traceJob?.cancel()
        traceRunning = false
    }

    internal suspend fun locateHop(ip: String): IpMapLocation? {
        if (!ExposureStore.publicAddress(ip)) return null
        return withContext(Dispatchers.IO) {
            val geo = RipeStatClient.geolocation(ip)
            IpMapLocation.of(geo.latitude, geo.longitude, geo.city, geo.country)
        }
    }

    fun runServices() {
        val ip = info?.ip ?: return
        if (servicesRunning) return
        servicesRunning = true
        viewModelScope.launch(Dispatchers.IO) { services = IpTools.openServices(ip); servicesRunning = false }
    }

    fun loadMine(force: Boolean = false) {
        if (mineLoading || (mine != null && !force)) return
        mineJob?.cancel()
        mineLoading = true
        mine = null
        mineJob = viewModelScope.launch {
            try {
                val out = withContext(Dispatchers.IO) { MyNetworkProbe.measure(getApplication<Application>()) }
                mine = out
                mineLoading = false
                val direct = out.ipv4.ifBlank { out.ipv6 }
                if (direct.isNotBlank()) {
                    val provider = withContext(Dispatchers.IO) { ProviderApiClient.providerForAddress(direct) }
                    mine = out.copy(provider = provider.displayName(), asn = provider.asn)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (mine == null) mine = MyNetwork(error = "Не удалось получить сведения о сети")
                mineLoading = false
            }
        }
    }
}
