package pro.sitescope.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Журнал сохранённых отчётов: имя, источник, формат, размер и время. Содержимое файлов здесь не хранится. */
object ReportLog {
    class Entry(val name: String, val source: String, val format: String, val size: Long, val time: Long, val uri: String = "")

    private const val PREFS = "report_log"
    private const val KEY = "entries"
    private const val LIMIT = 100

    @Synchronized fun add(context: Context, name: String, source: String, format: String, size: Long, uri: String = "") {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = try { JSONArray(prefs.getString(KEY, "[]")) } catch (e: Exception) { JSONArray() }
        val next = JSONArray()
        next.put(JSONObject().put("name", name).put("source", source).put("format", format).put("size", size).put("time", System.currentTimeMillis()).put("uri", uri))
        for (i in 0 until minOf(old.length(), LIMIT - 1)) next.put(old.getJSONObject(i))
        prefs.edit().putString(KEY, next.toString()).apply()
    }

    @Synchronized fun list(context: Context): List<Entry> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = try { JSONArray(prefs.getString(KEY, "[]")) } catch (e: Exception) { JSONArray() }
        val out = ArrayList<Entry>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            out.add(Entry(o.optString("name"), o.optString("source"), o.optString("format"), o.optLong("size"), o.optLong("time"), o.optString("uri")))
        }
        return out
    }

    @Synchronized fun remove(context: Context, time: Long) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val old = try { JSONArray(prefs.getString(KEY, "[]")) } catch (e: Exception) { JSONArray() }
        val next = JSONArray()
        for (i in 0 until old.length()) { val o = old.optJSONObject(i) ?: continue; if (o.optLong("time") != time) next.put(o) }
        prefs.edit().putString(KEY, next.toString()).apply()
    }

    fun count(context: Context): Int = list(context).size

    @Synchronized fun clear(context: Context) { context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply() }
}
