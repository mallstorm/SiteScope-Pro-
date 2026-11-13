package pro.sitescope.app.dpi

import org.json.JSONArray
import org.json.JSONObject

/** Встраивает byedpi в конфигурацию Xray: исходящие соединения ядра идут через локальный SOCKS5 byedpi (sockopt.dialerProxy). */
object DpiChainConfig {
    const val TAG = "sitescope-dpi"

    @JvmStatic
    fun apply(json: String, port: Int): String {
        if (port <= 0) return json
        val root = JSONObject(json)
        val outbounds = root.optJSONArray("outbounds") ?: JSONArray().also { root.put("outbounds", it) }
        var hasTag = false
        for (i in 0 until outbounds.length()) if (outbounds.getJSONObject(i).optString("tag") == TAG) hasTag = true
        if (!hasTag) {
            val server = JSONObject().put("address", "127.0.0.1").put("port", port)
            outbounds.put(JSONObject().put("tag", TAG).put("protocol", "socks").put("settings", JSONObject().put("servers", JSONArray().put(server))))
        }
        for (i in 0 until outbounds.length()) {
            val o = outbounds.getJSONObject(i)
            val protocol = o.optString("protocol")
            if (o.optString("tag") == TAG || protocol == "dns" || protocol == "blackhole" || protocol == "loopback") continue
            val stream = o.optJSONObject("streamSettings") ?: JSONObject().also { o.put("streamSettings", it) }
            val sockopt = stream.optJSONObject("sockopt") ?: JSONObject().also { stream.put("sockopt", it) }
            if (!sockopt.has("dialerProxy")) sockopt.put("dialerProxy", TAG)
        }
        return root.toString()
    }
}
