package pro.sitescope.app

import android.app.Application
import android.os.CancellationSignal
import org.json.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class RouterLeaseStoreTest {
    @Before @After fun reset() = TestStores.reset()
    private val app get() = RuntimeEnvironment.getApplication() as Application
    private val mac = "02:12:34:56:78:90"
    private fun card(ip: String, port: Int = 443): String {
        val scope = NetworkScope("home", "Дом", true)
        DeviceRegistryStore.recordBatch(app, listOf(DeviceRegistryStore.scan(Models.PortResult().apply { this.ip = ip; this.port = port; open = true }, scope, 1000)))
        return DeviceRegistryStore.list(app, ip, 10).first().id
    }
    private fun snapshot(id: String = "one", network: String = "home", address: String = "192.168.1.2"): JSONObject {
        val s = JSONObject().put("id", id).put("time", 2000).put("adapter", "routeros").put("host", "192.168.1.1").put("name", "Дом").put("source", "REST · Дом").put("partial", false).put("rows", JSONArray().put(JSONObject().put("kind", "lease").put("mac", mac).put("address", address).put("name", "phone")))
        RouterComparison.attach(s, "https://192.168.1.1", NetworkScope(network, "Дом", true)); RouterStore.snapshot(app, s); return s
    }
    @Test fun linkDoesNotCreateServiceMeasurementsOrAlterCardCountsAndCanBeUndone() {
        val id = card("192.168.1.2"); val s = snapshot(); val count = DeviceRegistryStore.get(app, id, 100).memberCount
        val historyBefore = AppDatabase.get(app).exportPortable().getJSONObject("deviceRegistry").getJSONObject("history").toString()
        val link = RouterLeaseStore.link(app, "one", mac, id, "")
        assertEquals(id, link.getString("cardId")); assertEquals(1, RouterLeaseStore.forOrigin(app, RouterComparison.origin(s)).size); assertEquals(count, DeviceRegistryStore.get(app, id, 100).memberCount)
        assertEquals(historyBefore, AppDatabase.get(app).exportPortable().getJSONObject("deviceRegistry").getJSONObject("history").toString())
        RouterLeaseStore.unlink(app, link.getString("id"), link.getString("revision")); assertTrue(RouterLeaseStore.forCard(app, id).isEmpty()); assertEquals(1, DeviceRegistryStore.count(app))
    }
    @Test fun reusedMacInOtherNetworkHasIndependentLinkAndReassignmentRequiresFreshRevision() {
        val a = card("192.168.1.2"); val b = card("192.168.1.3"); snapshot(); snapshot("other", "other")
        val first = RouterLeaseStore.link(app, "one", mac, a, ""); RouterLeaseStore.link(app, "other", mac, b, "")
        assertThrows(Exception::class.java) { RouterLeaseStore.link(app, "one", mac, b, "") }
        val moved = RouterLeaseStore.link(app, "one", mac, b, first.getString("revision")); assertEquals(2, RouterLeaseStore.forCard(app, b).size)
        assertThrows(Exception::class.java) { RouterLeaseStore.unlink(app, moved.getString("id"), first.getString("revision")) }
    }
    @Test fun deletingSnapshotsKeepsEvidenceButMissingSnapshotOrCardCannotBeLinked() {
        val id = card("192.168.1.2"); snapshot(); val linked = RouterLeaseStore.link(app, "one", mac, id, ""); RouterStore.clearSnapshots(app)
        assertEquals("192.168.1.2", RouterLeaseStore.forCard(app, id).single().getString("address")); assertEquals(2000, linked.getLong("observedAt").toInt())
        assertThrows(Exception::class.java) { RouterLeaseStore.link(app, "one", mac, id, linked.getString("revision")) }
        snapshot(); assertThrows(Exception::class.java) { RouterLeaseStore.link(app, "one", mac, "deleted-card", linked.getString("revision")) }
    }
    @Test fun cardMergeMovesLinksAndSplitLeavesThemOnOriginalCard() {
        val a = card("192.168.1.2"); val b = card("192.168.1.3"); snapshot(); RouterLeaseStore.link(app, "one", mac, b, "")
        DeviceRegistryStore.merge(app, a, b); assertEquals(a, RouterLeaseStore.forCard(app, a).single().getString("cardId"))
        val observations = DeviceRegistryStore.get(app, a, 100).observations
        val split = DeviceRegistryStore.split(app, a, listOf(observations.first().id), "Отделённая")
        assertEquals(1, RouterLeaseStore.forCard(app, a).size); assertTrue(RouterLeaseStore.forCard(app, split).isEmpty())
    }
    @Test fun backupRoundTripAndInvalidCrossReferenceIsRejectedBeforeDeletion() {
        val id = card("192.168.1.2"); snapshot(); RouterLeaseStore.link(app, "one", mac, id, "")
        val backup = AppDatabase.get(app).exportPortable(); AppDatabase.get(app).restorePortable(backup); assertEquals(1, RouterLeaseStore.forCard(app, id).size)
        for (broken in listOf(JSONObject(backup.toString()).apply { getJSONArray("routerLeaseLinks").getJSONObject(0).put("cardId", "missing") }, JSONObject(backup.toString()).apply { getJSONArray("routerLeaseLinks").getJSONObject(0).put("origin", "other") }, JSONObject(backup.toString()).apply { getJSONArray("routerLeaseLinks").getJSONObject(0).put("password", "SECRET") })) {
            assertThrows(Exception::class.java) { AppDatabase.get(app).restorePortable(broken) }; assertEquals(1, RouterLeaseStore.forCard(app, id).size)
        }
        val old = JSONObject(backup.toString()).apply { remove("routerLeaseLinks") }; AppDatabase.get(app).restorePortable(old); assertTrue(RouterLeaseStore.forCard(app, id).isEmpty())
    }
    @Test fun unscopedOrAmbiguousSnapshotDoesNotPermitLinking() {
        val id = card("192.168.1.2"); val s = snapshot(); val db = AppDatabase.get(app).write()
        s.remove("scope"); s.remove("endpoint"); db.execSQL("UPDATE router_snapshots SET json=? WHERE id='one'", arrayOf(s.toString())); assertThrows(Exception::class.java) { RouterLeaseStore.link(app, "one", mac, id, "") }
        s.put("rows", JSONArray().put(JSONObject().put("kind", "lease").put("mac", mac)).put(JSONObject().put("kind", "lease").put("mac", mac))); RouterComparison.attach(s, "https://192.168.1.1", NetworkScope("home", "Дом", true)); db.execSQL("UPDATE router_snapshots SET json=? WHERE id='one'", arrayOf(s.toString())); assertThrows(Exception::class.java) { RouterLeaseStore.link(app, "one", mac, id, "") }
    }
    @Test fun v6MigrationPreservesCardsAndSnapshotsAndAddsLinkTable() {
        val id = card("192.168.1.2"); snapshot(); val db = AppDatabase.get(app).write(); db.execSQL("DROP TABLE router_lease_links"); db.version = 6; TestStores.reset()
        assertEquals(7, AppDatabase.get(app).read().version); assertEquals(1, RouterStore.snapshots(app).size); assertNotNull(DeviceRegistryStore.get(app, id, 1)); assertTrue(RouterLeaseStore.forCard(app, id).isEmpty())
    }
    @Test fun reportsExportLinkConfirmationAndObservationTimeButNoRawCredentials() {
        val id = card("192.168.1.2"); snapshot(); RouterLeaseStore.link(app, "one", mac, id, "")
        app.getSharedPreferences("sitescope_secure_v1", 0).edit().putString("router_credentials", "SECRET-NEVER-EXPORT").commit()
        for (format in listOf("csv", "json", "html")) {
            val out = ByteArrayOutputStream(); val result = ReportExporter.write(app, out, ReportExporter.Options(listOf("router_links"), ReportExporter.FIELDS, format, 0), CancellationSignal()); val text = out.toString("UTF-8")
            assertEquals(1, result.rows); assertTrue(text.contains(id)); assertTrue(text.contains("2000")); assertTrue(text.contains("доступность не проверялась")); assertFalse(text.contains("SECRET-NEVER-EXPORT"))
        }
    }
}
