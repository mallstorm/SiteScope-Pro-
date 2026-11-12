package pro.sitescope.app.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import pro.sitescope.app.VpnDiagnostics
import pro.sitescope.app.XrayConfigBuilder

/**
 * Блокировка по хосту: домены и адреса из чёрного списка идут в outbound «blackhole» (обрыв
 * соединения), минуя и прямой выход, и туннель. Правило добавляется выше всех остальных — выше
 * даже доменов из [SplitRules] — поэтому применять нужно последним в цепочке построения конфига.
 */
object BlockRules {
    const val BLACKHOLE_TAG = "sitescope-blackhole"

    @JvmStatic fun apply(context: Context, json: String): String {
        val hosts = VpnSettings.blockedHosts(context)
        if (hosts.isEmpty()) return json
        return try {
            val root = JSONObject(json)
            val routing = root.getJSONObject("routing")
            val old = routing.getJSONArray("rules")

            val outbounds = root.getJSONArray("outbounds")
            if (!hasTag(outbounds, BLACKHOLE_TAG)) outbounds.put(JSONObject().put("tag", BLACKHOLE_TAG).put("protocol", "blackhole").put("settings", JSONObject()))

            val socks = JSONArray().put(XrayConfigBuilder.SOCKS_TAG)
            val fresh = JSONArray()
            var domainCount = 0; var ipCount = 0
            for (raw in hosts) {
                val clean = raw.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
                if (clean.isEmpty()) continue
                val rule = JSONObject().put("type", "field").put("inboundTag", socks).put("outboundTag", BLACKHOLE_TAG)
                if (isIpLike(clean)) { rule.put("ip", JSONArray().put(clean)); ipCount++ }
                else { rule.put("domain", JSONArray().put("domain:" + clean.removePrefix("*."))); domainCount++ }
                fresh.put(rule)
            }
            for (i in 0 until old.length()) fresh.put(old.get(i))
            routing.put("rules", fresh)
            VpnDiagnostics.log(context, "BLOCK_RULES_ON", "доменов: $domainCount, адресов: $ipCount")
            root.toString()
        } catch (e: Exception) {
            VpnDiagnostics.log(context, "BLOCK_RULES_SKIPPED", e.message ?: e.javaClass.simpleName)
            json
        }
    }

    private fun isIpLike(v: String): Boolean = v.all { it.isDigit() || it == '.' || it == '/' } || v.contains(':')

    private fun hasTag(outbounds: JSONArray, tag: String): Boolean {
        for (i in 0 until outbounds.length()) if (outbounds.optJSONObject(i)?.optString("tag") == tag) return true
        return false
    }
}
