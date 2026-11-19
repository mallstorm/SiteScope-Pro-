package pro.sitescope.app.vpn

import java.io.ByteArrayOutputStream
import java.util.TreeMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 9001 / RFC 9369 QUIC Initial inspection. Initial protection is intentionally
 * reversible by any observer (the keys derive from the public destination CID).
 * Handshake/1-RTT/HTTP3 application records are NOT decrypted here.
 */
internal object QuicInitialParser {
    const val V1 = 0x00000001
    const val V2 = 0x6b3343cf
    private val V1_SALT = hex("38762cf7f55934b34d179ae6a4c80cadccbb7f0a")
    private val V2_SALT = hex("0dede3def700a6db819381be6e269dcbf9bd2ed9")
    const val MAX_CRYPTO = 131072

    data class Keys(val key: ByteArray, val iv: ByteArray, val hp: ByteArray)
    data class Fragment(val offset: Long, val bytes: ByteArray)
    data class Initial(val version: String, val dcid: String, val fragments: List<Fragment>)

    private class Cursor(val bytes: ByteArray, var pos: Int = 0, val end: Int = bytes.size) {
        fun left() = end - pos
        fun u8(): Int { require(left() > 0); return bytes[pos++].toInt() and 255 }
        fun bytes(n: Int): ByteArray { require(n in 0..left()); return bytes.copyOfRange(pos, pos+n).also { pos += n } }
        fun number(): Long {
            val first = u8()
            val length = 1 shl (first ushr 6)
            require(length - 1 <= left())
            var n = (first and 0x3f).toLong()
            repeat(length - 1) { n = (n shl 8) or u8().toLong() }
            return n
        }
    }

    private fun hex(value: String): ByteArray {
        require(value.length % 2 == 0)
        return ByteArray(value.length / 2) { value.substring(it*2, it*2+2).toInt(16).toByte() }
    }
    private fun hexOf(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun hmac(key: ByteArray, input: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(input)
    }
    private fun expand(secret: ByteArray, label: String, length: Int): ByteArray {
        val longLabel = ("tls13 " + label).toByteArray(Charsets.US_ASCII)
        val info = byteArrayOf((length ushr 8).toByte(), length.toByte(), longLabel.size.toByte()) +
            longLabel + byteArrayOf(0)
        var prev = ByteArray(0)
        val out = ByteArrayOutputStream()
        var i = 1
        while (out.size() < length) {
            prev = hmac(secret, prev + info + byteArrayOf(i.toByte()))
            out.write(prev)
            i++
        }
        return out.toByteArray().copyOf(length)
    }
    internal fun initialKeys(version: Int, dcid: ByteArray): Keys? {
        if (dcid.size > 20) return null
        val salt = when(version) { V1 -> V1_SALT; V2 -> V2_SALT; else -> return null }
        val init = hmac(salt, dcid)
        val client = expand(init, "client in", 32)
        val prefix = if (version == V2) "quicv2" else "quic"
        return Keys(expand(client, "$prefix key", 16), expand(client, "$prefix iv", 12),
            expand(client, "$prefix hp", 16))
    }

    /** Parses the first client Initial packet in an outgoing UDP datagram. */
    fun decode(datagram: ByteArray, size: Int): Initial? {
        return try {
        if (size !in 40..65535 || size > datagram.size) return null
        val data = datagram.copyOf(size)
        val cursor = Cursor(data)
        val first = cursor.u8()
        if ((first and 0xc0) != 0xc0) return null
        val version = (cursor.u8() shl 24) or (cursor.u8() shl 16) or
            (cursor.u8() shl 8) or cursor.u8()
        if (version != V1 && version != V2) return null
        val kind = (first ushr 4) and 3
        if (kind != if (version == V1) 0 else 1) return null
        val dcidSize = cursor.u8()
        if (dcidSize > 20) return null
        val dcid = cursor.bytes(dcidSize)
        val scidSize = cursor.u8()
        if (scidSize > 20) return null
        cursor.bytes(scidSize)
        val tokenSize = cursor.number()
        if (tokenSize > cursor.left()) return null
        cursor.bytes(tokenSize.toInt())
        val payloadLength = cursor.number()
        if (payloadLength < 17 || payloadLength > cursor.left()) return null
        val pnOffset = cursor.pos
        val packetEnd = (pnOffset + payloadLength).toInt()
        val keys = initialKeys(version, dcid) ?: return null
        val sample = pnOffset + 4
        if (sample + 16 > packetEnd) return null
        val ecb = Cipher.getInstance("AES/ECB/NoPadding")
        ecb.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.hp, "AES"))
        val mask = ecb.doFinal(data.copyOfRange(sample, sample+16))
        val header = first xor (mask[0].toInt() and 0x0f)
        val pnLen = (header and 3) + 1
        if (pnOffset + pnLen + 16 > packetEnd) return null
        data[0] = header.toByte()
        var packetNumber = 0L
        repeat(pnLen) { i ->
            val value = (data[pnOffset+i].toInt() and 255) xor (mask[i+1].toInt() and 255)
            data[pnOffset+i] = value.toByte()
            packetNumber = (packetNumber shl 8) or value.toLong()
        }
        val nonce = keys.iv.copyOf()
        repeat(8) { i -> nonce[nonce.size - 1 - i] =
            (nonce[nonce.size - 1 - i].toInt() xor ((packetNumber ushr (8*i)).toInt() and 255)).toByte() }
        val gcm = Cipher.getInstance("AES/GCM/NoPadding")
        gcm.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.key, "AES"), GCMParameterSpec(128, nonce))
        gcm.updateAAD(data, 0, pnOffset + pnLen)
        val frames = gcm.doFinal(data, pnOffset + pnLen, packetEnd - pnOffset - pnLen)
        val parts = ArrayList<Fragment>()
        val f = Cursor(frames)
        var loops = 0
        while (f.left() > 0 && loops++ < 256) {
            when (val type = f.number()) {
                0L, 1L -> Unit // PADDING and PING
                6L -> {
                    val offset = f.number()
                    val len = f.number()
                    if (offset > MAX_CRYPTO || len > MAX_CRYPTO || offset + len > MAX_CRYPTO || len > f.left()) break
                    parts.add(Fragment(offset, f.bytes(len.toInt())))
                }
                2L, 3L -> {
                    f.number() // largest acknowledged
                    f.number() // ACK delay
                    val count = f.number()
                    if (count > 256) break
                    f.number() // first ACK range
                    repeat(count.toInt()) { f.number(); f.number() }
                    if (type == 3L) repeat(3) { f.number() } // ECN
                }
                else -> break
            }
        }
        if (parts.isEmpty()) null else Initial(if (version == V1) "QUIC v1" else "QUIC v2", hexOf(dcid), parts)
        } catch (_: Exception) { null }
    }
}

/** Reassembles authenticated QUIC Initial CRYPTO frames by stream offset. */
internal object QuicHandshakeReassembler {
    private class Flight(var at: Long) { val fragments = TreeMap<Long, ByteArray>() }
    private val pending = LinkedHashMap<String, Flight>()
    private val completed = LinkedHashMap<String, Long>()

    fun clear() { pending.clear(); completed.clear() }
    fun accept(key: String, initial: QuicInitialParser.Initial, now: Long): TlsFingerprint? {
        if (completed.containsKey(key)) return null
        if (pending.size >= 96 || completed.size >= 300) {
            pending.entries.removeAll { now - it.value.at > 30_000 }
            completed.entries.removeAll { now - it.value > 60_000 }
            if (pending.size >= 96) pending.remove(pending.keys.first())
            if (completed.size >= 300) completed.remove(completed.keys.first())
        }
        val flight = pending.getOrPut(key) { Flight(now) }
        flight.at = now
        for (part in initial.fragments) {
            if (flight.fragments.size >= 96) { pending.remove(key); return null }
            if (part.bytes.isNotEmpty()) flight.fragments[part.offset] = part.bytes
        }
        val output = ByteArrayOutputStream()
        for ((offset, bytes) in flight.fragments) {
            val already = output.size().toLong()
            if (offset > already) break
            val skip = (already - offset).toInt()
            if (skip < bytes.size) output.write(bytes, skip, bytes.size - skip)
            if (output.size() > QuicInitialParser.MAX_CRYPTO) { pending.remove(key); return null }
        }
        val joined = output.toByteArray()
        if (joined.size < 4) return null
        if ((joined[0].toInt() and 255) != 1) { pending.remove(key); return null }
        val size = ((joined[1].toInt() and 255) shl 16) or
            ((joined[2].toInt() and 255) shl 8) or (joined[3].toInt() and 255)
        if (size > QuicInitialParser.MAX_CRYPTO - 4) { pending.remove(key); return null }
        if (joined.size < size + 4) return null
        val tls = TlsClientHelloParser.parse(joined.copyOfRange(0, size + 4)) ?: return null
        pending.remove(key)
        completed[key] = now
        return tls
    }
}
