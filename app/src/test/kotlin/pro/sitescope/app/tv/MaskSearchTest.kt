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
