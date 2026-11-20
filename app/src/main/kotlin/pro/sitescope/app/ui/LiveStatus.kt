package pro.sitescope.app.ui

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
import pro.sitescope.app.HostsVpnService
import pro.sitescope.app.ScanService
import pro.sitescope.app.anon.AnonNet
import pro.sitescope.app.core.TaskCenter
import pro.sitescope.app.dpi.DpiPhase
import pro.sitescope.app.dpi.DpiRuntime

/** Состояние VPN-службы Hosts/Xray, обновляемое при каждом изменении общих настроек. */
@Composable
fun rememberVpnLifecycle(): State<String> {
    val context = LocalContext.current
    val prefs = remember { context.applicationContext.getSharedPreferences(HostsVpnService.PREFS, Context.MODE_PRIVATE) }
    val state = remember { mutableStateOf(prefs.getString(HostsVpnService.KEY_STATE, HostsVpnService.STATE_OFF) ?: HostsVpnService.STATE_OFF) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == HostsVpnService.KEY_STATE) state.value = p.getString(HostsVpnService.KEY_STATE, HostsVpnService.STATE_OFF) ?: HostsVpnService.STATE_OFF
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return state
}

/** Подпись и признак наличия сети по текущему подключению по умолчанию. */
class NetLabel(val text: String, val connected: Boolean, val detail: String = "")

@Composable
fun rememberNetLabel(): State<NetLabel> {
    val context = LocalContext.current
    val label = remember { mutableStateOf(NetLabel("Сеть", true)) }
    DisposableEffect(Unit) {
        val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        fun details(network: Network?, caps: NetworkCapabilities?): String {
            if (manager == null || network == null) return ""
            val parts = mutableListOf<String>()
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) {
                try {
                    val op = (context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager)?.networkOperatorName
                    if (!op.isNullOrBlank()) parts += op
                } catch (_: Exception) { }
            }
            try {
                val ip = manager.getLinkProperties(network)?.linkAddresses?.map { it.address }?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }?.hostAddress
                if (ip != null) parts += ip
            } catch (_: Exception) { }
            return parts.joinToString(" · ")
        }
        fun describe(network: Network?, caps: NetworkCapabilities?): NetLabel = when {
            caps == null -> NetLabel("Нет сети", false)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetLabel("Wi-Fi", true, details(network, caps))
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetLabel("Мобильная сеть", true, details(network, caps))
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetLabel("Ethernet", true, details(network, caps))
            else -> NetLabel("Сеть", true, details(network, caps))
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { label.value = describe(network, manager?.getNetworkCapabilities(network)) }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { label.value = describe(network, caps) }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) { label.value = describe(network, manager?.getNetworkCapabilities(network)) }
            override fun onLost(network: Network) { label.value = NetLabel("Нет сети", false) }
        }
        var registered = false
        try {
            if (manager != null) {
                label.value = describe(manager.activeNetwork, manager.activeNetwork?.let { manager.getNetworkCapabilities(it) })
                manager.registerDefaultNetworkCallback(callback); registered = true
            }
        } catch (_: Exception) { }
        onDispose { if (registered) try { manager?.unregisterNetworkCallback(callback) } catch (_: Exception) { } }
    }
    return label
}

/** Живая служба, которую показываем среди задач наравне с операциями из [TaskCenter]. */
class ServiceItem(val key: String, val title: String, val detail: String, val route: String, val tab: Int = 0)

/** Работающие сейчас фоновые службы: VPN, обход DPI, Tor, I2P и сканер сети. */
@Composable
fun rememberRunningServices(): State<List<ServiceItem>> {
    val vpn by rememberVpnLifecycle()
    val dpi by DpiRuntime.state.collectAsState()
    val anon by AnonNet.state.collectAsState()
    var scanActive by remember { mutableStateOf(false) }
    var scanDetail by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            val snapshot = try { ScanService.snapshot() } catch (_: Exception) { null }
            scanActive = snapshot != null && snapshot.active
            scanDetail = if (snapshot != null && snapshot.active) {
                val total = snapshot.totalProbes
                (if (snapshot.targetExpression.isNotBlank()) snapshot.targetExpression + " · " else "") + (if (total > 0) "проверено ${snapshot.completedProbes} из $total" else "идёт сканирование")
            } else ""
            delay(2000)
        }
    }
    return remember {
        derivedStateOf {
            buildList {
                if (vpn == HostsVpnService.STATE_ACTIVE || vpn == HostsVpnService.STATE_STARTING) add(ServiceItem("vpn", "VPN", if (vpn == HostsVpnService.STATE_ACTIVE) "Подключён" else "Запуск…", "vpn"))
                if (dpi.phase == DpiPhase.RUNNING || dpi.phase == DpiPhase.STARTING) add(ServiceItem("dpi", "Обход блокировок (DPI)", if (dpi.phase == DpiPhase.RUNNING) "Работает, порт ${dpi.port}" else "Запуск…", "dpi"))
                if (anon.tor.phase.active) add(ServiceItem("tor", "Tor", anon.tor.message, "anon"))
                if (anon.i2p.phase.active) add(ServiceItem("i2p", "I2P", anon.i2p.message, "anon"))
                if (scanActive) add(ServiceItem("scan", "Сканирование сети", scanDetail, "network", 1))
            }
        }
    }
}

/** Число активных задач: операции из реестра, не считая сканер, плюс живые службы. */
@Composable
fun rememberActiveTaskCount(): Int {
    val tasks by TaskCenter.tasks.collectAsState()
    val services by rememberRunningServices()
    return tasks.count { it.active } + services.size
}

fun activeWord(count: Int): String = when {
    count % 100 in 11..14 -> "активно"
    count % 10 == 1 -> "активна"
    count % 10 in 2..4 -> "активны"
    else -> "активно"
}
