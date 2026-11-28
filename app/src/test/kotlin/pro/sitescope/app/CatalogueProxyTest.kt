package pro.sitescope.app

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pro.sitescope.app.keys.KeyExport
import pro.sitescope.app.keys.KeyItem

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class CatalogueProxyTest {
    @Test fun socksAndEliteHttpUseTheSameRuntimeAsSavedProfiles() {
        val socks = KeyItem("socks5://user:p%40ss@[2001:db8::1]:1080", "SOCKS", "socks", "2001:db8::1", 1080, "test", 1)
        socks.ms = 50
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

    @Test fun unsupportedKeysRemainExportable() {
        val item = KeyItem("hysteria2://secret@192.0.2.4:443", "Hysteria2", "hysteria2", "192.0.2.4", 443, "test", 1)
        assertNull(KeyExport.profileLink(item))
        assertFalse(KeyExport.canConnect(item))
        assertTrue(KeyExport.text(listOf(item)).startsWith(item.link))
    }
}
