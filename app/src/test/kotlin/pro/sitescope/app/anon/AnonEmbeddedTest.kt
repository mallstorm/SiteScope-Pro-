package pro.sitescope.app.anon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxySpecTest {
    @Test fun parsesCommonForms() {
        assertEquals(ProxySpec("127.0.0.1", 1080), ProxySpec.parse("127.0.0.1:1080"))
        assertEquals(ProxySpec("10.0.0.2", 9050), ProxySpec.parse(" socks5://10.0.0.2:9050/ "))
        assertEquals(ProxySpec("::1", 1080), ProxySpec.parse("[::1]:1080"))
        assertEquals("socks://127.0.0.1:1080", ProxySpec("127.0.0.1", 1080).socksUrl)
    }

    @Test fun rejectsBroken() {
        assertNull(ProxySpec.parse(""))
        assertNull(ProxySpec.parse("host"))
        assertNull(ProxySpec.parse("host:0"))
        assertNull(ProxySpec.parse("host:70000"))
        assertNull(ProxySpec.parse("ho st:80"))
        assertNull(ProxySpec.parse(":80"))
    }

    @Test fun transportKeys() {
        assertEquals(AnonTransport.DPI, AnonTransport.from("dpi"))
        assertEquals(AnonTransport.AUTO, AnonTransport.from("что-то"))
        assertEquals(AnonTransport.AUTO, AnonTransport.from(null))
    }
}

class AnonDiagnosticsTest {
    @Test fun tlsEofIsBlockingAndBypassHelps() {
        val d = AnonDiagnostics.tor("TLS_ERROR: unexpected eof while reading; SSL state error in HANDSHAKE")
        assertEquals(AnonFailure.TLS_BLOCKED, d.kind)
        assertTrue(d.bypassHelps)
    }

    @Test fun directoryAndClock() {
        assertEquals(AnonFailure.DIRECTORY, AnonDiagnostics.tor("Unable to download consensus from directory server").kind)
        val clock = AnonDiagnostics.tor("Your system clock is wrong")
        assertEquals(AnonFailure.CLOCK, clock.kind)
        assertFalse(clock.bypassHelps)
    }

    @Test fun benignHandshakeIsNotBlocking() {
        val d = AnonDiagnostics.tor("Starting circuit handshake with relay")
        assertEquals(AnonFailure.UNKNOWN, d.kind)
        assertFalse(d.bypassHelps)
    }

    @Test fun timeoutKeepsTlsDiagnosis() {
        val tls = AnonDiagnostics.tor("SSL state error in HANDSHAKE")
        val d = AnonDiagnostics.torTimeout(10, tls)
        assertEquals(AnonFailure.TLS_BLOCKED, d.kind)
        assertTrue(d.message.contains("10%"))
        assertEquals(AnonFailure.TIMEOUT, AnonDiagnostics.torTimeout(40, null).kind)
    }

    @Test fun sanitizeHidesSecrets() {
        val s = AnonDiagnostics.sanitize("auth cookie=abcdef token=xyz ok")
        assertFalse(s.contains("abcdef")); assertFalse(s.contains("xyz")); assertTrue(s.contains("<hidden>"))
    }

    @Test fun i2pReseedFailureDetected() {
        val log = """
            error: Reseed: SSL handshake failed: handshake failure
            error: Reseed: Couldn't connect to reseed.i2pgit.org
            warn: Reseed: Failed to reseed from https://reseed.diva.exchange
        """.trimIndent()
        val d = AnonDiagnostics.i2p(log)
        assertEquals(AnonFailure.RESEED, d.kind)
        assertTrue(d.bypassHelps)
        assertEquals(AnonFailure.NONE, AnonDiagnostics.i2p("info: Tunnels: Inbound tunnel 1 has been created").kind)
        assertEquals(AnonFailure.RESEED, AnonDiagnostics.i2p("NetDbReq: No known routers, reseed seems to be totally failed").kind)
    }
}

class AnonTransportPlannerTest {
    private val dpi = ProxySpec("127.0.0.1", 1081)
    private val custom = ProxySpec("10.0.0.2", 1080)

    @Test fun autoStartsDirect() {
        val p = AnonTransportPlanner.plan(AnonTransport.AUTO, null, null)
        assertEquals(AnonTransport.DIRECT, p.actual); assertNull(p.proxy); assertNull(p.error)
    }

    @Test fun dpiNeedsEngine() {
        assertEquals(dpi, AnonTransportPlanner.plan(AnonTransport.DPI, dpi, null).proxy)
        assertNotNull(AnonTransportPlanner.plan(AnonTransport.DPI, null, null).error)
    }

    @Test fun proxyNeedsAddress() {
        assertEquals(custom, AnonTransportPlanner.plan(AnonTransport.PROXY, dpi, custom).proxy)
        assertNotNull(AnonTransportPlanner.plan(AnonTransport.PROXY, dpi, null).error)
    }

    @Test fun fallbackOnlyAutoDirectToDpi() {
        assertEquals(AnonTransport.DPI, AnonTransportPlanner.fallback(AnonTransport.AUTO, AnonTransport.DIRECT, true))
        assertNull(AnonTransportPlanner.fallback(AnonTransport.AUTO, AnonTransport.DIRECT, false))
        assertNull(AnonTransportPlanner.fallback(AnonTransport.AUTO, AnonTransport.DPI, true))
        assertNull(AnonTransportPlanner.fallback(AnonTransport.DIRECT, AnonTransport.DIRECT, true))
        assertNull(AnonTransportPlanner.fallback(AnonTransport.PROXY, AnonTransport.PROXY, true))
    }

    @Test fun torStuckThreshold() {
        assertFalse(AnonTransportPlanner.torStuck(10, 30_000))
        assertTrue(AnonTransportPlanner.torStuck(10, 45_000))
        assertFalse(AnonTransportPlanner.torStuck(60, 120_000))
    }
}

class TorConfTest {
    @Test fun progressParsing() {
        assertEquals(45, TorConf.progress("Nov 13 [notice] Bootstrapped 45% (loading_status): Loading"))
        assertEquals(100, TorConf.progress("Bootstrapped 100% (done): Done"))
        assertNull(TorConf.progress("Opening Socks listener"))
    }

    @Test fun torrcBasics() {
        val t = TorConf.torrc("/data dir/d", "/c", 19050, null, emptyList())
        assertTrue(t.contains("DataDirectory \"/data dir/d\""))
        assertTrue(t.contains("SocksPort 127.0.0.1:19050 IsolateSOCKSAuth"))
        assertTrue(t.contains("ClientOnly 1"))
        assertFalse(t.contains("Socks5Proxy")); assertFalse(t.contains("UseBridges"))
        assertFalse(t.contains("ControlPort"))
    }

    @Test fun torrcWithProxyAndBridges() {
        val t = TorConf.torrc("/d", "/c", 19051, ProxySpec("127.0.0.1", 1080), listOf("Bridge 1.2.3.4:443 " + "A".repeat(40)))
        assertTrue(t.contains("Socks5Proxy 127.0.0.1:1080"))
        assertTrue(t.contains("UseBridges 1"))
        assertTrue(t.contains("Bridge 1.2.3.4:443"))
    }

    @Test fun bridgesFilterUnsupportedTransports() {
        val fp = "B".repeat(40)
        val b = TorConf.parseBridges("""
            # comment
            Bridge 1.2.3.4:443 $fp
            5.6.7.8:9001
            obfs4 9.9.9.9:443 $fp cert=abc iat-mode=0
            webtunnel [2001:db8::1]:443 $fp url=https://x
            [2001:db8::2]:443 $fp
            garbage line
        """.trimIndent())
        assertEquals(listOf("Bridge 1.2.3.4:443 $fp", "Bridge 5.6.7.8:9001", "Bridge [2001:db8::2]:443 $fp"), b.lines)
        assertEquals(3, b.skipped.size)
    }
}

class I2pConfTest {
    @Test fun directConfig() {
        val c = I2pConf.build(I2pOptions("/data/i2p"))
        assertTrue(c.contains("logfile=/data/i2p/i2pd.log"))
        assertTrue(c.contains("notransit=true"))
        assertTrue(c.contains("[httpproxy]\nenabled=true\naddress=127.0.0.1\nport=14444"))
        assertTrue(c.contains("[socksproxy]\nenabled=true\naddress=127.0.0.1\nport=14447"))
        assertTrue(c.contains("[ssu2]\nenabled=true"))
        assertFalse(c.contains("proxy=socks"))
        assertFalse(c.contains("\nfile="))
        assertTrue(c.contains("[http]\nenabled=false"))
    }

    @Test fun reseedViaDpiProxyKeepsSsu2() {
        val c = I2pConf.build(I2pOptions("/d", reseedProxy = "socks://127.0.0.1:1081"))
        assertTrue(c.contains("[reseed]\nverify=true\nproxy=socks://127.0.0.1:1081"))
        assertTrue(c.contains("[ssu2]\nenabled=true"))
        assertFalse(c.contains("ntcpproxy"))
    }

    @Test fun fullProxyDisablesSsu2AndSetsReseedFile() {
        val c = I2pConf.build(I2pOptions("/d", ntcpProxy = "socks://10.0.0.2:1080", reseedFile = "/x/reseed.su3"))
        assertTrue(c.contains("ntcpproxy=socks://10.0.0.2:1080"))
        assertTrue(c.contains("[ntcp2]\nenabled=true\npublished=false\nproxy=socks://10.0.0.2:1080"))
        assertTrue(c.contains("[ssu2]\nenabled=false"))
        assertTrue(c.contains("proxy=socks://10.0.0.2:1080\nfile=/x/reseed.su3"))
    }

    @Test fun logReadiness() {
        val none = I2pLog.parse("starting")
        assertFalse(none.ready)
        val half = I2pLog.parse("Tunnels: Inbound tunnel 123 has been created")
        assertEquals(1, half.inbound); assertFalse(half.ready)
        val ready = I2pLog.parse("Tunnels: Inbound tunnel 1 has been created\nTunnels: Outbound tunnel 2 has been created\nTunnels: Outbound tunnel 3 has been created")
        assertTrue(ready.ready); assertEquals(2, ready.outbound)
        assertTrue(I2pLog.parse("warn: No peers available").peersUnavailable)
    }
}
