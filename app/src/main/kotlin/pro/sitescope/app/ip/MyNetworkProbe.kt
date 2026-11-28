package pro.sitescope.app.ip

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import pro.sitescope.app.ExposureStore
import pro.sitescope.app.VpnNetworkCoordinator
import pro.sitescope.app.ipintel.ProviderApiClient

internal object MyNetworkProbe {
    suspend fun measure(context: Context): MyNetwork = coroutineScope {
        val out = MyNetwork()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val active = manager?.activeNetwork
        if (manager == null || active == null) {
            out.error = "Активная сеть недоступна"
            return@coroutineScope out
        }
        val capabilities = manager.getNetworkCapabilities(active)
        if (capabilities == null) {
            out.error = "Не удалось определить активную сеть"
            return@coroutineScope out
        }
        out.vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        val direct = if (out.vpn) VpnNetworkCoordinator.snapshot(context).underlying else active
        val physical = direct?.let(manager::getNetworkCapabilities)
        out.transport = when {
            physical?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
            physical?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Мобильная сеть"
            physical?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> "Ethernet"
            else -> ""
        }
        direct?.let(manager::getLinkProperties)?.let { link ->
            out.iface = link.interfaceName.orEmpty()
            out.localAddresses = link.linkAddresses.joinToString("\n") { it.toString() }
            out.dns = link.dnsServers.joinToString("\n") { it.hostAddress.orEmpty() }
        }
        if (out.vpn) {
            out.vpnDns = manager.getLinkProperties(active)?.dnsServers.orEmpty().joinToString("\n") { it.hostAddress.orEmpty() }
        }

        val direct4 = async(Dispatchers.IO) { address(direct, ipv6 = false) }
        val direct6 = async(Dispatchers.IO) { address(direct, ipv6 = true) }
        val tunnel = active.takeIf { out.vpn }
        val tunnel4 = async(Dispatchers.IO) { address(tunnel, ipv6 = false) }
        val tunnel6 = async(Dispatchers.IO) { address(tunnel, ipv6 = true) }
        val observed = listOf(direct4.await(), direct6.await(), tunnel4.await(), tunnel6.await())
        if (manager.activeNetwork != active) {
            out.error = "Сеть сменилась во время замера. Обновите результат."
            return@coroutineScope out
        }
        val stillPhysical = direct?.let(manager::getNetworkCapabilities)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == false
        if (stillPhysical) {
            out.ipv4 = observed[0]
            out.ipv6 = observed[1]
        }
        if (out.vpn) {
            out.vpn4 = observed[2]
            out.vpn6 = observed[3]
        }
        if (!out.vpn && out.ipv4.isEmpty() && out.ipv6.isEmpty()) {
            out.error = "Публичный IP основной сети не определён"
        }
        out
    }

    private fun address(network: Network?, ipv6: Boolean): String {
        if (network == null) return ""
        return runCatching { ProviderApiClient.measuredAddress(network, ipv6) }
            .getOrDefault("").takeIf { (':' in it) == ipv6 && ExposureStore.publicAddress(it) }.orEmpty()
    }
}
