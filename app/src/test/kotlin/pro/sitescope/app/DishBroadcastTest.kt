package pro.sitescope.app

import org.junit.Assert.*
import org.junit.Test
import pro.sitescope.app.satellite.*
import java.io.File

class DishBroadcastTest {
    @Test fun parserTracksMuxThroughChannelAndPackageRowsWithoutLeakingAcrossTables() {
        val html = """
            <title>Test Satellite at 90.0°E - LyngSat</title>
            <table>
              <tr><td rowspan="2"><a href="/muxes/test_3728-R.html">3728&nbsp;R</a></td>
                <td rowspan="2">DVB-S2<br>8PSK<br>15284<br>3/4<br>T2-MI<br>PLP 0</td>
                <td></td><td><a href="/providers/ru/RTRS.html">RTRS</a></td><td></td><td>12-34</td><td></td><td></td><td></td></tr>
              <tr><td>2090</td><td><a href="/tvchannels/ru/TNT--4.html">TNT +4</a></td>
                <td></td><td>MPEG-4</td><td>2091</td><td>2092 Rus</td><td></td></tr>
              <tr><td>2100</td><td><a href="/tvchannels/ru/Off.html">Outside mux</a></td>
                <td></td><td>MPEG-4</td><td>2101</td><td>2102 Rus</td><td></td></tr>
            </table>
        """.trimIndent()
        val row = LyngSatCatalogue.parse(html).single()
        assertEquals(90.0, row.orbit, 0.0)
        assertEquals("3728 МГц · R · 15284 ксимв/с · DVB-S2", row.tuning)
        assertEquals("RTRS", row.network)
        assertEquals("T2-MI · PLP 0", row.note)
        assertTrue(row.matches("ТНТ"))
        assertTrue(row.matches("Русский пакет"))
        assertFalse(row.matches("ТНТ 75"))
    }

    @Test fun reportsChangedTuningSeparatelyFromAddedAndRemovedServices() {
        val old = Broadcast("Example", 75.0, "TNT", "Test", "11605", "V", "43200",
            sid = "10", mux = "old", service = "/tvchannels/ru/TNT.html")
        val moved = old.copy(frequency = "11629", mux = "new")
        val removed = old.copy(channel = "Removed", sid = "21", service = "/tvchannels/ru/Removed.html")
        val added = old.copy(channel = "Added", sid = "22", service = "/tvchannels/ru/Added.html")
        assertTrue(compareBroadcasts(emptyList(), listOf(old)).isEmpty())
        assertEquals(listOf(
            BroadcastChange("Изменились параметры", old, moved),
            BroadcastChange("Убран из источника", removed, null),
            BroadcastChange("Появился в источнике", null, added),
        ), compareBroadcasts(listOf(old, removed), listOf(moved, added)))
    }

    @Test fun geometricPositionMatchesSyntheticObserver() {
        val site = SitePoint(45.0, 30.0)
        val yamal = dishAngles(site, 90.0)
        assertEquals(112.2, yamal.azimuth!!, 0.1)
        assertEquals(12.2, yamal.elevation, 0.1)
        assertEquals(40_347.0, yamal.rangeKm, 1.0)
        assertEquals("восток", compassDirection(yamal.azimuth))
        assertEquals(-47.8, dishAngles(site, -131.1).elevation, 0.1)
    }

    @Test fun parserReadsDownloadedSamplesWhenSupplied() {
        val directory = System.getProperty("sitescope.dish.samples") ?: return
        for (name in listOf("yamal", "abs", "express", "amu", "at2")) {
            val channels = LyngSatCatalogue.parse(File(directory, "$name.html").readText())
            assertTrue("No channels or rates for $name", channels.size > 10 && channels.all { it.rate.isNotBlank() })
            val matches = channels.filter { it.matches("ТНТ") }
            if (name == "yamal" || name == "abs") assertTrue("No TNT for $name", matches.isNotEmpty())
            println("$name ${channels.size} channels, TNT: ${matches.take(12).joinToString { "${it.channel} ${it.tuning} ${it.network} ${it.note}" }}")
        }
    }
}
