package pro.sitescope.app.vpn

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class HttpsCaptureDecoderTest {
    private val random = ByteArray(32) { it.toByte() }
    private val clientSecret = ByteArray(32) { (it + 4).toByte() }
    private val serverSecret = ByteArray(32) { (it + 37).toByte() }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun two(n: Int) = byteArrayOf((n ushr 8).toByte(), n.toByte())

    private fun hkdf(secret: ByteArray, label: String, length: Int): ByteArray {
        val full = ("tls13 " + label).toByteArray()
        val info = two(length) + byteArrayOf(full.size.toByte()) + full + byteArrayOf(0)
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret, "HmacSHA256"))
        return mac.doFinal(info + byteArrayOf(1)).copyOf(length)
    }

    private fun tlsRecord(type: Int, content: ByteArray): ByteArray =
        byteArrayOf(type.toByte(), 3, 3) + two(content.size) + content

    private fun hello(type: Int): ByteArray {
        val body = byteArrayOf(3, 3) + (if (type == 1) random else ByteArray(32) { 7 }) +
            byteArrayOf(0) + two(0x1301) + byteArrayOf(0)
        val h = byteArrayOf(type.toByte(), 0, (body.size ushr 8).toByte(), body.size.toByte()) + body
        return tlsRecord(22, h)
    }

    private fun encrypted(secret: ByteArray, text: String): ByteArray {
        val plain = text.toByteArray(Charsets.UTF_8) + byteArrayOf(23)
        val key = hkdf(secret, "key", 16)
        val nonce = hkdf(secret, "iv", 12)
        val header = byteArrayOf(23, 3, 3) + two(plain.size + 16)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        c.updateAAD(header)
        return header + c.doFinal(plain)
    }

    private fun packet(fromClient: Boolean, seq: Int, payload: ByteArray): ByteArray {
        val p = ByteArray(40 + payload.size)
        p[0] = 0x45
        p[2] = (p.size ushr 8).toByte()
        p[3] = p.size.toByte()
        p[9] = 6
        val local = byteArrayOf(10,10,14,1)
        val server = byteArrayOf(93,184.toByte(),215.toByte(),14)
        (if (fromClient) local else server).copyInto(p, 12)
        (if (fromClient) server else local).copyInto(p, 16)
        val sp = if (fromClient) 50000 else 443
        val dp = if (fromClient) 443 else 50000
        two(sp).copyInto(p, 20)
        two(dp).copyInto(p, 22)
        p[24] = (seq ushr 24).toByte()
        p[25] = (seq ushr 16).toByte()
        p[26] = (seq ushr 8).toByte()
        p[27] = seq.toByte()
        p[32] = 0x50
        payload.copyInto(p, 40)
        return p
    }

    private fun fixture(withSecrets: Boolean, corrupt: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        PcapNg.header(out)
        if (withSecrets) {
            val body = "CLIENT_TRAFFIC_SECRET_0 " + hex(random) + " " + hex(clientSecret) + "\n" +
                "SERVER_TRAFFIC_SECRET_0 " + hex(random) + " " + hex(serverSecret) + "\n"
            PcapNg.secrets(out, body.toByteArray())
        }
        var time = 1000000L
        fun write(outgoing: Boolean, seq: Int, bytes: ByteArray) {
            val p = packet(outgoing, seq, bytes)
            PcapNg.packet(out, p, p.size, time++)
        }
        val a = hello(1)
        val b = hello(2)
        write(true, 100, a)
        write(false, 200, b)
        val req = encrypted(clientSecret,
            "GET /health HTTP/1.1\r\nHost: example.com\r\nAuthorization: Bearer very-secret\r\n\r\n")
        val resp = encrypted(serverSecret, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nOK")
        if (corrupt) req[req.size - 1] = (req.last().toInt() xor 1).toByte()
        write(true, 100 + a.size, req)
        write(false, 200 + b.size, resp)
        return out.toByteArray()
    }

    @Test fun decryptsHttpsInAppWithKeylog() {
        val report = HttpsCaptureDecoder.decode(ByteArrayInputStream(fixture(true)))
        assertTrue(report.hasSecrets)
        assertEquals(1, report.decryptedConnections)
        assertEquals(2, report.entries.size)
        assertEquals("GET /health HTTP/1.1", report.entries[0].title)
        assertTrue(report.entries[0].text.contains("Authorization: [скрыто]"))
        assertFalse(report.entries[0].text.contains("very-secret"))
        assertEquals("HTTP/1.1 200 OK", report.entries[1].title)
    }

    @Test fun doesNotPretendToDecryptWithoutKeys() {
        val report = HttpsCaptureDecoder.decode(ByteArrayInputStream(fixture(false)))
        assertFalse(report.hasSecrets)
        assertTrue(report.entries.isEmpty())
    }

    @Test fun authenticatedTamperingIsRejected() {
        val report = HttpsCaptureDecoder.decode(ByteArrayInputStream(fixture(true, corrupt = true)))
        assertEquals(1, report.entries.size)
        assertEquals("HTTP/1.1 200 OK", report.entries[0].title)
    }
}
