package pro.sitescope.app.dpi

/**
 * Список стратегий для подбора: от простых и дешёвых к сложным. Каждая — строка ключей ciadpi.
 * {sni} подменяется на поддельный SNI (по умолчанию google.com).
 */
object DpiStrategies {
    private val splitPositions = listOf("1", "2", "3", "1+s", "2+s", "3+s", "5+s", "1+sm", "0+sm")

    /** Простые: один метод десинхронизации, без цепочек. */
    private fun single(): List<String> {
        val out = mutableListOf<String>()
        for (pos in splitPositions) for (key in listOf("-s", "-d", "-o", "-q")) out += "$key$pos"
        for (pos in listOf("1+s", "2+s", "5+s", "1+sm", "3+s")) out += "-r$pos"
        return out
    }

    /** TLS-запись + разделение: самый частый рабочий вариант против DPI провайдеров. */
    private fun tlsRecordPlus(): List<String> {
        val out = mutableListOf<String>()
        for (rec in listOf("1+s", "5+s", "1+sm")) for (m in listOf("-s1+s", "-d1+s", "-s3+s", "-o1+s", "-s1", "-d3+s")) out += "-r$rec $m"
        out += "-r1+s -s1+s -d3+s"; out += "-r5+s -s1+s -d3+s -s6+s"; out += "-r1+s -d1 -s3+s -d6+s"
        return out
    }

    /** Поддельные пакеты: нужен TTL, который не доходит до сервера, но обрабатывается DPI. */
    private fun fake(): List<String> {
        val out = mutableListOf<String>()
        for (ttl in listOf(4, 6, 8, 10)) {
            out += "-f-1 -t$ttl"
            out += "-f1+s -t$ttl -n {sni} -Qr"
            out += "-f-1 -t$ttl -n {sni} -Qr -s1+s"
            out += "-f1+s -t$ttl -S -s1+s"
            out += "-f-1 -t$ttl -d1+s -r1+s"
        }
        out += "-n {sni} -Qr -f-204 -s1:5+sm -a1 -As -d1 -s3+s -s5+s -q7 -a1 -As -o2 -f-43 -a1 -As -r5 -Mh -s1:5+s -s3:7+sm -a1"
        out += "-f-1 -Qr -n {sni} -t5 -d1+s -s3+s -a1"
        out += "-f1+nme -t6 -a1 -As -n {sni} -Qr -s1:6+sm -a1 -As -s5:12+sm -a1 -As -d3 -q7 -r6 -Mh -a1"
        return out
    }

    /** Цепочки split/disorder по всему ClientHello. */
    private fun chains(): List<String> = listOf(
        "-d1 -s1+s -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -a1",
        "-d1 -d3+s -s6+s -d9+s -s12+s -d15+s -s20+s -d25+s -s30+s -d35+s -r1+s -S -a1",
        "-d1 -s1+s -s3+s -s6+s -s9+s -s12+s -s15+s -s20+s -s30+s -a1",
        "-s1+s -s3+s -s6+s -s9+s -s12+s -s20+s -s30+s -a1",
        "-d1+s -s50+s -a1 -As -f20 -r2+s -a1 -At -d2 -s1+s -s5+s -s10+s -s15+s -s25+s -s35+s -s50+s -s60+s -a1",
        "-o1 -a1 -At,r,s -f-1 -a1 -At,r,s -d1:11+sm -S -a1",
        "-o1 -d1 -a1 -At,r,s -s1 -d1 -s5+s -s10+s -s15+s -s20+s -r1+s -S -a1",
        "-q1+s -s29+s -s30+s -s14+s -o5+s -f-1 -S -a1",
        "-d9+s -q20+s -s25+s -t5 -a1 -At,r,s -r1+h -a1",
        "-o2 -O4 -s1 -q1 -a1 -Ar -s5 -o1+s -f1+s -r20+s -a1",
    )

    /** HTTP-манипуляции (для сайтов без TLS и редиректов-заглушек). */
    private fun http(): List<String> = listOf(
        "-Mh,d,r -s1+h", "-Mh -s1+h", "-Md -s3+h", "-Mr -d1+h", "-Mh,d,r -s1+h -r1+s -s1+s", "-s1+h -d3+s", "-o1+h -s3+s",
    )

    /** Автоматический режим: срабатывает при признаках блокировки и подбирает группы сам. */
    private fun auto(): List<String> = listOf(
        "-An -Kt,h -o1 -Ar -f-1 -t6 -At -d1+s -r1+s -As -s1+s",
        "-At,r,s -s1+s -r1+s -d3+s",
        "-Ar,s -o1+s -f-1 -t6 -At,r -s1+s",
        "-An -o1 -a1 -At,r,s -f-1 -t8",
        "-Qr -f-1 -t8 -n {sni} -An -s1+s -Ar -d3+s -As -o1+s",
    )

    val all: List<String> by lazy {
        (single() + tlsRecordPlus() + fake() + chains() + http() + auto()).distinct()
    }

    /** Первые [n] самых вероятных — «быстрый» подбор. */
    fun quick(n: Int = 40): List<String> = all.take(n)
}

/** Наборы сайтов для проверки. Контрольные нужны, чтобы отличить «обход работает» от «обход всё сломал». */
object DpiSites {
    class Group(val id: String, val title: String, val domains: List<String>)

    val groups = listOf(
        Group("youtube", "YouTube", listOf("www.youtube.com", "youtu.be", "i.ytimg.com", "yt3.ggpht.com", "redirector.googlevideo.com")),
        Group("discord", "Discord", listOf("discord.com", "gateway.discord.gg", "cdn.discordapp.com", "media.discordapp.net", "status.discord.com")),
        Group("telegram", "Telegram", listOf("telegram.org", "web.telegram.org", "t.me", "core.telegram.org", "api.telegram.org")),
        Group("social", "Соцсети", listOf("www.instagram.com", "www.facebook.com", "x.com", "www.linkedin.com", "www.reddit.com", "www.tiktok.com")),
        Group("cloudflare", "Cloudflare/CDN", listOf("www.cloudflare.com", "speed.cloudflare.com", "cdnjs.cloudflare.com", "www.fastly.com")),
        Group("general", "Разное", listOf("rutracker.org", "nnmclub.to", "nyaa.si", "www.speedtest.net", "medium.com", "soundcloud.com")),
        Group("ai", "Сервисы ИИ", listOf("chatgpt.com", "openai.com", "claude.ai", "gemini.google.com")),
    )

    /** Заведомо не блокируемые узлы. Если они перестали открываться — стратегия вредит. */
    val control = listOf("www.wikipedia.org", "www.microsoft.com", "www.apple.com")

    fun domainsOf(ids: Collection<String>): List<String> = groups.filter { it.id in ids }.flatMap { it.domains }.distinct()

    /** Разбор пользовательского списка: домены или URL через пробел/запятую/перенос. */
    fun parse(text: String): List<String> = text.split(Regex("[\\s,;]+")).map {
        it.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':').lowercase()
    }.filter { it.contains('.') && it.all { c -> c.isLetterOrDigit() || c == '.' || c == '-' } }.distinct()
}
