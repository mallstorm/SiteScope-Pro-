package pro.sitescope.app.satellite

import org.jsoup.Jsoup
import java.net.URI
import kotlin.math.abs

internal data class DishRegion(val key: String, val title: String) {
    val url get() = "https://www.lyngsat.com/$key.html"
}

internal data class SatellitePage(val url: String, val name: String, val orbit: Double)

internal object DishRegions {
    val regions = listOf(
        DishRegion("asia", "Азия и Тихий океан"),
        DishRegion("europe", "Европа, Африка и Ближний Восток"),
        DishRegion("atlantic", "Атлантика"),
        DishRegion("america", "Северная и Южная Америка"),
    )
    private val position = Regex("""(\d{1,3}(?:[.,]\d+)?)\s*°\s*([EW])""", RegexOption.IGNORE_CASE)
    private val space = Regex("""\s+""")
    private val satellitePath = Regex("""/[A-Za-z0-9][A-Za-z0-9_.%+-]*\.html""")
    private val root = URI("https://www.lyngsat.com/")

    fun satelliteUrl(href: String): String? {
        val uri = runCatching { root.resolve(href.trim()) }.getOrNull() ?: return null
        if (uri.scheme !in listOf("http", "https") || !LyngSatCatalogue.supports(uri) ||
            uri.port != -1 || uri.userInfo != null || uri.query != null || uri.fragment != null ||
            !satellitePath.matches(uri.rawPath)) return null
        return root.resolve(uri.rawPath).toString()
    }

    fun parse(html: String): List<SatellitePage> {
        val candidates = mutableListOf<SatellitePage>()
        for (table in Jsoup.parse(html).select("table")) {
            var orbit = 0.0
            var rowsLeft = 0
            for (row in table.select("tr").filter { it.closest("table") == table }) {
                val cells = row.children().filter { it.normalName() == "td" }
                val first = cells.firstOrNull() ?: continue
                val rawPosition = position.matchEntire(first.text().replace(space, ""))
                val satelliteCell = if (rawPosition != null) {
                    orbit = rawPosition.groupValues[1].replace(',', '.').toDouble()
                    if (rawPosition.groupValues[2].equals("W", true)) orbit = -orbit
                    rowsLeft = first.attr("rowspan").toIntOrNull()?.coerceIn(1, 40) ?: 1
                    cells.getOrNull(1)
                } else {
                    first
                }
                if (rowsLeft <= 0) continue
                rowsLeft--
                if (abs(orbit) > 180 || satelliteCell == null) continue
                for (link in satelliteCell.select("a[href]")) {
                    val url = satelliteUrl(link.attr("href")) ?: continue
                    val name = link.text().trim()
                    if (name.isNotBlank() && name.length <= 160 && !position.matches(name)) {
                        candidates += SatellitePage(url, name, orbit)
                    }
                }
            }
        }
        return candidates.distinctBy { it.url }.also {
            require(it.isNotEmpty() && it.size <= 512) { "Формат списка спутников изменился" }
        }
    }

    fun parseSeed(lines: List<String>, region: String): List<SatellitePage> = lines.mapNotNull { line ->
        val fields = line.split('\t')
        if (fields.size != 4 || fields[0] != region) return@mapNotNull null
        val url = satelliteUrl(fields[1]) ?: return@mapNotNull null
        val orbit = fields[2].toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: return@mapNotNull null
        SatellitePage(url, fields[3], orbit)
    }
}
