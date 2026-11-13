package pro.sitescope.app.anon

/** Общие типы состояния встроенных Tor и I2P. Чистая логика без Android — проверяется JVM-тестами. */
enum class RunPhase {
    OFF, STARTING, BOOTSTRAPPING, READY, STOPPING, FAILED;

    val active: Boolean get() = this == STARTING || this == BOOTSTRAPPING || this == READY

    companion object { fun from(value: String?): RunPhase = values().firstOrNull { it.name == value } ?: OFF }
}

enum class AnonFailure {
    NONE, CLOCK, NETWORK, PROXY, TLS_BLOCKED, DIRECTORY, TIMEOUT, EXITED, RESEED, PEERS, UNKNOWN;

    companion object { fun from(value: String?): AnonFailure = values().firstOrNull { it.name == value } ?: NONE }
}

/** Как встроенный Tor/I2P выходит в интернет: напрямую, через byedpi (обход DPI) или через чужой SOCKS5. */
enum class AnonTransport(val key: String, val title: String) {
    AUTO("auto", "Авто"), DIRECT("direct", "Напрямую"), DPI("dpi", "Обход DPI"), PROXY("proxy", "SOCKS5");

    companion object {
        fun from(key: String?): AnonTransport = values().firstOrNull { it.key == key || it.name == key } ?: AUTO
    }
}

data class ProxySpec(val host: String, val port: Int) {
    override fun toString() = "$host:$port"
    val socksUrl: String get() = "socks://$host:$port"

    companion object {
        fun parse(text: String?): ProxySpec? {
            var t = text?.trim().orEmpty()
            if (t.isEmpty()) return null
            t = t.removePrefix("socks5h://").removePrefix("socks5://").removePrefix("socks4://").removePrefix("socks://").trimEnd('/')
            val i = t.lastIndexOf(':')
            if (i <= 0) return null
            val host = t.substring(0, i).trim('[', ']')
            val port = t.substring(i + 1).toIntOrNull() ?: return null
            if (host.isBlank() || host.any { it.isWhitespace() } || port !in 1..65535) return null
            return ProxySpec(host, port)
        }
    }
}

data class TorSnapshot(
    val phase: RunPhase = RunPhase.OFF,
    val progress: Int = 0,
    val port: Int = 0,
    val message: String = "Tor выключен",
    val error: String? = null,
    val requested: AnonTransport = AnonTransport.AUTO,
    val actual: AnonTransport = AnonTransport.DIRECT,
    val failure: AnonFailure = AnonFailure.NONE,
    val bypassHelps: Boolean = false,
    val diagnostic: String? = null,
) {
    val ready: Boolean get() = phase == RunPhase.READY && port > 0
}

data class I2pSnapshot(
    val phase: RunPhase = RunPhase.OFF,
    val progress: Int = 0,
    val httpPort: Int = 0,
    val socksPort: Int = 0,
    val message: String = "I2P выключен",
    val error: String? = null,
    val inbound: Int = 0,
    val outbound: Int = 0,
    val actual: AnonTransport = AnonTransport.DIRECT,
    val failure: AnonFailure = AnonFailure.NONE,
    val bypassHelps: Boolean = false,
) {
    val ready: Boolean get() = phase == RunPhase.READY && httpPort > 0
}

/** Классификация журналов Tor/i2pd в понятные пользователю причины. */
object AnonDiagnostics {
    data class Diagnosis(
        val kind: AnonFailure,
        val message: String,
        val raw: String? = null,
        /** Поможет ли обход DPI (стоит ли предложить/включить его). */
        val bypassHelps: Boolean = false,
    ) { val meaningful: Boolean get() = kind != AnonFailure.NONE }

    fun sanitize(value: String): String =
        value.replace(Regex("(?i)(password|cookie|token|authcookie)=\\S+")) { "${it.groupValues[1]}=<hidden>" }.take(800)

    fun tor(line: String): Diagnosis {
        val raw = sanitize(line)
        val l = raw.lowercase()
        return when {
            raw.isBlank() -> Diagnosis(AnonFailure.NONE, "")
            "clock" in l || "system time" in l || "date and time" in l ->
                Diagnosis(AnonFailure.CLOCK, "Проверьте дату и время на устройстве: при неверных часах Tor не принимает сертификаты.", raw)
            "network is unreachable" in l || "network unreachable" in l || "no route to host" in l ->
                Diagnosis(AnonFailure.NETWORK, "Сеть недоступна для Tor.", raw)
            "socks5proxy" in l && ("connection refused" in l || "unavailable" in l || "failed" in l) ->
                Diagnosis(AnonFailure.PROXY, "Tor не смог подключиться к заданному SOCKS5-прокси.", raw)
            isTls(l) -> Diagnosis(AnonFailure.TLS_BLOCKED,
                "TLS-соединения Tor обрываются — похоже на фильтрацию провайдера. Включите «Обход DPI» или добавьте мосты.", raw, bypassHelps = true)
            "directory" in l || "consensus" in l || "dirserver" in l ->
                Diagnosis(AnonFailure.DIRECTORY, "Tor не получает каталог сети. Попробуйте «Обход DPI» или мосты.", raw, bypassHelps = true)
            "timed out" in l || "timeout" in l -> Diagnosis(AnonFailure.TIMEOUT, "Tor не достиг готовности за отведённое время.", raw)
            "exited with code" in l || "завершился с кодом" in l -> Diagnosis(AnonFailure.EXITED, "Процесс Tor неожиданно завершился.", raw)
            else -> Diagnosis(AnonFailure.UNKNOWN, raw, raw)
        }
    }

    fun torTimeout(progress: Int, previous: Diagnosis?): Diagnosis {
        val p = progress.coerceIn(0, 100)
        if (previous != null && (previous.kind == AnonFailure.TLS_BLOCKED || previous.kind == AnonFailure.DIRECTORY))
            return previous.copy(message = previous.message + " Остановилось на $p%.")
        return Diagnosis(AnonFailure.TIMEOUT, "Tor не достиг готовности за отведённое время. Последняя стадия: $p%.", previous?.raw)
    }

    private fun isTls(l: String): Boolean {
        val strong = listOf("tls_error", "tls error", "ssl state error", "unexpected eof while reading", "wrong version number",
            "certificate verify failed", "connection reset during ssl", "error in the pull function").any { it in l }
        val handshake = "handshake" in l && listOf("error", "failed", "failure", "eof", "reset", "closed", "unexpected").any { it in l }
        return strong || handshake
    }

    /** Разбор хвоста журнала i2pd: не удалось получить список узлов (reseed) или нет пиров. */
    fun i2p(logTail: String): Diagnosis {
        val tail = logTail.takeLast(24_000)
        val reseedMarks = listOf("Reseed: SSL handshake failed", "Reseed: Couldn't connect", "Reseed: SU3 download failed", "Reseed: Failed to reseed from",
            "Reseed: SOCKS handshake failed", "unable to reseed", "reseed seems to be totally failed")
        val failures = reseedMarks.sumOf { m -> Regex(Regex.escape(m)).findAll(tail).count() }
        if (failures >= 2 || "reseed seems to be totally failed" in tail || "unable to reseed" in tail)
            return Diagnosis(AnonFailure.RESEED,
                "I2P не смог получить список узлов (reseed): серверы недоступны из этой сети. Включите «Обход DPI», задайте SOCKS5 или загрузите файл reseed (.su3).",
                tail.lines().lastOrNull { it.contains("eseed") }?.take(300), bypassHelps = true)
        if (Regex("(?i)(no peers available|no compatible addresses available)").containsMatchIn(tail))
            return Diagnosis(AnonFailure.PEERS, "I2P ищет узлы и строит туннели.", null)
        return Diagnosis(AnonFailure.NONE, "")
    }
}

/** Выбор способа выхода в сеть для Tor/I2P. */
object AnonTransportPlanner {
    data class Plan(val actual: AnonTransport, val proxy: ProxySpec?, val error: String? = null, val reason: String = "")

    /** [dpi] — адрес запущенного byedpi (если удалось поднять), [custom] — заданный пользователем SOCKS5. */
    fun plan(requested: AnonTransport, dpi: ProxySpec?, custom: ProxySpec?): Plan = when (requested) {
        AnonTransport.DIRECT, AnonTransport.AUTO -> Plan(AnonTransport.DIRECT, null, reason = if (requested == AnonTransport.AUTO) "авто: сначала напрямую" else "напрямую по выбору")
        AnonTransport.DPI -> if (dpi != null) Plan(AnonTransport.DPI, dpi, reason = "через byedpi $dpi")
        else Plan(AnonTransport.DPI, null, "Обход DPI недоступен: движок byedpi не найден или не запустился (только arm64).", "byedpi недоступен")
        AnonTransport.PROXY -> if (custom != null) Plan(AnonTransport.PROXY, custom, reason = "через SOCKS5 $custom")
        else Plan(AnonTransport.PROXY, null, "Укажите адрес SOCKS5-прокси в формате хост:порт.", "прокси не задан")
    }

    /** Альтернатива при зависании: только из режима «Авто» и только Direct → DPI. */
    fun fallback(requested: AnonTransport, actual: AnonTransport, dpiAvailable: Boolean): AnonTransport? =
        if (requested == AnonTransport.AUTO && actual == AnonTransport.DIRECT && dpiAvailable) AnonTransport.DPI else null

    /** Tor завис на ранней стадии. */
    fun torStuck(progress: Int, elapsedMs: Long): Boolean = elapsedMs >= 45_000 && progress <= 15
}
