package pro.sitescope.app.tv

import android.content.Context
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.zip.GZIPInputStream

class Programme(val start: Long, val stop: Long, val title: String, val desc: String) {
    fun progress(now: Long): Float = if (stop <= start) 0f else ((now - start).toFloat() / (stop - start)).coerceIn(0f, 1f)
}

/** Телепрограмма (XMLTV). Хранит только ближайшие сутки и только для каналов из плейлиста. */
object Epg {
    private const val FILE = "tv_epg.json"
    private const val MAX_PROGRAMMES = 80_000
    private val data = HashMap<String, ArrayList<Programme>>()
    private val nameToId = HashMap<String, String>()
    @Volatile var fetchedAt = 0L; private set
    @Volatile var channelCount = 0; private set
    private var loaded = false

    fun key(name: String): String = name.lowercase().replace(Regex("\\s+(hd|fhd|uhd|4k|sd)$"), "").replace(Regex("[^a-zа-яё0-9]"), "")

    @Synchronized fun forChannel(c: Channel): List<Programme> {
        data[c.tvgId]?.let { if (c.tvgId.isNotEmpty()) return it }
        val id = nameToId[key(c.name)] ?: nameToId[key(c.tvgName)] ?: return emptyList()
        return data[id] ?: emptyList()
    }

    fun now(c: Channel, time: Long = System.currentTimeMillis()): Programme? = forChannel(c).firstOrNull { it.start <= time && time < it.stop }
    fun next(c: Channel, time: Long = System.currentTimeMillis()): Programme? = forChannel(c).firstOrNull { it.start > time }

    @Synchronized fun loadCache(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val file = File(context.filesDir, FILE)
            if (!file.exists()) return
            val o = JSONObject(file.readText())
            fetchedAt = o.optLong("at")
            val names = o.optJSONObject("names")
            if (names != null) for (k in names.keys()) nameToId[k] = names.getString(k)
            val ch = o.getJSONObject("ch")
            val cutoff = System.currentTimeMillis() - 24 * 3600_000L
            for (id in ch.keys()) {
                val arr = ch.getJSONArray(id); val list = ArrayList<Programme>()
                for (i in 0 until arr.length()) {
                    val p = arr.getJSONArray(i)
                    if (p.getLong(1) > cutoff) list.add(Programme(p.getLong(0), p.getLong(1), p.getString(2), p.optString(3)))
                }
                if (list.isNotEmpty()) data[id] = list
            }
            channelCount = data.size
        } catch (e: Exception) { }
    }

    /** Скачивает и разбирает программу. Возвращает число каналов с передачами. */
    fun refresh(context: Context, urls: List<String>, channels: List<Channel>): Int {
        val wantedIds = channels.map { it.tvgId }.filter { it.isNotEmpty() }.toHashSet()
        val wantedNames = HashSet<String>()
        for (c in channels) { wantedNames.add(key(c.name)); if (c.tvgName.isNotEmpty()) wantedNames.add(key(c.tvgName)) }
        val fresh = HashMap<String, ArrayList<Programme>>(); val names = HashMap<String, String>()
        var lastError: Exception? = null
        for (url in urls) {
            try { open(url).use { parse(it, wantedIds, wantedNames, fresh, names) } } catch (e: Exception) { lastError = e }
        }
        if (fresh.isEmpty() && lastError != null) throw lastError
        for (list in fresh.values) list.sortBy { it.start }
        synchronized(this) {
            data.clear(); data.putAll(fresh); nameToId.clear(); nameToId.putAll(names)
            fetchedAt = System.currentTimeMillis(); channelCount = data.size; loaded = true
        }
        saveCache(context)
        return fresh.size
    }

    private fun open(url: String): InputStream {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 40000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", StreamChecker.DEFAULT_UA)
        if (c.responseCode !in 200..299) throw java.io.IOException("Телепрограмма недоступна (${c.responseCode})")
        val input = BufferedInputStream(c.inputStream, 65536)
        input.mark(4); val b0 = input.read(); val b1 = input.read(); input.reset()
        return if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(input, 65536) else input
    }

    fun parse(input: InputStream, wantedIds: Set<String>, wantedNames: Set<String>,
              out: HashMap<String, ArrayList<Programme>>, names: HashMap<String, String>) {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)
        val now = System.currentTimeMillis()
        val from = now - 24 * 3600_000L; val to = now + 30 * 3600_000L
        val accepted = HashSet<String>(wantedIds)
        var total = out.values.sumOf { it.size }
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "channel" -> {
                        val id = parser.getAttributeValue(null, "id") ?: ""
                        var depth = 1
                        while (depth > 0) {
                            val e = parser.next()
                            if (e == XmlPullParser.START_TAG) {
                                depth++
                                if (parser.name == "display-name") {
                                    val name = key(parser.nextText()); depth--
                                    if (id.isNotEmpty() && name.isNotEmpty() && wantedNames.contains(name)) { names[name] = id; accepted.add(id) }
                                }
                            } else if (e == XmlPullParser.END_TAG) depth--
                            else if (e == XmlPullParser.END_DOCUMENT) return
                        }
                    }
                    "programme" -> {
                        val id = parser.getAttributeValue(null, "channel") ?: ""
                        val start = time(parser.getAttributeValue(null, "start"))
                        val stop = time(parser.getAttributeValue(null, "stop"))
                        val keep = accepted.contains(id) && stop > from && start < to && total < MAX_PROGRAMMES
                        var title = ""; var desc = ""
                        var depth = 1
                        while (depth > 0) {
                            val e = parser.next()
                            if (e == XmlPullParser.START_TAG) {
                                depth++
                                if (keep && parser.name == "title" && title.isEmpty()) { title = parser.nextText().trim(); depth-- }
                                else if (keep && parser.name == "desc" && desc.isEmpty()) { desc = parser.nextText().trim().take(400); depth-- }
                            } else if (e == XmlPullParser.END_TAG) depth--
                            else if (e == XmlPullParser.END_DOCUMENT) return
                        }
                        if (keep && title.isNotEmpty()) { out.getOrPut(id) { ArrayList() }.add(Programme(start, stop, title, desc)); total++ }
                    }
                }
            }
            event = parser.next()
        }
    }

    fun time(value: String?): Long {
        if (value == null || value.length < 12) return 0
        return try {
            val text = value.trim()
            val base = SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
            val zone = text.substringAfter(' ', "").trim()
            base.timeZone = if (zone.length >= 5) TimeZone.getTimeZone("GMT$zone") else TimeZone.getTimeZone("UTC")
            base.parse(text.take(14).padEnd(14, '0'))?.time ?: 0
        } catch (e: Exception) { 0 }
    }

    @Synchronized private fun saveCache(context: Context) {
        try {
            val ch = JSONObject()
            for ((id, list) in data) {
                val arr = JSONArray()
                for (p in list) arr.put(JSONArray().put(p.start).put(p.stop).put(p.title).put(p.desc))
                ch.put(id, arr)
            }
            val names = JSONObject(); for ((k, v) in nameToId) names.put(k, v)
            File(context.filesDir, FILE).writeText(JSONObject().put("at", fetchedAt).put("ch", ch).put("names", names).toString())
        } catch (e: Exception) { }
    }
}
