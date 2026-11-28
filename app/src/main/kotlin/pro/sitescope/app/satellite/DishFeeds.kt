package pro.sitescope.app.satellite

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
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
internal data class RegionalCoverage(
    val region: DishRegion, val satellites: List<SatellitePage>, val checked: Int,
    val errors: Int, val updated: Long, val indexError: String
)
internal data class DishFeedEntry(val row: Broadcast, val url: String, val updated: Long)
internal data class DishFeedState(
    val feeds: List<DishFeed> = emptyList(),
    val entries: List<DishFeedEntry> = emptyList(),
    val events: List<FeedEvent> = emptyList(),
    val regions: List<RegionalCoverage> = emptyList(),
    val worldAttempted: Boolean = false,
    val checkedSatellites: Int = 0,
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
    private const val SYNC_START = "worldSyncStart"
    private const val SYNC_ATTEMPTED = "worldSyncAttempted"
    private const val XML_SOURCE = "https://raw.githubusercontent.com/oe-alliance/oe-alliance-tuxbox-common/master/src/satellites.xml"
    private val priorityPages = listOf("Yamal-401", "ABS-2A", "Express-80", "Express-AMU1", "Express-AT2")
        .map { "https://www.lyngsat.com/$it.html" }
    private fun prefs(context: Context) = context.getSharedPreferences("dish-install", Context.MODE_PRIVATE)
    fun sources(context: Context): List<String> =
        prefs(context).getStringSet("feeds", null)?.sorted() ?: listOf(XML_SOURCE)

    fun setSources(context: Context, urls: List<String>) {
        require(urls.distinct().size <= 64) { "Можно добавить до 64 собственных источников" }
        urls.forEach(::checkedUrl)
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
                .setInitialDelay(24, TimeUnit.HOURS).setConstraints(connected)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).build()
            manager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
        } else manager.cancelUniqueWork(PERIODIC)
    }
    fun refresh(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            MANUAL, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DishFeedWorker>().setConstraints(connected)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS).build()
        )
    }

    private fun seed(context: Context): List<String> =
        context.assets.open("dish-world-index.tsv").bufferedReader().use { it.readLines() }

    private fun world(db: FeedDb, seed: List<String>): Map<DishRegion, List<SatellitePage>> =
        DishRegions.regions.associateWith { region ->
            db.region(region).ifEmpty { DishRegions.parseSeed(seed, region.key) }
        }

    fun satellites(context: Context): List<SatellitePage> = FeedDb(context).use { db ->
        world(db, seed(context)).values.flatten().distinctBy { it.url }.sortedBy { it.orbit }
    }

    fun state(context: Context): DishFeedState = FeedDb(context).use { db ->
        val regionalPages = world(db, seed(context))
        val primary = sources(context)
        val urls = (primary + regionalPages.values.flatten().map { it.url }).distinct()
        val metadata = db.metadata()
        val entries = urls.flatMap { url ->
            val date = metadata[url]?.updated ?: 0L
            db.rows(url).map { DishFeedEntry(it, url, date) }
        }
        val coverage = regionalPages.map { (region, pages) ->
            val index = metadata[region.url]
            RegionalCoverage(region, pages,
                pages.count { (metadata[it.url]?.updated ?: 0L) > 0L },
                pages.count { !metadata[it.url]?.error.isNullOrBlank() },
                index?.updated ?: 0L, index?.error.orEmpty())
        }
        val feeds = primary.map { url ->
            val info = metadata[url]
            DishFeed(url, info?.updated ?: 0L, info?.count ?: 0, info?.error.orEmpty())
        }
        DishFeedState(feeds, entries, db.events(urls.toSet()), coverage,
            prefs(context).getBoolean(SYNC_ATTEMPTED, false),
            regionalPages.values.flatten().map { it.url }.distinct().count { (metadata[it]?.updated ?: 0L) > 0L })
    }

    suspend fun sync(
        context: Context,
        progress: suspend (checked: Int, total: Int) -> Unit,
    ): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            val settings = prefs(context)
            val now = System.currentTimeMillis()
            val began = settings.getLong(SYNC_START, 0L).takeIf { now - it in 0..TimeUnit.DAYS.toMillis(2) }
                ?: now.also { settings.edit().putLong(SYNC_START, it).apply() }
            settings.edit().putBoolean(SYNC_ATTEMPTED, true).apply()
            val deadline = SystemClock.elapsedRealtime() + TimeUnit.MINUTES.toMillis(6)
            val bundled = seed(context)
            FeedDb(context).use { db ->
                val known = db.metadata().toMutableMap()
                suspend fun attempt(url: String, region: DishRegion? = null): Boolean {
                    coroutineContext.ensureActive()
                    val old = known[url] ?: SourceSnapshot()
                    var limited = false
                    try {
                        val response = download(url, old)
                        val updated = System.currentTimeMillis()
                        when {
                            response.body == null -> db.confirm(url, updated)
                            region != null -> {
                                val pages = DishRegions.parse(response.body)
                                val fallbackCount = DishRegions.parseSeed(bundled, region.key).size
                                db.saveRegion(region, pages, updated, response, fallbackCount)
                            }
                            else -> {
                                val rows = if (LyngSatCatalogue.supports(checkedUrl(url))) {
                                    LyngSatCatalogue.parse(response.body, includeTransponders = true)
                                } else readCatalog(response.body.byteInputStream()).second.map(DishEntry::asBroadcast)
                                db.save(url, rows, updated, response)
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        db.fail(url, e.localizedMessage.orEmpty().take(180).ifBlank { "Ошибка обновления" })
                        limited = e is FeedRateLimit
                    }
                    known[url] = old.copy(attempted = System.currentTimeMillis())
                    if (LyngSatCatalogue.supports(checkedUrl(url))) delay(850)
                    return !limited
                }
                for (region in DishRegions.regions) {
                    if ((known[region.url]?.attempted ?: 0L) < began && !attempt(region.url, region)) {
                        return@withLock false
                    }
                }
                val pages = world(db, bundled).values.flatten().map { it.url }.distinct()
                // Keep the previous five popular positions responsive during the first worldwide scan.
                val targets = (priorityPages.filter { it in pages } + sources(context) + pages).distinct()
                val pending = targets.filter { (known[it]?.attempted ?: 0L) < began }
                var checked = targets.size - pending.size
                progress(checked, targets.size)
                for (url in pending) {
                    if (SystemClock.elapsedRealtime() >= deadline || !attempt(url)) {
                        return@withLock false
                    }
                    checked++
                    if (checked % 20 == 0) progress(checked, targets.size)
                }
                settings.edit().remove(SYNC_START).apply()
                progress(targets.size, targets.size)
                true
            }
        }
    }

    private fun checkedUrl(url: String): URI {
        val uri = URI(url)
        require(url.length <= 1000 && uri.scheme == "https" && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.fragment == null) { "Нужен HTTPS-адрес источника" }
        return uri
    }

    private fun download(url: String, snapshot: SourceSnapshot): FeedResponse {
        val connection = (checkedUrl(url).toURL().openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = false
            connectTimeout = 8000
            readTimeout = 12000
            setRequestProperty("User-Agent", "SiteScopePro/2.0 (github.com/mallstorm/SiteScope-Pro-)")
            if (snapshot.updated > 0L) {
                snapshot.etag.takeIf(String::isNotEmpty)?.let { setRequestProperty("If-None-Match", it) }
                snapshot.modified.takeIf(String::isNotEmpty)?.let { setRequestProperty("If-Modified-Since", it) }
            }
        }
        try {
            val code = connection.responseCode
            if (code == 429) throw FeedRateLimit()
            if (code == 304 && snapshot.updated > 0L) return FeedResponse(null)
            require(code == 200) { "HTTP $code" }
            return FeedResponse(
                connection.inputStream.use { it.readBounded() }.toString(Charsets.UTF_8),
                connection.getHeaderField("ETag").orEmpty(),
                connection.getHeaderField("Last-Modified").orEmpty(),
            )
        } finally {
            connection.disconnect()
        }
    }

}

class DishFeedWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = try {
        val complete = DishFeeds.sync(applicationContext) { checked, total ->
            setProgress(workDataOf("satellitesChecked" to checked, "satellitesTotal" to total))
        }
        if (complete) Result.success() else Result.retry()
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

private data class FeedResponse(val body: String?, val etag: String = "", val modified: String = "")
private class FeedRateLimit : Exception("Источник просит снизить частоту запросов (HTTP 429)")
private data class SourceSnapshot(
    val updated: Long = 0L,
    val attempted: Long = 0L,
    val error: String = "",
    val etag: String = "",
    val modified: String = "",
    val count: Int = 0,
)

private class FeedDb(context: Context) : SQLiteOpenHelper(context, "dish-feeds.db", null, 2) {
    init { setWriteAheadLoggingEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE snapshot(url TEXT PRIMARY KEY, updated INTEGER DEFAULT 0, error TEXT DEFAULT '')")
        db.execSQL("CREATE TABLE entries(url TEXT NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE INDEX entries_source ON entries(url)")
        db.execSQL("CREATE TABLE changes(id INTEGER PRIMARY KEY, url TEXT, time INTEGER, payload TEXT)")
        upgradeFromOne(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) upgradeFromOne(db)
    }

    private fun upgradeFromOne(db: SQLiteDatabase) {
        db.execSQL("ALTER TABLE snapshot ADD COLUMN attempted INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE snapshot ADD COLUMN etag TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE snapshot ADD COLUMN modified TEXT NOT NULL DEFAULT ''")
        db.execSQL("CREATE TABLE region_satellites(region TEXT, url TEXT, name TEXT, orbit REAL, PRIMARY KEY(region,url))")
    }

    fun metadata(): Map<String, SourceSnapshot> =
        readableDatabase.rawQuery(
            """SELECT s.url,s.updated,s.attempted,s.error,s.etag,s.modified,
                (SELECT COUNT(*) FROM entries e WHERE e.url=s.url)
                FROM snapshot s""", null
        ).use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), SourceSnapshot(
                    cursor.getLong(1), cursor.getLong(2), cursor.getString(3), cursor.getString(4),
                    cursor.getString(5), cursor.getInt(6),
                ))
            }
        }

    fun region(region: DishRegion): List<SatellitePage> =
        readableDatabase.rawQuery("SELECT url,name,orbit FROM region_satellites WHERE region=?", arrayOf(region.key)).use {
            buildList { while (it.moveToNext()) add(SatellitePage(it.getString(0), it.getString(1), it.getDouble(2))) }
        }

    fun saveRegion(region: DishRegion, pages: List<SatellitePage>, now: Long, response: FeedResponse, fallbackCount: Int) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val previous = region(region).size.takeIf { it > 0 } ?: fallbackCount
            require(pages.size >= previous * 0.65) {
                "Список региона неполный. Сохранён предыдущий список"
            }
            db.delete("region_satellites", "region=?", arrayOf(region.key))
            db.compileStatement("INSERT INTO region_satellites(region,url,name,orbit) VALUES(?,?,?,?)").use { insert ->
                insert.bindString(1, region.key)
                for (page in pages) {
                    insert.bindString(2, page.url)
                    insert.bindString(3, page.name)
                    insert.bindDouble(4, page.orbit)
                    insert.executeInsert()
                }
            }
            saveMetadata(db, region.url, now, response)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun saveMetadata(db: SQLiteDatabase, url: String, now: Long, response: FeedResponse) {
        db.insertWithOnConflict("snapshot", null, ContentValues().apply {
            put("url", url)
            put("updated", now)
            put("attempted", now)
            put("error", "")
            put("etag", response.etag)
            put("modified", response.modified)
        }, SQLiteDatabase.CONFLICT_REPLACE).also { check(it != -1L) }
    }

    fun confirm(url: String, now: Long) {
        writableDatabase.execSQL("UPDATE snapshot SET updated=?,attempted=?,error='' WHERE url=?", arrayOf(now, now, url))
    }

    fun rows(url: String): List<Broadcast> =
        readableDatabase.rawQuery("SELECT payload FROM entries WHERE url=?", arrayOf(url)).use { cursor ->
            buildList { while (cursor.moveToNext()) add(JSONArray(cursor.getString(0)).decode()) }
        }

    fun fail(url: String, message: String) {
        val db = writableDatabase
        db.execSQL("INSERT OR IGNORE INTO snapshot(url) VALUES(?)", arrayOf(url))
        db.execSQL("UPDATE snapshot SET error=?,attempted=? WHERE url=?", arrayOf(message, System.currentTimeMillis(), url))
    }

    fun save(url: String, rows: List<Broadcast>, now: Long, response: FeedResponse) {
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
            saveMetadata(db, url, now, response)
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
