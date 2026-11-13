package pro.sitescope.app

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class ScannerParallelTest {
    private class Result : ScannerEngine.Listener {
        val done = CountDownLatch(1)
        val count = AtomicInteger()
        val error = AtomicReference("")
        @Volatile var cancelled = false
        override fun onStarted(hosts: Int, ports: Int, total: Int) { }
        override fun onProgress(completed: Int, total: Int, openCount: Int, current: String?) { }
        override fun onOpen(result: Models.PortResult?) { count.incrementAndGet() }
        override fun onComplete(results: MutableList<Models.PortResult>?, stopped: Boolean, duration: Long, message: String?) {
            cancelled = stopped; error.set(message ?: ""); done.countDown()
        }
    }

    @Test fun scannerUsesSelectedConcurrencyAndPacingOnLoopback() {
        val parallel = scan(2, 0)
        assertEquals(2, parallel.first)
        val paced = scan(4, 200).second.sorted()
        assertEquals(4, paced.size)
        assertTrue("Pacing applies across workers", paced.last() - paced.first() >= 450_000_000L)
    }

    private fun scan(workers: Int, delay: Int): Pair<Int, List<Long>> {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 5000
        val pool = Executors.newFixedThreadPool(5)
        val active = AtomicInteger(); val peak = AtomicInteger()
        val times = Collections.synchronizedList(ArrayList<Long>())
        val serving = pool.submit {
            val jobs = (1..4).map {
                val socket = server.accept()
                pool.submit {
                    socket.use {
                        times.add(System.nanoTime())
                        val n = active.incrementAndGet(); peak.updateAndGet { old -> maxOf(old, n) }
                        try {
                            Thread.sleep(150)
                            it.getOutputStream().write("SSH-2.0-Test\r\n".toByteArray())
                        } finally { active.decrementAndGet() }
                    }
                }
            }
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
        }
        val result = Result()
        val config = ScannerEngine.Config().apply { concurrency = workers; delayMs = delay; detectServices = false }
        val task = ScannerEngine.start(listOf("127.0.0.1"), List(4) { server.localPort }, config, result)
        try {
            assertTrue(result.done.await(8, TimeUnit.SECONDS))
            serving.get(5, TimeUnit.SECONDS)
            assertEquals("", result.error.get())
            assertEquals(4, result.count.get())
            assertFalse(result.cancelled)
            assertTrue(peak.get() <= workers)
            return peak.get() to ArrayList(times)
        } finally { task.cancel(); server.close(); pool.shutdownNow() }
    }

    @Test fun stopDuringFiveSecondDelayCompletesAndPreventsQueuedConnections() {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        server.soTimeout = 3000
        val config = ScannerEngine.Config().apply { concurrency = 2; delayMs = 5000; detectServices = false }
        val result = Result()
        val task = ScannerEngine.start(listOf("127.0.0.1"), List(20) { server.localPort }, config, result)
        try {
            server.accept().use { socket ->
                task.pause()
                task.cancel()
                assertTrue(result.done.await(1, TimeUnit.SECONDS))
                assertTrue(result.cancelled)
                socket.soTimeout = 1000
                assertEquals(-1, socket.getInputStream().read())
            }
            server.soTimeout = 200
            try { server.accept().close(); fail("Started another connection after stop") } catch (_: SocketTimeoutException) { }
        } finally { task.cancel(); server.close() }
    }
}
