package pro.sitescope.app.dpi

/** Работа с командной строкой byedpi (ciadpi): разбор, проверка, подстановка адреса. Чистая логика без Android. */
object DpiArgs {
    /** Разбивает строку как shell: пробелы, кавычки '...' и "...", обратная косая черта. */
    fun split(line: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var quote = 0.toChar()
        var has = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quote != 0.toChar() -> if (c == quote) quote = 0.toChar() else if (c == '\\' && quote == '"' && i + 1 < line.length) { cur.append(line[++i]) } else cur.append(c)
                c == '\'' || c == '"' -> { quote = c; has = true }
                c == '\\' && i + 1 < line.length -> { cur.append(line[++i]); has = true }
                c.isWhitespace() -> if (has || cur.isNotEmpty()) { out += cur.toString(); cur.clear(); has = false }
                else -> { cur.append(c); has = true }
            }
            i++
        }
        if (has || cur.isNotEmpty()) out += cur.toString()
        return out
    }

    /** Ключи, которые нельзя передавать: справка, демон, pid-файл, прозрачный режим, выгрузка кеша в файл. Адрес и порт задаёт приложение. */
    private val forbiddenShort = setOf('h', 'v', 'D', 'w', 'E', 'y', 'i', 'p')
    private val forbiddenLong = setOf("--help", "--version", "--daemon", "--pidfile", "--transparent", "--cache-dump", "--ip", "--port")

    class Checked(val args: List<String>, val removed: List<String>)

    /** Убирает запрещённые ключи вместе с их значениями; остальное оставляет как есть. */
    fun sanitize(tokens: List<String>): Checked {
        val keep = mutableListOf<String>()
        val removed = mutableListOf<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val long = t.startsWith("--")
            val name = if (long) t.substringBefore('=') else t
            val bad = if (long) name in forbiddenLong else t.length >= 2 && t[0] == '-' && t[1] in forbiddenShort
            if (bad) {
                removed += t
                // значение отдельным токеном: «-p 1080», «--port 1080»
                val needsValue = if (long) (name == "--pidfile" || name == "--cache-dump" || name == "--ip" || name == "--port") && !t.contains('=')
                else t.length == 2 && t[1] in setOf('w', 'y', 'i', 'p')
                if (needsValue && i + 1 < tokens.size && !tokens[i + 1].startsWith("-")) { removed += tokens[i + 1]; i++ }
            } else keep += t
            i++
        }
        return Checked(keep, removed)
    }

    /** Итоговые аргументы процесса: адрес/порт приложения + пользовательские. [placeholders] — {sni} и {list:Имя}. */
    fun build(userLine: String, port: Int, sni: String = "google.com", lists: Map<String, List<String>> = emptyMap()): List<String> {
        val line = expand(userLine, sni, lists)
        val checked = sanitize(split(line))
        return listOf("-i127.0.0.1", "-p$port") + checked.args
    }

    private val listRef = Regex("""\{list:([^}]+)}""")

    fun expand(line: String, sni: String, lists: Map<String, List<String>>): String {
        val withLists = listRef.replace(line) { m ->
            val key = m.groupValues[1].trim()
            lists.entries.firstOrNull { it.key.equals(key, true) }?.value?.joinToString(" ") ?: ""
        }
        return withLists.replace("{sni}", sni.ifBlank { "google.com" })
    }

    /** Простейшая проверка синтаксиса без запуска: ключи начинаются с «-», нет пустых значений. */
    fun looksValid(line: String): String? {
        val tokens = split(line.replace("{sni}", "x.com"))
        if (tokens.isEmpty()) return null
        if (!tokens[0].startsWith("-")) return "Строка должна начинаться с ключа (например -s1+s)"
        return null
    }
}

/** Настройки «конструктора» — то же, что можно собрать вручную из ключей. */
data class DpiSimple(
    val method: Method = Method.SPLIT,
    val position: Int = 1,
    val atHost: Boolean = true,
    val tlsRecord: Boolean = true,
    val tlsRecordPosition: Int = 1,
    val tlsRecordAtSni: Boolean = true,
    val fakeTtl: Int = 8,
    val fakeSni: String = "",
    val oobByte: String = "a",
    val httpMixedCaseHost: Boolean = false,
    val httpMixedCaseDomain: Boolean = false,
    val httpRemoveSpace: Boolean = false,
    val desyncHttps: Boolean = true,
    val desyncHttp: Boolean = true,
    val udpFake: Int = 0,
    val tfo: Boolean = false,
    val dropSack: Boolean = false,
    val defaultTtl: Int = 0,
) {
    enum class Method(val key: String, val title: String) {
        NONE("", "Без изменения"), SPLIT("-s", "Разделение (split)"), DISORDER("-d", "Обратный порядок (disorder)"),
        OOB("-o", "Внеполосные данные (oob)"), DISOOB("-q", "disorder + oob"), FAKE("-f", "Поддельный пакет (fake)")
    }

    fun toLine(): String {
        val a = mutableListOf<String>()
        val protos = buildList { if (desyncHttps) add("t"); if (desyncHttp) add("h") }
        if (protos.isNotEmpty()) a += "-K" + protos.joinToString(",")
        if (defaultTtl > 0) a += "-g$defaultTtl"
        if (method != Method.NONE) a += method.key + position + if (atHost) "+s" else ""
        if (method == Method.FAKE) {
            if (fakeTtl > 0) a += "-t$fakeTtl"
            if (fakeSni.isNotBlank()) a += "-n${fakeSni.trim()}"
        }
        if ((method == Method.OOB || method == Method.DISOOB) && oobByte.isNotEmpty()) a += "-e${oobByte[0].code}"
        val mods = buildList { if (httpMixedCaseHost) add("h"); if (httpMixedCaseDomain) add("d"); if (httpRemoveSpace) add("r") }
        if (mods.isNotEmpty()) a += "-M" + mods.joinToString(",")
        if (tlsRecord) a += "-r$tlsRecordPosition" + if (tlsRecordAtSni) "+s" else ""
        if (tfo) a += "-F"
        if (dropSack) a += "-Y"
        if (udpFake > 0) { a += "-Ku"; a += "-a$udpFake" }
        return a.joinToString(" ")
    }
}
