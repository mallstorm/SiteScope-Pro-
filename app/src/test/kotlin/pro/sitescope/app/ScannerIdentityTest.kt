package pro.sitescope.app

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class ScannerIdentityTest {
    @Test fun customPortScanPersistsObservedHardwareWithoutUsingThePortAsIdentity() {
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newCachedThreadPool()
        val stopped = AtomicBoolean()
        val received = AtomicReference<Models.PortResult>()
        val done = CountDownLatch(1)
        pool.submit {
            while (!stopped.get()) try {
                val socket = server.accept()
                pool.submit {
                    socket.use {
                        try {
                            it.soTimeout = 250
                            val reader = it.getInputStream().bufferedReader()
                            val first = reader.readLine() ?: return@use
                            if (first.startsWith("GET ") || first.startsWith("OPTIONS ")) {
                                var n = 0
                                while (++n < 64) { if (reader.readLine().isNullOrEmpty()) break }
                            }
                            val body = if (first == "GET / HTTP/1.0") "<title>ASUSWRT ASUS RT-AX58U Router</title>" else ""
                            val reply = "HTTP/1.0 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${body.toByteArray().size}\r\n\r\n$body"
                            it.getOutputStream().write(reply.toByteArray())
                        } catch (_: Exception) { }
                    }
                }
            } catch (_: Exception) { break }
        }
        val error = AtomicReference("")
        val config = ScannerEngine.Config().apply { timeoutMs = 350; detectServices = true }
        val task = ScannerEngine.start(listOf("127.0.0.1"), listOf(server.localPort), config, object : ScannerEngine.Listener {
            override fun onStarted(hosts: Int, ports: Int, total: Int) { }
            override fun onProgress(completed: Int, total: Int, openCount: Int, current: String?) { }
            override fun onOpen(result: Models.PortResult?) { received.set(result) }
            override fun onComplete(results: MutableList<Models.PortResult>?, cancelled: Boolean, durationMs: Long, message: String?) { error.set(message ?: ""); done.countDown() }
        })
        try {
            assertTrue(done.await(12, TimeUnit.SECONDS)); assertEquals("", error.get())
            assertEquals("HTTP", received.get().service)
            assertEquals("ASUS", received.get().identity.manufacturer)
            assertEquals("RT-AX58U", received.get().identity.model)
            assertEquals("medium", received.get().identity.confidence)
        } finally { stopped.set(true); task.cancel(); server.close(); pool.shutdownNow() }
    }
}
