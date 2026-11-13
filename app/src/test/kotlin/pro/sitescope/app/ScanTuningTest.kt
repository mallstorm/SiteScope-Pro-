package pro.sitescope.app

import android.app.Application
import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class ScanTuningTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    @Before fun clear() {
        app.getSharedPreferences(SettingsStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("iptv_discovery", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun migratesSelectedIptvSettingsOnceAndSharesFutureChanges() {
        val old = app.getSharedPreferences("iptv_discovery", Context.MODE_PRIVATE)
        old.edit().putInt("threads", 180).putInt("delay_ms", 350).commit()
        assertEquals(180, SettingsStore.scannerConcurrency(app))
        assertEquals(350, SettingsStore.scannerDelayMs(app))
        SettingsStore.saveScanTuning(app, 250, 5000)
        old.edit().putInt("threads", 8).putInt("delay_ms", 0).commit()
        assertEquals(250, SettingsStore.scannerConcurrency(app))
        assertEquals(5000, SettingsStore.scannerDelayMs(app))
        SettingsStore.saveScanner(app, 900, 120, true, true)
        assertEquals(120, SettingsStore.scannerConcurrency(app))
        assertEquals(5000, SettingsStore.scannerDelayMs(app))
    }

    @Test fun preservesExistingScannerChoiceWithoutIptvOverrideAndNormalizesBoundaries() {
        app.getSharedPreferences(SettingsStore.PREFS, Context.MODE_PRIVATE).edit().putInt("scanner_concurrency", 48).commit()
        assertEquals(48, SettingsStore.scannerConcurrency(app))
        SettingsStore.saveScanTuning(app, 1, -20)
        assertEquals(2, SettingsStore.scannerConcurrency(app))
        assertEquals(0, SettingsStore.scannerDelayMs(app))
        SettingsStore.saveScanTuning(app, 900, 9000)
        assertEquals(250, SettingsStore.scannerConcurrency(app))
        assertEquals(5000, SettingsStore.scannerDelayMs(app))
    }

    @Test fun checkpointRestoresSelectedDelayAndReadsOlderCheckpoints() {
        val state = ScanService.Snapshot().apply { concurrency = 250; delayMs = 450; active = true }
        val restored = ScanService.Snapshot.fromJson(state.toCheckpointJson().toString())
        assertEquals(250, restored.concurrency)
        assertEquals(450, restored.delayMs)
        assertEquals(0, ScanService.Snapshot.fromJson("{\"concurrency\":32}").delayMs)
    }
}
