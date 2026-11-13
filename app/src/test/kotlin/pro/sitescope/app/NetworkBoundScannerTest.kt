package pro.sitescope.app

import android.app.Application
import android.net.Network
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class, shadows = [NetworkBoundScannerTest.BoundNetwork::class])
class NetworkBoundScannerTest {
    @Implements(Network::class)
    class BoundNetwork {
        companion object {
            val bindings = AtomicInteger(); val resolutions = AtomicInteger()
            @Volatile var unavailable = false
        }
        @Implementation fun bindSocket(socket: Socket) {
            bindings.incrementAndGet()
            if (unavailable) throw IOException("Selected network disappeared")
        }
        @Implementation fun getByName(host: String): InetAddress {
            resolutions.incrementAndGet()
            return InetAddress.getByName("127.0.0.1")
        }
    }
    @Before fun reset() { BoundNetwork.bindings.set(0); BoundNetwork.resolutions.set(0); BoundNetwork.unavailable = false }

    private fun probe(port: Int): List<Models.PortResult> {
        val done = CountDownLatch(1)
        val results = mutableListOf<Models.PortResult>()
        val config = ScannerEngine.Config().apply { timeoutMs = 250; detectServices = false; boundNetwork = Shadow.newInstanceOf(Network::class.java) }
        val task = ScannerEngine.start(listOf("scope-test.invalid"), listOf(port), config, object : ScannerEngine.Listener {
            override fun onStarted(hosts: Int, ports: Int, total: Int) { }
            override fun onProgress(completed: Int, total: Int, openCount: Int, currentTarget: String?) { }
            override fun onOpen(result: Models.PortResult) { results.add(result) }
            override fun onComplete(openResults: MutableList<Models.PortResult>?, cancelled: Boolean, durationMs: Long, error: String?) { done.countDown() }
        })
        try { assertTrue(done.await(8, TimeUnit.SECONDS)); return results } finally { task.cancel() }
    }

    @Test fun usesSelectedNetworkForSocketAndDns() {
        ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).use { server ->
            assertEquals("127.0.0.1", probe(server.localPort).single().ip)
            assertEquals(1, BoundNetwork.bindings.get()); assertEquals(1, BoundNetwork.resolutions.get())
        }
    }
    @Test fun missingSelectedNetworkDoesNotFallBackToDefaultNetwork() {
        ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).use { server ->
            BoundNetwork.unavailable = true
            assertTrue(probe(server.localPort).isEmpty())
            assertEquals(1, BoundNetwork.bindings.get()); assertEquals(0, BoundNetwork.resolutions.get())
        }
    }
}
