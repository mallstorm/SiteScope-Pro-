package pro.sitescope.app.vpn

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

class TlsInspectorTest {
    private fun two(value: Int) = byteArrayOf((value ushr 8).toByte(), value.toByte())
    private fun ext(kind: Int, body: ByteArray) = two(kind) + two(body.size) + body
    private fun hello(): ByteArray {
        val name = "example.com".toByteArray(Charsets.US_ASCII)
        val serverName = byteArrayOf(0) + two(name.size) + name
        val sni = ext(0, two(serverName.size) + serverName)
        val alpn = byteArrayOf(2) + "h2".toByteArray() + byteArrayOf(8) + "http/1.1".toByteArray()
        val extensions = sni + ext(16, two(alpn.size) + alpn) + ext(43, byteArrayOf(2, 3, 4))
        val body = byteArrayOf(3, 3) + ByteArray(32) + byteArrayOf(0) +
            two(4) + byteArrayOf(0x13, 0x01, 0x13, 0x02) + byteArrayOf(1, 0) +
            two(extensions.size) + extensions
        return byteArrayOf(1, (body.size ushr 16).toByte(), (body.size ushr 8).toByte(), body.size.toByte()) + body
    }
    private fun tlsRecord(): ByteArray {
        val data = hello()
        return byteArrayOf(22, 3, 1) + two(data.size) + data
    }
    private fun ipTcp(payload: ByteArray, seq: Int): ByteArray {
        val p = ByteArray(40 + payload.size)
        p[0] = 0x45
        p[2] = ((p.size ushr 8) and 255).toByte()
        p[3] = (p.size and 255).toByte()
        p[9] = 6
        p[12] = 10; p[13] = 10; p[14] = 14; p[15] = 1
        p[16] = 93; p[17] = 184.toByte(); p[18] = 215.toByte(); p[19] = 14
        p[20] = 0xC3.toByte(); p[21] = 0x50
        p[22] = 0x20; p[23] = 0xFB.toByte() // remote :8443
        p[24] = (seq ushr 24).toByte(); p[25] = (seq ushr 16).toByte()
        p[26] = (seq ushr 8).toByte(); p[27] = seq.toByte()
        p[32] = 0x50
        payload.copyInto(p, 40)
        return p
    }

    @After fun reset() { TlsInspectorHub.setEnabled(false); TlsInspectorHub.clear() }

    @Test fun parsesTls13SniAlpnAndJa3() {
        val fp = TlsClientHelloParser.parse(hello())
        assertNotNull(fp)
        assertEquals("example.com", fp!!.serverName)
        assertEquals("TLS 1.3", fp.version)
        assertEquals(listOf("h2", "http/1.1"), fp.alpn)
        assertEquals("771,4865-4866,0-16-43,,", fp.ja3Raw)
        val expected = MessageDigest.getInstance("MD5").digest(fp.ja3Raw.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        assertEquals(expected, fp.ja3)
    }

    @Test fun rejectsTruncatedClientHello() {
        val bytes = hello()
        assertNull(TlsClientHelloParser.parse(bytes.copyOf(bytes.size - 1)))
    }

    @Test fun readsSplitTcpHandshakeOnlyWhenEnabled() {
        val record = tlsRecord()
        TlsInspectorHub.setEnabled(true)
        val a = ipTcp(record.copyOfRange(0, 19), 1000)
        val b = ipTcp(record.copyOfRange(19, record.size), 1019)
        TlsInspectorHub.observe(a, a.size, 1234)
        assertTrue(TlsInspectorHub.snapshot().isEmpty())
        TlsInspectorHub.observe(b, b.size, 1235)
        assertEquals(1, TlsInspectorHub.snapshot().size)
        assertEquals(8443, TlsInspectorHub.snapshot()[0].remotePort)
        TlsInspectorHub.observe(b, b.size, 1236)
        assertEquals(1, TlsInspectorHub.snapshot().size)
        TlsInspectorHub.setEnabled(false)
        TlsInspectorHub.clear()
        TlsInspectorHub.observe(a, a.size)
        TlsInspectorHub.observe(b, b.size)
        assertTrue(TlsInspectorHub.snapshot().isEmpty())
    }

    @Test fun tcpGapDoesNotProduceFalseHandshake() {
        val record = tlsRecord()
        TlsInspectorHub.setEnabled(true)
        val a = ipTcp(record.copyOfRange(0, 19), 1000)
        val b = ipTcp(record.copyOfRange(19, record.size), 1050)
        TlsInspectorHub.observe(a, a.size)
        TlsInspectorHub.observe(b, b.size)
        assertTrue(TlsInspectorHub.snapshot().isEmpty())
    }
}
