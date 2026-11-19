package pro.sitescope.app.vpn

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.io.ByteArrayOutputStream

class QuicInitialTest {
    private fun hex(s: String) = ByteArray(s.length/2) { s.substring(it*2,it*2+2).toInt(16).toByte() }
    private fun show(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private val cid = hex("8394c8f03e515708")
    private fun u16(n: Int) = byteArrayOf((n ushr 8).toByte(), n.toByte())
    private fun varint(n: Int): ByteArray =
        if (n < 64) byteArrayOf(n.toByte()) else u16(n or 0x4000)
    private fun ext(kind: Int, value: ByteArray) = u16(kind) + u16(value.size) + value

    private fun hello(): ByteArray {
        val domain = "example.com".toByteArray()
        val name = byteArrayOf(0) + u16(domain.size) + domain
        val alpn = byteArrayOf(2) + "h3".toByteArray()
        val allExtensions = ext(0, u16(name.size) + name) +
            ext(16, u16(alpn.size) + alpn) + ext(43, byteArrayOf(2, 3, 4))
        val body = byteArrayOf(3, 3) + ByteArray(32) + byteArrayOf(0) +
            u16(2) + byteArrayOf(0x13, 0x01) + byteArrayOf(1, 0) +
            u16(allExtensions.size) + allExtensions
        return byteArrayOf(1, 0, (body.size ushr 8).toByte(), body.size.toByte()) + body
    }
    private fun quic(version: Int): ByteArray {
        val keys = QuicInitialParser.initialKeys(version, cid)!!
        val h = hello()
        val crypto = byteArrayOf(6, 0) + varint(h.size) + h
        val first = if (version == QuicInitialParser.V1) 0xc1 else 0xd1
        val head = ByteArrayOutputStream().apply {
            write(first)
            write(byteArrayOf((version ushr 24).toByte(),(version ushr 16).toByte(),
                (version ushr 8).toByte(),version.toByte()))
            write(cid.size); write(cid)
            write(0) // SCID length
            write(0) // token length
            write(varint(1200 - 18)) // PN + encrypted frames + tag
        }.toByteArray()
        assertEquals(18, head.size)
        val plain = crypto + ByteArray(1200 - head.size - 2 - 16 - crypto.size)
        val pn = byteArrayOf(0, 2)
        val unprotected = head + pn
        val nonce = keys.iv.copyOf()
        nonce[11] = (nonce[11].toInt() xor 2).toByte()
        val gcm = Cipher.getInstance("AES/GCM/NoPadding")
        gcm.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(128, nonce))
        gcm.updateAAD(unprotected)
        val ciphertext = gcm.doFinal(plain)
        val protected = unprotected + ciphertext
        val sample = protected.copyOfRange(head.size + 4, head.size + 20)
        val ecb = Cipher.getInstance("AES/ECB/NoPadding")
        ecb.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.hp, "AES"))
        val mask = ecb.doFinal(sample)
        protected[0] = (protected[0].toInt() xor (mask[0].toInt() and 0x0f)).toByte()
        for (i in 0 until 2) protected[head.size+i] =
            (protected[head.size+i].toInt() xor mask[1+i].toInt()).toByte()
        assertEquals(1200, protected.size)
        return protected
    }

    private fun ipv4Udp(quic: ByteArray): ByteArray {
        val bytes = ByteArray(20 + 8 + quic.size)
        bytes[0] = 0x45
        bytes[2] = (bytes.size ushr 8).toByte()
        bytes[3] = bytes.size.toByte()
        bytes[9] = 17
        bytes[12] = 10; bytes[13] = 10; bytes[14] = 14; bytes[15] = 1
        bytes[16] = 93; bytes[17] = 184.toByte(); bytes[18] = 215.toByte(); bytes[19] = 14
        bytes[20] = 0xc3.toByte(); bytes[21] = 0x50; bytes[22] = 1; bytes[23] = 0xbb.toByte()
        val size = 8 + quic.size
        bytes[24] = (size ushr 8).toByte(); bytes[25] = size.toByte()
        quic.copyInto(bytes, 28)
        return bytes
    }

    @After fun reset() { TlsInspectorHub.setEnabled(false); TlsInspectorHub.clear() }

    @Test fun derivesRfc9001V1Keys() {
        val k = QuicInitialParser.initialKeys(QuicInitialParser.V1, cid)!!
        assertEquals("1f369613dd76d5467730efcbe3b1a22d", show(k.key))
        assertEquals("fa044b2f42a3fd3b46fb255c", show(k.iv))
        assertEquals("9f50449e04a0e810283a1e9933adedd2", show(k.hp))
    }

    @Test fun derivesRfc9369V2Keys() {
        val k = QuicInitialParser.initialKeys(QuicInitialParser.V2, cid)!!
        assertEquals("8b1a0bc121284290a29e0971b5cd045d", show(k.key))
        assertEquals("91f73e2351d8fa91660e909f", show(k.iv))
        assertEquals("45b95e15235d6f45a6b19cbcb0294ba9", show(k.hp))
    }

    @Test fun detectsV1AndV2OnUdpWithAuthenticatedInitial() {
        TlsInspectorHub.setEnabled(true)
        for (version in listOf(QuicInitialParser.V1, QuicInitialParser.V2)) {
            val packet = ipv4Udp(quic(version))
            TlsInspectorHub.observe(packet, packet.size)
        }
        val results = TlsInspectorHub.snapshot()
        assertEquals(2, results.size)
        assertEquals(setOf("QUIC v1","QUIC v2"), results.map { it.quicVersion }.toSet())
        assertTrue(results.all { it.transport == "UDP/QUIC" })
        assertTrue(results.all { it.fingerprint.serverName == "example.com" })
        assertTrue(results.all { it.fingerprint.alpn == listOf("h3") })
    }

    @Test fun rejectsCorruptedAuthenticatedInitial() {
        val packet = ipv4Udp(quic(QuicInitialParser.V1))
        packet[packet.size-12] = (packet[packet.size-12].toInt() xor 0x80).toByte()
        TlsInspectorHub.setEnabled(true)
        TlsInspectorHub.observe(packet, packet.size)
        assertTrue(TlsInspectorHub.snapshot().isEmpty())
    }

    @Test fun doesNotMislabelUnencryptedRandomUdp() {
        TlsInspectorHub.setEnabled(true)
        val p = ipv4Udp(ByteArray(1200) { 0x40 })
        TlsInspectorHub.observe(p,p.size)
        assertTrue(TlsInspectorHub.snapshot().isEmpty())
    }
}
