package pro.sitescope.app.keys

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** One permanently excluded *configuration*, never an entire server/IP. */
data class BlockedKey(
    val item: KeyItem,
    val reason: String,
    val blockedAt: Long = System.currentTimeMillis(),
    val lastCheckAt: Long = 0L
) {
    val fingerprint: String get() = KeyBlocklistStore.fingerprint(item.link)
}

/**
 * Separate, persistent deny-list. A fresh key with different credentials must be
 * accepted even when it uses the same host/port as a blocked profile.
 * Secrets are not written to logs; the profile itself stays in app-private storage
 * because it must be available for later re-validation.
 */
object KeyBlocklistStore {
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "keyhub_blacklist.json"))

    fun fingerprint(link: String): String {
        val raw = link.substringBefore('#').trim()
        val material = if (raw.startsWith("vmess://", ignoreCase = true)) {
            // VMess names are part of encoded JSON. Ignore only the display name.
            try {
                val payload = raw.substringAfter("://")
                val decoded = try {
                    Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP)
                } catch (_: Exception) { Base64.decode(payload, Base64.DEFAULT) }
                val json = JSONObject(String(decoded, Charsets.UTF_8))
                json.remove("ps")
                "vmess|" + json.keys().asSequence().toList().sorted()
                    .joinToString("|") { name -> "$name=" + json.opt(name) }
            } catch (_: Exception) { raw }
        } else raw
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    @Synchronized
    fun load(context: Context): List<BlockedKey> {
        val atomic = file(context)
        if (!atomic.baseFile.exists()) return emptyList()
        return try {
            val contents = atomic.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
            val array = JSONArray(contents)
            val result = LinkedHashMap<String, BlockedKey>()
            for (index in 0 until array.length()) {
                val obj = array.optJSONObject(index) ?: continue
                val raw = obj.optString("link")
                val type = obj.optString("type")
                if (raw.isBlank() || type.isBlank()) continue
                val item = KeyItem(
                    raw, obj.optString("name"), type, obj.optString("host"),
                    obj.optInt("port"), obj.optString("source"), obj.optLong("foundAt")
                )
                item.ip = obj.optString("ip")
                item.country = obj.optString("country")
                item.ms = obj.optLong("ms", -1L)
                item.tls = obj.optInt("tls")
                item.anon = obj.optInt("anon")
                item.https = obj.optInt("https")
                item.checkedAt = obj.optLong("checkedAt")
                item.validation = obj.optInt("validation")
                item.validationMs = obj.optLong("validationMs", -1L)
                item.validationAt = obj.optLong("validationAt")
                item.validationNote = obj.optString("validationNote")
                val entry = BlockedKey(
                    item, obj.optString("reason", "Исключён вручную"),
                    obj.optLong("blockedAt"), obj.optLong("lastCheckAt")
                )
                result.putIfAbsent(entry.fingerprint, entry)
            }
            result.values.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(context: Context, entries: List<BlockedKey>) {
        val array = JSONArray()
        entries.distinctBy { it.fingerprint }.forEach { entry ->
            val k = entry.item
            array.put(JSONObject()
                .put("link", k.link).put("name", k.name).put("type", k.type)
                .put("host", k.host).put("port", k.port)
                .put("source", k.source).put("foundAt", k.foundAt)
                .put("ip", k.ip).put("country", k.country).put("ms", k.ms)
                .put("tls", k.tls).put("anon", k.anon).put("https", k.https)
                .put("checkedAt", k.checkedAt)
                .put("validation", k.validation).put("validationMs", k.validationMs)
                .put("validationAt", k.validationAt).put("validationNote", k.validationNote)
                .put("reason", entry.reason).put("blockedAt", entry.blockedAt)
                .put("lastCheckAt", entry.lastCheckAt)
            )
        }
        val atomic = file(context)
        val stream = atomic.startWrite()
        try {
            stream.write(array.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (error: Exception) {
            atomic.failWrite(stream)
            throw error
        }
    }
}
