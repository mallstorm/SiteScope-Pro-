package pro.sitescope.app

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class ExposureStoreTest {
    @Before @After fun reset() = TestStores.reset()
    private val app get() = RuntimeEnvironment.getApplication() as Application
    private fun run(id: String, time: Long = 1000) = JSONObject().put("id", id).put("time", time).put("host", "owned.example").put("ports", "443")
        .put("source", "Мобильная сеть").put("transport", "cellular").put("externalPoint", true).put("cancelled", false).put("error", "")
        .put("results", JSONArray().put(JSONObject().put("address", "1.1.1.1").put("port", 443).put("state", "open").put("latencyMs", 12)))
    @Test fun targetsAcceptOneHostButRejectUrlsCredentialsRangesAndTooManyPorts() {
        assertEquals("example.test", ExposureStore.host("EXAMPLE.TEST"))
        assertEquals("192.168.1.10", ExposureStore.host("192.168.1.10"))
        assertTrue(ExposureStore.host("[::1]").contains(':'))
        for (invalid in listOf("https://example.test/", "u:p@example.test", "192.168.1.0/24", "192.168.1.1-192.168.1.9", "a.test,b.test", "*.example.test", "a.test/path")) assertThrows(Exception::class.java) { ExposureStore.host(invalid) }
        val target = ExposureStore.Target().apply { host = "owned.example"; ports = "1-65" }
        assertThrows(Exception::class.java) { ExposureStore.save(app, target) }; assertTrue(ExposureStore.targets(app).isEmpty())
    }
    @Test fun publicClassificationExcludesPrivateCarrierReservedAndDocumentationAddresses() {
        for (ip in listOf("127.0.0.1", "0.0.0.0", "10.1.2.3", "172.16.0.1", "192.168.1.1", "100.64.1.1", "169.254.1.1", "192.0.2.1", "198.51.100.1", "203.0.113.1", "224.0.0.1", "255.255.255.255", "::1", "::", "fc00::1", "fe80::1", "2001:db8::1", "3fff::1")) assertFalse(ip, ExposureStore.publicAddress(ip))
        for (ip in listOf("1.1.1.1", "8.8.8.8", "192.2.1.1", "198.51.1.1", "2606:4700:4700::1111")) assertTrue(ip, ExposureStore.publicAddress(ip))
        assertFalse(ExposureStore.publicAddress("owned.example"))
    }
    @Test fun deletingTargetKeepsMeasurementsAndHistoryIsBoundedAndClearable() {
        val target = ExposureStore.Target().apply { host = "owned.example"; ports = "443"; name = "Свой сервер" }
        ExposureStore.save(app, target); assertEquals("Свой сервер", ExposureStore.targets(app).single().name)
        for (i in 0..101) ExposureStore.saveRun(app, run("run-$i", i.toLong()))
        assertEquals(100, ExposureStore.runs(app).size)
        ExposureStore.remove(app, target.id); assertTrue(ExposureStore.targets(app).isEmpty()); assertEquals(100, ExposureStore.runs(app).size)
        ExposureStore.clearRuns(app); assertTrue(ExposureStore.runs(app).isEmpty())
    }
    @Test fun backupRestoresTargetsMeasurementsAndRejectsInconsistentPointAtomically() {
        ExposureStore.save(app, ExposureStore.Target().apply { host = "owned.example"; ports = "443" })
        ExposureStore.saveRun(app, run("one")); val backup = AppDatabase.get(app).exportPortable()
        ExposureStore.clearRuns(app); AppDatabase.get(app).restorePortable(backup)
        assertEquals(1, ExposureStore.targets(app).size); assertEquals("one", ExposureStore.runs(app).single().getString("id"))
        val broken = JSONObject(backup.toString()); broken.getJSONObject("exposure").getJSONArray("exposure_runs").getJSONObject(0).put("transport", "wifi")
        assertThrows(Exception::class.java) { AppDatabase.get(app).restorePortable(broken) }; assertEquals(1, ExposureStore.runs(app).size)
        val old = JSONObject(backup.toString()).apply { remove("exposure") }; AppDatabase.get(app).restorePortable(old)
        assertTrue(ExposureStore.targets(app).isEmpty()); assertTrue(ExposureStore.runs(app).isEmpty())
    }
    @Test fun sqliteFourUpgradePreservesCardsAndAddsOwnServiceTables() {
        DeviceRegistryStore.recordBatch(app, listOf(DeviceRegistryStore.scan(Models.PortResult().apply { ip = "192.168.1.10"; port = 443; open = true }, NetworkScope("home", "Дом", true), System.currentTimeMillis())))
        val db = AppDatabase.get(app).write(); db.execSQL("DROP TABLE exposure_targets"); db.execSQL("DROP TABLE exposure_runs"); db.version = 4
        TestStores.reset(); assertEquals(7, AppDatabase.get(app).read().version); assertEquals(1, DeviceRegistryStore.count(app)); assertTrue(ExposureStore.targets(app).isEmpty())
    }
}
