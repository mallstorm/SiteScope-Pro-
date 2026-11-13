package pro.sitescope.app.dpi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Все настройки обхода DPI. Хранятся в SharedPreferences «dpi_bypass» (входят в резервную копию). */
data class DpiConfig(
    /** proxy — сервис без VPN; vpn_all / vpn_only / vpn_except — VPN для всех / только выбранных / всех кроме выбранных. */
    val mode: String = "proxy",
    val apps: Set<String> = emptySet(),
    val port: Int = 1080,
    val line: String = "-r1+s -s1+s",
    val useSimple: Boolean = false,
    val simple: DpiSimple = DpiSimple(),
    val sni: String = "google.com",
    val hostsOnly: Boolean = false,
    val hosts: String = "",
    val dns: String = "1.1.1.1",
    val ipv6: Boolean = false,
    val autostart: Boolean = false,
    val chainXray: Boolean = false,
    val browserViaDpi: Boolean = false,
    val history: List<String> = emptyList(),
) {
    val isVpn get() = mode.startsWith("vpn")

    /** Строка стратегии с учётом конструктора и списка доменов. */
    fun effectiveLine(): String {
        val base = if (useSimple) simple.toLine() else line
        val domains = DpiSites.parse(hosts)
        return if (hostsOnly && domains.isNotEmpty()) "\"-H:${domains.joinToString(" ")}\" $base" else base
    }

    fun args(portOverride: Int = port): List<String> = DpiArgs.build(effectiveLine(), portOverride, sni)
}

object DpiStore {
    private const val PREFS = "dpi_bypass"

    fun load(context: Context): DpiConfig {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = DpiConfig()
        val simple = try {
            val o = JSONObject(p.getString("simple", "{}") ?: "{}")
            val s = DpiSimple()
            DpiSimple(
                method = runCatching { DpiSimple.Method.valueOf(o.optString("method", s.method.name)) }.getOrDefault(s.method),
                position = o.optInt("position", s.position), atHost = o.optBoolean("atHost", s.atHost),
                tlsRecord = o.optBoolean("tlsRecord", s.tlsRecord), tlsRecordPosition = o.optInt("tlsRecordPosition", s.tlsRecordPosition),
                tlsRecordAtSni = o.optBoolean("tlsRecordAtSni", s.tlsRecordAtSni), fakeTtl = o.optInt("fakeTtl", s.fakeTtl),
                fakeSni = o.optString("fakeSni", s.fakeSni), oobByte = o.optString("oobByte", s.oobByte),
                httpMixedCaseHost = o.optBoolean("mh", s.httpMixedCaseHost), httpMixedCaseDomain = o.optBoolean("md", s.httpMixedCaseDomain),
                httpRemoveSpace = o.optBoolean("mr", s.httpRemoveSpace), desyncHttps = o.optBoolean("https", s.desyncHttps),
                desyncHttp = o.optBoolean("http", s.desyncHttp), udpFake = o.optInt("udpFake", s.udpFake), tfo = o.optBoolean("tfo", s.tfo),
                dropSack = o.optBoolean("dropSack", s.dropSack), defaultTtl = o.optInt("defaultTtl", s.defaultTtl),
            )
        } catch (e: Exception) { DpiSimple() }
        val history = try { JSONArray(p.getString("history", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } } catch (e: Exception) { emptyList() }
        return DpiConfig(
            mode = p.getString("mode", d.mode) ?: d.mode, apps = p.getStringSet("apps", emptySet())?.toSet() ?: emptySet(),
            port = p.getInt("port", d.port).coerceIn(1024, 65535), line = p.getString("line", d.line) ?: d.line,
            useSimple = p.getBoolean("useSimple", d.useSimple), simple = simple, sni = p.getString("sni", d.sni) ?: d.sni,
            hostsOnly = p.getBoolean("hostsOnly", d.hostsOnly), hosts = p.getString("hosts", "") ?: "",
            dns = p.getString("dns", d.dns) ?: d.dns, ipv6 = p.getBoolean("ipv6", d.ipv6), autostart = p.getBoolean("autostart", d.autostart),
            chainXray = p.getBoolean("chainXray", d.chainXray), browserViaDpi = p.getBoolean("browserViaDpi", d.browserViaDpi), history = history,
        )
    }

    fun save(context: Context, c: DpiConfig) {
        val s = c.simple
        val simple = JSONObject().put("method", s.method.name).put("position", s.position).put("atHost", s.atHost).put("tlsRecord", s.tlsRecord)
            .put("tlsRecordPosition", s.tlsRecordPosition).put("tlsRecordAtSni", s.tlsRecordAtSni).put("fakeTtl", s.fakeTtl).put("fakeSni", s.fakeSni)
            .put("oobByte", s.oobByte).put("mh", s.httpMixedCaseHost).put("md", s.httpMixedCaseDomain).put("mr", s.httpRemoveSpace)
            .put("https", s.desyncHttps).put("http", s.desyncHttp).put("udpFake", s.udpFake).put("tfo", s.tfo).put("dropSack", s.dropSack)
            .put("defaultTtl", s.defaultTtl)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("mode", c.mode).putStringSet("apps", c.apps).putInt("port", c.port).putString("line", c.line)
            .putBoolean("useSimple", c.useSimple).putString("simple", simple.toString()).putString("sni", c.sni)
            .putBoolean("hostsOnly", c.hostsOnly).putString("hosts", c.hosts).putString("dns", c.dns).putBoolean("ipv6", c.ipv6)
            .putBoolean("autostart", c.autostart).putBoolean("chainXray", c.chainXray).putBoolean("browserViaDpi", c.browserViaDpi)
            .putString("history", JSONArray(c.history.take(15)).toString()).apply()
    }

    /** Делает [line] активной стратегией и запоминает в истории. */
    fun browserViaDpi(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("browserViaDpi", false)

    fun applyLine(context: Context, line: String): DpiConfig {
        val c = load(context)
        val n = c.copy(line = line, useSimple = false, history = (listOf(line) + c.history.filter { it != line }).take(15))
        save(context, n)
        return n
    }
}
