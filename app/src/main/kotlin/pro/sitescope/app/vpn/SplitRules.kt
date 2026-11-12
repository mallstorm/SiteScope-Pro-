package pro.sitescope.app.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import pro.sitescope.app.VpnDiagnostics
import pro.sitescope.app.XrayConfigBuilder

/**
 * Доменная маршрутизация внутри тоннеля: часть сайтов — напрямую с телефона (например, сайт
 * не работает из-за VPN-сервера или через него медленнее), часть — обязательно через тоннель,
 * даже если другое правило (например, «свой адрес» в MITM) забрало бы их себе.
 * Применяется последним, поэтому встаёт выше всех прочих правил маршрутизации.
 */
object SplitRules {
    const val DIRECT_TAG = "sitescope-split-direct"

    @JvmStatic fun apply(context: Context, json: String): String {
        val rules = VpnSettings.splitRules(context)
        if (rules.isEmpty()) return json
        return try {
            val root = JSONObject(json)
            val routing = root.getJSONObject("routing")
            val old = routing.getJSONArray("rules")
            val mainTag = findMainTag(old)

            val outbounds = root.getJSONArray("outbounds")
            if (!hasTag(outbounds, DIRECT_TAG)) outbounds.put(JSONObject().put("tag", DIRECT_TAG).put("protocol", "freedom")
                .put("settings", JSONObject().put("domainStrategy", "UseIP")))

            val socks = JSONArray().put(XrayConfigBuilder.SOCKS_TAG)
            val fresh = JSONArray()
            for ((domain, direct) in rules) {
                val clean = domain.trim().removePrefix("http://").removePrefix("https://").substringBefore('/')
                if (clean.isEmpty()) continue
                val domainMatch = JSONArray().put("domain:" + clean.removePrefix("*."))
                fresh.put(JSONObject().put("type", "field").put("inboundTag", socks).put("domain", domainMatch)
                    .put("outboundTag", if (direct) DIRECT_TAG else mainTag))
            }
            for (i in 0 until old.length()) fresh.put(old.get(i))
            routing.put("rules", fresh)
            VpnDiagnostics.log(context, "SPLIT_RULES_ON", "${rules.size} домен(ов), прямых: ${rules.count { it.second }}")
            root.toString()
        } catch (e: Exception) {
            VpnDiagnostics.log(context, "SPLIT_RULES_SKIPPED", e.message ?: e.javaClass.simpleName)
            json
        }
    }

    private fun hasTag(outbounds: JSONArray, tag: String): Boolean {
        for (i in 0 until outbounds.length()) if (outbounds.optJSONObject(i)?.optString("tag") == tag) return true
        return false
    }

    /** Основной выход (реальный сервер) — правило без ограничений по порту/сети/домену. */
    private fun findMainTag(rules: JSONArray): String {
        for (i in 0 until rules.length()) {
            val rule = rules.optJSONObject(i) ?: continue
            val inbound = rule.optJSONArray("inboundTag")
            if (inbound != null && inbound.length() == 1 && inbound.optString(0) == XrayConfigBuilder.SOCKS_TAG &&
                !rule.has("port") && !rule.has("network") && !rule.has("domain") && !rule.has("ip")) return rule.optString("outboundTag", XrayConfigBuilder.PROXY_TAG)
        }
        return XrayConfigBuilder.PROXY_TAG
    }
}
