package pro.sitescope.app.audit

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pro.sitescope.app.BackupManager
import pro.sitescope.app.ConnectionProfileStore
import pro.sitescope.app.ServiceEndpoint
import pro.sitescope.app.AppDatabase
import pro.sitescope.app.BackupSanitizer
import pro.sitescope.app.DataStore
import pro.sitescope.app.TestStores
import pro.sitescope.app.proto.Snmp
import pro.sitescope.app.tv.Playlist
import pro.sitescope.app.tv.TvSource
import pro.sitescope.app.tv.TvStore
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class AuditRegressionTest {
    @Before @After fun resetSingletons() = TestStores.reset()
    @Test fun backupRestoresCurrentTvCatalog() {
        val app = RuntimeEnvironment.getApplication() as Application
        TvStore.load(app)
        TvStore.channels.clear(); TvStore.sources.clear()
        TvStore.putSource(TvSource("audit-source", "Audit source", "file", ""),
            Playlist.parse("#EXTM3U\n#EXTINF:-1,Audit channel\nhttp://audit.example/unique-channel.ts\n", "audit-source"))
        TvStore.channels.single().favorite = true
        TvStore.save(app)
        assertEquals(1, TvStore.snapshot().size)
        val backup = BackupManager.createBackup(app)
        TvStore.channels.clear(); TvStore.sources.clear()
        File(app.filesDir, "tv_state.json").delete()
        BackupManager.restore(app, backup)
        TvStore.javaClass.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(TvStore, false)
        TvStore.load(app)
        assertEquals("Backup must restore the current TV catalog", 1, TvStore.snapshot().size)
        assertTrue(TvStore.snapshot().single().favorite)
    }

    @Test fun failedRestoreLeavesExistingPreferencesUntouched() {
        val app = RuntimeEnvironment.getApplication() as Application
        val prefs = app.getSharedPreferences("wake_on_lan", Context.MODE_PRIVATE)
        prefs.edit().putString("items", "backup data").commit()
        val backup = BackupManager.createBackup(app)
        prefs.edit().putString("items", "current data").commit()
        backup.getJSONObject("database").put("projects", JSONArray().put("invalid project row"))
        var rejected = false
        try { BackupManager.restore(app, backup) } catch (e: Exception) { rejected = true }
        assertTrue("The malformed database must be rejected", rejected)
        assertEquals("A rejected import must not change current data", "current data", prefs.getString("items", ""))
    }

    @Test fun snmpPreservesOidWithLargeSecondArc() {
        val message = Snmp.message("public", 0xA2, 77, "2.999.3")
        assertEquals("2.999.3", Snmp.parse(message, message.size, 77)!!.oid)
    }

    @Test fun currentTvCatalogIsReflectedInBackupSummary() {
        val app = RuntimeEnvironment.getApplication() as Application
        TvStore.load(app)
        TvStore.channels.clear(); TvStore.sources.clear()
        TvStore.putSource(TvSource("audit-source", "Audit source", "file", ""),
            Playlist.parse("#EXTM3U\n#EXTINF:-1,Audit channel\nhttp://audit.example/channel.ts\n", "audit-source"))
        TvStore.save(app)
        assertEquals(1, BackupManager.createBackup(app).getJSONObject("summary").getInt("iptvChannels"))
    }

    @Test fun tvGuideNameSurvivesRestart() {
        val app = RuntimeEnvironment.getApplication() as Application
        TvStore.load(app)
        TvStore.channels.clear(); TvStore.sources.clear()
        TvStore.putSource(TvSource("audit-source", "Audit source", "file", ""),
            Playlist.parse("#EXTM3U\n#EXTINF:-1 tvg-name=\"Guide Channel\",Display Channel\nhttp://audit.example/channel.ts\n", "audit-source"))
        assertEquals("Guide Channel", TvStore.snapshot().single().tvgName)
        TvStore.save(app)
        TvStore.channels.clear(); TvStore.sources.clear()
        TvStore.javaClass.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(TvStore, false)
        TvStore.load(app)
        assertEquals("Guide Channel", TvStore.snapshot().single().tvgName)
    }

    @Test fun backupMarkedWithoutSecretsDoesNotExportUrlPassword() {
        val app = RuntimeEnvironment.getApplication() as Application
        val endpoint = ServiceEndpoint().apply {
            protocol = "RTSP"; host = "192.0.2.1"; port = 554
            uri = "rtsp://audit-user:AUDIT_PASSWORD_SENTINEL@192.0.2.1:554/live"
        }
        ConnectionProfileStore.save(app, endpoint)
        val backup = BackupManager.createBackup(app)
        assertFalse(backup.getBoolean("secretsIncluded"))
        assertFalse("Backup marked without secrets must not contain a password from the RTSP URL", backup.toString().contains("AUDIT_PASSWORD_SENTINEL"))
    }

    @Test fun oldBackupMigratesLegacyTvCatalog() {
        val app = RuntimeEnvironment.getApplication() as Application
        val backup = BackupManager.createBackup(app)
        backup.put("schemaVersion", 6); backup.remove("tv")
        val legacy = JSONArray().put(JSONObject().put("name", "Old channel").put("sourceUrl", "http://example.test/old.ts"))
        backup.getJSONObject("preferences").getJSONObject("sitescope_iptv")
            .put("channels", JSONObject().put("type", "string").put("value", legacy.toString()))
        BackupManager.restore(app, backup)
        assertEquals("Old channel", TvStore.snapshot().single().name)
    }

    @Test fun fileWriteFailureRollsBackDatabaseAndPreferences() {
        val app = RuntimeEnvironment.getApplication() as Application
        val prefs = app.getSharedPreferences("wake_on_lan", Context.MODE_PRIVATE)
        prefs.edit().putString("items", "backup").commit()
        val backup = BackupManager.createBackup(app)
        prefs.edit().putString("items", "current").commit()
        AppDatabase.get(app).restorePortable(JSONObject())
        DataStore(app).upsertProject("SEO", "Current project", "https://current.example/", -1, "")
        val blocked = File(app.filesDir, "not-a-directory").apply { writeText("file") }
        val context = object : ContextWrapper(app) { override fun getFilesDir(): File = blocked }
        var failed = false
        try { BackupManager.restore(context, backup) } catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertEquals("current", prefs.getString("items", ""))
        assertEquals("https://current.example/", DataStore(app).projects().single().target)
    }

    @Test fun redactsQueryTokensAndLegacyJsonWithoutChangingLiveData() {
        val value = "https://user:PASS_SENTINEL@example.test/live/user/PASS_SENTINEL/1.ts?token=TOKEN_SENTINEL"
        val live = JSONObject().put("uri", value).put("legacy", JSONArray().put(JSONObject().put("sourceUrl", value)).toString())
        val clean = BackupSanitizer.clean(live)
        assertFalse(clean.toString().contains("PASS_SENTINEL"))
        assertFalse(clean.toString().contains("TOKEN_SENTINEL"))
        assertEquals(value, live.getString("uri"))
        JSONArray(clean.getString("legacy")).getJSONObject(0).getString("sourceUrl")
    }

    @Test fun redactedTypedPreferenceDoesNotBreakRestore() {
        val app = RuntimeEnvironment.getApplication() as Application
        app.getSharedPreferences("sitescope_data_v1", Context.MODE_PRIVATE).edit().putString("password", "PRIVATE_SENTINEL").commit()
        val backup = BackupManager.createBackup(app)
        assertFalse(backup.toString().contains("PRIVATE_SENTINEL"))
        assertFalse(backup.getJSONObject("preferences").getJSONObject("sitescope_data_v1").has("password"))
        BackupManager.restore(app, backup)
    }

    @Test fun snmpRoundTripsAllFirstArcRanges() {
        for (oid in listOf("0.39.0", "1.39.0", "2.0.0", "2.39.1", "2.999.3", "2.4294967295.1")) {
            val packet = Snmp.message("public", 0xA2, 78, oid)
            assertEquals(oid, Snmp.parse(packet, packet.size, 78)!!.oid)
        }
    }
}
