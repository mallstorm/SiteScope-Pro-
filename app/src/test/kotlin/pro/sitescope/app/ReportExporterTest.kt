package pro.sitescope.app

import android.app.Application
import android.content.ContentValues
import android.os.CancellationSignal
import android.os.OperationCanceledException
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
class ReportExporterTest {
    @Before @After fun reset() = TestStores.reset()
    private val app get() = RuntimeEnvironment.getApplication() as Application
    private fun seed() {
        val p = Models.PortResult().apply { ip = "192.168.1.10"; port = 443; open = true; service = "HTTPS"; banner = "PRIVATE-BANNER"; evidence = "RAW-EVIDENCE" }
        DeviceRegistryStore.recordBatch(app, listOf(DeviceRegistryStore.scan(p, NetworkScope("home", "Дом", true), 1000)))
        val db = AppDatabase.get(app).write()
        db.execSQL("UPDATE device_cards SET alias=?", arrayOf("=HYPERLINK(\"https://bad.test\")<script>alert(1)</script>\nnext"))
        app.getSharedPreferences("sitescope_secure_v1", 0).edit().putString("secret", "NEVER-EXPORT-SECRET").commit()
    }
    private fun report(format: String = "json", sections: List<String> = ReportExporter.SECTIONS, fields: List<String> = ReportExporter.FIELDS, limit: Int = 0): Pair<String, ReportExporter.Result> {
        val out = ByteArrayOutputStream(); val result = ReportExporter.write(app, out, ReportExporter.Options(sections, fields, format, limit), CancellationSignal()); return out.toString("UTF-8") to result
    }
    @Test fun jsonProjectsOnlySelectedFieldsAndAlwaysIncludesTimeAndSource() {
        seed(); val (text, result) = report(fields = listOf("address")); val root = JSONObject(text)
        assertTrue(result.rows >= 2); assertEquals(result.rows, root.getJSONArray("rows").length())
        val row = root.getJSONArray("rows").getJSONObject(0)
        assertEquals(setOf("section", "time", "source", "address"), row.keys().asSequence().toSet())
        assertFalse(text.contains("PRIVATE-BANNER")); assertFalse(text.contains("RAW-EVIDENCE")); assertFalse(text.contains("NEVER-EXPORT-SECRET"))
    }
    @Test fun csvEscapesQuotesNewlinesAndFormulaPrefixesAndIncludesMetadata() {
        seed(); val (text, _) = report("csv", listOf("devices"))
        assertTrue(text.startsWith("\uFEFF")); assertTrue(text.contains("\"'=HYPERLINK(\"\"")); assertTrue(text.contains("\nnext\"")); assertTrue(text.contains("report_metadata"))
        for (danger in listOf("=1+1", " +1", "\t@SUM(A1)", "\r-1")) assertTrue(ReportText.csv(danger).startsWith("\"'"))
    }
    @Test fun htmlEscapesUserMarkupAndHasNoExecutableContent() {
        seed(); val (text, _) = report("html", listOf("devices")); assertFalse(text.contains("<script>")); assertTrue(text.contains("&lt;script&gt;")); assertTrue(text.contains("default-src 'none'")); assertFalse(text.contains("href="))
    }
    @Test fun rowLimitAndLongValuesAreExplicit() {
        seed(); val (text, result) = report(limit = 1); assertEquals(1, result.rows); assertTrue(result.truncated); assertTrue(JSONObject(text).getBoolean("truncated"))
        AppDatabase.get(app).write().execSQL("UPDATE device_cards SET alias=?", arrayOf("a".repeat(5000)))
        val (longText, longResult) = report(sections = listOf("devices")); assertEquals(1, longResult.shortenedValues); assertEquals(4097, JSONObject(longText).getJSONArray("rows").getJSONObject(0).getString("device").length)
    }
    @Test fun unknownFieldsAndSectionsAreRejectedAndCancellationStopsBeforeRead() {
        assertThrows(IllegalArgumentException::class.java) { ReportExporter.Options(listOf("secrets"), listOf("address"), "json", 0) }
        assertThrows(IllegalArgumentException::class.java) { ReportExporter.Options(listOf("devices"), listOf("password"), "json", 0) }
        val cancel = CancellationSignal().also { it.cancel() }
        assertThrows(OperationCanceledException::class.java) { ReportExporter.write(app, ByteArrayOutputStream(), ReportExporter.Options(listOf("devices"), emptyList(), "json", 0), cancel) }
    }
    @Test fun redactsUrlCredentialsBearerTokensAndPlainAssignments() {
        val output = ReportText.clean("https://user:secret@router.test/a?token=my-token password=plain-secret Bearer abc.def.ghi")
        val quoted = ReportText.clean("password=\"two word secret\" token='another secret'"); assertFalse(quoted, quoted.contains("two word secret")); assertFalse(quoted, quoted.contains("another secret"))
        for (secret in listOf("secret@", "my-token", "plain-secret", "abc.def.ghi")) assertFalse(output, output.contains(secret))
    }
    @Test fun servicesKeepMeasurementTimeScopeAndDoNotClaimProfileAvailability() {
        seed()
        // Profile-shaped observation has no credentials or network measurement.
        val o = DeviceRegistryStore.scan(Models.PortResult().apply { ip = "192.168.1.20"; port = 22; open = true }, NetworkScope("home", "Дом", true), 2000).apply { kind = "profile"; data = JSONObject() }
        DeviceRegistryStore.recordBatch(app, listOf(o))
        val rows = JSONObject(report(sections = listOf("services")).first).getJSONArray("rows")
        val list = (0 until rows.length()).map { rows.getJSONObject(it) }; assertTrue(list.any { it.getString("time") == "1000" && it.getString("source").contains("Дом") })
        assertTrue(list.any { it.getString("status").contains("доступность не проверена") })
    }
    @Test fun exposureConfirmationUsesSameRulesAsOnScreenIncludingCancelledRuns() {
        fun run(id: String, cancelled: Boolean) = JSONObject().put("id", id).put("time", 1000).put("host", "owned.example").put("ports", "443").put("source", "Мобильная сеть").put("transport", "cellular").put("externalPoint", !cancelled).put("cancelled", cancelled).put("error", "").put("results", JSONArray().put(JSONObject().put("address", "1.1.1.1").put("port", 443).put("state", "open").put("latencyMs", 12)))
        ExposureStore.saveRun(app, run("a", false)); ExposureStore.saveRun(app, run("b", true))
        val rows = JSONObject(report(sections = listOf("exposure")).first).getJSONArray("rows"); assertEquals("true", rows.getJSONObject(0).getString("external_tcp_confirmed")); assertEquals("false", rows.getJSONObject(1).getString("external_tcp_confirmed")); assertEquals("cancelled", rows.getJSONObject(1).getString("run_state"))
    }
    @Test fun unknownHistoryFieldCannotExportImportedSecret() {
        seed(); val db = AppDatabase.get(app).write(); val c = db.rawQuery("SELECT id FROM device_observations LIMIT 1", null); val id = try { c.moveToFirst(); c.getString(0) } finally { c.close() }
        val row = JSONObject().put("field", "password").put("before", "SECRET-UNKNOWN-FIELD").put("time", 1000)
        db.insertOrThrow("device_history", null, ContentValues().apply { put("id", "unsafe"); put("observation_id", id); put("time", 1000); put("json", row.toString()) })
        assertFalse(report(sections = listOf("changes")).first.contains("SECRET-UNKNOWN-FIELD"))
    }
}
