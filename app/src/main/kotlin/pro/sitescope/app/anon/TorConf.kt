package pro.sitescope.app.anon

/** torrc и разбор вывода встроенного Tor (исполняемый libtor.so запускается отдельным процессом). */
object TorConf {
    const val DEFAULT_SOCKS = 19050
    private val bootstrap = Regex("Bootstrapped\\s+(\\d+)%")

    fun progress(line: String): Int? = bootstrap.find(line)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, 100)

    class Bridges(val lines: List<String>, val skipped: List<String>)

    private val pluggable = setOf("obfs4", "obfs3", "webtunnel", "snowflake", "meek", "meek_lite", "scramblesuit", "conjure")
    private val plain = Regex("""^(\[[0-9a-fA-F:]+]|\d{1,3}(\.\d{1,3}){3}):\d{1,5}(\s+[0-9A-Fa-f]{40})?(\s+.*)?$""")

    /** Простые мосты `IP:порт [отпечаток]`. Мосты с подключаемыми транспортами (obfs4, snowflake…) требуют отдельного клиента, которого в сборке нет. */
    fun parseBridges(text: String): Bridges {
        val ok = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { raw ->
            val line = raw.removePrefix("Bridge ").removePrefix("bridge ").trim()
            val first = line.substringBefore(' ').lowercase()
            if (first in pluggable) skipped += raw
            else if (plain.matches(line)) ok += "Bridge $line"
            else skipped += raw
        }
        return Bridges(ok.distinct(), skipped)
    }

    fun quote(path: String) = "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    fun torrc(dataDir: String, cacheDir: String, socksPort: Int, proxy: ProxySpec?, bridges: List<String>): String = buildString {
        appendLine("DataDirectory ${quote(dataDir)}")
        appendLine("CacheDirectory ${quote(cacheDir)}")
        appendLine("SocksPort 127.0.0.1:$socksPort IsolateSOCKSAuth KeepAliveIsolateSOCKSAuth")
        appendLine("ClientOnly 1")
        appendLine("AvoidDiskWrites 1")
        appendLine("SafeLogging 1")
        appendLine("DormantCanceledByStartup 1")
        if (proxy != null) appendLine("Socks5Proxy ${proxy.host}:${proxy.port}")
        if (bridges.isNotEmpty()) {
            appendLine("UseBridges 1")
            bridges.forEach { appendLine(it) }
        }
        appendLine("Log notice stdout")
    }
}
