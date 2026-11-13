package pro.sitescope.app.anon

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/** Локальные проверки маршрутизации без сети: настоящий прокси, поддельные источник и SOCKS5-сервер. */
class AnonProxyTest {
    private val closers = mutableListOf<() -> Unit>()
    private val lo = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))

    @After fun cleanup() { closers.forEach { try { it() } catch (_: Exception) { } } }

    private class Origin(val server: ServerSocket) { val heads = CopyOnWriteArrayList<String>() }

    private fun origin(): Origin {
        val server = ServerSocket(0, 16, lo)
        closers += { server.close() }
        val o = Origin(server)
        Thread {
            while (true) {
                val s = try { server.accept() } catch (e: Exception) { break }
                Thread {
                    try {
                        val input = s.getInputStream()
                        val head = AnonProxy.readHead(input) ?: return@Thread
                        o.heads += String(head, Charsets.ISO_8859_1)
                        s.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray()); flush() }
                        s.shutdownOutput()
                        input.read()
                    } catch (_: Exception) { } finally { s.close() }
                }.start()
            }
        }.start()
        return o
    }

    private class FakeSocks(val server: ServerSocket) { val requested = CopyOnWriteArrayList<String>() }

    /** SOCKS5-сервер: записывает запрошенное имя и соединяет с указанным источником (или отвечает ошибкой). */
    private fun socks(target: Int, failCode: Int = 0): FakeSocks {
        val server = ServerSocket(0, 16, lo)
        closers += { server.close() }
        val f = FakeSocks(server)
        Thread {
            while (true) {
                val s = try { server.accept() } catch (e: Exception) { break }
                Thread {
                    try {
                        val input = s.getInputStream(); val out = s.getOutputStream()
                        val hello = ByteArray(3); readFully(input, hello)
                        out.write(byteArrayOf(5, 0)); out.flush()
                        val head = ByteArray(5); readFully(input, head)
                        val name = ByteArray(head[4].toInt()); readFully(input, name)
                        val port = ByteArray(2); readFully(input, port)
                        f.requested += String(name) + ":" + (((port[0].toInt() and 0xFF) shl 8) or (port[1].toInt() and 0xFF))
                        if (failCode != 0) { out.write(byteArrayOf(5, failCode.toByte(), 0, 1, 0, 0, 0, 0, 0, 0)); out.flush(); return@Thread }
                        val up = Socket(lo, target)
                        out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
                        val t = Thread { try { up.getInputStream().copyTo(out) } catch (_: Exception) { } finally { try { s.shutdownOutput() } catch (_: Exception) { } } }
                        t.start()
                        try { input.copyTo(up.getOutputStream()) } catch (_: Exception) { }
                        try { up.shutdownOutput() } catch (_: Exception) { }
                        t.join(3000); up.close()
                    } catch (_: Exception) { } finally { s.close() }
                }.start()
            }
        }.start()
        return f
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        var n = 0
        while (n < buffer.size) { val r = input.read(buffer, n, buffer.size - n); if (r < 0) throw java.io.EOFException(); n += r }
    }

    private fun proxy(config: AnonConfig): AnonProxy {
        val p = AnonProxy({ config }, connectTimeoutMs = 5000)
        p.start()
        closers += { p.stop() }
        return p
    }

    private fun exchange(port: Int, request: String): String {
        Socket(lo, port).use { s ->
            s.soTimeout = 8000
            s.getOutputStream().apply { write(request.toByteArray()); flush() }
            val out = java.io.ByteArrayOutputStream()
            try { s.getInputStream().copyTo(out) } catch (_: java.net.SocketTimeoutException) { }
            return out.toString("UTF-8")
        }
    }

    @Test fun onionGoesThroughSocksWithRemoteDns() {
        val o = origin(); val s = socks(o.server.localPort)
        val p = proxy(AnonConfig(tor = Upstream.Socks5("127.0.0.1", s.server.localPort)))
        val reply = exchange(p.port, "GET http://abcdefghijklmnop.onion/x?y=1 HTTP/1.1\r\nHost: abcdefghijklmnop.onion\r\nProxy-Connection: keep-alive\r\n\r\n")
        assertTrue(reply, reply.endsWith("ok"))
        assertEquals(listOf("abcdefghijklmnop.onion:80"), s.requested.toList())
        val head = o.heads.single()
        assertTrue(head, head.startsWith("GET /x?y=1 HTTP/1.1\r\n"))
        assertFalse(head, head.contains("Proxy-Connection", ignoreCase = true))
        assertTrue(head, head.contains("Connection: close"))
    }

    @Test fun ordinaryHostIsDirect() {
        val o = origin(); val s = socks(o.server.localPort)
        val p = proxy(AnonConfig(tor = Upstream.Socks5("127.0.0.1", s.server.localPort)))
        val reply = exchange(p.port, "GET http://127.0.0.1:${o.server.localPort}/a HTTP/1.1\r\nHost: x\r\n\r\n")
        assertTrue(reply, reply.endsWith("ok"))
        assertTrue(s.requested.isEmpty())
        assertTrue(o.heads.single().startsWith("GET /a HTTP/1.1"))
    }

    @Test fun i2pViaHttpProxyKeepsAbsoluteUri() {
        val fakeI2p = origin()
        val p = proxy(AnonConfig(i2p = Upstream.Http("127.0.0.1", fakeI2p.server.localPort)))
        val reply = exchange(p.port, "GET http://stats.i2p/p HTTP/1.1\r\nHost: stats.i2p\r\n\r\n")
        assertTrue(reply, reply.endsWith("ok"))
        assertTrue(fakeI2p.heads.single(), fakeI2p.heads.single().startsWith("GET http://stats.i2p/p HTTP/1.1"))
    }

    @Test fun i2pViaSocks() {
        val o = origin(); val s = socks(o.server.localPort)
        val p = proxy(AnonConfig(i2p = Upstream.Socks5("127.0.0.1", s.server.localPort)))
        val reply = exchange(p.port, "GET http://stats.i2p/q HTTP/1.1\r\nHost: stats.i2p\r\n\r\n")
        assertTrue(reply, reply.endsWith("ok"))
        assertEquals(listOf("stats.i2p:80"), s.requested.toList())
        assertTrue(o.heads.single().startsWith("GET /q HTTP/1.1"))
    }

    @Test fun missingTorNeverLeaksDirect() {
        val o = origin()
        val p = proxy(AnonConfig(tor = null))
        val reply = exchange(p.port, "GET http://abc.onion/ HTTP/1.1\r\nHost: abc.onion\r\n\r\n")
        assertTrue(reply, reply.startsWith("HTTP/1.1 502"))
        assertTrue(o.heads.isEmpty())
    }

    @Test fun connectTunnelDirect() {
        val o = origin()
        val p = proxy(AnonConfig())
        Socket(lo, p.port).use { s ->
            s.soTimeout = 8000
            s.getOutputStream().apply { write("CONNECT 127.0.0.1:${o.server.localPort} HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); flush() }
            val first = AnonProxy.readHead(s.getInputStream())!!
            assertTrue(String(first), String(first).startsWith("HTTP/1.1 200"))
            s.getOutputStream().apply { write("GET /t HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()); flush() }
            val out = java.io.ByteArrayOutputStream()
            try { s.getInputStream().copyTo(out) } catch (_: java.net.SocketTimeoutException) { }
            assertTrue(out.toString(), out.toString().endsWith("ok"))
        }
        assertTrue(o.heads.single().startsWith("GET /t HTTP/1.1"))
    }

    @Test fun connectOnionViaSocks() {
        val o = origin(); val s = socks(o.server.localPort)
        val p = proxy(AnonConfig(tor = Upstream.Socks5("127.0.0.1", s.server.localPort)))
        Socket(lo, p.port).use { sock ->
            sock.soTimeout = 8000
            sock.getOutputStream().apply { write("CONNECT secret.onion:443 HTTP/1.1\r\nHost: secret.onion:443\r\n\r\n".toByteArray()); flush() }
            val first = AnonProxy.readHead(sock.getInputStream())!!
            assertTrue(String(first), String(first).startsWith("HTTP/1.1 200"))
        }
        assertEquals(listOf("secret.onion:443"), s.requested.toList())
    }

    @Test fun torForAllRoutesPublicButNotLocal() {
        val o = origin(); val s = socks(o.server.localPort)
        val p = proxy(AnonConfig(tor = Upstream.Socks5("127.0.0.1", s.server.localPort), torForAll = true))
        exchange(p.port, "GET http://example.com/ HTTP/1.1\r\nHost: example.com\r\n\r\n")
        assertEquals(listOf("example.com:80"), s.requested.toList())
        exchange(p.port, "GET http://127.0.0.1:${o.server.localPort}/local HTTP/1.1\r\nHost: x\r\n\r\n")
        assertEquals(1, s.requested.size)
        assertTrue(o.heads.any { it.startsWith("GET /local") })
    }

    @Test fun onionErrorIsExplained() {
        val s = socks(1, failCode = 0xF0)
        val p = proxy(AnonConfig(tor = Upstream.Socks5("127.0.0.1", s.server.localPort)))
        val reply = exchange(p.port, "GET http://nothere.onion/ HTTP/1.1\r\nHost: nothere.onion\r\n\r\n")
        assertTrue(reply, reply.startsWith("HTTP/1.1 502"))
        assertTrue(reply, reply.contains("Onion-сервис"))
    }
}
