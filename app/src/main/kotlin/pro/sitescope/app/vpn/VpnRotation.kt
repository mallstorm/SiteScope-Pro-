package pro.sitescope.app.vpn

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import pro.sitescope.app.HostsVpnService
import pro.sitescope.app.XrayProfileStore
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Плавающая ротация серверов. Пул до 10 серверов проверяется на отклик; медленные и недоступные
 * выпадают из списка, вернувшиеся в норму возвращаются. Раз в выбранный срок VPN переезжает на
 * следующий хороший сервер (по возможности в другой стране) горячей заменой ядра Xray: туннель
 * телефона не пересоздаётся. Если текущий сервер ухудшился, переезд происходит сразу.
 */
object VpnRotation {
    private const val PREFS = "vpn_rotation"
    private const val WORK = "sitescope_vpn_rotation"
    const val MAX_POOL = 10
    val INTERVALS = listOf(30, 60, 120, 360)
    private const val MIN_GAP_MS = 5 * 60_000L
    private const val FAILS_TO_DROP = 3

    class Stat(val id: String, var ema: Double = -1.0, var last: Long = -1, var fails: Int = 0, var dropped: Boolean = false, var usedAt: Long = 0, var checkedAt: Long = 0)
    class Event(val time: Long, val text: String)

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun enabled(c: Context) = p(c).getBoolean("enabled", false)
    fun intervalMin(c: Context) = p(c).getInt("interval", 60)
    fun pool(c: Context): List<String> = p(c).getString("pool", "")!!.split(',').filter { it.isNotBlank() }.take(MAX_POOL)
    fun nextAt(c: Context) = p(c).getLong("nextAt", 0L)
    fun lastSwitchAt(c: Context) = p(c).getLong("lastSwitch", 0L)

    fun setEnabled(c: Context, v: Boolean) {
        p(c).edit().putBoolean("enabled", v).putLong("nextAt", if (v) System.currentTimeMillis() + intervalMin(c) * 60_000L else 0L).apply()
        schedule(c)
        if (v) log(c, "Ротация включена")
    }
    fun setInterval(c: Context, min: Int) { p(c).edit().putInt("interval", min).putLong("nextAt", System.currentTimeMillis() + min * 60_000L).apply() }
    fun setPool(c: Context, ids: List<String>) { p(c).edit().putString("pool", ids.take(MAX_POOL).joinToString(",")).apply() }

    fun schedule(c: Context) {
        val wm = WorkManager.getInstance(c)
        if (enabled(c)) wm.enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, PeriodicWorkRequestBuilder<RotationWorker>(15, TimeUnit.MINUTES).build())
        else wm.cancelUniqueWork(WORK)
    }

    // ---------- состояние ----------
    fun stats(c: Context): Map<String, Stat> {
        val o = try { JSONObject(p(c).getString("stats", "{}")) } catch (e: Exception) { JSONObject() }
        val map = LinkedHashMap<String, Stat>()
        for (k in o.keys()) { val s = o.getJSONObject(k); map[k] = Stat(k, s.optDouble("ema", -1.0), s.optLong("last", -1), s.optInt("fails"), s.optBoolean("dropped"), s.optLong("usedAt"), s.optLong("checkedAt")) }
        return map
    }
    private fun saveStats(c: Context, m: Map<String, Stat>) {
        val o = JSONObject()
        m.values.forEach { o.put(it.id, JSONObject().put("ema", it.ema).put("last", it.last).put("fails", it.fails).put("dropped", it.dropped).put("usedAt", it.usedAt).put("checkedAt", it.checkedAt)) }
        p(c).edit().putString("stats", o.toString()).apply()
    }
    fun events(c: Context): List<Event> {
        val a = try { JSONArray(p(c).getString("events", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until a.length()).map { Event(a.getJSONObject(it).optLong("t"), a.getJSONObject(it).optString("m")) }
    }
    private fun log(c: Context, text: String) {
        val a = JSONArray(); a.put(JSONObject().put("t", System.currentTimeMillis()).put("m", text))
        val old = try { JSONArray(p(c).getString("events", "[]")) } catch (e: Exception) { JSONArray() }
        for (i in 0 until minOf(old.length(), 19)) a.put(old.get(i))
        p(c).edit().putString("events", a.toString()).apply()
    }

    private fun running(c: Context): Pair<Boolean, String> {
        val s = c.getSharedPreferences(HostsVpnService.PREFS, Context.MODE_PRIVATE)
        val on = s.getString(HostsVpnService.KEY_STATE, "") == HostsVpnService.STATE_ACTIVE && s.getString("mode", "") == HostsVpnService.MODE_XRAY
        return on to s.getString("profileId", "").orEmpty()
    }

    /** Один проход: замер пула, пересборка списка, при необходимости смена сервера. */
    suspend fun tick(c: Context, force: Boolean = false): String = withContext(Dispatchers.IO) {
        val store = XrayProfileStore(c)
        val all = store.profiles().associateBy { it.id }
        val ids = pool(c).filter { all.containsKey(it) }
        if (ids.size < 2) return@withContext "В пуле нужно минимум 2 сервера"
        val stats = stats(c).filterKeys { it in ids }.toMutableMap()
        val now = System.currentTimeMillis()
        coroutineScope {
            ids.map { id -> async { id to probe(all.getValue(id).endpoint) } }.awaitAll()
        }.forEach { (id, ms) ->
            val s = stats.getOrPut(id) { Stat(id) }
            s.checkedAt = now; s.last = ms
            if (ms < 0) s.fails++ else { s.fails = 0; s.ema = if (s.ema < 0) ms.toDouble() else s.ema * 0.6 + ms * 0.4 }
        }
        // Выпадение и возврат: гистерезис, чтобы список не мигал.
        val live = stats.values.filter { it.ema >= 0 && it.fails == 0 }
        val best = live.minOfOrNull { it.ema } ?: -1.0
        for (s in stats.values) {
            val bad = s.fails >= FAILS_TO_DROP || (best >= 0 && s.ema > maxOf(best * 2.5, 400.0) && s.fails == 0)
            val good = s.fails == 0 && s.ema >= 0 && (best < 0 || s.ema <= best * 1.5 + 50)
            if (!s.dropped && bad) { s.dropped = true; log(c, "Исключён: ${all[s.id]?.name.orEmpty().ifEmpty { s.id }} (${if (s.fails >= FAILS_TO_DROP) "не отвечает" else "отклик ${s.ema.toInt()} мс"})") }
            else if (s.dropped && good) { s.dropped = false; log(c, "Вернулся в список: ${all[s.id]?.name.orEmpty().ifEmpty { s.id }} (${s.ema.toInt()} мс)") }
        }
        saveStats(c, stats)

        val (on, currentId) = running(c)
        if (!on) return@withContext "Проверено. VPN не работает через сервер, переключать нечего"
        val healthy = stats.values.filter { !it.dropped && it.ema >= 0 && it.fails == 0 }.sortedBy { it.ema }
        if (healthy.isEmpty()) return@withContext "Проверено. Нет ни одного живого сервера в пуле"
        val cur = stats[currentId]
        val lastSwitch = lastSwitchAt(c)
        val currentBad = cur != null && (cur.dropped || cur.fails > 0)
        val due = force || now >= nextAt(c)
        if (!due && !(currentBad && now - lastSwitch > MIN_GAP_MS)) return@withContext "Проверено. Текущий сервер в норме"

        val curCountry = Countries.of(all[currentId]?.name.orEmpty(), all[currentId]?.endpoint.orEmpty())
        val candidates = healthy.filter { it.id != currentId }.take(5)
        if (candidates.isEmpty()) return@withContext "Проверено. Другого живого сервера нет"
        // Предпочитаем другую страну, затем тот, что давно не использовался.
        val pick = candidates.sortedWith(compareBy<Stat>({ if (Countries.of(all[it.id]?.name.orEmpty(), all[it.id]?.endpoint.orEmpty()) == curCountry && curCountry != "") 1 else 0 }, { it.usedAt }, { it.ema })).first()
        switchTo(c, pick.id, all.getValue(pick.id).name.ifEmpty { all.getValue(pick.id).endpoint })
        pick.usedAt = now; saveStats(c, stats)
        val jitter = (intervalMin(c) * 60_000L * (0.9 + Math.random() * 0.2)).toLong()
        p(c).edit().putLong("nextAt", now + jitter).putLong("lastSwitch", now).apply()
        log(c, (if (currentBad) "Аварийная смена → " else "Плановая смена → ") + all.getValue(pick.id).name.ifEmpty { all.getValue(pick.id).endpoint } + " (${pick.ema.toInt()} мс)")
        "Переключено на ${all.getValue(pick.id).name}"
    }

    /** Горячая смена: ядро Xray перечитывает конфиг, туннель и приложения остаются на месте. */
    fun switchTo(c: Context, id: String, name: String) {
        XrayProfileStore(c).select(id)
        c.getSharedPreferences(HostsVpnService.PREFS, Context.MODE_PRIVATE).edit().putString("profileId", id).putString("profile", name).apply()
        val i = Intent(c, HostsVpnService::class.java).setAction(HostsVpnService.ACTION_RELOAD)
        try { c.startService(i) } catch (e: Exception) { try { c.startForegroundService(i) } catch (_: Exception) { } }
    }

    private fun probe(endpoint: String): Long {
        val host = endpoint.substringBeforeLast(':', endpoint)
        val port = endpoint.substringAfterLast(':', "").toIntOrNull() ?: 443
        if (host.isEmpty()) return -1
        val samples = ArrayList<Long>()
        repeat(3) {
            try {
                val t0 = System.nanoTime()
                Socket().use { it.connect(InetSocketAddress(host, port), 3000) }
                samples += (System.nanoTime() - t0) / 1_000_000
            } catch (e: Exception) { }
        }
        return if (samples.isEmpty()) -1 else samples.sorted()[samples.size / 2]
    }
}

class RotationWorker(c: Context, params: WorkerParameters) : CoroutineWorker(c, params) {
    override suspend fun doWork(): Result {
        if (!VpnRotation.enabled(applicationContext)) return Result.success()
        try { VpnRotation.tick(applicationContext) } catch (e: Exception) { }
        return Result.success()
    }
}

/** Страна сервера по флагу-эмодзи, названию или домену. */
object Countries {
    private val names = listOf(
        "AT" to listOf("austria", "австри", "wien", "vienna"), "DE" to listOf("germany", "герман", "frankfurt", "berlin", "deutsch"),
        "NL" to listOf("netherlands", "нидерланд", "голланд", "amsterdam"), "FR" to listOf("france", "франци", "paris"),
        "GB" to listOf("united kingdom", "британи", "england", "london", "англи"), "US" to listOf("united states", "usa", "сша", "америк"),
        "FI" to listOf("finland", "финлянд", "helsinki"), "SE" to listOf("sweden", "швеци", "stockholm"), "PL" to listOf("poland", "польш", "warsaw"),
        "CH" to listOf("switzerland", "швейцар", "zurich"), "TR" to listOf("turkey", "турци", "istanbul"), "KZ" to listOf("kazakhstan", "казахстан"),
        "RU" to listOf("russia", "росси"), "JP" to listOf("japan", "япони", "tokyo"), "SG" to listOf("singapore", "сингапур"),
        "CZ" to listOf("czech", "чехи", "prague"), "RO" to listOf("romania", "румын"), "LV" to listOf("latvia", "латви"),
        "LT" to listOf("lithuania", "литв"), "EE" to listOf("estonia", "эстон"), "ES" to listOf("spain", "испани"), "IT" to listOf("italy", "итали"),
        "CA" to listOf("canada", "канад"), "AM" to listOf("armenia", "армени"), "GE" to listOf("georgia", "грузи"), "AE" to listOf("emirates", "эмират", "dubai"))
    private val tld = mapOf("at" to "AT", "de" to "DE", "nl" to "NL", "fr" to "FR", "uk" to "GB", "fi" to "FI", "se" to "SE", "pl" to "PL", "ch" to "CH", "tr" to "TR", "kz" to "KZ", "jp" to "JP", "sg" to "SG", "cz" to "CZ")

    fun of(name: String, endpoint: String): String {
        val cps = name.codePoints().toArray()
        for (i in 0 until cps.size - 1) if (cps[i] in 0x1F1E6..0x1F1FF && cps[i + 1] in 0x1F1E6..0x1F1FF)
            return "" + ('A' + (cps[i] - 0x1F1E6)) + ('A' + (cps[i + 1] - 0x1F1E6))
        val n = name.lowercase()
        names.firstOrNull { (_, keys) -> keys.any { n.contains(it) } }?.let { return it.first }
        Regex("(^|[^a-z])(at|de|nl|fr|uk|us|fi|se|pl|ch|tr|kz|ru|jp|sg|cz)([^a-z]|$)").find(n)?.let { return it.groupValues[2].uppercase().let { c -> if (c == "UK") "GB" else c } }
        return tld[endpoint.substringBeforeLast(':').substringAfterLast('.').lowercase()] ?: ""
    }

    fun flag(code: String): String = if (code.length != 2) "🌐" else String(Character.toChars(0x1F1E6 + (code[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (code[1] - 'A')))
}
