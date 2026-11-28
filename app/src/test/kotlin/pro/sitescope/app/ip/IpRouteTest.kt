package pro.sitescope.app.ip

import android.app.Application
import android.system.OsConstants
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pro.sitescope.app.radio.Pin
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class IpRouteTest {
    @Test fun parsesIntermediateSilentAndFinalRepliesSeparately() {
        val target = InetAddress.getByName("192.0.2.10")
        assertEquals("192.168.1.1", IpTrace.parse(1, "From gateway (192.168.1.1) icmp_seq=1 Time to live exceeded", target).address)
        assertFalse(IpTrace.parse(2, "From 192.0.2.10 icmp_seq=1 Destination Host Unreachable", target).reached)
        assertTrue(IpTrace.parse(3, "64 bytes from host (192.0.2.10): icmp_seq=1 ttl=52 time=1.25 ms", target).reached)
        assertFalse(IpTrace.parse(1, "64 bytes from 192.0.2.11: icmp_seq=1 ttl=52 time=1.25 ms", target).reached)
        assertEquals("", IpTrace.parse(4, "Request timeout", target).address)
        val v6 = InetAddress.getByName("2001:db8::5")
        assertTrue(IpTrace.parse(5, "64 bytes from 2001:db8::5: icmp_seq=1 ttl=52 time=9 ms", v6).reached)
    }

    @Test fun readsOnlyTimeExceededOffenderAddresses() {
        for (v4 in listOf(true, false)) {
            val ip = InetAddress.getByName(if (v4) "192.0.2.1" else "2001:db8::1")
            val body = ByteArray(44)
            body[4] = if (v4) 2 else 3
            body[5] = if (v4) 11 else 3
            val family = if (v4) OsConstants.AF_INET else OsConstants.AF_INET6
            ByteBuffer.wrap(body).order(ByteOrder.nativeOrder()).putShort(16, family.toShort())
            ip.address.copyInto(body, if (v4) 20 else 24)
            assertEquals(ip.hostAddress, IpIcmpProbe.timeExceededSender(body, v4))
            body[6] = 1
            assertNull(IpIcmpProbe.timeExceededSender(body, v4))
            assertNull(IpIcmpProbe.timeExceededSender(ByteArray(15), v4))
        }
    }

    @Test fun gapsAndVpnAreDashedAndMapFitsAcrossDateLine() {
        val destination = IpInfo().apply { ip = "192.0.2.10" }
        val position = Pin(47.0, 178.0, "Моё положение", isLocation = true)
        val first = IpTools.Hop(1, "192.0.2.1", 2.0, false)
        val silent = IpTools.Hop(2, "", 0.0, false)
        val end = IpTools.Hop(3, destination.ip, 15.0, true)
        val scene = IpRouteGeometry.scene(position, destination, listOf(first, silent, end),
            mapOf(first.address to IpMapLocation(47.5, 179.0, ""),
                end.address to IpMapLocation(48.0, -178.0, "")), vpn = true)
        assertEquals(listOf(0, 1, 3), scene.nodes.map { it.ttl })
        assertTrue(scene.arcs.all { it.estimated })
        assertEquals(-178.0, scene.arcs.last().points.last().longitude, 0.00001)
        val viewport = IpRouteGeometry.viewport(scene, 400.0, 400.0)
        assertTrue(kotlin.math.abs(viewport.center.lon) > 175)
        assertTrue(viewport.zoom > 4)
    }

    @Test fun knownDestinationWithoutResponseIsAnEstimate() {
        val ip = IpInfo().apply { this.ip = "203.0.113.8" }
        val path = IpRouteGeometry.scene(Pin(45.0, 20.0, ""), ip, emptyList(),
            mapOf(ip.ip to IpMapLocation(48.0, 23.0, "")), vpn = false)
        assertEquals("Ф", path.nodes.last().pin.markerLabel)
        assertTrue(path.arcs.single().estimated)
    }
}
