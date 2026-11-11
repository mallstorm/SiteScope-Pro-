package pro.sitescope.app.audit

import android.app.Application
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pro.sitescope.app.proto.WebDavFs
import java.io.*
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class WebDavStreamingTest {
    @Test fun uploadStartsBeforeWholeSourceIsRead() {
        val firstChunk = CountDownLatch(1)
        val total = 2 * 1024 * 1024
        var received = 0
        Server { socket ->
            val input = socket.getInputStream().buffered()
            val headers = headers(input)
            assertEquals("chunked", headers["transfer-encoding"])
            while (true) {
                val n = line(input).toInt(16); if (n == 0) break
                var remaining = n
                val buffer = ByteArray(32768)
                while (remaining > 0) { val r = input.read(buffer, 0, minOf(buffer.size, remaining)); check(r > 0); remaining -= r; received += r }
                assertEquals("", line(input)); firstChunk.countDown()
            }
            socket.getOutputStream().write("HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n".toByteArray())
        }.use { server ->
            val source = object : InputStream() {
                var emitted = 0
                override fun read(): Int = throw AssertionError("Use bounded bulk reads")
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (emitted >= 65536) assertTrue("Upload must already be on the wire", firstChunk.await(5, TimeUnit.SECONDS))
                    if (emitted == total) return -1
                    val n = minOf(len, total - emitted); b.fill(7, off, off + n); emitted += n; return n
                }
            }
            WebDavFs("127.0.0.1", server.port, false, "", "").upload("/file", source)
            server.await(); assertEquals(total, received)
        }
    }

    @Test fun largeDownloadIsWrittenInBoundedBuffers() {
        val firstWrite = CountDownLatch(1)
        val total = 4 * 1024 * 1024
        Server { socket ->
            headers(socket.getInputStream())
            val out = socket.getOutputStream()
            out.write("HTTP/1.1 200 OK\r\nContent-Length: $total\r\n\r\n".toByteArray())
            val buffer = ByteArray(32768) { 3 }
            out.write(buffer); out.flush()
            assertTrue("Do not buffer the whole response before writing", firstWrite.await(5, TimeUnit.SECONDS))
            repeat(total / buffer.size - 1) { out.write(buffer) }
        }.use { server ->
            var written = 0
            val target = object : OutputStream() {
                override fun write(b: Int) { written++; firstWrite.countDown() }
                override fun write(b: ByteArray, off: Int, len: Int) { assertTrue(len <= 32768); written += len; firstWrite.countDown() }
            }
            WebDavFs("127.0.0.1", server.port, false, "", "").download("/file", target)
            server.await(); assertEquals(total, written)
        }
    }

    @Test fun truncatedDownloadIsReportedAsFailure() {
        Server { socket ->
            headers(socket.getInputStream())
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\nshort".toByteArray())
        }.use { server ->
            var rejected = false
            try { WebDavFs("127.0.0.1", server.port, false, "", "").download("/file", ByteArrayOutputStream()) }
            catch (_: EOFException) { rejected = true }
            server.await(); assertTrue(rejected)
        }
    }

    @Test fun chunkedResponseIsDecoded() {
        Server { socket ->
            headers(socket.getInputStream())
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nok\r\n3\r\nyes\r\n0\r\n\r\n".toByteArray())
        }.use { server ->
            val target = ByteArrayOutputStream()
            WebDavFs("127.0.0.1", server.port, false, "", "").download("/file", target)
            server.await(); assertEquals("okyes", target.toString("UTF-8"))
        }
    }

    @Test fun closeInterruptsBlockedDownload() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        Server { socket ->
            headers(socket.getInputStream())
            socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\na".toByteArray())
            socket.getOutputStream().flush()
            release.await(10, TimeUnit.SECONDS)
        }.use { server ->
            val fs = WebDavFs("127.0.0.1", server.port, false, "", "")
            val worker = Executors.newSingleThreadExecutor()
            try {
                val transfer = worker.submit<Boolean> {
                    try {
                        fs.download("/file", object : OutputStream() {
                            override fun write(b: Int) { started.countDown() }
                            override fun write(b: ByteArray, off: Int, len: Int) { started.countDown() }
                        })
                        false
                    } catch (_: IOException) { true }
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                fs.close()
                assertTrue("Closing the client must interrupt its socket read", transfer.get(3, TimeUnit.SECONDS))
            } finally { fs.close(); release.countDown(); worker.shutdownNow() }
            server.await()
        }
    }

    private class Server(handler: (Socket) -> Unit) : Closeable {
        private val socket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port get() = socket.localPort
        private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "dav-test").apply { isDaemon = true } }
        private val future = pool.submit { socket.accept().use { it.soTimeout = 10000; handler(it) } }
        fun await() { future.get(15, TimeUnit.SECONDS) }
        override fun close() { socket.close(); pool.shutdownNow() }
    }
    companion object {
        private fun line(input: InputStream): String {
            val out = ByteArrayOutputStream()
            while (true) { val c = input.read(); if (c == -1 || c == 10) break; if (c != 13) out.write(c) }
            return out.toString("UTF-8")
        }
        private fun headers(input: InputStream): Map<String, String> {
            line(input)
            val result = HashMap<String, String>()
            while (true) { val l = line(input); if (l.isEmpty()) return result; result[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim() }
        }
    }
}
