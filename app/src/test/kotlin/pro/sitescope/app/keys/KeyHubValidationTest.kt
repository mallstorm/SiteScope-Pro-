package pro.sitescope.app.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyHubValidationTest {
    @Test fun tcpAndTlsNeverMakeAVpnKeyAlive() {
        val key = KeyItem("vless://fake@host.example:443", "test", "vless", "host.example", 443, "fixture", 1L)
        key.ms = 1
        key.tls = 1
        assertFalse("TCP and TLS alone are not proof of usable VLESS credentials", key.alive)
        key.validation = XrayKeyValidator.VERIFIED
        key.validationMs = 245
        key.validationAt = System.currentTimeMillis()
        assertTrue(key.alive)
        assertEquals(245L, key.verifiedPing)
    }

    @Test fun failedOrExpiredValidationIsNotAlive() {
        val key = KeyItem("vmess://fixture", "test", "vmess", "host.example", 443, "fixture", 1L)
        key.ms = 2
        key.validation = XrayKeyValidator.FAILED
        key.validationAt = System.currentTimeMillis()
        assertFalse(key.alive)
        key.validation = XrayKeyValidator.VERIFIED
        key.validationAt = System.currentTimeMillis() - 7L * 60 * 60 * 1000
        assertFalse("VPN proof older than 6 hours must expire", key.alive)
    }

    @Test fun udpCandidatesAreNotSilentlyMarkedWorking() {
        val key = KeyItem("hysteria2://fixture", "test", "hysteria2", "host.example", 443, "fixture", 1L)
        key.ms = -3
        assertFalse(key.alive)
    }

    @Test fun socksProxyRequiresAnActualThroughProxyResponse() {
        val key = KeyItem("socks5://1.2.3.4:1080", "test", "socks", "1.2.3.4", 1080, "fixture", 1L)
        key.ms = -1
        assertFalse(key.alive)
        key.ms = 180
        assertTrue(key.alive)
        assertEquals(180L, key.verifiedPing)
    }
}
