package pro.sitescope.app.tv

import org.junit.Assert.*
import org.junit.Test

class MaskSearchTest {
    private fun all(mask: String, o: MaskSearch.Options = MaskSearch.Options()): List<String> {
        val p = MaskSearch.parse(mask, o)
        return (0 until p.total).map { MaskSearch.candidate(p, it).url }
    }

    @Test fun portRangeIsEnumerated() {
        assertEquals(listOf("http://95.67.12.82:9066", "http://95.67.12.82:9067", "http://95.67.12.82:9068"),
            all("http://95.67.12.82:%d{9066-9068}"))
    }

    @Test fun defaultRangeAndPaddingAndSeveralPositions() {
        val o = MaskSearch.Options(min = 1, max = 2)
        assertEquals(listOf("http://h/a1/b01", "http://h/a1/b02", "http://h/a2/b01", "http://h/a2/b02"), all("http://h/a%d/b%02d", o))
    }

    @Test fun alternationOfSchemes() {
        assertEquals(listOf("http://1.2.3.4:80/x", "https://1.2.3.4:80/x", "rtsp://1.2.3.4:80/x"), all("(http|https|rtsp)://1.2.3.4:80/x"))
    }

    @Test fun wildcardUsesVariantsAndCollapsesEmptySegments() {
        val o = MaskSearch.Options(wildcards = listOf("", "udp"))
        assertEquals(listOf("http://h:1/x.m3u8", "http://h:1/udp/x.m3u8"), all("http://h:1/(.*)/x.m3u8", o))
    }

    @Test fun channelNameUsesVariantsAndKeepsHint() {
        val o = MaskSearch.Options(names = listOf("Рен ТВ"))
        val p = MaskSearch.parse("http://h:8080/channel_name", o)
        val urls = (0 until p.total).map { MaskSearch.candidate(p, it) }
        assertTrue(urls.any { it.url == "http://h:8080/Рен_ТВ" && it.nameHint == "Рен_ТВ" })
        assertTrue(urls.size >= 4)
    }

    @Test fun hostAndPortPlaceholders() {
        val o = MaskSearch.Options(hosts = listOf("10.0.0.1", "10.0.0.2"), ports = listOf(4022, 8080))
        assertEquals(4L, MaskSearch.parse("http://xxx.xxx.xxx.xxx:yyyyy/udp/239.1.1.1:1234", o).total)
    }

    @Test fun missingValuesGiveWarningsAndZeroTotal() {
        val p = MaskSearch.parse("http://xxx.xxx.xxx.xxx:80/channel_name", MaskSearch.Options())
        assertEquals(0L, p.total); assertTrue(p.warnings.size >= 2)
    }

    @Test fun totalSaturatesInsteadOfOverflowing() {
        val p = MaskSearch.parse("http://%d/%d/%d/%d/%d", MaskSearch.Options(min = 1, max = 1_000_000))
        assertTrue(p.total > 0)
    }

    @Test fun recognizesKnownChannelNames() {
        assertEquals("1+1", MaskSearch.recognize("1+1 UKRAINE HD", "").name)
        assertEquals("Интер", MaskSearch.recognize("Інтер HD", "").name)
        assertEquals("РЕН ТВ", MaskSearch.recognize("", "REN_TV").name)
        assertEquals("по маске", MaskSearch.recognize("", "НТВ").source)
        assertEquals("", MaskSearch.recognize("Service01", "").name)
    }
}

class MaskUdpProbeTest {
    private fun probe(url: String, udp: ((String, Int) -> MaskSearch.UdpResult?)?) =
        MaskSearch.probe(MaskSearch.Candidate(url, ""), MaskSearch.ScanConfig(udp = udp), HashSet(), HashSet(), HashSet())

    @Test fun multicastGroupIsProbedThroughHook() {
        val ok = { _: String, _: Int -> MaskSearch.UdpResult("1+1", "H.264", "AAC", 3500, 400, "UDP / MPEG-TS", byteArrayOf(1, 2, 3)) }
        val hit = probe("udp://@239.255.1.1:1234", ok)
        assertNotNull(hit); assertEquals(StreamStatus.OK, hit!!.status); assertEquals("1+1", hit.serviceName); assertNotNull(hit.sample)
        assertNotNull(probe("udp://239.255.1.1:1234", ok))
    }

    @Test fun unicastOrUnconfiguredUdpIsSkipped() {
        val ok = { _: String, _: Int -> MaskSearch.UdpResult("", "", "", 0, 0, "", null) }
        assertNull(probe("udp://@10.0.0.1:1234", ok))
        assertNull(probe("udp://@300.1.1.1:1234", ok))
        assertNull(probe("udp://@239.255.1.1:1234", null))
        assertNull(probe("udp://@239.255.1.1:1234") { _, _ -> null })
    }
}

class LogoOcrPickTest {
    private fun r(text: String, conf: Int) = LogoNamePicker.Reading(text, conf, "угол")

    @Test fun knownChannelAcceptedWithOneLetterError() {
        assertEquals("1+1", LogoNamePicker.pickName(listOf(r("1+1", 70), r("XYZ", 20))))
        assertEquals("Новый канал", LogoNamePicker.pickName(listOf(r("НОВЫЙ КАНАЛ", 60))))
        assertEquals("Новый канал", LogoNamePicker.pickName(listOf(r("НОВЫИ КАНАЛ", 60))))
    }

    @Test fun shortKnownKeysRequireExactMatch() {
        assertEquals("НТВ", LogoNamePicker.pickName(listOf(r("НТВ", 80))))
        assertEquals("", LogoNamePicker.pickName(listOf(r("НТБ", 80))))
    }

    @Test fun unknownTextNeedsTwoConfidentReadings() {
        assertEquals("", LogoNamePicker.pickName(listOf(r("ПОГОДА", 90))))
        assertEquals("ПОГОДА", LogoNamePicker.pickName(listOf(r("Погода", 85), r("ПОГОДА", 80))))
        assertEquals("", LogoNamePicker.pickName(listOf(r("12:34", 90), r("12:34", 90))))
    }

    @Test fun emptyAndNoiseGiveNoName() {
        assertEquals("", LogoNamePicker.pickName(emptyList()))
        assertEquals("", LogoNamePicker.pickName(listOf(r("  ", 90), r("~ ^ `", 90))))
    }
}
