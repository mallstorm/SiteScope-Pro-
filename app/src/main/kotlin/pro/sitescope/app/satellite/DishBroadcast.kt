package pro.sitescope.app.satellite

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URI
import java.util.Locale

internal data class Broadcast(
    val satellite: String,
    val orbit: Double,
    val channel: String,
    val network: String,
    val frequency: String,
    val polarization: String,
    val rate: String,
    val standard: String = "",
    val access: String = "",
    val kind: String = "",
    val language: String = "",
    val sid: String = "",
    val mux: String = "",
    val service: String = "",
    val note: String = "",
) {
    val tuning: String
        get() = listOfNotNull(
            frequency.takeIf(String::isNotBlank)?.let { "$it МГц" },
            polarization.takeIf(String::isNotBlank),
            rate.takeIf(String::isNotBlank)?.let { "$it ксимв/с" },
            standard.takeIf(String::isNotBlank)
        ).joinToString(" · ").ifBlank { note }

    val identity: List<Any>
        get() = if (sid.isNotBlank() && mux.isNotBlank()) {
            listOf(orbit, mux, sid, service.ifBlank { searchKey(channel) })
        } else {
            listOf(orbit, searchKey(channel), searchKey(network), frequency, polarization, rate)
        }
}

private val searchSeparators = Regex("[^\\p{L}\\p{N}.]+")
private val russianPackages = setOf("русский пакет", "русские пакеты", "русское тв", "russian package")

internal fun searchKey(text: String) = text.lowercase(Locale.ROOT).replace('ё', 'е')
    .replace("тнт", "tnt").replace("мтс", "mts").replace("триколор", "trikolor")
    .replace("телекарта", "telekarta").replace("ямал", "yamal").replace(',', '.')
    .replace(searchSeparators, " ").trim()

internal fun Broadcast.matches(query: String): Boolean = matchesNormalized(searchKey(query))

internal fun Broadcast.matchesNormalized(key: String): Boolean {
    if (key.isBlank()) return true
    if (key in russianPackages) return language == "ru" && network.isNotBlank()
    val searchable = searchKey(listOf(channel, satellite, network, orbitalLabel(orbit), tuning).joinToString(" "))
    return key.split(' ').all { it in searchable }
}

internal fun DishEntry.asBroadcast(): Broadcast {
    val tune = tuning.split(" · ")
    return Broadcast(name, orbital, channel, provider,
        tune.getOrNull(0)?.substringBefore(' ')?.toDoubleOrNull()?.let { tune[0].substringBefore(' ') }.orEmpty(),
        tune.getOrNull(1)?.takeIf { it in setOf("H", "V", "L", "R") }.orEmpty(),
        tune.getOrNull(2)?.substringBefore(' ')?.toIntOrNull()?.toString().orEmpty(),
        access = access, kind = kind,
        language = if (channel.any { it in 'А'..'я' || it == 'ё' }) "ru" else "", note = tuning)
}

internal object LyngSatCatalogue {
    private val title = Regex("^(.+?) at ([0-9.]+)°\\s*([EW])(?: - .*)?$", RegexOption.IGNORE_CASE)
    private val frequency = Regex("""(\d{4,5})\s*([HVLR])""")
    private val numericRate = Regex("""\d{3,6}""")
    private val language = Regex("""\bRus\b""", RegexOption.IGNORE_CASE)
    private val servicePath = Regex("""^/(tvchannels|radiochannels)/([^/]+)/[^/]+\.html$""")

    private data class Mux(
        val id: String, val frequency: String, val polarization: String, val rate: String,
        val standard: String, val special: String, var rows: Int, var network: String,
    )

    fun parse(html: String, includeTransponders: Boolean = false): List<Broadcast> {
        val document = Jsoup.parse(html)
        val match = title.matchEntire(document.title()) ?: error("Нужна страница отдельного спутника LyngSat")
        val satellite = match.groupValues[1]
        val orbit = match.groupValues[2].toDouble() * if (match.groupValues[3].equals("W", true)) -1 else 1
        require(orbit in -180.0..180.0)
        val result = mutableListOf<Broadcast>()
        for (table in document.select("table")) {
            var mux: Mux? = null
            for (row in table.select("tr").filter { it.closest("table") == table }) {
                val cells = row.children().filter { it.normalName() == "td" }
                if (cells.isEmpty()) continue
                val link = cells[0].selectFirst("a[href^=/muxes/]")
                val tune = link?.let { frequency.find(it.text()) } ?: frequency.find(cells[0].text())
                    ?.takeIf { cells.getOrNull(1)?.text()?.startsWith("DVB-S") == true }
                val startsMux = link != null || tune != null
                if (startsMux) {
                    mux = tune?.let { readMux(cells, it, link?.attr("href").orEmpty()) }
                    if (includeTransponders) mux?.let {
                        result += Broadcast(satellite, orbit, "", "", it.frequency, it.polarization, it.rate,
                            it.standard, mux = it.id, note = it.special)
                    }
                }
                val current = mux ?: continue
                if (current.rows-- <= 0) {
                    mux = null
                    continue
                }
                val data = if (startsMux) cells.drop(2) else cells
                if (data.size < 7) continue
                val name = data[1]
                val serviceLink = name.select("a[href]").firstOrNull {
                    servicePath.matches(it.attr("href"))
                }
                val provider = name.selectFirst("a[href^=/providers/]")
                val packages = data[2].select("a[href^=/packages/]").map {
                    it.attr("href").substringAfterLast('/').removeSuffix(".html").replace('-', ' ')
                }
                if (provider != null) current.network = provider.text()
                if (packages.isNotEmpty() && serviceLink == null) current.network = packages.joinToString(" / ")
                val sid = data[0].text().trim().takeIf { it.matches(Regex("""\d{1,5}""")) } ?: continue
                val channel = serviceLink?.text() ?: name.text().takeIf {
                    provider == null && it.isNotBlank() && (data[3].text().isNotBlank() || data[5].text().isNotBlank())
                } ?: continue
                val service = serviceLink?.attr("href").orEmpty()
                val isRussian = service.startsWith("/tvchannels/ru/") || service.startsWith("/radiochannels/ru/") ||
                    data.drop(3).take(3).any { language.containsMatchIn(it.text()) }
                val encryption = data[6].text()
                result += Broadcast(satellite, orbit, channel, packages.joinToString(" / ").ifBlank { current.network },
                    current.frequency, current.polarization, current.rate, current.standard,
                    if (encryption.isBlank()) "" else "CA · $encryption",
                    if (service.startsWith("/radiochannels/")) "Радио" else if (data[3].text().isNotBlank() || service.isNotBlank()) "ТВ" else "",
                    if (isRussian) "ru" else "", sid, current.id, service, current.special)
            }
        }
        return result.distinctBy { if (it.channel.isBlank()) listOf(it.orbit, it.mux, it.tuning, it.note) else it.identity }.also { require(it.isNotEmpty()) { "На странице не найдены каналы с параметрами" } }
    }

    private fun readMux(cells: List<Element>, tune: MatchResult, href: String): Mux? {
        val attributes = cells.getOrNull(1)?.wholeText()?.lineSequence()?.map(String::trim)?.filter(String::isNotEmpty)?.toList() ?: return null
        val system = attributes.firstOrNull { it.startsWith("DVB-S") } ?: return null
        val rate = attributes.firstOrNull { numericRate.matches(it) } ?: return null
        val beam = cells[0].selectFirst("a[href^=/maps/footprints/]")?.text().orEmpty()
        val special = (listOf(beam) + attributes.filter { it.startsWith("T2-MI") || it.startsWith("PLP") || it.startsWith("MIS") })
            .filter(String::isNotBlank).joinToString(" · ")
        val id = href.substringAfterLast('/').ifBlank { "${tune.value}-$rate" } +
            "/" + attributes.firstOrNull { it.startsWith("PLP") || it.startsWith("MIS") }.orEmpty()
        return Mux(id, tune.groupValues[1], tune.groupValues[2], rate, system, special,
            cells[0].attr("rowspan").toIntOrNull()?.coerceIn(1, 500) ?: 1, "")
    }

    fun supports(uri: URI): Boolean = uri.host.equals("www.lyngsat.com", true) || uri.host.equals("lyngsat.com", true)
}
