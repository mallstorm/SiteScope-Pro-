package pro.sitescope.app.ip

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import pro.sitescope.app.VpnNetworkCoordinator
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.TimeUnit

internal data class TraceOrigin(val local: String, val gateway: String, val vpn: Boolean, val tunnel: String)

internal object IpTrace {
    private val echo = Regex("^\\s*\\d+ bytes from (\\S+)(?:\\s+\\(([^)]+)\\))?", RegexOption.IGNORE_CASE)
    private val reporter = Regex("^\\s*from (\\S+)(?:\\s+\\(([^)]+)\\))?", RegexOption.IGNORE_CASE)
    private val rtt = Regex("time[=<]([0-9.]+)", RegexOption.IGNORE_CASE)
    private val unusable = Regex("invalid option|unknown option|usage:|operation not permitted|permission denied", RegexOption.IGNORE_CASE)

    fun origin(context: Context, network: Network?, ipv4: Boolean): TraceOrigin {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = network?.let { manager?.getNetworkCapabilities(it) }
        val vpn = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val physical = if (vpn) VpnNetworkCoordinator.snapshot(context).underlying else network
        val properties = physical?.let { manager?.getLinkProperties(it) }
        fun localAddress(link: android.net.LinkProperties?) = link?.linkAddresses?.map { it.address }
            ?.filter { (it is Inet4Address) == ipv4 && !it.isLoopbackAddress }
            ?.minByOrNull { if (it.isLinkLocalAddress) 1 else 0 }?.hostAddress.orEmpty()
        val gateway = properties?.routes?.firstOrNull {
            val candidate = it.gateway
            it.isDefaultRoute && candidate?.isAnyLocalAddress == false && (candidate is Inet4Address) == ipv4
        }?.gateway
        val tunnel = if (vpn) localAddress(network?.let { manager?.getLinkProperties(it) }) else ""
        return TraceOrigin(localAddress(properties), gateway?.hostAddress.orEmpty(), vpn, tunnel)
    }

    /** RTT is available only when ping prints it for that reply. */
    fun parse(ttl: Int, output: String, target: InetAddress): IpTools.Hop {
        for (line in output.lineSequence()) {
            val response = echo.find(line)
            val from = reporter.find(line)
            val token = response?.let { it.groupValues[2].ifEmpty { it.groupValues[1].removeSuffix(":") } }
                ?: from?.let { it.groupValues[2].ifEmpty { it.groupValues[1] } }
                ?: continue
            val address = numeric(token) ?: numeric(token.removeSuffix(":")) ?: continue
            val latency = rtt.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
            return IpTools.Hop(ttl, address.hostAddress?.substringBefore('%').orEmpty(), latency, response != null && address == target)
        }
        return IpTools.Hop(ttl, "", 0.0, false)
    }

    fun run(ip: String, network: Network?, onHop: (IpTools.Hop) -> Unit, cancelled: () -> Boolean): String {
        val target = numeric(ip) ?: return "Не удалось определить числовой адрес"
        val ipv4 = target is Inet4Address
        val ping6 = File("/system/bin/ping6")
        val command = when {
            !ipv4 && ping6.canExecute() -> listOf(ping6.path)
            else -> listOf("/system/bin/ping") + if (ipv4) emptyList() else listOf("-6")
        }
        var useSocket = true
        for (ttl in 1..30) {
            if (cancelled()) return ""
            if (useSocket && network != null && Build.VERSION.SDK_INT >= 31) {
                val hop = runCatching { IpIcmpProbe.hop(network, target, ttl) }.getOrNull()
                if (hop != null) {
                    if (cancelled()) return ""
                    onHop(hop)
                    if (hop.reached) return ""
                    continue
                }
                useSocket = false
            }
            val process = try {
                ProcessBuilder(command + listOf("-c", "1", "-W", "1", "-t", ttl.toString(), ip))
                    .redirectErrorStream(true).apply { environment()["LC_ALL"] = "C" }.start()
            } catch (_: Exception) { return "Системная ICMP-трассировка недоступна" }
            val output = try {
                var finished = false
                for (attempt in 1..25) {
                    if (cancelled()) return ""
                    if (process.waitFor(100, TimeUnit.MILLISECONDS)) {
                        finished = true
                        break
                    }
                }
                if (finished) process.inputStream.bufferedReader().use { it.readText() } else ""
            } finally { process.destroy() }
            if (unusable.containsMatchIn(output)) return "Система не разрешила ICMP-трассировку"
            if (cancelled()) return ""
            val hop = parse(ttl, output, target)
            onHop(hop)
            if (hop.reached) return ""
        }
        return "Назначение не ответило в пределах 30 переходов"
    }

    private fun numeric(token: String): InetAddress? {
        val address = token.substringBefore('%')
        if (!Subnet.isIpv4(address) && !Subnet.isIpv6(address)) return null
        return runCatching { InetAddress.getByName(address) }.getOrNull()
    }
}
