package pro.sitescope.app.vpn

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.Locale
import java.util.TreeMap
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * On-device viewer for SiteScope PCAPNG with a TLS key-log block.
 * Decrypts TLS 1.3 application records with secrets emitted by explicitly enabled MITM.
 * HTTP/1.1 preview only. HTTP/2, QUIC 1-RTT and TLS 1.2 are not decoded here.
 */
object HttpsCaptureDecoder {
    data class Entry(val client: String, val remote: String, val direction: String,
                     val title: String, val text: String, val cipher: String)
    data class Report(val entries: List<Entry>, val decryptedConnections: Int,
                      val hasSecrets: Boolean, val note: String)

    private const val MAX_FILE = 64L * 1024 * 1024
    private const val MAX_EVENTS = 160
    private const val MAX_STREAM = 256 * 1024

    private fun u16(b: ByteArray, o: Int) =
        ((b[o].toInt() and 255) shl 8) or (b[o+1].toInt() and 255)
    private fun u32(b: ByteArray, o: Int): Long =
        ((b[o].toLong() and 255) shl 24) or ((b[o+1].toLong() and 255) shl 16) or
            ((b[o+2].toLong() and 255) shl 8) or (b[o+3].toLong() and 255)
    private fun le32(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 255) or ((b[o+1].toInt() and 255) shl 8) or
            ((b[o+2].toInt() and 255) shl 16) or (b[o+3].toInt() shl 24)
    private fun readFull(input: InputStream, bytes: ByteArray): Boolean {
        var n = 0
        while (n < bytes.size) {
            val next = input.read(bytes, n, bytes.size - n)
            if (next < 0) return false
            if (next > 0) n += next
        }
        return true
    }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(Locale.US, it.toInt() and 255) }
    private fun unhex(s: String): ByteArray? = try {
        if (s.length % 2 != 0 || s.length !in 32..128) null
        else ByteArray(s.length / 2) { s.substring(it*2, it*2+2).toInt(16).toByte() }
    } catch (_: Exception) { null }

    private class KeyLog {
        private val secrets = HashMap<String, MutableMap<String, ByteArray>>()
        fun add(block: ByteArray) {
            if (block.size < 8 || le32(block, 0) != 0x544c534b) return
            val len = le32(block, 4)
            if (len !in 1..262144 || len > block.size - 8) return
            String(block, 8, len, Charsets.US_ASCII).lineSequence().take(2000).forEach { line ->
                val terms = line.trim().split(Regex("\\s+"))
                if (terms.size != 3 || terms[1].length != 64) return@forEach
                if (terms[0] !in setOf("CLIENT_TRAFFIC_SECRET_0", "SERVER_TRAFFIC_SECRET_0")) return@forEach
                val secret = unhex(terms[2]) ?: return@forEach
                if (secret.size != 32 && secret.size != 48) return@forEach
                val random = terms[1].lowercase(Locale.US)
                if (secrets.size < 400 || random in secrets) secrets.getOrPut(random) { HashMap() }[terms[0]] = secret
            }
        }
        fun get(random: String, label: String) = secrets[random]?.get(label)
        fun available() = secrets.isNotEmpty()
    }

    private fun hkdf(secret: ByteArray, label: String, length: Int, algorithm: String): ByteArray {
        val full = ("tls13 " + label).toByteArray(Charsets.US_ASCII)
        val info = byteArrayOf((length ushr 8).toByte(), length.toByte(), full.size.toByte()) +
            full + byteArrayOf(0)
        var prev = ByteArray(0)
        val output = ByteArrayOutputStream()
        var counter = 1
        while (output.size() < length) {
            val mac = Mac.getInstance(algorithm)
            mac.init(SecretKeySpec(secret, algorithm))
            prev = mac.doFinal(prev + info + byteArrayOf(counter.toByte()))
            output.write(prev)
            counter++
        }
        return output.toByteArray().copyOf(length)
    }

    private class Tls13Keys(secret: ByteArray, private val suite: Int) {
        private val algorithm = if (suite == 0x1302) "HmacSHA384" else "HmacSHA256"
        private val key = hkdf(secret, "key", if (suite == 0x1301) 16 else 32, algorithm)
        private val iv = hkdf(secret, "iv", 12, algorithm)
        var sequence = 0L
        fun decrypt(header: ByteArray, payload: ByteArray): ByteArray? = try {
            val nonce = iv.copyOf()
            for (i in 0..7) {
                val p = nonce.size - 1 - i
                nonce[p] = (nonce[p].toInt() xor ((sequence ushr (8*i)).toInt() and 255)).toByte()
            }
            val cipher = if (suite == 0x1303) Cipher.getInstance("ChaCha20-Poly1305")
                         else Cipher.getInstance("AES/GCM/NoPadding")
            if (suite == 0x1303) cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "ChaCha20"), IvParameterSpec(nonce))
            else cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            val plaintext = cipher.doFinal(payload)
            var last = plaintext.size - 1
            while (last >= 0 && plaintext[last] == 0.toByte()) last--
            if (last < 0 || (plaintext[last].toInt() and 255) !in listOf(22, 23)) return null
            sequence++
            if ((plaintext[last].toInt() and 255) == 23) plaintext.copyOfRange(0, last)
            else ByteArray(0) // post-handshake messages do not contain HTTP
        } catch (_: Exception) { null }
    }

    /** Per-direction ordered TCP byte stream: duplicate retransmissions are ignored. */
    private class Direction {
        var next: Long? = null
        val pending = TreeMap<Long, ByteArray>()
        val tls = ByteArrayOutputStream()
        val plain = ByteArrayOutputStream()
        var traffic: Tls13Keys? = null
        fun push(sequence: Long, bytes: ByteArray, onRecord: (ByteArray, ByteArray) -> Unit) {
            if (bytes.isEmpty() || bytes.size > 65536) return
            var seq = sequence
            var chunk = bytes
            if (next == null) next = seq
            val expected = next ?: return
            if (seq < expected) {
                val skip = expected - seq
                if (skip >= chunk.size) return
                chunk = chunk.copyOfRange(skip.toInt(), chunk.size)
                seq = expected
            }
            if (seq > expected) {
                if (pending.size < 24) pending[seq] = chunk
                return
            }
            fun append(data: ByteArray) {
                next = (next ?: return) + data.size
                if (tls.size() + data.size > MAX_STREAM) { tls.reset(); return }
                tls.write(data)
                while (tls.size() >= 5) {
                    val all = tls.toByteArray()
                    if ((all[0].toInt() and 255) !in listOf(20,21,22,23) ||
                        (all[1].toInt() and 255) != 3) { tls.reset(); return }
                    val length = u16(all, 3)
                    if (length > 18432) { tls.reset(); return }
                    if (all.size < length + 5) break
                    onRecord(all.copyOfRange(0, 5), all.copyOfRange(5, length + 5))
                    tls.reset()
                    if (all.size > length + 5) tls.write(all, length + 5, all.size - length - 5)
                }
            }
            append(chunk)
            while (true) {
                val current = next ?: break
                val item = pending.floorEntry(current) ?: break
                pending.remove(item.key)
                val skip = (current - item.key).toInt().coerceAtLeast(0)
                if (skip < item.value.size) append(item.value.copyOfRange(skip, item.value.size))
            }
        }
    }

    private class Session(val client: String, val remote: String) {
        var clientRandom = ""
        var suite = 0
        var success = false
        val outgoing = Direction()
        val incoming = Direction()

        fun record(clientToServer: Boolean, header: ByteArray, data: ByteArray, log: KeyLog,
                   output: MutableList<Entry>) {
            val contentType = header[0].toInt() and 255
            if (contentType == 22) {
                if (data.size >= 38 && clientToServer && data[0].toInt() == 1)
                    clientRandom = hex(data.copyOfRange(6, 38))
                if (data.size >= 41 && !clientToServer && data[0].toInt() == 2) {
                    val sessionIdLen = data[38].toInt() and 255
                    if (39 + sessionIdLen + 2 <= data.size) suite = u16(data, 39 + sessionIdLen)
                }
                return
            }
            if (contentType != 23 || clientRandom.isEmpty() || suite !in 0x1301..0x1303) return
            val side = if (clientToServer) outgoing else incoming
            if (side.traffic == null) {
                val label = if (clientToServer) "CLIENT_TRAFFIC_SECRET_0" else "SERVER_TRAFFIC_SECRET_0"
                val secret = log.get(clientRandom, label) ?: return
                side.traffic = Tls13Keys(secret, suite)
            }
            val plaintext = side.traffic?.decrypt(header, data) ?: return
            success = true
            if (plaintext.isNotEmpty() && output.size < MAX_EVENTS)
                http(side, plaintext, if (clientToServer) "Запрос" else "Ответ", output)
        }

        private fun http(side: Direction, content: ByteArray, direction: String, output: MutableList<Entry>) {
            if (side.plain.size() + content.size > MAX_STREAM) side.plain.reset()
            if (content.size > MAX_STREAM) return
            side.plain.write(content)
            val all = side.plain.toByteArray()
            val sep = (0 until (all.size - 3).coerceAtLeast(0))
                .firstOrNull { all[it] == 13.toByte() && all[it+1] == 10.toByte() &&
                               all[it+2] == 13.toByte() && all[it+3] == 10.toByte() } ?: return
            val header = String(all, 0, sep, Charsets.ISO_8859_1)
            val first = header.lineSequence().firstOrNull()?.trim().orEmpty()
            val request = Regex("^(GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS|CONNECT)\\s+\\S+\\s+HTTP/1\\.[01]$")
            if (!first.startsWith("HTTP/1.") && !request.matches(first)) {
                side.plain.reset() // HTTP/2 uses binary HPACK; do not misrepresent it.
                return
            }
            val safeHeader = header.lineSequence().take(80).joinToString("\n") { line ->
                if (Regex("(?i)^(authorization|cookie|set-cookie|proxy-authorization|x-api-key):").containsMatchIn(line))
                    line.substringBefore(":") + ": [скрыто]" else line
            }.take(8192)
            val body = all.copyOfRange(sep + 4, minOf(all.size, sep + 4 + 4096))
            val printable = body.isNotEmpty() &&
                body.count { val n = it.toInt() and 255; n in 32..126 || n in 128..255 || n in listOf(9,10,13) } * 10 >= body.size * 8
            val preview = safeHeader + if (printable) "\n\n" + String(body, Charsets.UTF_8).take(4096) else ""
            output.add(Entry(client, remote, direction, first.take(240), preview,
                when (suite) { 0x1301 -> "AES-128-GCM"; 0x1302 -> "AES-256-GCM"; else -> "ChaCha20" }))
            side.plain.reset()
        }
    }

    fun decode(file: File): Report {
        if (!file.isFile || !file.name.endsWith(".pcapng", true) || file.length() > MAX_FILE)
            return Report(emptyList(), 0, false, "Выберите завершённый PCAPNG размером до 64 МБ.")
        return try { file.inputStream().buffered().use { decode(it) } }
        catch (_: Exception) { Report(emptyList(), 0, false, "Ошибка чтения PCAPNG.") }
    }

    fun decode(input: InputStream): Report {
        val log = KeyLog()
        val sessions = LinkedHashMap<String, Session>()
        val results = ArrayList<Entry>()
        var count = 0
        var consumed = 0L
        val head = ByteArray(8)
        while (readFull(input, head)) {
            val type = le32(head, 0)
            val size = le32(head, 4)
            if (size !in 12..524288 || consumed + size > MAX_FILE) break
            val rest = ByteArray(size - 8)
            if (!readFull(input, rest) || le32(rest, rest.size - 4) != size) break
            consumed += size
            val block = rest.copyOf(rest.size - 4)
            if (type == 10) log.add(block)
            if (type != 6 || block.size < 20 || count++ > 60000 || results.size >= MAX_EVENTS) continue
            val captureSize = le32(block, 12)
            if (captureSize !in 40..65535 || captureSize > block.size - 20) continue
            val p = block.copyOfRange(20, 20 + captureSize)
            val info = PacketInfo.parse(p, p.size) ?: continue
            if (info.protocol != 6 || info.payloadOffset >= p.size) continue
            val l4 = when (info.version) {
                4 -> { if ((PacketInfo.u16(p, 6) and 0x3fff) != 0) continue; (p[0].toInt() and 15) * 4 }
                6 -> { if (p[6].toInt() and 255 != 6) continue; 40 }
                else -> continue
            }
            if (l4 + 20 > p.size || info.payloadOffset <= l4) continue
            val outbound = info.src.startsWith("10.10.14.") || info.src.startsWith("10.203.0.") ||
                info.src.startsWith("fc00:")
            val inbound = info.dst.startsWith("10.10.14.") || info.dst.startsWith("10.203.0.") ||
                info.dst.startsWith("fc00:")
            if (!outbound && !inbound) continue
            val client = if (outbound) info.src + ":" + info.srcPort else info.dst + ":" + info.dstPort
            val remote = if (outbound) info.dst + ":" + info.dstPort else info.src + ":" + info.srcPort
            val key = client + ">" + remote
            val session = sessions[key] ?: if (sessions.size < 250)
                Session(client, remote).also { sessions[key] = it } else continue
            val seq = u32(p, l4 + 4)
            val data = p.copyOfRange(info.payloadOffset, p.size)
            val direction = if (outbound) session.outgoing else session.incoming
            direction.push(seq, data) { header, payload -> session.record(outbound, header, payload, log, results) }
        }
        val successful = sessions.values.count { it.success }
        val notice = when {
            !log.available() -> "TLS-ключей нет. Включите MITM и запись перед открытием HTTPS-сайта."
            results.isEmpty() -> "TLS-ключи обнаружены, но HTTP/1.1 не распознан. Возможны HTTP/2, TLS 1.2, QUIC или неполный захват."
            else -> "Расшифрованные HTTP/1.1-заголовки и текстовый фрагмент. Чувствительные заголовки скрыты."
        }
        return Report(results, successful, log.available(), notice)
    }
}
