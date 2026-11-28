package pro.sitescope.app

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pro.sitescope.app.keys.KeyExport
import pro.sitescope.app.keys.KeyItem
import pro.sitescope.app.keys.KeyStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CatalogueProxyTest {
    @Test fun socksAndEliteHttpUseTheSameRuntimeAsSavedProfiles() {
        val socks = KeyItem("socks5://user:p%40ss@[2001:db8::1]:1080", "SOCKS", "socks", "2001:db8::1", 1080, "test", 1)
        socks.ms = 50
        assertFalse(KeyExport.canConnect(socks))
        socks.https = 1
        assertTrue(KeyExport.canConnect(socks))
        val outbound = XrayProfileParser.parse(KeyExport.profileLink(socks)).outbound
        val server = outbound.getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
        assertEquals("socks", outbound.getString("protocol"))
        assertEquals("2001:db8::1", server.getString("address"))
        assertEquals("p@ss", server.getJSONArray("users").getJSONObject(0).getString("pass"))

        val elite = KeyItem("http://192.0.2.4:8080", "Proxy", "http", "192.0.2.4", 8080, "test", 1)
        elite.ms = 80
        elite.anon = 3
        assertFalse(KeyExport.canConnect(elite))
        elite.https = 1
        assertTrue(KeyExport.canConnect(elite))
        assertEquals("http", XrayProfileParser.parse(KeyExport.profileLink(elite)).outbound.getString("protocol"))
    }

    @Test fun base64SocksCredentialsWithSlashMatchThePlainLink() {
        val item = KeyItem("socks://dXNlcjphYmM/@192.0.2.2:1080#Found", "Found", "socks", "192.0.2.2", 1080, "test", 1)
        val imported = XrayProfileParser.parse(KeyExport.profileLink(item))
        val manual = XrayProfileParser.parse("socks5://user:abc%3F@192.0.2.2:1080#Manual")
        assertEquals(manual.endpoint, imported.endpoint)
        assertEquals(manual.outbound.toString(), imported.outbound.toString())
    }

    @Test fun legacyHttpGeographyIsClearedUntilAnHttpsExitIsKnown() {
        val app = RuntimeEnvironment.getApplication()
        val legacy = KeyItem("http://192.0.2.1:80", "", "http", "192.0.2.1", 80, "test", 1).apply {
            ms = 90; country = "DE"; anon = 3; https = 2
        }
        val verified = KeyItem("socks5://192.0.2.2:1080", "", "socks", "192.0.2.2", 1080, "test", 2).apply {
            ms = 80; country = "NL"; exitIp = "198.51.100.1"; https = 1
        }
        try {
            KeyStore.save(app, listOf(legacy, verified))
            val records = KeyStore.load(app).associateBy { it.host }
            val old = records.getValue(legacy.host)
            assertEquals("", old.country)
            assertEquals(0, old.anon)
            assertFalse(KeyExport.canConnect(old))
            val usable = records.getValue(verified.host)
            assertEquals("NL", usable.country)
            assertEquals("198.51.100.1", usable.exitIp)
            assertTrue(KeyExport.canConnect(usable))
        } finally { KeyStore.clear(app) }
    }

    @Test fun unsupportedKeysRemainExportable() {
        val item = KeyItem("hysteria2://secret@192.0.2.4:443", "Hysteria2", "hysteria2", "192.0.2.4", 443, "test", 1)
        assertNull(KeyExport.profileLink(item))
        assertFalse(KeyExport.canConnect(item))
        assertTrue(KeyExport.text(listOf(item)).startsWith(item.link))
    }
}
