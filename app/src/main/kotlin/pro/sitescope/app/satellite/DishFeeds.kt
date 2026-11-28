package pro.sitescope.app.satellite

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.TimeUnit

internal data class BroadcastChange(val kind: String, val before: Broadcast?, val after: Broadcast?)
internal data class FeedEvent(val time: Long, val source: String, val change: BroadcastChange)
internal data class DishFeed(val url: String, val updated: Long, val count: Int, val error: String)
internal data class DishFeedEntry(val row: Broadcast, val url: String, val updated: Long)
internal data class DishFeedState(
    val feeds: List<DishFeed> = emptyList(),
    val entries: List<DishFeedEntry> = emptyList(),
    val events: List<FeedEvent> = emptyList(),
)

internal fun compareBroadcasts(before: List<Broadcast>, after: List<Broadcast>): List<BroadcastChange> {
    if (before.isEmpty()) return emptyList()
    val old = before.associateBy { it.identity }.toMutableMap()
    val new = after.associateBy { it.identity }.toMutableMap()
    val changes = mutableListOf<BroadcastChange>()
    fun update(was: Broadcast, now: Broadcast) {
        if (was.tuning != now.tuning || was.access != now.access || was.network != now.network || was.note != now.note)
            changes += BroadcastChange("Изменились параметры", was, now)
    }
    for (key in old.keys.intersect(new.keys)) update(old.remove(key)!!, new.remove(key)!!)
    // Match a move only if both snapshots contain a single unambiguous version of this missing service.
    fun service(row: Broadcast) = Triple(row.orbit, row.service.ifBlank { searchKey(row.channel) }, row.network)
    val candidates = new.values.groupBy(::service)
    for ((service, matches) in old.values.groupBy(::service)) {
        val was = matches.singleOrNull() ?: continue
        val now = candidates[service]?.singleOrNull() ?: continue
        changes += BroadcastChange("Изменились параметры", was, now)
        old.remove(was.identity)
        new.remove(now.identity)
    }
    return changes + old.values.map { BroadcastChange("Убран из источника", it, null) } +
        new.values.map { BroadcastChange("Появился в источнике", null, it) }
}

internal object DishFeeds {
    const val MANUAL = "dish-feed-refresh"
    const val PERIODIC = "dish-feed-periodic"
    private val mutex = Mutex()
    private val connected = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
    private val defaults = listOf(
        "https://www.lyngsat.com/Yamal-401.html",
        "https://www.lyngsat.com/ABS-2A.html",
        "https://www.lyngsat.com/Express-80.html",
        "https://www.lyngsat.com/Express-AMU1.html",
        "https://www.lyngsat.com/Express-AT2.html",
        "https://raw.githubusercontent.com/oe-alliance/oe-alliance-tuxbox-common/master/src/satellites.xml"
    )
    private fun prefs(context: Context) = context.getSharedPreferences("dish-install", Context.MODE_PRIVATE)
    fun sources(context: Context): List<String> =
        prefs(context).getStringSet("feeds", null)?.sorted() ?: defaults

    fun setSources(context: Context, urls: List<String>) {
        require(urls.distinct().size <= 16) { "Можно добавить до 16 источников" }
        urls.forEach(::checkUrl)
        prefs(context).edit().putStringSet("feeds", urls.toSet()).apply()
    }
    fun custom(context: Context): List<Broadcast> = prefs(context).getStringSet("customTransponders", emptySet())
        .orEmpty().mapNotNull { runCatching { JSONArray(it).decode() }.getOrNull() }.sortedBy { it.orbit }
    fun saveCustom(context: Context, rows: List<Broadcast>) {
        prefs(context).edit().putStringSet("customTransponders", rows.map { it.encode().toString() }.toSet()).apply()
    }

    fun auto(context: Context) = prefs(context).getBoolean("feedAuto", false)
    fun setAuto(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("feedAuto", enabled).apply()
        configure(context)
        if (enabled) refresh(context)
    }
    fun configure(context: Context) {
        val manager = WorkManager.getInstance(context)
        if (auto(context)) {
            val request = PeriodicWorkRequestBuilder<DishFeedWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(24, TimeUnit.HOURS).setConstraints(connected).build()
            manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        } else manager.cancelUniqueWork(PERIODIC)
    }
    fun refresh(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<DishFeedWorker>().setConstraints(connected).build()
        )
    }

    fun state(context: Context): DishFeedState = FeedDb(context).use { db ->
        val entries = mutableListOf<DishFeedEntry>()
        val feeds = sources(context).map { url ->
            db.read(url).let { (updated, error) ->
                val rows = db.rows(url)
                entries += rows.map { DishFeedEntry(it, url, updated) }
                DishFeed(url, updated, rows.size, error)
            }
        }
        DishFeedState(feeds, entries, db.events(feeds.map { it.url }.toSet()))
    }

    suspend fun sync(context: Context) = withContext(Dispatchers.IO) {
        mutex.withLock {
            FeedDb(context).use { db ->
                for (url in sources(context)) {
                    try {
                        val rows = download(url)
                        db.save(url, rows, System.currentTimeMillis())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        db.fail(url, e.localizedMessage.orEmpty().take(180).ifBlank { "Ошибка обновления" })
                    }
                }
            }
        }
    }

    private fun checkUrl(url: String): URI {
        val uri = URI(url)
        require(url.length <= 1000 && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.fragment == null) { "Нужен HTTPS-адрес источника" }
        return uri
    }

    private fun download(url: String): List<Broadcast> {
        val uri = checkUrl(url)
        val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("User-Agent", "SiteScopePro/2.0 (github.com/mallstorm/SiteScope-Pro-)")
        }
        try {
            require(connection.responseCode == 200) { "HTTP ${connection.responseCode}" }
            return if (LyngSatCatalogue.supports(uri)) {
                val html = connection.inputStream.use { input ->
                    val bytes = input.readBounded()
                    bytes.toString(Charsets.UTF_8)
                }
                LyngSatCatalogue.parse(html)
            } else {
                readCatalog(connection.inputStream).second.map(DishEntry::asBroadcast)
            }
        } finally {
            connection.disconnect()
        }
    }
}

class DishFeedWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        DishFeeds.sync(applicationContext)
        Result.success()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure()
    }
}

private fun Broadcast.encode() = JSONArray(listOf(satellite, orbit, channel, network, frequency,
    polarization, rate, standard, access, kind, language, sid, mux, service, note))

private fun JSONArray.decode() = Broadcast(getString(0), getDouble(1), getString(2),
    getString(3), getString(4), getString(5), getString(6), optString(7), optString(8), optString(9),
    optString(10), optString(11), optString(12), optString(13), optString(14))

private class FeedDb(context: Context) : SQLiteOpenHelper(context, "dish-feeds.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE snapshot(url TEXT PRIMARY KEY, updated INTEGER DEFAULT 0, error TEXT DEFAULT '')")
        db.execSQL("CREATE TABLE entries(url TEXT NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX entries_source ON entries(url)")
        db.execSQL("CREATE TABLE changes(id INTEGER PRIMARY KEY, url TEXT, time INTEGER, payload TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun read(url: String): Pair<Long, String> =
        readableDatabase.rawQuery("SELECT updated,error FROM snapshot WHERE url=?", arrayOf(url)).use {
            if (it.moveToFirst()) it.getLong(0) to it.getString(1) else 0L to ""
        }

    fun rows(url: String): List<Broadcast> =
        readableDatabase.rawQuery("SELECT payload FROM entries WHERE url=?", arrayOf(url)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(JSONArray(cursor.getString(0)).decode()) }
        }

    fun fail(url: String, message: String) {
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO snapshot(url) VALUES(?)", arrayOf(url))
        db.execSQL("UPDATE snapshot SET error=? WHERE url=?", arrayOf(message, url))
    }

    fun save(url: String, rows: List<Broadcast>, now: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            require(rows.size <= 25_000) { "В источнике больше 25 000 записей" }
            val before = rows(url).filter { it.channel.isNotBlank() }
            val after = rows.filter { it.channel.isNotBlank() }
            require(before.isEmpty() || after.size >= (before.size * 0.6).coerceAtLeast(1.0)) {
                "Источник вернул неполный список. Предыдущие данные сохранены"
            }
            val changes = compareBroadcasts(before, after)
            db.insertWithOnConflict("snapshot", null, ContentValues().apply {
                put("url", url)
                put("updated", now)
                put("error", "")
            }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
            db.delete("entries", "url=?", arrayOf(url))
            db.compileStatement("INSERT INTO entries(url,payload) VALUES(?,?)").use { insert ->
                insert.bindString(1, url)
                for (row in rows) {
                    insert.bindString(2, row.encode().toString())
                    insert.executeInsert()
                }
            }
            for (change in changes.take(1000)) {
                val payload = JSONObject().put("kind", change.kind)
                    .put("before", change.before?.encode()).put("after", change.after?.encode())
                db.execSQL("INSERT INTO changes(url,time,payload) VALUES(?,?,?)", arrayOf(url, now, payload.toString()))
            }
            db.execSQL("DELETE FROM changes WHERE id NOT IN (SELECT id FROM changes ORDER BY id DESC LIMIT 1000)")
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun events(active: Set<String>): List<FeedEvent> =
        readableDatabase.rawQuery("SELECT time,url,payload FROM changes ORDER BY id DESC", null).use { cursor ->
            buildList {
                while (cursor.moveToNext() && size < 200) {
                    val url = cursor.getString(1)
                    if (url !in active) continue
                    val json = JSONObject(cursor.getString(2))
                    add(FeedEvent(cursor.getLong(0), url, BroadcastChange(json.getString("kind"),
                        json.optJSONArray("before")?.decode(), json.optJSONArray("after")?.decode())))
                }
            }
        }
}
