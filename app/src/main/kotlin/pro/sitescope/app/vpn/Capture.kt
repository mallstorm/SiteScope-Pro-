package pro.sitescope.app.vpn

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Файл дампа в формате pcap (открывается в Wireshark). Пакеты пишутся как «сырые» IP, без канального заголовка. */
class PcapWriter(stream: OutputStream) {
    private val out = BufferedOutputStream(stream, 64 * 1024)
    var bytes = 24L; private set

    init {
        val h = ByteArray(24)
        le32(h, 0, 0xa1b2c3d4L); le16(h, 4, 2); le16(h, 6, 4)   // сигнатура и версия 2.4
        le32(h, 16, 65535); le32(h, 20, 101)                    // максимум пакета; тип канала 101 = RAW IP
        out.write(h)
    }

    @Synchronized fun write(packet: ByteArray, offset: Int, length: Int, timeMillis: Long) {
        val h = ByteArray(16)
        le32(h, 0, timeMillis / 1000); le32(h, 4, (timeMillis % 1000) * 1000)
        le32(h, 8, length.toLong()); le32(h, 12, length.toLong())
        out.write(h); out.write(packet, offset, length); bytes += 16 + length
    }

    @Synchronized fun flush() { try { out.flush() } catch (e: Exception) { } }
    @Synchronized fun close() { try { out.flush(); out.close() } catch (e: Exception) { } }

    private fun le16(b: ByteArray, o: Int, v: Int) { b[o] = v.toByte(); b[o + 1] = (v shr 8).toByte() }
    private fun le32(b: ByteArray, o: Int, v: Long) { for (i in 0 until 4) b[o + i] = (v shr (8 * i)).toByte() }
}

/** Разобранный заголовок IP-пакета: кто, кому, какой протокол и где начинаются данные. */
class PacketInfo(val version: Int, val protocol: Int, val src: String, val dst: String, val srcPort: Int, val dstPort: Int, val payloadOffset: Int) {
    val protocolName: String get() = when (protocol) { 6 -> "TCP"; 17 -> "UDP"; 1, 58 -> "ICMP"; else -> "IP/$protocol" }

    companion object {
        fun parse(p: ByteArray, length: Int): PacketInfo? {
            if (length < 20) return null
            val version = (p[0].toInt() and 0xF0) shr 4
            val proto: Int; val src: String; val dst: String; val l4: Int
            if (version == 4) {
                val ihl = (p[0].toInt() and 0x0F) * 4
                if (ihl < 20 || length < ihl) return null
                proto = p[9].toInt() and 255; src = v4(p, 12); dst = v4(p, 16); l4 = ihl
            } else if (version == 6) {
                if (length < 40) return null
                proto = p[6].toInt() and 255; l4 = 40
                src = InetAddress.getByAddress(p.copyOfRange(8, 24)).hostAddress ?: ""
                dst = InetAddress.getByAddress(p.copyOfRange(24, 40)).hostAddress ?: ""
            } else return null
            var sp = 0; var dp = 0; var payload = l4
            if ((proto == 6 || proto == 17) && length >= l4 + 8) {
                sp = u16(p, l4); dp = u16(p, l4 + 2)
                payload = if (proto == 17) l4 + 8 else l4 + ((p[l4 + 12].toInt() and 0xF0) shr 4) * 4
            }
            return PacketInfo(version, proto, src, dst, sp, dp, payload)
        }

        private fun v4(p: ByteArray, o: Int) = "${p[o].toInt() and 255}.${p[o + 1].toInt() and 255}.${p[o + 2].toInt() and 255}.${p[o + 3].toInt() and 255}"
        fun u16(p: ByteArray, o: Int) = ((p[o].toInt() and 255) shl 8) or (p[o + 1].toInt() and 255)
    }
}

/** Минимальный разбор DNS-сообщения: имя из вопроса и адреса из ответа. */
class DnsMessage(val response: Boolean, val name: String, val addresses: List<String>) {
    companion object {
        fun parse(p: ByteArray, offset: Int, end: Int): DnsMessage? {
            try {
                if (end - offset < 12) return null
                val flags = PacketInfo.u16(p, offset + 2)
                val questions = PacketInfo.u16(p, offset + 4); val answers = PacketInfo.u16(p, offset + 6)
                if (questions < 1 || questions > 4) return null
                var pos = offset + 12
                var first = ""
                for (i in 0 until questions) {
                    val (name, next) = readName(p, pos, offset, end) ?: return null
                    if (i == 0) first = name
                    pos = next + 4
                }
                val list = ArrayList<String>()
                if (flags and 0x8000 != 0) for (i in 0 until minOf(answers, 40)) {
                    val (_, next) = readName(p, pos, offset, end) ?: break
                    if (next + 10 > end) break
                    val type = PacketInfo.u16(p, next); val len = PacketInfo.u16(p, next + 8); val data = next + 10
                    if (data + len > end) break
                    if (type == 1 && len == 4 || type == 28 && len == 16) list.add(InetAddress.getByAddress(p.copyOfRange(data, data + len)).hostAddress ?: "")
                    pos = data + len
                }
                return DnsMessage(flags and 0x8000 != 0, first.lowercase(Locale.US), list)
            } catch (e: Exception) { return null }
        }

        private fun readName(p: ByteArray, start: Int, base: Int, end: Int): Pair<String, Int>? {
            val sb = StringBuilder(); var pos = start; var after = -1; var jumps = 0
            while (true) {
                if (pos >= end) return null
                val len = p[pos].toInt() and 255
                if (len == 0) { pos++; break }
                if (len and 0xC0 == 0xC0) {
                    if (pos + 1 >= end || ++jumps > 12) return null
                    if (after < 0) after = pos + 2
                    pos = base + (((len and 0x3F) shl 8) or (p[pos + 1].toInt() and 255)); continue
                }
                if (pos + 1 + len > end) return null
                if (sb.isNotEmpty()) sb.append('.')
                sb.append(String(p, pos + 1, len, Charsets.ISO_8859_1)); pos += 1 + len
            }
            return sb.toString() to (if (after >= 0) after else pos)
        }
    }
}

/** Одно «соединение» в списке записи: с кем общались и сколько передали. */
class Flow(val remote: String, val port: Int, val protocol: String) {
    var host = ""; var packets = 0L; var sent = 0L; var received = 0L; var lastAt = 0L
}

/**
 * Запись трафика. Получает каждый пакет, проходящий через VPN, считает статистику, ведёт список
 * соединений и пишет подходящие пакеты в файл. Если задан сайт, в файл попадают только его
 * DNS-запросы и пакеты на адреса, которые вернул DNS.
 */
class CaptureSession(val file: File?, stream: OutputStream?, filterText: String, private val localPrefixes: List<String> = listOf("10.10.14.", "10.203.0.", "fc00:")) {
    private val writer = if (stream != null) PcapWriter(stream) else null
    private val domains = ArrayList<String>()
    private val allowed = HashSet<String>()
    private val names = HashMap<String, String>()
    private val flows = LinkedHashMap<String, Flow>()
    val startedAt = System.currentTimeMillis()
    @Volatile var seen = 0L; private set
    @Volatile var written = 0L; private set
    @Volatile var writtenBytes = 0L; private set
    val filtered: Boolean

    init {
        for (token in filterText.lowercase(Locale.US).split(',', ' ', '\n', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val clean = token.removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('?')
            if (clean.isEmpty()) continue
            if (clean.all { it.isDigit() || it == '.' } || clean.contains(':')) allowed.add(normalizeIp(clean)) else domains.add(clean.removePrefix("www."))
        }
        filtered = domains.isNotEmpty() || allowed.isNotEmpty()
    }

    private fun normalizeIp(ip: String): String = try { InetAddress.getByName(ip).hostAddress ?: ip } catch (e: Exception) { ip }
    private fun matches(name: String) = domains.any { name == it || name.endsWith(".$it") }
    private fun isLocal(ip: String) = localPrefixes.any { ip.startsWith(it) }

    @Synchronized fun onPacket(packet: ByteArray, length: Int, now: Long = System.currentTimeMillis()) {
        val info = PacketInfo.parse(packet, length) ?: return
        seen++
        val outbound = isLocal(info.src) || !isLocal(info.dst)
        val remote = if (outbound) info.dst else info.src
        val remotePort = if (outbound) info.dstPort else info.srcPort
        var keep = !filtered
        if (info.protocol == 17 && (info.srcPort == 53 || info.dstPort == 53)) {
            val dns = DnsMessage.parse(packet, info.payloadOffset, length)
            if (dns != null) {
                val hit = matches(dns.name)
                if (dns.response) for (a in dns.addresses) { if (names.size < 5000) names[a] = dns.name; if (hit) allowed.add(a) }
                if (filtered) keep = hit
            }
        } else if (filtered) keep = allowed.contains(remote)

        if (info.protocol == 6 || info.protocol == 17) {
            val key = info.protocolName + "|" + remote + "|" + remotePort
            var flow = flows[key]
            if (flow == null && flows.size < 400) { flow = Flow(remote, remotePort, info.protocolName); flows[key] = flow }
            if (flow != null) {
                if (flow.host.isEmpty()) flow.host = names[remote] ?: ""
                flow.packets++; flow.lastAt = now
                if (outbound) flow.sent += length else flow.received += length
            }
        }
        if (keep && writer != null) { writer.write(packet, 0, length, now); written++; writtenBytes = writer.bytes }
    }

    /** Самые «тяжёлые» соединения — для показа на экране. */
    @Synchronized fun topFlows(limit: Int): List<Flow> = flows.values.sortedByDescending { it.sent + it.received }.take(limit).map { f ->
        Flow(f.remote, f.port, f.protocol).also { it.host = f.host; it.packets = f.packets; it.sent = f.sent; it.received = f.received; it.lastAt = f.lastAt }
    }

    fun flush() { writer?.flush() }
    fun close() { writer?.close() }
}

/** Точка, через которую служба VPN отдаёт пакеты на запись. */
object CaptureHub {
    @Volatile var session: CaptureSession? = null; private set
    @Volatile var lastFile: File? = null; private set

    @JvmStatic fun tap(packet: ByteArray, length: Int) { try { session?.onPacket(packet, length) } catch (e: Throwable) { } }
    @JvmStatic fun active(): Boolean = session != null

    @JvmStatic @Synchronized fun start(dir: File, filter: String): CaptureSession {
        stop()
        if (!dir.exists()) dir.mkdirs()
        val label = filter.trim().lowercase(Locale.US).filter { it.isLetterOrDigit() || it == '.' || it == '-' }.take(40)
        val name = "sitescope-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + (if (label.isNotEmpty()) "-$label" else "") + ".pcap"
        val file = File(dir, name)
        val s = CaptureSession(file, FileOutputStream(file), filter)
        session = s; lastFile = file
        return s
    }

    @JvmStatic @Synchronized fun stop() { val s = session; session = null; s?.close() }

    fun files(dir: File): List<File> = (dir.listFiles { f -> f.isFile && f.name.endsWith(".pcap") } ?: emptyArray()).sortedByDescending { it.lastModified() }
}
