package pro.sitescope.app

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class IptvTargetsTest {
    @Test fun largeRangesStayLazyAndCountsDoNotOverflow() {
        assertEquals(254L, IptvTargets.parse("192.168.1.0/24").count())
        assertEquals(65534L, IptvTargets.parse("10.0.0.0/16").count())
        assertEquals(16777214L, IptvTargets.parse("10.0.0.0/8").count())
        val all = IptvTargets.parse("0.0.0.0/0")
        assertEquals(4294967294L, all.count())
        assertEquals(listOf("0.0.0.1", "0.0.0.2", "0.0.0.3"), all.take(3))
    }
    @Test fun rangesDeduplicateAndPreserveEndpoints() {
        assertEquals(254L, IptvTargets.parse("192.168.1.* 192.168.1.1-254 192.168.1.2").count())
        assertEquals(listOf("10.0.0.0", "10.0.0.1"), IptvTargets.parse("10.0.0.0/31").toList())
        assertEquals(listOf("10.0.0.1"), IptvTargets.parse("10.0.0.1/32").toList())
        assertEquals(254L, IptvTargets.parse("192.168.1.12/255.255.255.0").count())
        for (bad in listOf("10.0.0.0/33", "10.0.0.0/-1", "10.0.0.5-1", "256.1.1.*", "10.0.0.0/255.0.255.0")) {
            try { IptvTargets.parse(bad); fail(bad) } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun cancellationDoesNotExpandOrProbeLargeRange() {
        val found = IptvEngine.discoverUdpxy(IptvTargets.parse("10.0.0.0/8"), listOf(4022), 100, object : IptvEngine.DiscoveryListener {
            override fun isCancelled() = true
            override fun onProgress(completed: Long, total: Long, current: String?) { fail("Cancelled before IO") }
            override fun onFound(endpoint: Models.UdpxyEndpoint?) { fail("Cancelled before IO") }
        })
        assertTrue(found.isEmpty())
    }
    @Test fun discoveryActuallyChecksMoreThan32Hosts() {
        val server = ServerSocket(0)
        val pool = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        val work = pool.submit {
            repeat(40) {
                server.accept().use { socket ->
                    socket.soTimeout = 3000
                    val input = socket.getInputStream().bufferedReader()
                    while (!input.readLine().isNullOrEmpty()) { }
                    val body = "udpxy status"
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray())
                }
            }
        }
        try {
            var completed = 0L
            val listener = object : IptvEngine.DiscoveryListener {
                override fun onProgress(done: Long, total: Long, current: String?) { completed = done; assertEquals(40L, total) }
                override fun onFound(endpoint: Models.UdpxyEndpoint?) { }
            }
            val results = IptvEngine.discoverUdpxy(IptvTargets.parse("127.0.0.1-40"), listOf(server.localPort), 1000, listener)
            work.get(10, TimeUnit.SECONDS)
            assertEquals(40, results.size); assertEquals(40L, completed)
        } finally { server.close(); pool.shutdownNow() }
    }
}
