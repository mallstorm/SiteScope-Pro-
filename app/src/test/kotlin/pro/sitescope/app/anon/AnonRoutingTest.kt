package pro.sitescope.app.anon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnonRoutingTest {
    @Test fun hostsAreRecognized() {
        assertTrue(AnonRouting.isOnion("abcdefghij.onion"))
        assertTrue(AnonRouting.isOnion("sub.ABC.onion."))
        assertFalse(AnonRouting.isOnion("onion.example.com"))
        assertTrue(AnonRouting.isI2p("stats.i2p"))
        assertFalse(AnonRouting.isI2p("i2p.example.com"))
    }

    @Test fun hostOfHandlesTypedInput() {
        assertEquals("abc.onion", AnonRouting.hostOf("abc.onion"))
        assertEquals("abc.onion", AnonRouting.hostOf("http://abc.onion:8080/path?q=1"))
        assertEquals("stats.i2p", AnonRouting.hostOf("  stats.i2p/  "))
        assertEquals("192.168.1.1", AnonRouting.hostOf("http://admin:pw@192.168.1.1/"))
        assertEquals("", AnonRouting.hostOf(""))
    }

    @Test fun routing() {
        assertEquals(AnonRouting.Route.TOR, AnonRouting.routeOf("abc.onion"))
        assertEquals(AnonRouting.Route.I2P, AnonRouting.routeOf("stats.i2p"))
        assertEquals(AnonRouting.Route.DIRECT, AnonRouting.routeOf("example.com"))
        assertEquals(AnonRouting.Route.TOR, AnonRouting.routeOf("example.com", torForAll = true))
        assertEquals(AnonRouting.Route.DIRECT, AnonRouting.routeOf("192.168.1.1", torForAll = true))
        assertEquals(AnonRouting.Route.DIRECT, AnonRouting.routeOf("router", torForAll = true))
        assertEquals(AnonRouting.Route.I2P, AnonRouting.routeOf("stats.i2p", torForAll = true))
    }

    @Test fun localAddresses() {
        listOf("10.0.0.5", "172.16.3.1", "172.31.255.1", "192.168.0.1", "127.0.0.1", "169.254.1.1", "localhost", "nas.local", "::1", "fd00::1").forEach { assertTrue(it, AnonRouting.isLocalHost(it)) }
        listOf("8.8.8.8", "172.32.0.1", "example.com", "192.169.0.1").forEach { assertFalse(it, AnonRouting.isLocalHost(it)) }
    }

    @Test fun normalizeAddsScheme() {
        assertEquals("http://abc.onion", AnonRouting.normalize("abc.onion"))
        assertEquals("https://example.com/x", AnonRouting.normalize("https://example.com/x"))
        assertEquals("http://192.168.1.1", AnonRouting.normalize(" 192.168.1.1 "))
        assertEquals("about:blank", AnonRouting.normalize("about:blank"))
    }

    @Test fun proxyRequestParsing() {
        val get = ProxyRequest.parse("GET http://Abc.onion:8080/p?q=1 HTTP/1.1\r\nHost: abc.onion\r\n\r\n".toByteArray())!!
        assertEquals("abc.onion", get.host); assertEquals(8080, get.port); assertFalse(get.connect)
        val connect = ProxyRequest.parse("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n".toByteArray())!!
        assertEquals("example.com", connect.host); assertEquals(443, connect.port); assertTrue(connect.connect)
        assertNull(ProxyRequest.parse("GET /relative HTTP/1.1\r\n\r\n".toByteArray()))
        assertNull(ProxyRequest.parse("garbage\r\n\r\n".toByteArray()))
        assertNull(ProxyRequest.parse("CONNECT nohostport HTTP/1.1\r\n\r\n".toByteArray()))
    }
}
