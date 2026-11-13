package pro.sitescope.app

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HttpFingerprintTest {
    private fun parse(body: String, status: Int = 200, extra: String = ""): HttpFingerprintResponse =
        HttpFingerprintResponse.parse(("HTTP/1.1 $status OK\r\nContent-Type: text/html; charset=UTF-8\r\n$extra\r\n$body").toByteArray())

    @Test fun parsesOnlyDocumentTitleAndDecodesEntities() {
        val p = parse("<!-- <title>Dahua</title> --><script>const x='<title>Hikvision</title>';</script><title>ASUS RT&#45;AX58U &amp; Router</title><p>TP-Link</p>")
        assertEquals("ASUS RT-AX58U & Router", p.title)
        assertEquals("ASUS", DeviceFingerprintEngine.fromHttp(p.server, p.realm, p.title, "").manufacturer)
        assertEquals("", parse("<script><title>ASUS Router</title>").title)
        assertEquals("", parse("<title>ASUS</title><title>TP-Link</title>").title)
        assertEquals("", parse("<title>ASUS Router</titlefake>").title)
    }

    @Test fun doesNotTreatBodyMentionsErrorsRedirectsOrEncodedBytesAsTitles() {
        assertEquals("", parse("<p>ASUS RT-AX58U</p>").title)
        assertEquals("", parse("<title>ASUS Router</title>", 404).title)
        assertEquals("", parse("<title>ASUS Router</title>", 302, "Location: http://example.invalid/\r\n").title)
        assertEquals("", parse("<title>ASUS Router</title>", extra = "Content-Encoding: gzip\r\n").title)
        assertFalse(HttpFingerprintResponse.parse(ByteArray(HttpFingerprintResponse.MAX_BYTES + 1)).isHttp)
    }

    @Test fun readsRealmAndChunkedDocumentAcrossChunks() {
        val body = "<title>TP-Link Archer C6</title>".chunked(7).joinToString("") { it.length.toString(16) + "\r\n" + it + "\r\n" } + "0\r\n\r\n"
        val p = parse(body, extra = "Transfer-Encoding: chunked\r\nWWW-Authenticate: Basic realm=\"TP-LINK Router\"\r\n")
        assertEquals("TP-LINK Router", p.realm); assertEquals("TP-Link Archer C6", p.title)
    }

    @Test(expected = IllegalArgumentException::class) fun rejectsHeaderInjectionBeforeUsingASocket() {
        Socket().use { socket -> HttpFingerprintResponse.request(socket, "router\r\nX-Test: one\r\n", 500) }
    }

    @Test fun rootRequestWaitsForSplitResponseAndSendsNoCredentials() {
        val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val pool = Executors.newSingleThreadExecutor()
        try {
            val request = pool.submit<String> {
                server.accept().use { socket ->
                    socket.soTimeout = 2000
                    val lines = ArrayList<String>(); val input = socket.getInputStream().bufferedReader()
                    while (true) { val line = input.readLine() ?: break; if (line.isEmpty()) break; lines.add(line) }
                    socket.getOutputStream().write("HTTP/1.0 200 OK\r\nContent-Type: text/html\r\n\r\n<title>ASUS ".toByteArray())
                    socket.getOutputStream().flush(); Thread.sleep(60)
                    socket.getOutputStream().write("RT-AX58U Router</title>".toByteArray())
                    lines.joinToString("\n")
                }
            }
            Socket("127.0.0.1", server.localPort).use { socket ->
                val p = HttpFingerprintResponse.request(socket, "127.0.0.1", 1200)
                assertEquals("ASUS RT-AX58U Router", p.title)
                assertEquals("RT-AX58U", DeviceFingerprintEngine.fromHttp(p.server, p.realm, p.title, "").model)
            }
            val sent = request.get(3, TimeUnit.SECONDS)
            assertTrue(sent.startsWith("GET / HTTP/1.0"))
            assertFalse(sent.contains("Authorization:", true)); assertFalse(sent.contains("Cookie:", true))
        } finally { server.close(); pool.shutdownNow() }
    }
}
