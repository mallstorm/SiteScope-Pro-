package pro.sitescope.app.anon

/** Конфигурация встроенного i2pd (официальная JNI-библиотека PurpleI2P в отдельном процессе :i2pcore). */
data class I2pOptions(
    val dataDir: String,
    val httpPort: Int = I2pConf.DEFAULT_HTTP,
    val socksPort: Int = I2pConf.DEFAULT_SOCKS,
    /** socks://host:port — прокси только для загрузки списка узлов (reseed). */
    val reseedProxy: String? = null,
    /** socks://host:port — прокси для всех соединений NTCP2 (режим «через SOCKS5»). */
    val ntcpProxy: String? = null,
    /** Локальный .su3 или HTTPS-адрес, откуда брать список узлов. */
    val reseedFile: String? = null,
)

object I2pConf {
    const val DEFAULT_HTTP = 14444
    const val DEFAULT_SOCKS = 14447

    fun build(o: I2pOptions): String {
        val ssu2 = o.ntcpProxy == null
        return buildString {
            appendLine("log=file")
            appendLine("logfile=${o.dataDir.trimEnd('/')}/i2pd.log")
            appendLine("loglevel=info")
            appendLine("daemon=false")
            appendLine("ipv4=true")
            appendLine("ipv6=false")
            appendLine("bandwidth=L")
            appendLine("notransit=true")
            appendLine("share=0")
            o.ntcpProxy?.let { appendLine("ntcpproxy=$it") }
            appendLine()
            appendLine("[ntcp2]"); appendLine("enabled=true"); appendLine("published=false")
            o.ntcpProxy?.let { appendLine("proxy=$it") }
            appendLine()
            appendLine("[ssu2]"); appendLine("enabled=$ssu2"); appendLine("published=false")
            appendLine()
            appendLine("[http]"); appendLine("enabled=false")
            appendLine()
            appendLine("[httpproxy]"); appendLine("enabled=true"); appendLine("address=127.0.0.1"); appendLine("port=${o.httpPort}"); appendLine("keys=proxy-keys.dat")
            appendLine()
            appendLine("[socksproxy]"); appendLine("enabled=true"); appendLine("address=127.0.0.1"); appendLine("port=${o.socksPort}"); appendLine("keys=proxy-keys.dat")
            appendLine()
            appendLine("[sam]"); appendLine("enabled=false")
            appendLine()
            appendLine("[bob]"); appendLine("enabled=false")
            appendLine()
            appendLine("[i2cp]"); appendLine("enabled=false")
            appendLine()
            appendLine("[reseed]"); appendLine("verify=true")
            (o.reseedProxy ?: o.ntcpProxy)?.let { appendLine("proxy=$it") }
            o.reseedFile?.takeIf { it.isNotBlank() }?.let { appendLine("file=$it") }
            appendLine()
            appendLine("[upnp]"); appendLine("enabled=false")
        }
    }
}

/** Осторожный разбор журнала i2pd: считаем только созданные входящие и исходящие туннели. */
object I2pLog {
    data class State(val inbound: Int, val outbound: Int, val peersUnavailable: Boolean) {
        val ready: Boolean get() = inbound > 0 && outbound > 0
    }

    private val inCreated = Regex("Inbound tunnel \\d+ has been created")
    private val outCreated = Regex("Outbound tunnel \\d+ has been created")
    private val noPeers = Regex("(?i)(no peers available|no inbound tunnels found|no compatible addresses available)")

    fun parse(log: String) = State(
        inbound = inCreated.findAll(log).count(),
        outbound = outCreated.findAll(log).count(),
        peersUnavailable = noPeers.containsMatchIn(log.takeLast(32_000)),
    )
}
