package pro.sitescope.app

import org.junit.Assert.*
import org.junit.Test
import pro.sitescope.app.net.SpeedEngine
import pro.sitescope.app.net.SpeedServers
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class SpeedSelectionTest {
    @Test fun probesFileWithRangedGetAndRejectsErrorsAndHtml() {
        val requests = CopyOnWriteArrayList<Pair<String, String?>>()
        val fixture = MiniHttp().apply {
            createContext("/file") { exchange ->
                requests.add(exchange.method to exchange.requestHeaders.getFirst("Range"))
                if (exchange.method == "HEAD") exchange.sendResponseHeaders(405, 0)
                else {
                    exchange.responseHeaders.add("Content-Type", "application/octet-stream")
                    exchange.responseBody.write(7)
                    exchange.sendResponseHeaders(206, 1)
                }
            }
            createContext("/denied") { it.sendResponseHeaders(403, 0) }
            createContext("/html") {
                it.responseHeaders.add("Content-Type", "text/html; charset=UTF-8")
                it.responseBody.write("<html>maintenance</html>".toByteArray())
            }
            createContext("/empty") { it.responseHeaders.add("Content-Type", "text/html") }
            start()
        }
        try {
            val base = "http://127.0.0.1:${fixture.address.port}"
            val rows = listOf("file", "denied", "html", "empty").map { id ->
                SpeedEngine.Server(id, id, "", "$base/$id", "",
                    pingEndpoint = "$base/empty".takeIf { id == "empty" }, binaryDownload = true)
            }
            val probes = SpeedEngine.probeServers(rows, AtomicBoolean(false)) { _, _, _ -> }.associateBy { it.server.id }
            assertEquals(4, probes.size)
            assertTrue(probes.getValue("file").available)
            assertTrue(probes.getValue("empty").available)
            assertEquals("HTTP 403", probes.getValue("denied").error)
            assertFalse(probes.getValue("html").available)
            assertEquals(listOf("GET" to "bytes=0-0", "GET" to "bytes=0-0"), requests)
        } finally { fixture.stop(0) }
    }

    @Test fun rankingHonorsRequestedUploadThenReliabilityAndLatency() {
        fun node(id: String, upload: Boolean, ping: Double, failures: Int = 0) = SpeedEngine.ServerProbe(
            SpeedEngine.Server(id, id, "", "https://example.test/file", "https://example.test/upload".takeIf { upload }.orEmpty()),
            ping, failures, ""
        )
        val probes = listOf(node("offline", true, -1.0, 2), node("download", false, 4.0), node("flaky", true, 8.0, 1),
            node("slower", true, 35.0), node("fast", true, 20.0))
        assertEquals(listOf("fast", "slower", "flaky", "download"), SpeedEngine.rankedServers(SpeedEngine.Settings(), probes).map { it.id })
        assertEquals("download", SpeedEngine.rankedServers(SpeedEngine.Settings(doUp = false), probes).first().id)
        assertFalse(SpeedEngine.rankedServers(SpeedEngine.Settings(doPing = false, doDown = false), probes).any { it.id == "download" })
    }

    @Test fun publicEndpointsUseHttpsAndLibreSpeedGetsMebibyteChunks() {
        val servers = SpeedServers.public
        assertEquals(30, servers.size)
        assertEquals(servers.size, servers.map { it.id }.toSet().size)
        assertTrue(servers.all { it.down(1).startsWith("https://") && it.pingUrl.startsWith("https://") })
        assertTrue(servers.first { it.id == "clouvider-lon" }.down(25_000_000).endsWith("ckSize=24"))
        assertTrue(servers.first { it.id == "cf" }.down(25_000_000).endsWith("bytes=25000000"))
    }
}
