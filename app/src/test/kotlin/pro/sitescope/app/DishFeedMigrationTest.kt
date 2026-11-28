package pro.sitescope.app

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pro.sitescope.app.satellite.DishFeeds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DishFeedMigrationTest {
    @Test fun worldwideCataloguePreservesAlpha5SnapshotsAndUserFeeds() {
        val context = RuntimeEnvironment.getApplication()
        val database = context.getDatabasePath("dish-feeds.db")
        database.parentFile?.mkdirs()
        val sample = "https://www.lyngsat.com/Yamal-401.html"
        val custom = "https://example.org/transponders.xml"
        context.getSharedPreferences("dish-install", Context.MODE_PRIVATE).edit().putStringSet("feeds", setOf(custom)).apply()
        SQLiteDatabase.openOrCreateDatabase(database, null).use { db ->
            db.execSQL("CREATE TABLE snapshot(url TEXT PRIMARY KEY, updated INTEGER DEFAULT 0, error TEXT DEFAULT '')")
            db.execSQL("CREATE TABLE entries(url TEXT NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE INDEX entries_source ON entries(url)")
            db.execSQL("CREATE TABLE changes(id INTEGER PRIMARY KEY, url TEXT, time INTEGER, payload TEXT)")
            db.execSQL("INSERT INTO snapshot(url,updated) VALUES(?,?)", arrayOf(sample, 123L))
            val row = JSONArray(listOf("Yamal 401", 90.0, "Example", "Example", "11000", "H", "30000"))
            db.execSQL("INSERT INTO entries(url,payload) VALUES(?,?)", arrayOf(sample, row.toString()))
            db.version = 1
        }
        val state = DishFeeds.state(context)
        assertEquals(4, state.regions.size)
        assertEquals(238, state.regions.flatMap { it.satellites }.distinctBy { it.url }.size)
        assertTrue(state.regions.single { it.region.key == "asia" }.checked >= 1)
        assertEquals(custom, state.feeds.single().url)
        assertEquals("Example", state.entries.single().row.channel)
        assertEquals(123L, state.entries.single().updated)
    }
}
