package pro.sitescope.app

import android.app.Application
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
class DeviceHistoryTest {
    @Before @After fun reset() = TestStores.reset()
    private val app get() = RuntimeEnvironment.getApplication() as Application
    private val lan = NetworkScope("history:home", "Дом", true)
    private val base = System.currentTimeMillis() - 100_000
    private fun observation(time: Long, model: String = "", cert: String = "", port: Int = 443) = DeviceRegistryStore.scan(Models.PortResult().apply {
        ip = "192.168.1.10"; target = ip; this.port = port; open = true; service = "HTTPS"; confidence = "high"; tlsFingerprint = cert
        identity = DeviceIdentity().apply { manufacturer = "ASUS"; this.model = model; confidence = "medium" }
    }, lan, base + time)
    private fun record(o: DeviceRegistryStore.Observation) = DeviceRegistryStore.recordBatch(app, listOf(o))
    private fun history(card: String = "") = DeviceHistoryStore.list(app, card, 100)
    private fun finish(time: Long, ports: String = "443", targets: String = "192.168.1.10", complete: Boolean = true, error: String = "", cancelled: Boolean = false, scope: NetworkScope = lan) {
        val s = Models.ScanSession().apply { timestamp = base + time; durationMs = 100; targetExpression = targets; portExpression = ports; this.scope = scope; totalProbes = ScannerEngine.parseTargets(targets).size * ScannerEngine.parsePorts(ports).size; completedProbes = if (complete) totalProbes else 0; this.error = error; this.cancelled = cancelled }
        DeviceHistoryStore.scanFinished(AppDatabase.get(app).write(), s, scope, emptyList())
    }

    @Test fun recordsModelProtocolAndCertificateChangesWithoutDuplicatesOrSubjectGuessing() {
        record(observation(1000, "RT-AX58U", "a".repeat(64)))
        record(observation(2000, "RT-AX88U", "b".repeat(64)).apply { protocol = "TLS" })
        assertEquals(setOf("availability", "model", "certificate", "protocol"), history().map { it.field }.toSet())
        assertEquals("RT-AX58U", history().single { it.field == "model" }.before)
        assertEquals("b".repeat(64), history().single { it.field == "certificate" }.after)
        val size = history().size
        record(observation(2000, "RT-AX88U", "b".repeat(64)).apply { protocol = "TLS" })
        assertEquals(size, history().size)
        assertTrue(history().all { it.source.contains("Дом") && it.time >= base })
    }
    @Test fun missingHardwareOrTlsEvidenceDoesNotEraseKnownBaseline() {
        record(observation(1000, "First", "a".repeat(64)))
        record(observation(2000))
        record(observation(3000, "Second", "b".repeat(64)))
        assertEquals("First", history().single { it.field == "model" }.before)
        assertEquals("a".repeat(64), history().single { it.field == "certificate" }.before)
    }
    @Test fun absenceDuringCompleteScanIsUnconfirmedResponseAndRecoveryIsVisible() {
        record(observation(1000))
        finish(2000)
        assertTrue(history().first().after.contains("не подтверждён"))
        record(observation(3000))
        assertEquals("Сервис ответил", history().first().after)
        assertTrue(history().none { it.after.contains("выключен") || it.after.contains("закрыт") })
    }
    @Test fun cancellationErrorAndIncompleteRunsNeverProduceDisappearance() {
        record(observation(1000)); finish(2000, error = "Network lost")
        assertTrue(history().first().after.contains("ошибкой"))
        val n = history().size
        finish(3000, cancelled = true); finish(4000, complete = false)
        assertEquals(n, history().size)
    }
    @Test fun recoveredJobDoesNotClaimFreshCompleteCoverage() {
        record(observation(1000))
        val session = Models.ScanSession().apply { scope = lan; timestamp = base + 2000; totalProbes = 1; completedProbes = 1; targetExpression = "192.168.1.10"; portExpression = "443"; recovered = true }
        DeviceHistoryStore.scanFinished(AppDatabase.get(app).write(), Models.ScanSession.fromJson(session.toJson()), lan, emptyList())
        assertTrue(history().first().after.contains("не завершена"))
    }

    @Test fun unrelatedPortsNetworksAndUnresolvedDomainsCannotProduceAbsence() {
        record(observation(1000)); val count = history().size
        finish(2000, ports = "80"); finish(3000, targets = "192.168.1.11")
        finish(4000, scope = NetworkScope("other", "Офис", true)); finish(5000, targets = "test.invalid")
        assertEquals(count, history().size)
    }
    @Test fun olderOrRepeatedMeasurementCannotRewriteNewerHistory() {
        record(observation(1000, "First")); record(observation(3000, "Second")); record(observation(2000, "Old"))
        finish(1500)
        assertEquals(2, history().size); assertEquals("Second", history().single { it.field == "model" }.after)
    }
    @Test fun mergeSplitAndSyncKeepEventsWithTheirOriginalObservations() {
        val a = observation(1000, port = 443); val b = observation(1100, port = 8443); record(a); record(b)
        val cards = DeviceRegistryStore.list(app, "", 100); val first = cards.single { it.observations.single().id == a.id }.id
        val other = cards.single { it.id != first }.id
        DeviceRegistryStore.merge(app, first, other)
        assertEquals(2, history(first).size)
        val split = DeviceRegistryStore.split(app, first, listOf(b.id), "Separate")
        assertEquals(a.id, history(first).single().observationId); assertEquals(b.id, history(split).single().observationId)
        DeviceRegistryStore.sync(app)
        assertEquals(2, history().size)
    }
    @Test fun clearHistoryKeepsBaselineAndDoesNotRefillFromHistoricalSync() {
        record(observation(1000, "First")); DeviceHistoryStore.clear(app)
        DeviceRegistryStore.sync(app); record(observation(1000, "First")); record(observation(2000, "First"))
        assertTrue(history().isEmpty())
        record(observation(3000, "Second")); assertEquals("First", history().single().before)
    }
    @Test fun retentionAndGlobalCapAreEnforcedWithoutDeletingCards() {
        record(observation(1000)); val db = AppDatabase.get(app).write()
        db.execSQL("UPDATE device_history SET time=?", arrayOf(System.currentTimeMillis() - 40 * DeviceHistoryStore.DAY))
        DeviceHistoryStore.setRetention(app, 30); assertTrue(history().isEmpty()); assertEquals(1, DeviceRegistryStore.count(app))
        val oid = observation(1000).id
        db.beginTransaction(); try {
            for (i in 0..20000) db.execSQL("INSERT INTO device_history(id,observation_id,time,json) VALUES(?,?,?,?)", arrayOf("cap-$i", oid, System.currentTimeMillis(), "{}"))
            DeviceHistoryStore.prune(db, System.currentTimeMillis()); db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        db.rawQuery("SELECT COUNT(*) FROM device_history", null).use { it.moveToFirst(); assertEquals(20000, it.getInt(0)) }
    }
    @Test fun backupPreservesEventsAndRejectsDanglingHistoryBeforeReplacingData() {
        record(observation(1000, "First")); record(observation(2000, "Second")); DeviceHistoryStore.setRetention(app, 365)
        val db = AppDatabase.get(app); val backup = db.exportPortable(); DeviceHistoryStore.clear(app); db.restorePortable(backup)
        assertEquals(2, history().size); assertEquals(365, DeviceHistoryStore.retention(app))
        val broken = JSONObject(backup.toString()); broken.getJSONObject("deviceRegistry").getJSONObject("history").getJSONArray("device_history").getJSONObject(0).put("observationId", "missing")
        assertThrows(Exception::class.java) { db.restorePortable(broken) }; assertEquals(2, history().size)
        val old = JSONObject(backup.toString()); old.getJSONObject("deviceRegistry").remove("history"); db.restorePortable(old)
        assertTrue(history().isEmpty()); assertEquals(90, DeviceHistoryStore.retention(app))
    }
    @Test fun versionThreeUpgradeCreatesHistoryAndPreservesDeviceCards() {
        record(observation(1000)); val card = DeviceRegistryStore.list(app, "", 10).single().id
        val db = AppDatabase.get(app).write(); db.execSQL("DROP TABLE device_history"); db.execSQL("DROP TABLE device_history_state"); db.version = 3
        TestStores.reset()
        assertEquals(6, AppDatabase.get(app).read().version); assertEquals(card, DeviceRegistryStore.list(app, "", 10).single().id); assertTrue(history().isEmpty())
    }
    @Test fun realSaveScanPersistsCoverageErrorAndTransitionsAtomically() {
        val s = Models.ScanSession().apply { scope = lan; timestamp = base + 1000; targetExpression = "192.168.1.10"; portExpression = "443"; totalProbes = 1; completedProbes = 1; openResults.add(Models.PortResult.fromJson(observation(1000).data)) }
        DataStore(app).saveScan(s); assertEquals(1, history().size)
        s.timestamp = base + 2000; s.openResults.clear(); s.error = "Network lost"; DataStore(app).saveScan(s)
        assertTrue(history().first().after.contains("ошибкой"))
        assertEquals("Network lost", Models.ScanSession.fromJson(s.toJson()).error)
        assertEquals("a".repeat(64), Models.PortResult.fromJson(observation(3000, cert = "a".repeat(64)).data).tlsFingerprint)
    }
}
