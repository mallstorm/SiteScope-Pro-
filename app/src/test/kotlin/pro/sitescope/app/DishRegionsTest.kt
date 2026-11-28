package pro.sitescope.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.sitescope.app.satellite.DishRegions
import pro.sitescope.app.satellite.LyngSatCatalogue
import java.io.File

class DishRegionsTest {
    @Test fun expandsIndividualPagesAtSharedSlotsAndIgnoresCrossLinks() {
        val pages = DishRegions.parse("""
            <table>
              <tr><td><a href="News.html">News</a></td></tr>
              <tr><td rowspan="2"><a href="Combined.html">19.<font>2</font>°E</a></td>
                <td><a href="Astra-1.html">Astra 1</a> <a href="/tracker/Astra-1.html">Track</a></td></tr>
              <tr><td><a href="/Astra-2.html">Astra 2</a></td></tr>
              <tr><td><a href="79W.html">79.0°W</a></td><td><a href="West.html">Western satellite</a>
                <a href="https://example.org/Other.html">Ad</a></td></tr>
              <tr><td><a href="/After.html">Invalid position</a></td></tr>
            </table>
            <table><tr><td><a href="/Other.html">Another table</a></td></tr></table>
        """.trimIndent())
        assertEquals(listOf("Astra 1", "Astra 2", "Western satellite"), pages.map { it.name })
        assertEquals(listOf(19.2, 19.2, -79.0), pages.map { it.orbit })
        assertEquals("https://www.lyngsat.com/Astra-2.html", pages[1].url)
        assertNull(DishRegions.satelliteUrl("https://www.lyngsat.com@elsewhere.example/Foo.html"))
    }

    @Test fun retainsTransponderWhenThereAreNoNamedChannels() {
        val rows = LyngSatCatalogue.parse("""
          <title>Example at 79.0°W - LyngSat</title>
          <table><tr><td>11000 H</td><td>DVB-S2<br>8PSK<br>30000<br>3/4</td><td>(feeds)</td></tr></table>
        """.trimIndent(), includeTransponders = true)
        val transponder = rows.single()
        assertEquals(-79.0, transponder.orbit, 0.0)
        assertEquals("11000 МГц · H · 30000 ксимв/с · DVB-S2", transponder.tuning)
        assertTrue(transponder.channel.isEmpty())
    }

    @Test fun bundledIndexIncludesAllFourRegionsAndMatchesDownloadedSamples() {
        val seed = File("src/main/assets/dish-world-index.tsv").readLines()
        val sampleRoot = System.getProperty("sitescope.dish.regions")?.let(::File)
        val all = DishRegions.regions.flatMap { region ->
            val pages = DishRegions.parseSeed(seed, region.key)
            assertTrue("${region.key} has incomplete coverage", pages.size >= 30)
            sampleRoot?.resolve("${region.key}.html")?.takeIf(File::isFile)?.let { sample ->
                assertEquals(pages, DishRegions.parse(sample.readText()))
            }
            pages
        }
        assertEquals(238, all.distinctBy { it.url }.size)
        assertTrue(all.any { it.orbit >= 170.0 } && all.any { it.orbit <= -170.0 })
    }
}
