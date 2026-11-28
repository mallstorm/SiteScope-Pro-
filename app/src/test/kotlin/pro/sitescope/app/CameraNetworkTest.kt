package pro.sitescope.app

import android.app.Application
import android.net.*
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import pro.sitescope.app.net.findLocalLan

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class, shadows = [CameraNetworkTest.Choice::class])
class CameraNetworkTest {
    @Implements(ConnectivityManager::class)
    class Choice {
        companion object {
            var active: Network? = null
            val caps = linkedMapOf<Network, NetworkCapabilities>()
            val links = mutableMapOf<Network, LinkProperties>()
        }
        @Implementation fun getActiveNetwork() = active
        @Implementation fun getAllNetworks() = caps.keys.toTypedArray()
        @Implementation fun getNetworkCapabilities(network: Network) = caps[network]
        @Implementation fun getLinkProperties(network: Network) = links[network]
    }
    @Before fun reset() { Choice.active = null; Choice.caps.clear(); Choice.links.clear() }
    private fun network(id: Int, transport: Int, prefix: String? = null): Network {
        val n = ShadowNetwork.newInstance(id)
        Choice.caps[n] = NetworkCapabilities().apply { Shadow.extract<ShadowNetworkCapabilities>(this).addTransportType(transport) }
        if (prefix != null) Choice.links[n] = LinkProperties().apply {
            interfaceName = "test$id"
            val destination = IpPrefix(java.net.InetAddress.getByName(prefix.substringBefore('/')), prefix.substringAfter('/').toInt())
            val route = org.robolectric.util.ReflectionHelpers.callConstructor(RouteInfo::class.java,
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(IpPrefix::class.java, destination),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(java.net.InetAddress::class.java, java.net.InetAddress.getByName("0.0.0.0")),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(String::class.java, "test$id"))
            addRoute(route)
        }
        return n
    }
    private val app get() = RuntimeEnvironment.getApplication() as Application
    @Test fun currentLanIgnoresMobileAndTunnelAddressesAndUsesActualWifiPrefix() {
        fun link(cidr: String) = LinkProperties().apply {
            val address = ReflectionHelpers.callConstructor(LinkAddress::class.java,
                ClassParameter.from(InetAddress::class.java, InetAddress.getByName(cidr.substringBefore('/'))),
                ClassParameter.from(Int::class.javaPrimitiveType, cidr.substringAfter('/').toInt()))
            ReflectionHelpers.callInstanceMethod<Boolean>(this, "addLinkAddress", ClassParameter.from(LinkAddress::class.java, address))
        }
        val manager = app.getSystemService(ConnectivityManager::class.java)!!
        val mobile = network(110, NetworkCapabilities.TRANSPORT_CELLULAR)
        val vpn = network(111, NetworkCapabilities.TRANSPORT_VPN)
        Choice.links[vpn] = link("10.10.14.2/30")
        Choice.active = vpn
        assertNull(findLocalLan(manager))

        val wifi = network(112, NetworkCapabilities.TRANSPORT_WIFI)
        Choice.links[wifi] = link("192.168.3.148/25")
        Choice.active = mobile
        assertNull(findLocalLan(manager))
        Choice.active = vpn
        assertEquals(wifi, findLocalLan(manager)?.network)
        assertEquals("192.168.3.128/25", findLocalLan(manager)?.subnet)
        Shadow.extract<ShadowNetworkCapabilities>(Choice.caps.getValue(vpn)).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        assertNull(findLocalLan(manager))
    }

    @Test fun discoveredLanRemainsSelectedWhenVpnIsDefault() {
        val wifi = network(101, NetworkCapabilities.TRANSPORT_WIFI)
        Choice.active = network(102, NetworkCapabilities.TRANSPORT_VPN)
        val ep = ServiceEndpoint().apply { host = "192.168.1.10"; scope = NetworkScope.capture(app, wifi) }
        assertEquals(wifi, CameraClient.selectNetwork(app, ep))
    }
    @Test fun localRouteWinsOverCellularAndUsesMostSpecificPrefix() {
        Choice.active = network(100, NetworkCapabilities.TRANSPORT_CELLULAR)
        network(101, NetworkCapabilities.TRANSPORT_WIFI, "192.168.1.0/24")
        val ethernet = network(103, NetworkCapabilities.TRANSPORT_ETHERNET, "192.168.1.128/25")
        assertEquals(ethernet, CameraClient.selectNetwork(app, ServiceEndpoint().apply { host = "192.168.1.200" }))
        assertEquals(Choice.active, CameraClient.selectNetwork(app, ServiceEndpoint().apply { host = "203.0.113.10" }))
    }
    @Test fun unscopedVpnIsRespectedAndNoNetworkDoesNotStartAConnection() {
        val vpn = network(100, NetworkCapabilities.TRANSPORT_VPN); Choice.active = vpn
        network(101, NetworkCapabilities.TRANSPORT_WIFI, "192.168.1.0/24")
        assertEquals(vpn, CameraClient.selectNetwork(app, ServiceEndpoint().apply { host = "192.168.1.10" }))
        Choice.active = null; Choice.caps.clear()
        assertNull(CameraClient.selectNetwork(app, ServiceEndpoint().apply { host = "192.168.1.10" }))
    }
}
