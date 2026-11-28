package pro.sitescope.app.net

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Inet4Address

internal data class LocalLan(val network: Network, val address: Inet4Address, val prefix: Int, val ethernet: Boolean) {
    val description get() = if (ethernet) "Ethernet" else "Wi-Fi"
    val subnet get() = ipv4Subnet(address.address, prefix)
    val scanSubnet get() = ipv4Subnet(address.address, prefix.coerceAtLeast(24))
}

internal fun ipv4Subnet(address: ByteArray, prefix: Int): String {
    require(address.size == 4 && prefix in 0..32)
    val ip = address.fold(0L) { value, octet -> (value shl 8) + (octet.toInt() and 255) }
    val mask = if (prefix == 0) 0 else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
    val start = ip and mask
    return (24 downTo 0 step 8).joinToString(".") { ((start shr it) and 255).toString() } + "/$prefix"
}

internal fun findLocalLan(cm: ConnectivityManager): LocalLan? {
    val active = cm.activeNetwork ?: return null
    val caps = cm.getNetworkCapabilities(active) ?: return null
    fun lan(network: Network): LocalLan? {
        val info = cm.getNetworkCapabilities(network) ?: return null
        val ethernet = info.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (info.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !(ethernet || info.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))) return null
        val address = cm.getLinkProperties(network)?.linkAddresses?.firstOrNull {
            it.address is Inet4Address && !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress
        } ?: return null
        return LocalLan(network, address.address as Inet4Address, address.prefixLength, ethernet)
    }
    if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return lan(active)
    val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    val ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !wifi && !ethernet) return null
    @Suppress("DEPRECATION")
    val available = cm.allNetworks.mapNotNull(::lan)
    return when {
        wifi || ethernet -> available.firstOrNull { (it.ethernet && ethernet) || (!it.ethernet && wifi) }
        else -> available.singleOrNull()
    }
}
