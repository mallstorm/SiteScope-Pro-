package pro.sitescope.app.ip

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

class MyNetwork {
    var ipv4 = ""; var ipv6 = ""; var provider4 = ""; var provider6 = ""; var asn4 = ""
    var transport = ""; var vpn = false; var localAddresses = ""; var dns = ""; var iface = ""; var error = ""
}

class IpViewModel(app: Application) : AndroidViewModel(app) {
    var page by mutableStateOf(0)

    // Поиск
    var query by mutableStateOf("")
    var searching by mutableStateOf(false)
    var info by mutableStateOf<IpInfo?>(null)
    var hits by mutableStateOf(listOf<ProviderHit>())
    var searchError by mutableStateOf("")
    var recent by mutableStateOf(listOf<String>())

    var blacklists by mutableStateOf<List<IpTools.BlacklistEntry>?>(null)
    var blacklistRunning by mutableStateOf(false)
    var ping by mutableStateOf<IpTools.PingResult?>(null)
    var pingRunning by mutableStateOf(false)
    var hops by mutableStateOf(listOf<IpTools.Hop>())
    var hopPlaces by mutableStateOf(mapOf<String, String>())
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
    @Volatile private var traceCancelled = false
    private val prefs = app.getSharedPreferences("ip_recent", Context.MODE_PRIVATE)

    init { recent = (prefs.getString("list", "") ?: "").split('\n').filter { it.isNotBlank() } }

    private fun remember(q: String) {
        recent = (listOf(q) + recent.filter { it != q }).take(8)
        prefs.edit().putString("list", recent.joinToString("\n")).apply()
    }

    fun search(text: String = query) {
        val q = text.trim()
        if (q.isEmpty() || searching) return
        query = q; searching = true; info = null; hits = emptyList(); searchError = ""
        blacklists = null; ping = null; hops = emptyList(); services = null; traceCancelled = true
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
                if (info != null) remember(q)
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
        if (traceRunning) { traceCancelled = true; return }
        traceRunning = true; traceCancelled = false; hops = emptyList(); hopPlaces = emptyMap()
        traceJob = viewModelScope.launch(Dispatchers.IO) {
            val db = try { IpDataStore(getApplication<Application>()) } catch (e: Throwable) { null }
            try {
                IpTools.traceroute(ip, onHop = { hop ->
                    if (!traceCancelled) {
                        hops = hops + hop
                        if (hop.address.isNotEmpty() && db != null) try {
                            db.lookupGeo(hop.address)?.let { g ->
                                val place = listOf(g.city, g.country).filter { it.isNotBlank() }.joinToString(", ")
                                if (place.isNotEmpty()) hopPlaces = hopPlaces + (hop.address to place)
                            }
                        } catch (e: Exception) { }
                    }
                }, cancelled = { traceCancelled })
            } finally { try { db?.close() } catch (e: Exception) { } }
            traceRunning = false
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
        mineLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            val out = MyNetwork()
            try {
                val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val network = cm.activeNetwork
                val caps = cm.getNetworkCapabilities(network)
                val link = cm.getLinkProperties(network)
                if (caps != null) {
                    out.vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    out.transport = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Мобильная сеть"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Кабель (Ethernet)"
                        out.vpn -> "VPN"
                        else -> "Сеть"
                    }
                }
                if (link != null) {
                    out.iface = link.interfaceName ?: ""
                    out.localAddresses = link.linkAddresses.joinToString("\n") { (it.address.hostAddress ?: "") + "/" + it.prefixLength }
                    out.dns = link.dnsServers.joinToString("\n") { it.hostAddress ?: "" }
                }
                if (network == null) out.error = "Нет подключения к сети"
                else {
                    val current = ProviderApiClient.currentProvider()
                    out.ipv4 = current.addresses.ipv4; out.ipv6 = current.addresses.ipv6
                    current.ipv4Provider?.let { out.provider4 = it.displayName(); out.asn4 = it.asn }
                    current.ipv6Provider?.let { out.provider6 = it.displayName() }
                    if (out.ipv4.isEmpty() && out.ipv6.isEmpty()) out.error = "Не удалось узнать внешний адрес. Проверьте интернет."
                }
            } catch (e: Throwable) { out.error = "Не удалось получить сведения о сети" }
            mine = out; mineLoading = false
        }
    }
}
