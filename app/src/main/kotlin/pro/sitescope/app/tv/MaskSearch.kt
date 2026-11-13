package pro.sitescope.app.tv

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Поиск потоков по маске ссылки. Маска — обычная ссылка с подстановками:
 *  - `%d` — число из диапазона по умолчанию (в настройках, например 1–10000); `%d{9000-9100}` — свой диапазон;
 *    `%03d` — с нулями слева; `%d` можно ставить в любом числе мест (комбинации перебираются целиком);
 *  - `(http|https|rtsp)` — любой из перечисленных вариантов (схема, путь, расширение — что угодно);
 *  - `(.*)` — любое значение: подставляются варианты, заданные в настройках;
 *  - `channel_name` — название канала: берётся из списка названий (и из ваших каналов);
 *  - `xxx.xxx.xxx.xxx` — адрес из заданных IP/подсетей; `yyyyy` — порт из заданных портов.
 * Парсинг и перебор — чистая логика без Android, поэтому покрыты тестами.
 */
object MaskSearch {
    const val HARD_MAX_CANDIDATES = 200_000L
    private const val MAX_NUM_SPAN = 1_000_000L

    sealed class Part {
        class Lit(val text: String) : Part()
        class Num(val min: Long, val max: Long, val width: Int) : Part()
        class Alt(val options: List<String>, val kind: Kind) : Part()
    }
    enum class Kind { ALT, WILD, NAME, HOST, PORT }

    class Options(
        val min: Long = 1, val max: Long = 10_000,
        val wildcards: List<String> = listOf("", "udp", "live", "stream", "hls", "play"),
        val names: List<String> = emptyList(),
        val hosts: List<String> = emptyList(),
        val ports: List<Int> = emptyList()
    )

    class Parsed(val parts: List<Part>, val warnings: List<String>) {
        /** Сколько всего комбинаций (с насыщением, чтобы не переполнить Long). */
        val total: Long get() {
            var t = 1L
            for (p in parts) {
                val n = size(p)
                if (n == 0L) return 0
                t = if (t > Long.MAX_VALUE / n) Long.MAX_VALUE / 2 else t * n
            }
            return t
        }
    }

    class Candidate(val url: String, val nameHint: String)

    private val TOKEN = Regex(
        """%(0\d)?d(?:\{(\d+)-(\d+)\})?|\(\.\*\)|\(([^()|]*(?:\|[^()|]*)+)\)|channel_name|xxx\.xxx\.xxx\.xxx|yyyyy""",
        RegexOption.IGNORE_CASE
    )

    fun size(p: Part): Long = when (p) { is Part.Lit -> 1L; is Part.Num -> p.max - p.min + 1; is Part.Alt -> p.options.size.toLong() }

    /** Варианты написания названия для подстановки в ссылку. */
    fun nameVariants(name: String): List<String> {
        val n = name.trim(); if (n.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        out.add(n); out.add(n.lowercase())
        out.add(n.replace(' ', '_')); out.add(n.lowercase().replace(' ', '_'))
        out.add(n.lowercase().replace(" ", ""))
        try { out.add(java.net.URLEncoder.encode(n, "UTF-8").replace("+", "%20")) } catch (_: Exception) { }
        return out.toList()
    }

    fun parse(template: String, o: Options): Parsed {
        val warnings = ArrayList<String>()
        val parts = ArrayList<Part>()
        val src = template.trim()
        var last = 0
        val names = o.names.flatMap { nameVariants(it) }.distinct()
        for (m in TOKEN.findAll(src)) {
            if (m.range.first > last) parts.add(Part.Lit(src.substring(last, m.range.first)))
            last = m.range.last + 1
            val text = m.value
            when {
                text.startsWith("%") -> {
                    var lo = o.min; var hi = o.max
                    if (m.groupValues[2].isNotEmpty()) { lo = m.groupValues[2].toLongOrNull() ?: lo; hi = m.groupValues[3].toLongOrNull() ?: hi }
                    if (lo > hi) { val t = lo; lo = hi; hi = t }
                    if (hi - lo + 1 > MAX_NUM_SPAN) { hi = lo + MAX_NUM_SPAN - 1; warnings.add("Диапазон %d ограничен $MAX_NUM_SPAN значениями") }
                    val width = m.groupValues[1].removePrefix("0").toIntOrNull() ?: 0
                    parts.add(Part.Num(lo, hi, width))
                }
                text == "(.*)" -> parts.add(Part.Alt(if (o.wildcards.isEmpty()) listOf("") else o.wildcards, Kind.WILD))
                text.startsWith("(") -> parts.add(Part.Alt(m.groupValues[4].split('|'), Kind.ALT))
                text.equals("channel_name", true) -> {
                    if (names.isEmpty()) warnings.add("Для channel_name задайте названия каналов")
                    parts.add(Part.Alt(names, Kind.NAME))
                }
                text.equals("yyyyy", true) -> {
                    if (o.ports.isEmpty()) warnings.add("Для yyyyy задайте порты")
                    parts.add(Part.Alt(o.ports.map { it.toString() }, Kind.PORT))
                }
                else -> {
                    if (o.hosts.isEmpty()) warnings.add("Для xxx.xxx.xxx.xxx задайте IP-адреса или подсеть")
                    parts.add(Part.Alt(o.hosts, Kind.HOST))
                }
            }
        }
        if (last < src.length) parts.add(Part.Lit(src.substring(last)))
        if (src.isEmpty()) warnings.add("Введите ссылку-маску")
        else if (!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://").containsMatchIn(src) && !src.startsWith("(")) warnings.add("Маска должна начинаться со схемы: http://, rtsp://…")
        return Parsed(parts, warnings)
    }

    private fun valueOf(p: Part, k: Int): String = when (p) {
        is Part.Lit -> p.text
        is Part.Num -> { val v = (p.min + k).toString(); if (p.width > v.length) v.padStart(p.width, '0') else v }
        is Part.Alt -> p.options[k]
    }

    /** k-я комбинация; последний подставляемый элемент меняется быстрее всех. */
    fun candidate(parsed: Parsed, index: Long): Candidate {
        var rest = index
        val values = arrayOfNulls<String>(parsed.parts.size)
        var hint = ""
        for (i in parsed.parts.indices.reversed()) {
            val p = parsed.parts[i]
            val n = size(p)
            val k = (rest % n).toInt(); rest /= n
            values[i] = valueOf(p, k)
        }
        for (i in parsed.parts.indices) { val p = parsed.parts[i]; if (p is Part.Alt && p.kind == Kind.NAME) { hint = values[i] ?: ""; break } }
        return Candidate(tidy(values.joinToString("") { it ?: "" }), hint)
    }

    /** Склеивает лишние «//» (кроме после схемы), которые появляются от пустых вариантов (.*). */
    fun tidy(url: String): String = url.replace(Regex("(?<!:)/{2,}"), "/")

    // ------------------------------------------------------------------ названия каналов

    private class Known(val title: String, vararg aliases: String) { val keys = aliases.map { normalize(it) }.toSet() + normalize(title) }

    fun normalize(raw: String): String {
        var s = raw.lowercase()
            .replace('і', 'и').replace('ї', 'и').replace('є', 'е').replace('ґ', 'г').replace('ё', 'е')
            .replace("+", "plus")
        s = s.replace(Regex("(^|[^a-zа-я0-9])(hd|fhd|uhd|4k|sd|hevc|h265)(?=$|[^a-zа-я0-9])"), "$1")
        return s.replace(Regex("[^a-zа-я0-9]"), "")
    }

    private val known = listOf(
        Known("1+1", "1+1 Україна", "1+1 Ukraine", "один плюс один", "1plus1"),
        Known("2+2", "2+2 Україна", "два плюс два"),
        Known("Интер", "Inter", "Інтер"),
        Known("НТВ", "NTV"),
        Known("РЕН ТВ", "REN TV", "РЕН-ТВ", "Рен"),
        Known("СТБ", "STB"),
        Known("ICTV", "ИСТВ", "ІСТВ"),
        Known("Новый канал", "Новий канал", "Novy Kanal"),
        Known("ТЕТ", "TET"),
        Known("К1", "K1", "Канал К1"),
        Known("К2", "K2"),
        Known("Украина", "ТРК Украина", "ТРК Україна", "Ukraina"),
        Known("5 канал", "5 Канал", "5 kanal", "П'ятий канал"),
        Known("Первый канал", "Перший", "Первый", "Channel One"),
        Known("Россия 1", "Россия-1", "Russia 1", "Rossiya 1"),
        Known("Россия 24", "Россия-24", "Russia 24"),
        Known("Матч ТВ", "Match TV", "Матч!"),
        Known("ТНТ", "TNT"),
        Known("СТС", "STS"),
        Known("Пятница", "Pyatnica", "Пятница!"),
        Known("Звезда", "Zvezda"),
        Known("ОТР", "OTR"),
        Known("МИР", "Mir", "МИР ТВ"),
        Known("ТВ-3", "ТВ3", "TV3"),
        Known("Карусель", "Karusel"),
        Known("Домашний", "Domashniy"),
        Known("Че", "Che"),
        Known("Discovery Channel", "Discovery"),
        Known("National Geographic", "Nat Geo", "NatGeo"),
        Known("Euronews", "Евроньюс"),
        Known("Nickelodeon", "Никелодеон"),
        Known("MTV", "МТВ"),
        Known("Футбол 1", "Football 1"),
        Known("Футбол 2", "Football 2"),
        Known("Мега", "Mega"),
        Known("Прямой", "Pryamiy", "Прямий"),
        Known("Эспрессо ТВ", "Espreso TV", "Еспресо"),
        Known("24 канал", "24 Канал", "24 kanal")
    )

    /** Приводит название к привычному виду, если оно известно: «Інтер HD» → «Интер», «1+1 UKRAINE» → «1+1». */
    fun canonicalName(raw: String): String? {
        val k = normalize(raw)
        if (k.isEmpty()) return null
        return known.firstOrNull { k in it.keys }?.title
    }

    private val GENERIC_NAME = Regex("(?i)^(service|сервис|unknown|provider|noname|no name|mpeg-?ts|stream|channel)?[\\s_-]*\\d*$")

    /** Название из служебных таблиц потока (SDT): убирает мусор вроде «Service01». */
    fun cleanServiceName(raw: String): String {
        val s = raw.filter { !it.isISOControl() }.trim().take(60)
        return if (s.isEmpty() || GENERIC_NAME.matches(s)) "" else s
    }

    /** Итоговое название и откуда оно взято: «SDT потока» / «маска» / пусто. */
    class Recognized(val name: String, val source: String)

    fun recognize(serviceName: String, nameHint: String): Recognized {
        val sdt = cleanServiceName(serviceName)
        if (sdt.isNotEmpty()) return Recognized(canonicalName(sdt) ?: sdt, "по данным потока")
        val hint = nameHint.replace('_', ' ').trim()
        if (hint.isNotEmpty()) return Recognized(canonicalName(hint) ?: hint, "по маске")
        return Recognized("", "")
    }

    // ------------------------------------------------------------------ перебор

    class ScanConfig(
        val concurrency: Int = 16, val timeoutMs: Int = 3000, val maxCandidates: Long = 2000,
        val stopAfter: Int = 0, val userAgent: String = StreamChecker.DEFAULT_UA
    )

    class Hit(val url: String, val status: StreamStatus, val detail: String, val serviceName: String, val resolution: String,
              val bitrateKbps: Long, val startMs: Long, val nameHint: String, val container: String)

    interface Listener {
        fun progress(done: Long, total: Long)
        fun hit(h: Hit)
    }

    fun limit(parsed: Parsed, cfg: ScanConfig): Long = minOf(parsed.total, cfg.maxCandidates.coerceIn(1, HARD_MAX_CANDIDATES))

    /** Перебирает комбинации и проверяет их; блокирует вызывающий поток до конца или отмены. */
    fun scan(parsed: Parsed, cfg: ScanConfig, cancel: AtomicBoolean, listener: Listener) {
        val total = limit(parsed, cfg)
        if (total <= 0) { listener.progress(0, 0); return }
        val next = AtomicLong(0); val done = AtomicLong(0); val found = AtomicInteger(0)
        val dead = ConcurrentHashMap.newKeySet<String>(); val alive = ConcurrentHashMap.newKeySet<String>(); val reportedRaw = ConcurrentHashMap.newKeySet<String>()
        val threads = cfg.concurrency.coerceIn(1, 48)
        val pool = Executors.newFixedThreadPool(threads)
        val stopped = { cancel.get() || (cfg.stopAfter > 0 && found.get() >= cfg.stopAfter) }
        repeat(threads) {
            pool.execute {
                while (!stopped()) {
                    val i = next.getAndIncrement(); if (i >= total) break
                    try {
                        val c = candidate(parsed, i)
                        val hit = probe(c, cfg, dead, alive, reportedRaw)
                        if (hit != null && !stopped()) {
                            if (hit.status == StreamStatus.OK || hit.status == StreamStatus.SLOW) found.incrementAndGet()
                            listener.hit(hit)
                        }
                    } catch (_: Exception) { }
                    val d = done.incrementAndGet()
                    if (d % 5L == 0L || d == total) listener.progress(d, total)
                }
            }
        }
        pool.shutdown()
        try { while (!pool.awaitTermination(300, TimeUnit.MILLISECONDS)) { if (cancel.get()) pool.shutdownNow() } } catch (_: InterruptedException) { pool.shutdownNow() }
        listener.progress(minOf(done.get(), total), total)
    }

    private fun defaultPort(scheme: String) = when (scheme) { "http" -> 80; "https" -> 443; "rtsp" -> 554; "rtmp" -> 1935; "mms", "mmsh" -> 1755; else -> -1 }

    internal fun probe(c: Candidate, cfg: ScanConfig, dead: MutableSet<String>, alive: MutableSet<String>, reportedRaw: MutableSet<String>): Hit? {
        val uri = try { URI(c.url.replace(" ", "%20")) } catch (e: Exception) { return null }
        val scheme = (uri.scheme ?: return null).lowercase()
        val host = uri.host ?: return null
        val port = if (uri.port > 0) uri.port else defaultPort(scheme)
        if (port <= 0) return null                       // udp:// и rtp:// без подписки на группу не проверить
        val key = "$host:$port"
        if (key in dead) return null
        if (key !in alive) {
            val ok = try { Socket().use { it.connect(InetSocketAddress(host, port), minOf(cfg.timeoutMs, 2000)); true } } catch (e: Exception) { false }
            if (!ok) { dead.add(key); return null }
            alive.add(key)
        }
        return when (scheme) {
            "http", "https" -> {
                val r = StreamChecker.check(c.url, cfg.userAgent, "", cfg.timeoutMs)
                if (r.status == StreamStatus.DEAD || r.status == StreamStatus.UNKNOWN) null
                else Hit(c.url, r.status, r.detail, r.serviceName, r.resolution, r.bitrateKbps, r.startMs, c.nameHint, r.container)
            }
            "rtsp" -> probeRtsp(c, host, port, cfg)
            "rtmp", "mms", "mmsh" -> if (reportedRaw.add(key)) Hit(c.url, StreamStatus.UNKNOWN, "Порт открыт, но имя потока проверить нельзя — откройте канал в плеере", "", "", 0, 0, c.nameHint, scheme.uppercase()) else null
            else -> null
        }
    }

    private fun probeRtsp(c: Candidate, host: String, port: Int, cfg: ScanConfig): Hit? = try {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), cfg.timeoutMs); s.soTimeout = cfg.timeoutMs
            val t0 = System.currentTimeMillis()
            s.getOutputStream().write("DESCRIBE ${c.url} RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: ${cfg.userAgent}\r\nAccept: application/sdp\r\n\r\n".toByteArray())
            val line = readLine(s.getInputStream())
            val ms = System.currentTimeMillis() - t0
            when {
                line.startsWith("RTSP/1.0 200") -> Hit(c.url, StreamStatus.OK, "RTSP-сервер отдал описание потока", "", "", 0, ms, c.nameHint, "RTSP")
                line.startsWith("RTSP/1.0 401") || line.startsWith("RTSP/1.0 403") -> Hit(c.url, StreamStatus.AUTH, "Нужен вход", "", "", 0, ms, c.nameHint, "RTSP")
                else -> null
            }
        }
    } catch (e: Exception) { null }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (sb.length < 200) { val b = input.read(); if (b < 0 || b == '\n'.code) break; if (b != '\r'.code) sb.append(b.toChar()) }
        return sb.toString()
    }

    /** M3U-текст для найденных каналов. */
    fun toM3u(items: List<Triple<String, String, String>>): String {
        val sb = StringBuilder("#EXTM3U\n")
        for ((name, group, url) in items) sb.append("#EXTINF:-1 group-title=\"").append(group).append("\",").append(name).append('\n').append(url).append('\n')
        return sb.toString()
    }
}
