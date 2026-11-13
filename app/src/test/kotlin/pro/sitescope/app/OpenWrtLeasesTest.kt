package pro.sitescope.app

import android.app.Application
import android.os.CancellationSignal
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class OpenWrtLeasesTest {
    @Before @After fun reset() = TestStores.reset()
    @Test fun luciProjectsOnlyIpv4LeaseFieldsAndPreservesUnknownAvailability() {
        val rows = OpenWrtLeases.luci(JSONObject("""{"dhcp_leases":[{"hostname":"<script>x</script>","ipaddr":"192.168.1.2","mac":"021234567890","password":"SECRET","expires":500}],"dhcp6_leases":[{"hostname":"ignored"}]}"""))
        assertEquals(1, rows.length()); val row = rows.getJSONObject(0); assertEquals("192.168.1.2", row.getString("address")); assertEquals(OpenWrtLeases.LUCI, row.getString("lease_source")); assertFalse(row.has("status")); assertFalse(rows.toString().contains("SECRET")); assertFalse(rows.toString().contains("ignored"))
    }
    @Test fun odhcpdAcceptsNewAndOldAddressFieldsAndEmptyDeviceMap() {
        assertEquals(0, OpenWrtLeases.odhcpd(JSONObject().put("device", JSONObject())).length())
        val rows = OpenWrtLeases.odhcpd(JSONObject("""{"device":{"lan":{"leases":[{"address":"192.168.1.2"},{"ipaddr":"192.168.1.3"}]}}}""")); assertEquals("192.168.1.3", rows.getJSONObject(1).getString("address"))
    }
    @Test fun malformedContainersAndNonScalarFieldsAreRejectedBeforeCommittingRows() {
        for (raw in listOf("{}", "{\"dhcp_leases\":null}", "{\"dhcp_leases\":[0]}", "{\"dhcp_leases\":[{\"mac\":{\"password\":\"SECRET\"}}]}")) assertThrows(JSONException::class.java) { OpenWrtLeases.luci(JSONObject(raw)) }
        assertThrows(JSONException::class.java) { OpenWrtLeases.odhcpd(JSONObject("{\"device\":{\"lan\":{}}}")) }
    }
    private fun snapshot(id: String, source: String, rows: JSONArray) = JSONObject().put("id", id).put("time", if (id == "a") 1000 else 2000).put("adapter", "openwrt").put("host", "192.168.1.1").put("source", "OpenWrt · Дом").put("dhcpSource", source).put("partial", false).put("rows", rows).also { RouterComparison.attach(it, "https://192.168.1.1", NetworkScope("home", "Дом", true)) }
    @Test fun changingDhcpProviderCannotClaimThatClientsAppearedOrDisappeared() {
        val rows = OpenWrtLeases.luci(JSONObject("""{"dhcp_leases":[{"mac":"021234567890","ipaddr":"192.168.1.2"}]}"""))
        val result = RouterComparison.compare(snapshot("a", OpenWrtLeases.LUCI, rows), snapshot("b", OpenWrtLeases.ODHCPD, JSONArray())); assertTrue(result.partial); assertTrue(result.changes.isEmpty())
    }
    @Test fun provenanceSurvivesBackupAndCsvJsonHtmlExports() {
        val app = RuntimeEnvironment.getApplication() as Application
        val rows = OpenWrtLeases.luci(JSONObject("""{"dhcp_leases":[{"hostname":"<script>x</script>","ipaddr":"192.168.1.2","mac":"021234567890"}]}""")); RouterStore.snapshot(app, snapshot("a", OpenWrtLeases.LUCI, rows))
        val backup = AppDatabase.get(app).exportPortable(); AppDatabase.get(app).restorePortable(backup); assertEquals(OpenWrtLeases.LUCI, RouterStore.snapshots(app).single().getString("dhcpSource"))
        for (format in listOf("csv", "json", "html")) { val output = ByteArrayOutputStream(); ReportExporter.write(app, output, ReportExporter.Options(listOf("routers"), ReportExporter.FIELDS, format, 0), CancellationSignal()); val text = output.toString("UTF-8"); assertTrue(text.contains(OpenWrtLeases.LUCI)); if (format == "html") assertFalse(text.contains("<script>")) }
    }
}
