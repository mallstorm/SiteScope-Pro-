package pro.sitescope.app.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyQualityPolicyTest {
    @Test fun workingThresholdRejectsOneSecond() {
        assertTrue(KeyQualityPolicy.fastEnough(50))
        assertTrue(KeyQualityPolicy.fastEnough(600))
        assertTrue(KeyQualityPolicy.fastEnough(999))
        assertFalse(KeyQualityPolicy.fastEnough(1000))
        assertFalse(KeyQualityPolicy.fastEnough(1200))
        assertFalse(KeyQualityPolicy.fastEnough(-2))
    }

    @Test fun latencyCategoriesMatchThreeBands() {
        assertEquals("Быстрый", KeyQualityPolicy.className(200))
        assertEquals("Нормальный", KeyQualityPolicy.className(201))
        assertEquals("Нормальный", KeyQualityPolicy.className(600))
        assertEquals("Медленный", KeyQualityPolicy.className(601))
        assertEquals("Медленный", KeyQualityPolicy.className(999))
        assertEquals("Слишком медленный", KeyQualityPolicy.className(1000))
    }

    @Test fun workingStatusRequiresRealRoutedProof() {
        val key = KeyItem("vless://id@vpn.example:443", "test", "vless", "vpn.example", 443, "test", 0)
        key.ms = 80
        assertFalse(key.alive)
        key.validation = XrayKeyValidator.VERIFIED
        key.validationAt = System.currentTimeMillis()
        key.validationMs = 999
        assertTrue(key.alive)
        key.validationMs = 1000
        assertFalse(key.alive)
    }

    @Test fun duplicateFingerprintNormalizesLabelsAndQueryOrder() {
        val first = "vless://id@vpn.example:443?type=grpc&security=tls#first"
        val second = "vless://id@VPN.EXAMPLE:443?security=tls&type=grpc#second"
        assertEquals(KeyBlocklistStore.fingerprint(first), KeyBlocklistStore.fingerprint(second))
    }
}
