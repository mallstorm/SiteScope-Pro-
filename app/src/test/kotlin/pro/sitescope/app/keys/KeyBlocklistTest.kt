package pro.sitescope.app.keys

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class KeyBlocklistTest {
    @Test fun displayNameDoesNotBypassBlacklist() {
        val prefix = "vless://aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa@test.example:443?security=reality&type=tcp"
        assertEquals(
            KeyBlocklistStore.fingerprint(prefix + "#old"),
            KeyBlocklistStore.fingerprint(prefix + "#renamed")
        )
    }

    @Test fun equivalentQueryOrderDoesNotBypassBlacklist() {
        val first = "vless://aaaa@test.example:443?security=reality&sni=site.example&type=grpc#old"
        val same = "vless://aaaa@TEST.EXAMPLE:443?type=grpc&sni=site.example&security=reality#new"
        assertEquals(KeyBlocklistStore.fingerprint(first), KeyBlocklistStore.fingerprint(same))
    }

    @Test fun updatedCredentialsOnSameHostAreNewConfig() {
        val old = "vless://aaaa@test.example:443?security=reality"
        val fresh = "vless://bbbb@test.example:443?security=reality"
        assertNotEquals(KeyBlocklistStore.fingerprint(old), KeyBlocklistStore.fingerprint(fresh))
    }

    @Test fun changedSniIsNewConfiguration() {
        val old = "trojan://secret@test.example:443?sni=old.example"
        val fresh = "trojan://secret@test.example:443?sni=new.example"
        assertNotEquals(KeyBlocklistStore.fingerprint(old), KeyBlocklistStore.fingerprint(fresh))
    }
}
