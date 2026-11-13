package pro.sitescope.app

import android.app.Application
import org.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class RouterComparisonTest {
    private fun row(kind: String, vararg values: Pair<String, String>) = JSONObject().put("kind", kind).apply { values.forEach { put(it.first, it.second) } }
    private fun snapshot(id: String, time: Long, vararg rows: JSONObject, network: String = "home", partial: Boolean = false, endpoint: String = "https://192.168.1.1", adapter: String = "routeros"): JSONObject = JSONObject().put("id", id).put("time", time).put("adapter", adapter).put("host", java.net.URI(endpoint).host).put("name", "Дом").put("source", "API · Дом").put("partial", partial).put("rows", JSONArray(rows.toList())).also { RouterComparison.attach(it, endpoint, NetworkScope(network, "Дом", true)) }
    private fun lease(mac: String = "02:12:34:56:78:90", ip: String = "192.168.1.2", name: String = "phone") = row("lease", "mac" to mac, "address" to ip, "name" to name)
    @Test fun changesAddressAndNameByMacInsteadOfTreatingReusedIpAsIdentity() {
        val before = snapshot("a", 1, lease())
        val after = snapshot("b", 2, lease("021234567890", "192.168.1.3", "new-name"), lease("02:12:34:56:78:91", "192.168.1.2"))
        val result = RouterComparison.compare(before, after)
        assertEquals(2, result.changes.count { it.type == "changed" }); assertEquals(1, result.changes.count { it.type == "added" }); assertEquals(0, result.changes.count { it.type == "removed" })
        assertTrue(result.changes.any { it.field == "address" && it.before == "192.168.1.2" && it.after == "192.168.1.3" })
    }
    @Test fun rejectsDifferentNetworksEndpointsAdaptersMissingScopesAndReversedTime() {
        val a = snapshot("a", 1, lease())
        for (b in listOf(snapshot("b", 2, lease(), network = "other"), snapshot("b", 2, lease(), endpoint = "https://192.168.1.1:8443"), snapshot("b", 2, lease(), adapter = "openwrt"), snapshot("b", 2, lease()).apply { remove("scope"); remove("endpoint") }, snapshot("b", 0, lease()), a)) assertThrows(IllegalArgumentException::class.java) { RouterComparison.compare(a, b) }
        val unknown = snapshot("b", 2, lease()).apply { getJSONObject("scope").put("reliable", false) }; assertThrows(IllegalArgumentException::class.java) { RouterComparison.compare(a, unknown) }
    }
    @Test fun partialMeasurementsNeverProduceAddedOrRemovedRecords() {
        val a = snapshot("a", 1, lease(), row("interface", "name" to "wan", "status" to "true"))
        val b = snapshot("b", 2, lease(ip = "192.168.1.3"), lease("02:12:34:56:78:91"), partial = true)
        val result = RouterComparison.compare(a, b); assertTrue(result.partial); assertEquals(listOf("changed"), result.changes.map { it.type })
        assertTrue(RouterComparison.compare(snapshot("c", 0, partial = true), a).changes.isEmpty())
        val unknown = snapshot("d", 0).apply { remove("partial") }; assertTrue(RouterComparison.compare(unknown, a).partial); assertTrue(RouterComparison.compare(unknown, a).changes.isEmpty())
    }
    @Test fun ambiguousMacAndInterfaceNamesAreExcludedWithoutGuessing() {
        val a = snapshot("a", 1, lease(), lease(ip = "192.168.1.3"), row("interface", "name" to "lan", "status" to "true"), row("interface", "name" to "lan", "status" to "false"), lease("", "192.168.1.4"))
        val result = RouterComparison.compare(a, snapshot("b", 2, lease(ip = "192.168.1.5")))
        assertEquals(2, result.ambiguous); assertEquals(1, result.unkeyed); assertTrue(result.changes.isEmpty())
        assertThrows(IllegalArgumentException::class.java) { RouterComparison.uniqueLease(a, "021234567890") }
    }
    @Test fun systemRowsCoalesceKnownFieldsAndIgnoreUptimeAndMissingMeasurements() {
        val a = snapshot("a", 1, row("system", "name" to "router"), row("system", "software" to "7.19", "uptime" to "1d", "model" to "hAP"))
        val b = snapshot("b", 2, row("system", "name" to "router"), row("system", "software" to "7.20", "uptime" to "2d"))
        val result = RouterComparison.compare(a, b); assertEquals(1, result.changes.size); assertEquals("software", result.changes.single().field)
        val conflict = snapshot("c", 3, row("system", "software" to "7.20"), row("system", "software" to "unknown")); assertEquals(1, RouterComparison.compare(b, conflict).ambiguous)
    }
    @Test fun normalizesOnlyValidIndividualMacAddresses() {
        for (valid in listOf("021234567890", "02:12:34:56:78:90", "02-12-34-56-78-90", "0212.3456.7890")) assertEquals("02:12:34:56:78:90", RouterComparison.mac(valid))
        for (invalid in listOf("00:00:00:00:00:00", "ff:ff:ff:ff:ff:ff", "01:00:5e:00:00:01", "02/12/34/56/78/90", "junk021234567890", "")) assertEquals("", RouterComparison.mac(invalid))
    }
    @Test fun scopeMetadataSurvivesValidationAndRejectsForeignHostsOrExtraFields() {
        val valid = snapshot("a", 1, lease()); RouterStore.validateSnapshot(valid); assertFalse(RouterComparison.origin(valid).isEmpty())
        val wrongHost = JSONObject(valid.toString()).put("host", "192.168.1.9"); assertThrows(Exception::class.java) { RouterStore.validateSnapshot(wrongHost) }
        val extra = JSONObject(valid.toString()).apply { getJSONObject("scope").put("password", "SECRET") }; assertThrows(Exception::class.java) { RouterStore.validateSnapshot(extra) }
    }
}
