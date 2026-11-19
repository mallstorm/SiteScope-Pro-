package pro.sitescope.app.vpn

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale

data class TlsFingerprint(
    val serverName: String, val alpn: List<String>, val version: String,
    val legacyVersion: Int, val cipherSuites: List<Int>, val extensions: List<Int>,
    val groups: List<Int>, val pointFormats: List<Int>, val ja3: String,
    val ja3Raw: String, val echOffered: Boolean
)
data class TlsHandshake(
    val timestamp: Long, val source: String, val sourcePort: Int,
    val remote: String, val remotePort: Int, val fingerprint: TlsFingerprint,
    val transport: String = "TCP/TLS", val quicVersion: String = ""
)

/** Bounded decoder for the plaintext TLS ClientHello handshake message, not encrypted application data. */
object TlsClientHelloParser {
    private class Reader(val data: ByteArray, var pos: Int = 0, val end: Int = data.size) {
        fun u8(): Int { require(pos < end); return data[pos++].toInt() and 255 }
        fun u16(): Int = (u8() shl 8) or u8()
        fun u24(): Int = (u8() shl 16) or (u8() shl 8) or u8()
        fun take(n: Int): ByteArray {
            require(n >= 0 && n <= end - pos)
            return data.copyOfRange(pos, pos + n).also { pos += n }
        }
        fun skip(n: Int) { take(n) }
        fun left(): Int = end - pos
    }

    fun parse(bytes: ByteArray): TlsFingerprint? {
        return try {
            if (bytes.size < 45 || bytes.size > 131072) return null
            val r = Reader(bytes)
            if (r.u8() != 1 || r.u24() != r.left()) return null
            val legacy = r.u16()
            r.skip(32)
            r.skip(r.u8())
            val suiteBytes = r.u16()
            require(suiteBytes >= 2 && suiteBytes % 2 == 0 && suiteBytes <= r.left())
            val suites = (0 until suiteBytes / 2).map { r.u16() }
            r.skip(r.u8()) // compression methods
            val ext = ArrayList<Int>()
            val groups = ArrayList<Int>()
            val formats = ArrayList<Int>()
            val alpn = ArrayList<String>()
            val versions = ArrayList<Int>()
            var sni = ""
            var ech = false
            if (r.left() > 0) {
                require(r.u16() == r.left())
                while (r.left() >= 4) {
                    val kind = r.u16()
                    val n = r.u16()
                    require(n <= r.left())
                    val end = r.pos + n
                    val e = Reader(bytes, r.pos, end)
                    ext.add(kind)
                    when (kind) {
                        0 -> if (e.left() >= 2) {
                            val size = e.u16()
                            if (size <= e.left()) {
                                val last = e.pos + size
                                while (e.pos + 3 <= last && sni.isEmpty()) {
                                    val kindName = e.u8()
                                    val raw = e.take(e.u16())
                                    if (kindName == 0) {
                                        val name = String(raw, Charsets.US_ASCII).lowercase(Locale.US)
                                        if (name.length <= 253 && name.all { it.code in 33..126 }) sni = name
                                    }
                                }
                            }
                        }
                        10 -> if (e.left() >= 2) {
                            val size = e.u16()
                            if (size % 2 == 0 && size <= e.left()) repeat(size / 2) { groups.add(e.u16()) }
                        }
                        11 -> if (e.left() >= 1) {
                            val size = e.u8()
                            if (size <= e.left()) repeat(size) { formats.add(e.u8()) }
                        }
                        16 -> if (e.left() >= 2) {
                            val size = e.u16()
                            if (size <= e.left()) {
                                val last = e.pos + size
                                while (e.pos < last && alpn.size < 16) {
                                    val len = e.u8()
                                    if (len == 0 || len > last - e.pos) break
                                    alpn.add(String(e.take(len), Charsets.US_ASCII))
                                }
                            }
                        }
                        43 -> if (e.left() >= 1) {
                            val size = e.u8()
                            if (size % 2 == 0 && size <= e.left()) repeat(size / 2) { versions.add(e.u16()) }
                        }
                        0xfe0d -> ech = true
                    }
                    r.pos = end
                }
                require(r.left() == 0)
            }
            fun normalized(values: List<Int>) = values.filterNot { isGrease(it) }.joinToString("-")
            val raw = listOf(legacy.toString(), normalized(suites), normalized(ext), normalized(groups), formats.joinToString("-")).joinToString(",")
            val ja3 = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.US_ASCII))
                .joinToString("") { "%02x".format(Locale.US, it.toInt() and 255) }
            val version = when (versions.filterNot { isGrease(it) }.maxOrNull() ?: legacy) {
                0x0304 -> "TLS 1.3"
                0x0303 -> "TLS 1.2"
                0x0302 -> "TLS 1.1"
                0x0301 -> "TLS 1.0"
                else -> "TLS ?"
            }
            TlsFingerprint(sni, alpn, version, legacy, suites, ext, groups, formats, ja3, raw, ech)
        } catch (_: Exception) { null }
    }

    private fun isGrease(v: Int): Boolean = (v and 0x0f0f) == 0x0a0a && (v ushr 8) == (v and 255)
}

/**
 * Passive in-memory TCP ClientHello observer. Works on outgoing raw IP frames from TunTee.
 * Payloads are discarded after extracting metadata; max 96 pending streams, 300 events.
 * IPv4 fragmentation and IPv6 extension chains are intentionally unsupported.
 */
object TlsInspectorHub {
    private const val MAX_BYTES = 131072
    @Volatile private var enabled = false
    private val pending = LinkedHashMap<String, Stream>()
    private val results = ArrayDeque<TlsHandshake>()
    private class Stream(var next: Long, var at: Long) { val out = ByteArrayOutputStream() }

    @JvmStatic fun isEnabled(): Boolean = enabled
    @JvmStatic @Synchronized fun setEnabled(value: Boolean) {
        enabled = value
        pending.clear()
        QuicHandshakeReassembler.clear()
    }
    @JvmStatic @Synchronized fun clear() { pending.clear(); QuicHandshakeReassembler.clear(); results.clear() }
    @JvmStatic @Synchronized fun snapshot(): List<TlsHandshake> = results.toList().asReversed()

    @JvmStatic @Synchronized fun observe(p: ByteArray, len: Int, at: Long = System.currentTimeMillis()) {
        if (!enabled || len > p.size || len < 40) return
        val info = PacketInfo.parse(p, len) ?: return
        if (!(info.src.startsWith("10.10.14.") ||
                    info.src.startsWith("10.203.0.") || info.src.startsWith("fc00:"))) return
        val l4 = when (info.version) {
            4 -> {
                if ((PacketInfo.u16(p, 6) and 0x3fff) != 0) return
                (p[0].toInt() and 15) * 4
            }
            6 -> { if ((p[6].toInt() and 255) != 6) return; 40 }
            else -> return
        }
        if (info.protocol == 17) {
            if (l4 + 8 > len || info.payloadOffset != l4 + 8) return
            val udpLength = PacketInfo.u16(p, l4 + 4)
            if (udpLength < 8 || udpLength > len - l4) return
            val udpSize = udpLength - 8
            val packet = p.copyOfRange(info.payloadOffset, info.payloadOffset + udpSize)
            val initial = QuicInitialParser.decode(packet, packet.size) ?: return
            val key = info.src + ":" + info.srcPort + ">" + info.dst + ":" + info.dstPort +
                "/" + initial.version + "/" + initial.dcid
            val hello = QuicHandshakeReassembler.accept(key, initial, at) ?: return
            results.addLast(TlsHandshake(at, info.src, info.srcPort, info.dst, info.dstPort, hello,
                "UDP/QUIC", initial.version))
            if (results.size > 300) results.removeFirst()
            return
        }
        if (info.protocol != 6 || l4 + 20 > len || info.payloadOffset <= l4 || info.payloadOffset > len) return
        val size = len - info.payloadOffset
        if (size <= 0) return
        val key = info.src + ":" + info.srcPort + ">" + info.dst + ":" + info.dstPort
        val seq = ((p[l4 + 4].toLong() and 255) shl 24) or ((p[l4 + 5].toLong() and 255) shl 16) or
            ((p[l4 + 6].toLong() and 255) shl 8) or (p[l4 + 7].toLong() and 255)
        val state = pending[key] ?: run {
            if ((p[info.payloadOffset].toInt() and 255) != 22) return
            if (pending.size >= 96) {
                val it = pending.entries.iterator()
                while (it.hasNext()) if (at - it.next().value.at > 20_000) it.remove()
                if (pending.size >= 96) pending.remove(pending.keys.first())
            }
            Stream(seq, at).also { pending[key] = it }
        }
        if (seq != state.next) {
            if (seq > state.next) pending.remove(key) // TCP gap: do not report a malformed handshake
            return
        }
        state.at = at
        state.next = (seq + size) and 0xffffffffL
        if (size > MAX_BYTES - state.out.size()) { pending.remove(key); return }
        state.out.write(p, info.payloadOffset, size)
        val parsed = fromRecords(state.out.toByteArray()) ?: return
        results.addLast(TlsHandshake(at, info.src, info.srcPort, info.dst, info.dstPort, parsed))
        if (results.size > 300) results.removeFirst()
        pending.remove(key)
    }

    private fun fromRecords(tcp: ByteArray): TlsFingerprint? {
        var offset = 0
        val handshake = ByteArrayOutputStream()
        while (tcp.size - offset >= 5) {
            if ((tcp[offset].toInt() and 255) != 22 || (tcp[offset + 1].toInt() and 255) != 3) return null
            val size = ((tcp[offset + 3].toInt() and 255) shl 8) or (tcp[offset + 4].toInt() and 255)
            if (size == 0 || size > 18432 || tcp.size - offset - 5 < size) return null
            handshake.write(tcp, offset + 5, size)
            val h = handshake.toByteArray()
            if (h.size >= 4) {
                if ((h[0].toInt() and 255) != 1) return null
                val n = ((h[1].toInt() and 255) shl 16) or ((h[2].toInt() and 255) shl 8) or (h[3].toInt() and 255)
                if (n > MAX_BYTES) return null
                if (h.size >= n + 4) return TlsClientHelloParser.parse(h.copyOfRange(0, n + 4))
            }
            offset += 5 + size
        }
        return null
    }
}
