package pro.sitescope.app

import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class IptvParallelTest {
    @Test fun selectedWorkersRunConcurrently() {
        val result = runScan(4, 0)
        assertEquals(4, result.first)
        assertTrue("Several probes must overlap", result.second >= 2)
        assertTrue("Concurrency must not exceed selected workers", result.second <= 4)
    }
    @Test fun delaySpacesOutProbeStarts() {
        val result = runScan(4, 200)
        val times = result.third.sorted()
        assertEquals(4, times.size)
        for (i in 1 until times.size) assertTrue("No burst starts with configured delay", times[i] - times[i - 1] >= 120_000_000L)
    }
    @Test fun workerSelectionAccepts250WithoutQueuingLargeRange() {
        val found = IptvEngine.discoverUdpxy(IptvTargets.parse("10.0.0.0/8"), listOf(4022), 100, 250, 5000, object : IptvEngine.DiscoveryListener {
            override fun isCancelled() = true
            override fun onProgress(completed: Long, total: Long, current: String?) { fail() }
            override fun onFound(endpoint: Models.UdpxyEndpoint?) { fail() }
        })
        assertTrue(found.isEmpty())
    }
    private fun runScan(workers: Int, delay: Long): Triple<Int, Int, List<Long>> {
        val server = ServerSocket(0)
        val handlers = Executors.newFixedThreadPool(4)
        val accepting = Executors.newSingleThreadExecutor()
        val active = AtomicInteger(); val peak = AtomicInteger()
        val times = Collections.synchronizedList(ArrayList<Long>())
        val done = accepting.submit {
            val jobs = (1..4).map {
                val socket = server.accept()
                handlers.submit {
                    socket.use {
                        it.soTimeout = 3000
                        val input = it.getInputStream().bufferedReader()
                        while (!input.readLine().isNullOrEmpty()) { }
                        times.add(System.nanoTime())
                        val count = active.incrementAndGet(); peak.updateAndGet { old -> maxOf(old, count) }
                        try {
                            Thread.sleep(350)
                            it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nConnection: close\r\n\r\nudpxy".toByteArray())
                        } finally { active.decrementAndGet() }
                    }
                }
            }
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
        }
        try {
            val found = IptvEngine.discoverUdpxy(IptvTargets.parse("127.0.0.1-4"), listOf(server.localPort), 1500, workers, delay, null)
            done.get(15, TimeUnit.SECONDS)
            return Triple(found.size, peak.get(), ArrayList(times))
        } finally { server.close(); accepting.shutdownNow(); handlers.shutdownNow() }
    }
}
