package pro.sitescope.app

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class ExposureTcpTest {
    @Test fun tcpOnlyReportsOpenAndRefusedWithoutSendingServiceRequestsOrReverseDns() {
        val loopback = InetAddress.getByName("127.0.0.1")
        ServerSocket(0, 16, loopback).use { server ->
            val closedPort = ServerSocket(0, 16, loopback).use { it.localPort }
            val pool = Executors.newSingleThreadExecutor()
            val received = pool.submit<Int> { server.accept().use { it.soTimeout = 3000; it.getInputStream().read() } }
            val done = CountDownLatch(1); val rows = CopyOnWriteArrayList<Models.PortResult>()
            val task = ScannerEngine.start(listOf("127.0.0.1"), listOf(server.localPort, closedPort), ScannerEngine.Config().apply { tcpOnly = true; timeoutMs = 500 }, object : ScannerEngine.Listener {
                override fun onStarted(hosts: Int, ports: Int, totalProbes: Int) { }
                override fun onProgress(completed: Int, total: Int, openCount: Int, currentTarget: String?) { }
                override fun onOpen(result: Models.PortResult) { }
                override fun onChecked(result: Models.PortResult) { rows.add(result) }
                override fun onComplete(results: MutableList<Models.PortResult>?, cancelled: Boolean, durationMs: Long, error: String?) { done.countDown() }
            })
            try {
                assertTrue(done.await(8, TimeUnit.SECONDS)); assertEquals(2, rows.size)
                assertEquals(setOf("open", "refused"), rows.map { it.probeStatus }.toSet())
                val open = rows.single { it.open }; assertEquals("TCP", open.service); assertEquals("", open.hostname); assertEquals("", open.banner)
                assertEquals(-1, received.get(3, TimeUnit.SECONDS))
                val restored = Models.PortResult.fromJson(rows.single { !it.open }.toJson()); assertEquals("refused", restored.probeStatus)
            } finally { task.cancel(); pool.shutdownNow() }
        }
    }
}
