package pro.sitescope.app.keys

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Durable, independent discovery pool. Discovery never silently truncates this
 * pool to the number selected for one check. Positive, slow and inconclusive
 * results are retained until the user retries, clears the pool, or blacklists
 * an entry. Verified usable keys live in KeyStore instead.
 */
object KeyCandidateStore {
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "keyhub_candidates.json"))

    @Synchronized
    fun load(context: Context): List<KeyItem> {
        val json = try {
            file(context).openRead().use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Exception) { return emptyList() }
        return try {
            val array = JSONArray(json)
            val unique = LinkedHashMap<String, KeyItem>()
            for (index in 0 until array.length()) {
                val o = array.optJSONObject(index) ?: continue
                val link = o.optString("link")
                if (link.isBlank()) continue
                val item = KeyItem(
                    link, o.optString("name"), o.optString("type"), o.optString("host"),
                    o.optInt("port"), o.optString("source"), o.optLong("foundAt")
                )
                item.ip = o.optString("ip")
                item.country = o.optString("country")
                item.ms = o.optLong("ms", -1L)
                item.tls = o.optInt("tls")
                item.checkedAt = o.optLong("checkedAt")
                item.anon = o.optInt("anon")
                item.https = o.optInt("https")
                item.validation = o.optInt("validation")
                item.validationMs = o.optLong("validationMs", -1L)
                item.validationAt = o.optLong("validationAt")
                item.validationNote = o.optString("validationNote")
                unique.putIfAbsent(KeyBlocklistStore.fingerprint(link), item)
            }
            unique.values.toList()
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized
    fun save(context: Context, entries: List<KeyItem>) {
        val array = JSONArray()
        for (item in entries.distinctBy { KeyBlocklistStore.fingerprint(it.link) }) {
            array.put(JSONObject()
                .put("link", item.link).put("name", item.name)
                .put("type", item.type).put("host", item.host)
                .put("port", item.port).put("source", item.source)
                .put("foundAt", item.foundAt).put("ip", item.ip)
                .put("country", item.country).put("ms", item.ms)
                .put("tls", item.tls).put("checkedAt", item.checkedAt)
                .put("anon", item.anon).put("https", item.https)
                .put("validation", item.validation).put("validationMs", item.validationMs)
                .put("validationAt", item.validationAt)
                .put("validationNote", item.validationNote))
        }
        val atomic = file(context)
        val output = atomic.startWrite()
        try {
            output.write(array.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    @Synchronized fun clear(context: Context) { file(context).delete() }
}
