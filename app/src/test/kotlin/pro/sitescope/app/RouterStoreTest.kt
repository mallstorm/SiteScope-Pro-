package pro.sitescope.app

import android.app.Application
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
import android.os.CancellationSignal

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class RouterStoreTest {
    @Before @After fun reset() = TestStores.reset()
    private val app get() = RuntimeEnvironment.getApplication() as Application
    private fun snapshot(id: String = "one", time: Long = 1000) = JSONObject().put("id", id).put("time", time).put("adapter", "routeros").put("source", "REST · Дом").put("name", "Дом").put("host", "192.168.1.1").put("partial", false).put("warnings", JSONArray()).put("rows", JSONArray().put(JSONObject().put("kind", "system").put("model", "hAP").put("software", "7.20")))
    @Test fun backupRestoresMetadataAndCuratedSnapshotsButNotCredentials() {
        val config = RouterStore.Config().apply { name = "Дом"; base = "https://192.168.1.1" }
        RouterStore.save(app, config); RouterStore.snapshot(app, snapshot())
        app.getSharedPreferences("sitescope_secure_v1", 0).edit().putString("router_credentials_${config.id}", "SECRET-CREDENTIALS").commit()
        val backup = AppDatabase.get(app).exportPortable(); assertFalse(backup.toString().contains("SECRET-CREDENTIALS"))
        RouterStore.clearSnapshots(app); RouterStore.remove(app, config.id); AppDatabase.get(app).restorePortable(backup)
        assertEquals(config.id, RouterStore.configs(app).single().id); assertEquals("hAP", RouterStore.snapshots(app).single().getJSONArray("rows").getJSONObject(0).getString("model"))
        assertArrayEquals(arrayOf("", ""), RouterStore.credentials(app, config.id)); assertEquals(12, BackupManager.SCHEMA_VERSION)
    }
    @Test fun invalidRestoreDoesNotDeleteExistingDataAndOldBackupsClearNewTables() {
        RouterStore.snapshot(app, snapshot()); val backup = AppDatabase.get(app).exportPortable()
        val broken = JSONObject(backup.toString()); broken.getJSONObject("routers").getJSONArray("router_snapshots").getJSONObject(0).getJSONArray("rows").getJSONObject(0).put("password", "PRIVATE")
        assertThrows(Exception::class.java) { AppDatabase.get(app).restorePortable(broken) }; assertEquals(1, RouterStore.snapshots(app).size)
        val old = JSONObject(backup.toString()).apply { remove("routers") }; AppDatabase.get(app).restorePortable(old); assertTrue(RouterStore.snapshots(app).isEmpty())
    }
    @Test fun historyIsBoundedAndRemovingConnectionKeepsSnapshots() {
        val c = RouterStore.Config().apply { base = "https://router.example" }; RouterStore.save(app, c)
        for (i in 0..21) RouterStore.snapshot(app, snapshot("s$i", i.toLong()))
        assertEquals(20, RouterStore.snapshots(app).size); assertEquals(21, RouterStore.snapshots(app).first().getLong("time").toInt())
        RouterStore.remove(app, c.id); assertEquals(20, RouterStore.snapshots(app).size); assertTrue(RouterStore.configs(app).isEmpty())
    }
    @Test fun v5MigrationPreservesCardsAndAddsRouterTables() {
        DeviceRegistryStore.recordBatch(app, listOf(DeviceRegistryStore.scan(Models.PortResult().apply { ip = "192.168.1.10"; port = 443; open = true }, NetworkScope("home", "Дом", true), 1000)))
        val db = AppDatabase.get(app).write(); db.execSQL("DROP TABLE router_connections"); db.execSQL("DROP TABLE router_snapshots"); db.version = 5; TestStores.reset()
        assertEquals(7, AppDatabase.get(app).read().version); assertEquals(1, DeviceRegistryStore.count(app)); assertTrue(RouterStore.configs(app).isEmpty()); assertTrue(RouterStore.snapshots(app).isEmpty())
    }
    @Test fun snapshotRejectsUnknownRootFieldsNonScalarRowsAndOversizedData() {
        for (bad in listOf(snapshot().put("password", "PRIVATE"), snapshot().apply { getJSONArray("rows").getJSONObject(0).put("model", JSONObject().put("password", "PRIVATE")) }, snapshot().apply { getJSONArray("rows").getJSONObject(0).put("model", "x".repeat(1025)) })) assertThrows(Exception::class.java) { RouterStore.snapshot(app, bad) }
        assertTrue(RouterStore.snapshots(app).isEmpty())
    }
    @Test fun routerSnapshotsEnterEveryExportFormatWithoutOpeningSecretStore() {
        RouterStore.snapshot(app, snapshot()); app.getSharedPreferences("sitescope_secure_v1", 0).edit().putString("bad", "PRIVATE-SECRET").commit()
        for (format in listOf("csv", "json", "html")) {
            val out = ByteArrayOutputStream(); val result = ReportExporter.write(app, out, ReportExporter.Options(listOf("routers"), listOf("model", "software"), format, 0), CancellationSignal())
            val text = out.toString("UTF-8"); assertEquals(1, result.rows); assertTrue(text.contains("hAP")); assertTrue(text.contains("REST")); assertFalse(text.contains("PRIVATE-SECRET"))
        }
    }
}
