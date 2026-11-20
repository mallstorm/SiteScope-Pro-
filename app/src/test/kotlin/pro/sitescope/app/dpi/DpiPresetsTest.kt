package pro.sitescope.app.dpi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DpiPresetsTest {
    @Test fun everyPresetSurvivesSanitizing() {
        for (p in DpiPresets.all) {
            val args = DpiArgs.build(p.line, 1080, "google.com")
            assertEquals("-i127.0.0.1", args[0])
            assertEquals("-p1080", args[1])
            assertTrue("пустой профиль ${p.id}", args.size > 2)
            assertTrue("запрещённый ключ в ${p.id}", DpiArgs.sanitize(DpiArgs.split(p.line)).removed.isEmpty())
            assertTrue(p.line.startsWith("-"))
        }
    }

    @Test fun titleLookup() {
        assertEquals("Мягкий", DpiPresets.titleOf(DpiConfig.SAFE_LINE))
        assertEquals("Своя строка", DpiPresets.titleOf("-s9+s"))
        assertNotNull(DpiPresets.byLine("  -r1+s -s1+s -d3+s "))
        assertNull(DpiPresets.byLine("-x"))
    }

    @Test fun presetIdsUnique() {
        assertEquals(DpiPresets.all.size, DpiPresets.all.map { it.id }.toSet().size)
    }

    @Test fun defaultsAreSafe() {
        val c = DpiConfig()
        assertEquals(DpiConfig.SAFE_LINE, c.line)
        assertTrue(c.watchdog)
        assertEquals("", c.searchSites)
    }

    @Test fun officialStrategiesAreCleanAndFirst() {
        assertTrue(DpiOfficialList.lines.size >= 60)
        for (l in DpiOfficialList.lines) {
            assertTrue("запрещённый ключ в: $l", DpiArgs.sanitize(DpiArgs.split(DpiArgs.expand(l, "google.com", emptyMap()))).removed.isEmpty())
            assertNull(DpiArgs.looksValid(l))
        }
        assertEquals(DpiOfficialList.lines.first(), DpiStrategies.quick().first())
        assertTrue(DpiStrategies.quick().size >= DpiOfficialList.lines.size)
    }

    @Test fun bogusDnsAddresses() {
        assertTrue(DpiDns.bogus("127.0.0.1")); assertTrue(DpiDns.bogus("10.1.2.3")); assertTrue(DpiDns.bogus("0.0.0.0"))
        assertTrue(!DpiDns.bogus("142.250.1.1")); assertTrue(!DpiDns.bogus(null))
    }
}
