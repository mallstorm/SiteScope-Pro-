package pro.sitescope.app

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.net.NetworkCapabilities
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DiscoveryNetworkTest {
    @Test fun cellularAndVpnAreNotLanTransports() {
        fun caps(transport: Int) = NetworkCapabilities().apply { org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowNetworkCapabilities>(this).addTransportType(transport) }
        assertFalse(NetworkDiscoveryEngine.isLan(caps(NetworkCapabilities.TRANSPORT_CELLULAR)))
        assertFalse(NetworkDiscoveryEngine.isLan(caps(NetworkCapabilities.TRANSPORT_WIFI).apply { org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowNetworkCapabilities>(this).addTransportType(NetworkCapabilities.TRANSPORT_VPN) }))
        assertTrue(NetworkDiscoveryEngine.isLan(caps(NetworkCapabilities.TRANSPORT_WIFI)))
        assertTrue(NetworkDiscoveryEngine.isLan(caps(NetworkCapabilities.TRANSPORT_ETHERNET)))
    }

    @Test fun noLanCompletesWithoutSendingPackets() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getSystemService(name: String): Any? = if (name == Context.CONNECTIVITY_SERVICE) null else super.getSystemService(name)
        }
        val done = CountDownLatch(1)
        var error = ""
        NetworkDiscoveryEngine.start(context, object : NetworkDiscoveryEngine.Listener {
            override fun onStatus(status: String?) { fail("No probes without a LAN") }
            override fun onDevice(device: Models.DiscoveredDevice?) { fail("No devices without a LAN") }
            override fun onComplete(devices: MutableList<Models.DiscoveredDevice>?, cancelled: Boolean, message: String?) {
                error = message.orEmpty(); done.countDown()
            }
        })
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertTrue(error.contains("Wi-Fi"))
        assertFalse(error.contains("EPERM"))
    }

    @Test fun permissionDenialHasActionableMessage() {
        val message = NetworkDiscoveryEngine.discoveryFailure(java.net.SocketException("sendto failed: EPERM (Operation not permitted)"))
        assertTrue(message.contains("разрешениях"))
        assertFalse(message.contains("sendto failed"))
    }
}
