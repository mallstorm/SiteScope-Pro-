package pro.sitescope.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import pro.sitescope.app.radio.*
import pro.sitescope.app.satellite.*
import pro.sitescope.app.ui.AppTheme
import java.io.File

/** Tests changing measurement source, unknown values and the satellite catalogue entry path. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class,qualifiers="w393dp-h851dp-xxxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RadioInterfaceTest {
    @get:Rule val compose=createEmptyComposeRule()

    private fun snapshot(name:String,activity:ComponentActivity) {
        val directory=System.getProperty("sitescope.ui.snapshots") ?: return
        val file=File(directory,"$name.png").apply{parentFile?.mkdirs()}
        compose.runOnIdle {
            val view=activity.window.decorView
            val bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()
        }
    }

    @Test fun selectingWifiAndSimChangesInstrumentWithoutInventingMissingValues() {
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            activity.get().setContent {
                AppTheme(dark=false) {
                    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        SignalDashboard(RadioFrame(1000,listOf(SimSignal("SIM 1","Test operator",false,2,0,"")),
                            listOf(RadioCell("SIM 1","LTE",true,"262","02",10L,42L,7,1350,-108,"RSRQ dB -11",1000)),
                            WifiReading("Test Wi-Fi",-67,2447,72),true,""),emptyMap(),false,{},{},{})
                    }
                }
            }
            compose.onNodeWithText("-108 dBm").assertIsDisplayed()
            snapshot("signal-sim",activity.get())
            compose.onNodeWithText("Wi-Fi",useUnmergedTree=true).performClick()
            compose.onNodeWithText("-67 dBm").assertIsDisplayed()
            snapshot("signal-wifi",activity.get())
            compose.onNodeWithText("Сеть").performClick()
            compose.onNodeWithText("SSID").assertIsDisplayed()
            compose.onAllNodesWithText("Test Wi-Fi").assertCountEquals(2)
        } finally {activity.pause().stop().destroy()}
    }

    @Test fun satelliteDashboardOpensSearchAndShowsUnknownAnglesUntilLocationIsSet() {
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            activity.get().getSharedPreferences("dish-install",0).edit().clear().commit()
            activity.get().setContent {AppTheme(dark=true){Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)){DishScreen()}}}
            compose.onNodeWithText("Точка установки").assertIsDisplayed()
            compose.onNodeWithText("Азимут").assertExists()
            compose.onAllNodesWithText("—").assertCountEquals(4)
            snapshot("dish-dashboard",activity.get())
            compose.onNodeWithText("Каталог").performClick()
            compose.onNodeWithText("Спутники").assertIsDisplayed()
            compose.onNodeWithText("Пакеты").performClick()
            compose.onNodeWithText("Выбрать файл").assertExists()
            snapshot("dish-catalogue",activity.get())
        } finally {activity.pause().stop().destroy()}
    }
    @Test fun neighboringCellCannotBecomeTheSelectedSimsInstrumentReading() {
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            activity.get().setContent {AppTheme {
                SignalDashboard(RadioFrame(1000,listOf(SimSignal("SIM 1","Test",false,2,0,"")),
                    listOf(RadioCell("SIM 1","LTE",false,"262","02",10,42,7,1350,-60,"",1000)),null,false,""),emptyMap(),false,{},{},{})
            }}
            compose.onNodeWithText("— dBm").assertIsDisplayed()
            compose.onNodeWithText("-60 dBm").assertDoesNotExist()
        } finally {activity.pause().stop().destroy()}
    }

    @Test
    @Config(qualifiers="w320dp-h640dp-xhdpi")
    fun radioInShellUsesOneNavigationBarAndOpensCellsAndJournalOnNarrowDisplay() {
        val activity=Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            val state=pro.sitescope.app.ui.AppState().apply{openRoute("radio")}
            activity.get().setContent {AppTheme(dark=false){pro.sitescope.app.ui.Shell(state)}}
            compose.onNodeWithText("Главная").assertDoesNotExist()
            compose.onNodeWithText("Соты").performClick()
            compose.onNodeWithText("Соседние").assertExists()
            snapshot("radio-cells",activity.get())
            compose.onNodeWithText("Журнал").performClick()
            compose.onNodeWithText("События").assertExists()
            compose.onNodeWithText("Ещё").performClick()
            compose.onNodeWithText("База станций CLF").performClick()
            compose.onNodeWithText("База станций").assertExists()
        } finally {activity.pause().stop().destroy()}
    }

}
