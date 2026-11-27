package pro.sitescope.app

import android.app.Application
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pro.sitescope.app.radio.*
import pro.sitescope.app.satellite.*
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class FieldTransferTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val store get() = SurveyStore.get(context)

    @Before fun clear() {
        SurveyService.running.value = false
        SurveyService.closing.value = false
        SurveyService.active.value = null
        val db = store.writableDatabase
        for (table in listOf("samples", "sessions", "towers", "events")) db.delete(table, null, null)
        File(context.filesDir, "dish-catalog.txt").delete()
    }

    @Test fun restoreRollbackPreservesDatabaseAndSatelliteFile() {
        store.begin(1000)
        File(context.filesDir, "dish-catalog.txt").writeText("Original;36.0")
        val original = FieldBackup.exportState(context)
        val changed = JSONObject(original.toString()).put("catalog", "Replacement;19.2")
        changed.getJSONArray("sessions").getJSONArray(0).put(1, 2000)
        FieldBackup.prepareRestore(context, changed).close()
        assertEquals(1000L, store.surveys().single().start)
        assertEquals("Original;36.0", File(context.filesDir, "dish-catalog.txt").readText())
    }

    @Test fun committedRestorePreservesLongIdsAndSealsInterruptedRecording() {
        val id = store.begin(1000)
        val row = RadioCell("SIM 1", "NR", true, "262", "02", 510, 68719476735, 12, 630000, -95, "SS-RSRP -95", 1100)
        store.append(id, RadioFrame(1100, emptyList(), listOf(row), null, true, ""), null)
        val root = FieldBackup.exportState(context)
        FieldBackup.prepareRestore(context, root).use { it.commit() }
        assertEquals(68719476735L, store.latest(id).single().cid)
        assertEquals(1100L, store.surveys().single().end)
    }

    @Test fun invalidCoordinatesCannotReplaceExistingData() {
        val id = store.begin(1000)
        store.append(id, RadioFrame(1100, emptyList(), emptyList(), null, false, ""), null)
        val root = FieldBackup.exportState(context)
        root.getJSONArray("samples").getJSONArray(0).put(15, 91.0)
        assertThrows(IllegalArgumentException::class.java) { FieldBackup.prepareRestore(context, root) }
        assertEquals(id, store.surveys().single().id)
    }

    @Test fun fullBackupContainsRadioPreferencesAndFieldData() {
        context.getSharedPreferences("radio-options", 0).edit().putInt("threshold", -107).commit()
        store.addEvent("Network changed")
        val backup = BackupManager.createBackup(context)
        assertEquals(14, backup.getInt("schemaVersion"))
        assertEquals(1, backup.getJSONObject("field").getJSONArray("events").length())
        assertTrue(backup.getJSONObject("preferences").has("radio-options"))
    }

    @Test fun satelliteXmlImportsNamesAndTranspondersAndRejectsEntities() {
        val xml = """<satellites><sat name="Satellite" position="360"><transponder frequency="12000000" symbol_rate="27500000" polarization="1"/></sat></satellites>"""
        val entries = DishCatalogue.parse(xml.lines())
        assertEquals(2, entries.size)
        assertEquals(36.0, entries.first().orbital, 0.01)
        assertTrue(entries.last().tuning.contains("27500"))
        assertThrows(IllegalArgumentException::class.java) {
            DishCatalogue.parse(listOf("""<!DOCTYPE test [<!ENTITY x SYSTEM "file:///etc/passwd">]><satellites/>"""))
        }
    }

    @Test fun lamedbAccessAndRadioMetadataRemainAvailableToFilters() {
        val list = DishCatalogue.parse(listOf("eDVB services /4/", "transponders",
            "00c00000:03e9:0001", "s 11214250:22000000:0:2:192:2:0", "/", "end",
            "services", "0001:00c00000:03e9:0001:2:0", "Radio", "p:Provider,C:1234", "end"))
        assertEquals("CA", list.single().access)
        assertEquals("Радио", list.single().kind)
    }

    @Test fun widgetHistoryIsBoundedAndUnknownMeasurementsRemainUnknown() {
        repeat(80) {
            RadioWidget.publish(context, RadioFrame(it.toLong(), emptyList(), emptyList(), WifiReading("Home", -60, 2412, 72), true, ""))
        }
        val prefs = context.getSharedPreferences("radio-widget", 0)
        assertEquals(60, JSONArray(prefs.getString("plot-Wi-Fi", "[]")).length())
        assertNull(prefs.getString("SIM 1", null))
        assertEquals("-60", prefs.getString("Wi-Fi", null))
    }
    @Test fun clf41AndCompressedImportsPreserveCoordinates() {
        val text = "26207;04002;00432;0;50.12345;-8.12345;-1;Sector;3;4002_3;240;30.5;65;7;6;1599XY03"
        val cell = parseTower(text)!!
        assertEquals("W", cell.rat)
        assertEquals(240, cell.bearing)
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use {
            it.putNextEntry(java.util.zip.ZipEntry("cells.clf"))
            it.write(text.toByteArray())
            it.closeEntry()
        }
        assertEquals(1, store.importTowers(out.toByteArray().inputStream()).first)
        assertEquals(50.12345, store.towers("Sector").single().lat!!, 0.000001)
    }

    @Test fun backupPreflightRejectsMissingFieldSectionAndFailedRestoreRollsBackRadio() {
        store.addEvent("Original event")
        val backup = BackupManager.createBackup(context)
        backup.remove("field")
        assertThrows(IllegalArgumentException::class.java) { BackupManager.restore(context, backup) }
        assertEquals("Original event", store.events().single().second)
    }

    @Test fun upgradesAlpha2RadioDatabaseWithoutLosingStations() {
        store.saveTower(Tower("L", "262", "02", 510, 100, null, null, null, null, null, "Old station"))
        store.writableDatabase.execSQL("DROP TABLE events")
        store.writableDatabase.version = 1
        store.close()
        assertTrue(store.events().isEmpty())
        assertEquals("Old station", store.towers("Old").single().name)
        assertEquals(2, store.readableDatabase.version)
    }

    @Test fun concurrentAutomaticAndManualSpeedResultsDoNotOverwriteEachOther() {
        val prefs = context.getSharedPreferences(pro.sitescope.app.net.SpeedStore.PREFS, 0)
        prefs.edit().clear().commit()
        val results = (1..20).map { index ->
            pro.sitescope.app.net.SpeedEngine.Result(index.toLong(), "Test", "Wi-Fi",
                1.0, 0.0, 0.0, 1.0, 10.0, 2.0, 10.0, 2.0, 100L, 10L, "")
        }
        val executor = java.util.concurrent.Executors.newFixedThreadPool(4)
        try {
            val futures = results.map { result ->
                executor.submit { pro.sitescope.app.net.SpeedStore.appendHistory(context, result) }
            }
            futures.forEach { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(20, pro.sitescope.app.net.SpeedStore.loadHistory(context).size)
            pro.sitescope.app.net.SpeedStore.removeHistory(context, results.first())
            assertEquals(19, pro.sitescope.app.net.SpeedStore.loadHistory(context).size)
        } finally { executor.shutdownNow() }
    }

}
