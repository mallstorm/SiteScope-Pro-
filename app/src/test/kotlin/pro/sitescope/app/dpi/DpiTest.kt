package pro.sitescope.app.dpi

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class DpiArgsTest {
    @Test fun splitHandlesQuotesAndSpaces() {
        assertEquals(listOf("-s1+s", "-n", "a b", "-d3"), DpiArgs.split("""  -s1+s -n "a b"   -d3 """))
        assertEquals(listOf("-n", "x y"), DpiArgs.split("-n 'x y'"))
        assertEquals(emptyList<String>(), DpiArgs.split("   "))
    }

    @Test fun sanitizeDropsForbiddenKeysWithValues() {
        val c = DpiArgs.sanitize(DpiArgs.split("-p 8080 -s1+s --port=1 -D -i 0.0.0.0 -d3+s --pidfile /tmp/x"))
        assertEquals(listOf("-s1+s", "-d3+s"), c.args)
        assertTrue(c.removed.containsAll(listOf("-p", "8080", "-D", "--pidfile")))
    }

    @Test fun sanitizeKeepsNormalKeys() {
        val line = "-An -Kt,h -o1 -Ar -f-1 -t6 -At -d1+s -r1+s -As -s1+s -Mh,d,r -S -a1 -Qr -Y -F"
        val c = DpiArgs.sanitize(DpiArgs.split(line))
        assertEquals(DpiArgs.split(line), c.args)
        assertTrue(c.removed.isEmpty())
    }

    @Test fun buildPrependsAddressAndExpandsPlaceholders() {
        val a = DpiArgs.build("-n {sni} -Qr -s1+s", 1234, "ya.ru")
        assertEquals(listOf("-i127.0.0.1", "-p1234", "-n", "ya.ru", "-Qr", "-s1+s"), a)
        assertEquals("-H:a.com b.com", DpiArgs.expand("-H:{list:My}", "x", mapOf("my" to listOf("a.com", "b.com"))))
        assertEquals("-n google.com", DpiArgs.expand("-n {sni}", "", emptyMap()))
    }

    @Test fun looksValid() {
        assertNull(DpiArgs.looksValid(""))
        assertNull(DpiArgs.looksValid("-s1 -d2"))
        assertNotNull(DpiArgs.looksValid("hello -s1"))
    }

    @Test fun simpleBuilderProducesValidLine() {
        val line = DpiSimple().toLine()
        assertTrue(line, line.contains("-s1+s"))
        assertTrue(line, line.contains("-r1+s"))
        assertNull(DpiArgs.looksValid(line))
        val fake = DpiSimple(method = DpiSimple.Method.FAKE, fakeTtl = 6, fakeSni = "ya.ru", tlsRecord = false).toLine()
        assertTrue(fake, fake.contains("-f1+s") && fake.contains("-t6") && fake.contains("-nya.ru"))
        assertFalse(DpiSimple(method = DpiSimple.Method.NONE, tlsRecord = false).toLine().contains("-s"))
    }
}

class DpiStrategiesTest {
    @Test fun noDuplicatesAndAllValid() {
        val all = DpiStrategies.all
        assertEquals(all.size, all.toSet().size)
        assertTrue(all.size > 60)
        all.forEach { line ->
            assertNull(line, DpiArgs.looksValid(line))
            val c = DpiArgs.sanitize(DpiArgs.split(line.replace("{sni}", "google.com")))
            assertTrue("запрещённые ключи в '$line': ${c.removed}", c.removed.isEmpty())
        }
    }

    @Test fun quickIsPrefix() {
        assertEquals(DpiStrategies.all.take(10), DpiStrategies.quick(10))
    }

    @Test fun sitesParse() {
        assertEquals(listOf("a.com", "b.org"), DpiSites.parse("https://A.com/x, b.org:443 ;; nodot a.com"))
        assertTrue(DpiSites.domainsOf(listOf("youtube")).contains("www.youtube.com"))
        assertTrue(DpiSites.control.isNotEmpty())
    }
}

class DpiScoringTest {
    private fun s(line: String, ok: Int, total: Int = 10, ms: Long = 100, control: Boolean = true, full: Boolean = true) =
        StrategyScore(line, ok, total, ms, control, full)

    @Test fun rankingPrefersSafeThenRateThenSpeed() {
        val ranked = DpiScoring.rank(listOf(
            s("breaks", 10, control = false),
            s("slow", 8, ms = 900),
            s("fast", 8, ms = 100),
            s("best", 9, ms = 500),
            s("quick8", 8, ms = 50, full = false),
        )).map { it.line }
        assertEquals(listOf("best", "fast", "slow", "quick8", "breaks"), ranked)
    }

    @Test fun targetSitesOnlyBlockedOrAll() {
        val all = listOf("a.com", "b.com", "c.com")
        assertEquals(listOf("b.com"), DpiScoring.targetSites(all, setOf("b.com", "zzz")))
        assertEquals(all, DpiScoring.targetSites(all, emptySet()))
    }

    @Test fun sampleIsEvenAndBounded() {
        val l = (1..20).map { "h$it" }
        val x = DpiScoring.sample(l, 5)
        assertEquals(5, x.size)
        assertEquals("h1", x.first())
        assertEquals(l, DpiScoring.sample(l, 50))
    }
}

@RunWith(RobolectricTestRunner::class)
class DpiChainConfigTest {
    @Test fun addsSocksOutboundAndDialerProxy() {
        val src = """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{}},{"tag":"direct","protocol":"freedom"},{"tag":"dns-out","protocol":"dns"},{"tag":"block","protocol":"blackhole"}]}"""
        val out = JSONObject(DpiChainConfig.apply(src, 4321))
        val ob = out.getJSONArray("outbounds")
        val byTag = (0 until ob.length()).associate { ob.getJSONObject(it).getString("tag") to ob.getJSONObject(it) }
        val chain = byTag.getValue(DpiChainConfig.TAG)
        assertEquals("socks", chain.getString("protocol"))
        assertEquals(4321, chain.getJSONObject("settings").getJSONArray("servers").getJSONObject(0).getInt("port"))
        assertEquals(DpiChainConfig.TAG, byTag.getValue("proxy").getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))
        assertFalse(byTag.getValue("dns-out").has("streamSettings"))
        assertFalse(byTag.getValue("block").has("streamSettings"))
        assertFalse(chain.has("streamSettings"))
    }

    @Test fun idempotentAndNoopWithoutPort() {
        val src = """{"outbounds":[{"tag":"proxy","protocol":"vless"}]}"""
        assertEquals(src, DpiChainConfig.apply(src, 0))
        val once = DpiChainConfig.apply(src, 1000)
        val twice = DpiChainConfig.apply(once, 1000)
        assertEquals(JSONObject(once).getJSONArray("outbounds").length(), JSONObject(twice).getJSONArray("outbounds").length())
    }

    @Test fun configEffectiveLineAndMode() {
        val c = DpiConfig(mode = "vpn_only", hostsOnly = true, hosts = "a.com b.com", line = "-s1+s")
        assertTrue(c.isVpn)
        assertEquals("\"-H:a.com b.com\" -s1+s", c.effectiveLine())
        assertEquals(listOf("-i127.0.0.1", "-p1080", "-H:a.com b.com", "-s1+s"), c.args(1080))
        assertFalse(DpiConfig().isVpn)
        assertEquals("-i127.0.0.1", DpiConfig().args(1080)[0])
    }
}
