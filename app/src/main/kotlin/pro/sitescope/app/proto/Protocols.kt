package pro.sitescope.app.proto

import android.text.Html
import android.util.Base64
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import pro.sitescope.app.UpnpDescriptionParser
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

// Протоколы без интерфейса: экраны только вызывают эти классы и показывают результат.

internal fun readExact(input: InputStream, n: Int): ByteArray {
    val b = ByteArray(n); var p = 0
    while (p < n) { val r = input.read(b, p, n - p); if (r < 0) throw java.io.EOFException("Соединение закрыто"); p += r }
    return b
}

internal fun localName(n: String): String { val c = n.indexOf(':'); return if (c >= 0) n.substring(c + 1) else n }
internal fun xmlEscape(x: String) = x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
internal fun pullParser(xml: String): XmlPullParser = XmlPullParserFactory.newInstance().newPullParser().apply { setInput(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)), "UTF-8") }

// ------------------------------------------------------------------ VNC (RFB 3.8)

/** Удалённый экран по протоколу VNC: без пароля или с классическим паролем VNC, несжатая картинка, указатель и клавиши. */
class VncClient(private val host: String, private val port: Int, private val password: String, private val listener: Listener) : Runnable {
    interface Listener {
        fun onStatus(text: String)
        fun onScreen(width: Int, height: Int, name: String)
        fun onPixels(x: Int, y: Int, w: Int, h: Int, pixels: IntArray)
        fun onClosed(error: String?)
    }

    private var socket: Socket? = null
    private lateinit var input: InputStream
    private lateinit var out: OutputStream
    @Volatile var active = false; private set
    @Volatile private var closedByUser = false
    var width = 0; private set
    var height = 0; private set

    override fun run() {
        var error: String? = null
        try {
            val s = Socket(); socket = s
            s.connect(InetSocketAddress(host, if (port > 0) port else 5900), 10000); s.soTimeout = 20000
            input = BufferedInputStream(s.getInputStream()); out = BufferedOutputStream(s.getOutputStream())
            if (!String(readExact(input, 12), Charsets.US_ASCII).startsWith("RFB ")) throw Exception("Это не сервер VNC")
            out.write("RFB 003.008\n".toByteArray(Charsets.US_ASCII)); out.flush()
            val count = input.read()
            if (count <= 0) throw Exception(String(readExact(input, readInt()), Charsets.UTF_8))
            val types = readExact(input, count).map { it.toInt() and 255 }
            val selected = when { 1 in types -> 1; 2 in types -> 2; else -> throw Exception("Сервер требует способ входа, который не поддерживается") }
            out.write(selected); out.flush()
            if (selected == 2) { out.write(auth(password, readExact(input, 16))); out.flush() }
            if (readInt() != 0) {
                val reason = try { String(readExact(input, readInt()), Charsets.UTF_8) } catch (e: Exception) { "" }
                throw Exception(if (reason.isBlank() || reason.contains("auth", true)) "Неверный пароль" else reason)
            }
            out.write(1); out.flush()
            width = readU16(); height = readU16(); readExact(input, 16)
            val name = String(readExact(input, readInt()), Charsets.UTF_8)
            send(byteArrayOf(0, 0, 0, 0, 32, 24, 0, 1, 0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte(), 16, 8, 0, 0, 0, 0))   // формат пикселя: 32 бита, BGRX
            send(ByteArrayOutputStream().apply { write(2); write(0); u16(this, 2); i32(this, 0); i32(this, -223) }.toByteArray())  // кодировки: raw и изменение размера
            active = true
            listener.onScreen(width, height, name)
            request(false); s.soTimeout = 0
            while (active) {
                val type = input.read()
                if (type < 0) break
                when (type) {
                    0 -> update()
                    2 -> { }
                    3 -> { readExact(input, 3); readExact(input, readInt()) }
                    else -> throw Exception("Сервер прислал неизвестное сообщение $type")
                }
            }
        } catch (e: Exception) { if (!closedByUser) error = e.message ?: e.javaClass.simpleName }
        finally { active = false; try { socket?.close() } catch (e: Exception) { }; listener.onClosed(error) }
    }

    private fun update() {
        input.read(); val rects = readU16()
        for (r in 0 until rects) {
            val x = readU16(); val y = readU16(); val w = readU16(); val h = readU16(); val enc = readInt()
            if (enc == 0) {
                val raw = readExact(input, w * h * 4); val pixels = IntArray(w * h)
                var p = 0
                for (i in pixels.indices) { pixels[i] = (0xFF shl 24) or ((raw[p + 2].toInt() and 255) shl 16) or ((raw[p + 1].toInt() and 255) shl 8) or (raw[p].toInt() and 255); p += 4 }
                listener.onPixels(x, y, w, h, pixels)
            } else if (enc == -223) { width = w; height = h; listener.onScreen(w, h, "") }
            else throw Exception("Сервер использует сжатие $enc, которое не поддерживается")
        }
        request(true)
    }

    private fun request(incremental: Boolean) = send(ByteArrayOutputStream().apply { write(3); write(if (incremental) 1 else 0); u16(this, 0); u16(this, 0); u16(this, width); u16(this, height) }.toByteArray())
    fun pointer(mask: Int, x: Int, y: Int) { try { send(ByteArrayOutputStream().apply { write(5); write(mask); u16(this, x); u16(this, y) }.toByteArray()) } catch (e: Exception) { } }
    fun key(keysym: Int, down: Boolean) { try { send(ByteArrayOutputStream().apply { write(4); write(if (down) 1 else 0); write(0); write(0); i32(this, keysym) }.toByteArray()) } catch (e: Exception) { } }
    fun press(keysym: Int) { key(keysym, true); key(keysym, false) }
    fun type(text: String) { for (c in text) press(if (c == '\n') 0xFF0D else c.code) }
    fun combo(vararg keys: Int) { for (k in keys) key(k, true); for (k in keys.reversed()) key(k, false) }
    fun close() { closedByUser = true; active = false; try { socket?.close() } catch (e: Exception) { } }

    @Synchronized private fun send(b: ByteArray) { out.write(b); out.flush() }
    private fun readU16(): Int { val b = readExact(input, 2); return ((b[0].toInt() and 255) shl 8) or (b[1].toInt() and 255) }
    private fun readInt(): Int { val b = readExact(input, 4); return ((b[0].toInt() and 255) shl 24) or ((b[1].toInt() and 255) shl 16) or ((b[2].toInt() and 255) shl 8) or (b[3].toInt() and 255) }
    private fun u16(o: OutputStream, v: Int) { o.write((v shr 8) and 255); o.write(v and 255) }
    private fun i32(o: OutputStream, v: Int) { o.write((v shr 24) and 255); o.write((v shr 16) and 255); o.write((v shr 8) and 255); o.write(v and 255) }

    companion object {
        const val KEY_ENTER = 0xFF0D; const val KEY_ESC = 0xFF1B; const val KEY_TAB = 0xFF09; const val KEY_BACKSPACE = 0xFF08
        const val KEY_CTRL = 0xFFE3; const val KEY_ALT = 0xFFE9; const val KEY_DELETE = 0xFFFF; const val KEY_SUPER = 0xFFEB

        /** Ответ на «вызов» сервера: DES с ключом из пароля, биты каждого байта перевёрнуты. */
        fun auth(password: String, challenge: ByteArray): ByteArray {
            val p = password.toByteArray(Charsets.ISO_8859_1)
            val key = ByteArray(8) { i -> var x = if (i < p.size) p[i].toInt() and 255 else 0; var r = 0; repeat(8) { r = (r shl 1) or (x and 1); x = x shr 1 }; r.toByte() }
            return Cipher.getInstance("DES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES")) }.doFinal(challenge)
        }
    }
}

// ------------------------------------------------------------------ SNMP v1/v2c (только чтение)

object Snmp {
    class VarBind(val oid: String, val value: String)
    private val ids = AtomicInteger(1000)

    fun get(host: String, port: Int, community: String, oid: String): List<VarBind> = listOfNotNull(query(host, port, community, normalize(oid), false))

    /** Обходит ветку, не более [limit] значений. */
    fun walk(host: String, port: Int, community: String, oid: String, limit: Int = 200): List<VarBind> {
        val base = normalize(oid); var current = base; val out = ArrayList<VarBind>()
        for (i in 0 until limit) {
            val vb = query(host, port, community, current, true) ?: break
            if (vb.oid != base && !vb.oid.startsWith("$base.") || vb.oid == current) break
            out.add(vb); current = vb.oid
        }
        return out
    }

    fun normalize(oid: String): String = oid.trim().trimStart('.')

    private fun query(host: String, port: Int, community: String, oid: String, next: Boolean): VarBind? {
        val id = ids.incrementAndGet()
        val request = message(community, if (next) 0xA1 else 0xA0, id, oid)
        DatagramSocket().use { socket ->
            socket.soTimeout = 5000
            socket.send(DatagramPacket(request, request.size, InetAddress.getByName(host), if (port > 0) port else 161))
            val buf = ByteArray(65535); val reply = DatagramPacket(buf, buf.size)
            socket.receive(reply)
            return parse(buf, reply.length, id)
        }
    }

    fun message(community: String, pduType: Int, id: Int, oid: String): ByteArray {
        val vbl = seq(seq(oidBytes(oid), tlv(0x05, ByteArray(0))))
        return seq(integer(1), tlv(0x04, community.toByteArray(Charsets.UTF_8)), tlv(pduType, integer(id.toLong()) + integer(0) + integer(0) + vbl))
    }

    fun parse(data: ByteArray, length: Int, wanted: Int): VarBind? {
        val r = Reader(data, length)
        r.enter(0x30); r.readInt(); r.readBytes(0x04)
        val pdu = r.peek(); if (pdu != 0xA2) throw Exception("Неожиданный ответ устройства")
        r.enter(pdu)
        val id = r.readInt(); val err = r.readInt(); r.readInt()
        if (id != wanted) throw Exception("Ответ не на тот запрос")
        if (err != 0) throw Exception(if (err == 2) "Такого значения на устройстве нет" else "Устройство вернуло ошибку $err")
        r.enter(0x30); if (!r.has()) return null
        r.enter(0x30)
        val oid = r.readOid(); return VarBind(oid, r.readValue(r.peek()))
    }

    private fun integer(v: Long): ByteArray {
        val b = ByteArrayOutputStream(); var x = v
        do { b.write((x and 255).toInt()); x = x shr 8 } while (x != 0L && x != -1L)
        var value = b.toByteArray().reversedArray()
        if (v >= 0 && value[0].toInt() and 0x80 != 0) value = byteArrayOf(0) + value
        return tlv(0x02, value)
    }
    private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    private fun oidBytes(text: String): ByteArray {
        val p = text.split('.'); if (p.size < 2) throw Exception("Некорректный OID")
        val b = ByteArrayOutputStream(); base128(b, p[0].toLong() * 40 + p[1].toLong())
        for (i in 2 until p.size) base128(b, p[i].toLong())
        return tlv(0x06, b.toByteArray())
    }
    private fun base128(b: ByteArrayOutputStream, value: Long) {
        var v = value; val tmp = ByteArray(10); var n = 0
        tmp[n++] = (v and 0x7F).toByte(); v = v shr 7
        while (v > 0) { tmp[n++] = ((v and 0x7F) or 0x80).toByte(); v = v shr 7 }
        for (i in n - 1 downTo 0) b.write(tmp[i].toInt())
    }
    private fun tlv(tag: Int, value: ByteArray) = byteArrayOf(tag.toByte()) + len(value.size) + value
    private fun len(size: Int): ByteArray {
        if (size < 128) return byteArrayOf(size.toByte())
        val bytes = when { size > 0xFFFFFF -> 4; size > 0xFFFF -> 3; size > 0xFF -> 2; else -> 1 }
        val out = ByteArray(1 + bytes); out[0] = (0x80 or bytes).toByte(); var n = size
        for (i in bytes downTo 1) { out[i] = (n and 255).toByte(); n = n shr 8 }
        return out
    }

    private class Reader(val d: ByteArray, var end: Int) {
        var p = 0
        fun peek() = d[p].toInt() and 255
        fun has() = p < end
        fun read() = d[p++].toInt() and 255
        fun readLen(): Int { var n = read(); if (n and 0x80 == 0) return n; val c = n and 0x7F; n = 0; repeat(c) { n = (n shl 8) or read() }; return n }
        fun enter(tag: Int) { val t = read(); if (t != tag) throw Exception("Ответ устройства не разобран"); end = p + readLen() }
        fun readInt(): Int { if (read() != 0x02) throw Exception("Ответ устройства не разобран"); val n = readLen(); var v = 0; repeat(n) { v = (v shl 8) or read() }; return v }
        fun readBytes(wanted: Int): ByteArray { if (read() != wanted) throw Exception("Ответ устройства не разобран"); val n = readLen(); val x = d.copyOfRange(p, p + n); p += n; return x }
        fun readOid(): String = oidText(readBytes(0x06))
        fun readValue(tag: Int): String {
            val x = readBytes(tag)
            return when {
                tag == 0x04 -> if (x.any { val c = it.toInt() and 255; c < 9 || c in 14..31 }) x.joinToString(":") { String.format("%02x", it.toInt() and 255) } else String(x, Charsets.UTF_8)
                tag == 0x06 -> oidText(x)
                tag == 0x43 -> { var v = 0L; for (b in x) v = (v shl 8) or (b.toLong() and 255); uptime(v) }
                tag == 0x02 || tag == 0x41 || tag == 0x42 || tag == 0x46 -> { var v = 0L; for (b in x) v = (v shl 8) or (b.toLong() and 255); v.toString() }
                tag == 0x40 && x.size == 4 -> x.joinToString(".") { (it.toInt() and 255).toString() }
                tag == 0x05 -> ""
                tag == 0x80 || tag == 0x81 -> "нет такого значения"
                tag == 0x82 -> "конец ветки"
                else -> "0x" + x.joinToString("") { String.format("%02x", it.toInt() and 255) }
            }
        }
        private fun oidText(x: ByteArray): String {
            val values = ArrayList<Long>(); var v = 0L
            for (b in x) { v = (v shl 7) or (b.toLong() and 0x7F); if (b.toInt() and 0x80 == 0) { values.add(v); v = 0 } }
            if (values.isEmpty()) return ""
            val first = values.removeAt(0)
            val arc = if (first < 40) 0L else if (first < 80) 1L else 2L
            return (listOf(arc, first - arc * 40) + values).joinToString(".")
        }
    }

    /** Сотые доли секунды → «3 дн 4 ч 12 мин». */
    fun uptime(ticks: Long): String {
        val s = ticks / 100; val d = s / 86400; val h = s % 86400 / 3600; val m = s % 3600 / 60
        return (if (d > 0) "$d дн " else "") + "$h ч $m мин"
    }
}

// ------------------------------------------------------------------ MQTT 3.1.1

class MqttClient(private val host: String, private val port: Int, private val tls: Boolean, private val clientId: String,
                 private val user: String, private val password: String, private val listener: Listener) {
    interface Listener {
        fun onConnected()
        fun onMessage(topic: String, payload: String)
        fun onInfo(text: String)
        fun onClosed(error: String?)
    }

    private var socket: Socket? = null
    private var out: OutputStream? = null
    @Volatile var running = false; private set
    @Volatile private var closedByUser = false
    private val packetId = AtomicInteger(1)

    fun connect() {
        Thread {
            var error: String? = null
            try {
                val p = if (port > 0) port else if (tls) 8883 else 1883
                val s = if (tls) TlsSockets.connect(host, p) else Socket().apply { socket = this; connect(InetSocketAddress(host, p), 10000); soTimeout = 15000 }
                socket = s
                val input = BufferedInputStream(s.getInputStream()); out = BufferedOutputStream(s.getOutputStream())
                send(connectPacket())
                if (input.read() != 0x20) throw Exception("Сервер не подтвердил подключение")
                val body = readExact(input, remaining(input))
                if (body.size < 2 || body[1].toInt() != 0) throw Exception(when (body.getOrNull(1)?.toInt()) { 4 -> "Неверный логин или пароль"; 5 -> "Доступ запрещён"; 2 -> "Сервер отклонил имя клиента"; else -> "Сервер отказал в подключении" })
                s.soTimeout = 0
                running = true; listener.onConnected()
                Thread { ping() }.start()
                while (running) {
                    val h = input.read(); if (h < 0) break
                    val data = readExact(input, remaining(input))
                    when (h shr 4) {
                        3 -> {
                            val tlen = ((data[0].toInt() and 255) shl 8) or (data[1].toInt() and 255)
                            var pos = 2 + tlen; if ((h shr 1) and 3 > 0) pos += 2
                            listener.onMessage(String(data, 2, tlen, Charsets.UTF_8), String(data, pos, data.size - pos, Charsets.UTF_8))
                        }
                        9 -> listener.onInfo("Подписка подтверждена")
                        else -> { }
                    }
                }
            } catch (e: Exception) { if (!closedByUser) error = e.message ?: e.javaClass.simpleName }
            finally { running = false; try { socket?.close() } catch (e: Exception) { }; listener.onClosed(error) }
        }.start()
    }

    private fun ping() { try { while (running) { Thread.sleep(20000); if (running) send(byteArrayOf(0xC0.toByte(), 0)) } } catch (e: Exception) { } }

    private fun connectPacket(): ByteArray {
        val v = ByteArrayOutputStream(); utf(v, "MQTT"); v.write(4)
        var flags = 2; if (user.isNotEmpty()) flags = flags or 0x80; if (password.isNotEmpty()) flags = flags or 0x40
        v.write(flags); v.write(0); v.write(30); utf(v, clientId)
        if (user.isNotEmpty()) utf(v, user); if (password.isNotEmpty()) utf(v, password)
        return packet(0x10, v.toByteArray())
    }

    fun subscribe(topic: String) { val b = ByteArrayOutputStream(); val id = nextId(); b.write(id shr 8); b.write(id); utf(b, topic); b.write(0); send(packet(0x82, b.toByteArray())) }
    fun unsubscribe(topic: String) { val b = ByteArrayOutputStream(); val id = nextId(); b.write(id shr 8); b.write(id); utf(b, topic); send(packet(0xA2, b.toByteArray())) }
    fun publish(topic: String, payload: String, retain: Boolean = false) { val b = ByteArrayOutputStream(); utf(b, topic); b.write(payload.toByteArray(Charsets.UTF_8)); send(packet(if (retain) 0x31 else 0x30, b.toByteArray())) }
    fun close() { closedByUser = true; running = false; try { send(byteArrayOf(0xE0.toByte(), 0)) } catch (e: Exception) { }; try { socket?.close() } catch (e: Exception) { } }

    @Synchronized private fun send(p: ByteArray) { val o = out ?: throw Exception("Нет соединения"); o.write(p); o.flush() }
    private fun nextId(): Int { var id = packetId.incrementAndGet(); if (id > 65535) { packetId.set(1); id = 1 }; return id }

    companion object {
        fun packet(header: Int, body: ByteArray): ByteArray {
            val b = ByteArrayOutputStream(); b.write(header); var x = body.size
            do { var d = x % 128; x /= 128; if (x > 0) d = d or 128; b.write(d) } while (x > 0)
            b.write(body); return b.toByteArray()
        }
        private fun utf(o: OutputStream, s: String) { val x = s.toByteArray(Charsets.UTF_8); o.write(x.size shr 8); o.write(x.size); o.write(x) }
        fun remaining(input: InputStream): Int {
            var m = 1; var v = 0
            while (true) { val d = input.read(); if (d < 0) throw java.io.EOFException("Соединение закрыто"); v += (d and 127) * m; m *= 128; if (d and 128 == 0) break; if (m > 128 * 128 * 128) throw Exception("Некорректный пакет") }
            return v
        }
    }
}

// ------------------------------------------------------------------ ONVIF (только чтение)

object Onvif {
    class Profile(val token: String, val name: String)
    class Device(val info: List<Pair<String, String>>, val mediaUrl: String, val profiles: List<Profile>)

    fun defaultUrl(host: String, port: Int, location: String): String =
        if (location.startsWith("http://") || location.startsWith("https://")) location else "http://$host:" + (if (port > 0 && port != 3702) port else 80) + "/onvif/device_service"

    fun inspect(base: String, user: String, password: String): Device {
        val info = soap(base, "http://www.onvif.org/ver10/device/wsdl/GetDeviceInformation", "<tds:GetDeviceInformation xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"/>", user, password)
        val caps = soap(base, "http://www.onvif.org/ver10/device/wsdl/GetCapabilities", "<tds:GetCapabilities xmlns:tds=\"http://www.onvif.org/ver10/device/wsdl\"><tds:Category>All</tds:Category></tds:GetCapabilities>", user, password)
        val media = mediaAddress(caps).ifEmpty { text(caps, "XAddr") }
        val profiles = if (media.isEmpty()) emptyList() else profiles(soap(media, "http://www.onvif.org/ver10/media/wsdl/GetProfiles", "<trt:GetProfiles xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\"/>", user, password))
        val rows = listOf("Производитель" to text(info, "Manufacturer"), "Модель" to text(info, "Model"), "Прошивка" to text(info, "FirmwareVersion"),
            "Серийный номер" to text(info, "SerialNumber"), "Идентификатор" to text(info, "HardwareId")).filter { it.second.isNotEmpty() }
        return Device(rows, media, profiles)
    }

    fun streamUri(mediaUrl: String, token: String, user: String, password: String): String {
        val xml = "<trt:GetStreamUri xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\"><trt:StreamSetup><tt:Stream xmlns:tt=\"http://www.onvif.org/ver10/schema\">RTP-Unicast</tt:Stream>" +
            "<tt:Transport xmlns:tt=\"http://www.onvif.org/ver10/schema\"><tt:Protocol>RTSP</tt:Protocol></tt:Transport></trt:StreamSetup><trt:ProfileToken>" + xmlEscape(token) + "</trt:ProfileToken></trt:GetStreamUri>"
        return text(soap(mediaUrl, "http://www.onvif.org/ver10/media/wsdl/GetStreamUri", xml, user, password), "Uri")
    }

    fun snapshotUri(mediaUrl: String, token: String, user: String, password: String): String = try {
        text(soap(mediaUrl, "http://www.onvif.org/ver10/media/wsdl/GetSnapshotUri", "<trt:GetSnapshotUri xmlns:trt=\"http://www.onvif.org/ver10/media/wsdl\"><trt:ProfileToken>" + xmlEscape(token) + "</trt:ProfileToken></trt:GetSnapshotUri>", user, password), "Uri")
    } catch (e: Exception) { "" }

    private fun soap(url: String, action: String, body: String, user: String, password: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10000; c.readTimeout = 15000; c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/soap+xml; charset=utf-8; action=\"$action\"")
            val payload = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?><s:Envelope xmlns:s=\"http://www.w3.org/2003/05/soap-envelope\"><s:Header>" + security(user, password) + "</s:Header><s:Body>" + body + "</s:Body></s:Envelope>").toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(payload.size); c.outputStream.use { it.write(payload) }
            val code = c.responseCode
            val data = (if (code in 200..399) c.inputStream else c.errorStream)?.readBytes() ?: ByteArray(0)
            if (code == 401 || code == 400 && String(data).contains("NotAuthorized")) throw Exception("Камера требует логин и пароль")
            if (code !in 200..399) throw Exception("Камера ответила ошибкой $code")
            return String(data, Charsets.UTF_8)
        } finally { c.disconnect() }
    }

    private fun security(user: String, password: String): String {
        if (user.isEmpty()) return ""
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val sha = MessageDigest.getInstance("SHA-1"); sha.update(nonce); sha.update(created.toByteArray(Charsets.UTF_8)); sha.update(password.toByteArray(Charsets.UTF_8))
        return "<wsse:Security s:mustUnderstand=\"1\" xmlns:wsse=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd\" xmlns:wsu=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd\">" +
            "<wsse:UsernameToken><wsse:Username>" + xmlEscape(user) + "</wsse:Username><wsse:Password Type=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest\">" +
            Base64.encodeToString(sha.digest(), Base64.NO_WRAP) + "</wsse:Password><wsse:Nonce EncodingType=\"http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary\">" +
            Base64.encodeToString(nonce, Base64.NO_WRAP) + "</wsse:Nonce><wsu:Created>" + created + "</wsu:Created></wsse:UsernameToken></wsse:Security>"
    }

    private fun profiles(xml: String): List<Profile> {
        val out = ArrayList<Profile>(); if (xml.isEmpty()) return out
        val p = pullParser(xml); var token: String? = null; var name = ""; var tag = ""; var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                tag = localName(p.name)
                if (tag.equals("Profiles", true)) { token = ""; name = ""; for (i in 0 until p.attributeCount) if (localName(p.getAttributeName(i)) == "token") token = p.getAttributeValue(i) }
            } else if (ev == XmlPullParser.TEXT && token != null && tag == "Name" && name.isEmpty()) name = p.text.trim()
            else if (ev == XmlPullParser.END_TAG && token != null && localName(p.name).equals("Profiles", true)) { out.add(Profile(token, name)); token = null }
            ev = p.next()
        }
        return out
    }

    private fun text(xml: String, wanted: String): String {
        try {
            val p = pullParser(xml); var tag = ""; var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) tag = localName(p.name)
                else if (ev == XmlPullParser.TEXT && tag == wanted) { val v = p.text.trim(); if (v.isNotEmpty()) return v }
                ev = p.next()
            }
        } catch (e: Exception) { }
        return ""
    }

    private fun mediaAddress(xml: String): String {
        try {
            val p = pullParser(xml); var mediaDepth = -1; var depth = 0; var tag = ""; var ev = p.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) { depth++; tag = localName(p.name); if (tag == "Media" || tag == "Media2") mediaDepth = depth }
                else if (ev == XmlPullParser.TEXT && mediaDepth > 0 && tag == "XAddr") { val v = p.text.trim(); if (v.isNotEmpty()) return v }
                else if (ev == XmlPullParser.END_TAG) { if (depth == mediaDepth) mediaDepth = -1; depth-- }
                ev = p.next()
            }
        } catch (e: Exception) { }
        return ""
    }
}

// ------------------------------------------------------------------ UPnP и DLNA

object Upnp {
    fun describe(location: String): UpnpDescriptionParser.Description {
        val c = URL(location).openConnection() as HttpURLConnection
        try { c.connectTimeout = 8000; c.readTimeout = 10000; return c.inputStream.use { UpnpDescriptionParser.parse(it) } } finally { c.disconnect() }
    }
    fun resolve(base: String, value: String): String = try { URL(URL(base), value).toString() } catch (e: Exception) { value }
}

object Dlna {
    class Item(val id: String, val title: String, val url: String, val mime: String, val container: Boolean)

    /** Адрес управления каталогом медиасервера или пустая строка, если устройство не раздаёт медиа. */
    fun controlUrl(location: String): String {
        val d = Upnp.describe(location)
        val service = d.serviceInfos.firstOrNull { it.serviceType.contains("ContentDirectory") } ?: return ""
        return Upnp.resolve(location, service.controlUrl)
    }

    fun browse(controlUrl: String, objectId: String): List<Item> {
        val body = "<u:Browse xmlns:u=\"urn:schemas-upnp-org:service:ContentDirectory:1\"><ObjectID>" + xmlEscape(objectId) +
            "</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter><StartingIndex>0</StartingIndex><RequestedCount>500</RequestedCount><SortCriteria></SortCriteria></u:Browse>"
        val envelope = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>$body</s:Body></s:Envelope>"
        val bytes = envelope.toByteArray(Charsets.UTF_8)
        val c = URL(controlUrl).openConnection() as HttpURLConnection
        val response = try {
            c.connectTimeout = 8000; c.readTimeout = 15000; c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "text/xml; charset=utf-8"); c.setRequestProperty("SOAPACTION", "\"urn:schemas-upnp-org:service:ContentDirectory:1#Browse\"")
            c.setFixedLengthStreamingMode(bytes.size); c.outputStream.use { it.write(bytes) }
            val code = c.responseCode
            if (code !in 200..399) throw Exception("Медиасервер ответил ошибкой $code")
            String(c.inputStream.readBytes(), Charsets.UTF_8)
        } finally { c.disconnect() }
        var didl = ""
        val p = pullParser(response); var tag = ""; var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) { if (ev == XmlPullParser.START_TAG) tag = localName(p.name) else if (ev == XmlPullParser.TEXT && tag == "Result" && p.text.isNotBlank()) { didl = p.text; break }; ev = p.next() }
        return parseDidl(if (didl.trimStart().startsWith("<")) didl else Html.fromHtml(didl, Html.FROM_HTML_MODE_LEGACY).toString())
    }

    private fun parseDidl(xml: String): List<Item> {
        val out = ArrayList<Item>(); if (xml.isBlank()) return out
        val p = pullParser(xml); var tag = ""; var ev = p.eventType
        var open = false; var container = false; var id = ""; var title = ""; var url = ""; var mime = ""
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                tag = localName(p.name)
                if (tag == "container" || tag == "item") { open = true; container = tag == "container"; id = ""; title = ""; url = ""; mime = ""; for (a in 0 until p.attributeCount) if (localName(p.getAttributeName(a)) == "id") id = p.getAttributeValue(a) }
                else if (open && tag == "res" && mime.isEmpty()) for (a in 0 until p.attributeCount) if (localName(p.getAttributeName(a)) == "protocolInfo") mime = p.getAttributeValue(a).split(':').getOrElse(2) { "" }
            } else if (ev == XmlPullParser.TEXT && open) {
                val v = p.text.trim()
                if (v.isNotEmpty()) { if (tag == "title") title = v else if (tag == "res" && url.isEmpty()) url = v }
            } else if (ev == XmlPullParser.END_TAG && open) {
                val n = localName(p.name)
                if (n == "container" || n == "item") { out.add(Item(id, title.ifEmpty { id }, url, mime, container)); open = false }
                tag = ""
            }
            ev = p.next()
        }
        return out
    }
}

// ------------------------------------------------------------------ Текстовая консоль (Telnet и любые TCP-сервисы)

class RawConsole(private val host: String, private val port: Int, private val tls: Boolean, private val listener: Listener) {
    interface Listener { fun onOpen(); fun onText(text: String); fun onClosed(error: String?) }
    private var socket: Socket? = null
    private var out: OutputStream? = null
    @Volatile var running = false; private set
    @Volatile private var closedByUser = false

    fun connect() {
        Thread {
            var error: String? = null
            try {
                val s = if (tls) TlsSockets.connect(host, port) else Socket().apply { socket = this; connect(InetSocketAddress(host, port), 10000) }
                socket = s; s.soTimeout = 0
                val input = BufferedInputStream(s.getInputStream()); out = BufferedOutputStream(s.getOutputStream())
                running = true; listener.onOpen()
                val buffer = ByteArray(4096)
                while (running) {
                    val n = input.read(buffer); if (n < 0) break
                    val text = ByteArrayOutputStream(); var i = 0
                    while (i < n) {
                        val b = buffer[i].toInt() and 255
                        if (b == 255 && i + 2 < n) {           // согласование Telnet: на всё отвечаем отказом
                            val cmd = buffer[i + 1].toInt() and 255; val opt = buffer[i + 2]
                            if (cmd == 253) write(byteArrayOf(255.toByte(), 252.toByte(), opt)) else if (cmd == 251) write(byteArrayOf(255.toByte(), 254.toByte(), opt))
                            i += 3
                        } else { text.write(b); i++ }
                    }
                    if (text.size() > 0) listener.onText(Terminal.clean(String(text.toByteArray(), Charsets.UTF_8)))
                }
            } catch (e: Exception) { if (!closedByUser) error = e.message ?: e.javaClass.simpleName }
            finally { running = false; try { socket?.close() } catch (e: Exception) { }; listener.onClosed(error) }
        }.start()
    }

    @Synchronized private fun write(b: ByteArray) { try { out?.write(b); out?.flush() } catch (e: Exception) { } }
    fun send(line: String) { Thread { write((line + "\r\n").toByteArray(Charsets.UTF_8)) }.start() }
    fun close() { closedByUser = true; running = false; try { socket?.close() } catch (e: Exception) { } }
}

object Terminal {
    private val csi = Regex("\u001B\\[[0-9;?]*[ -/]*[@-~]|\u001B\\][^\u0007\u001B]*(\u0007|\u001B\\\\)|\u001B[()][0-9A-Za-z]|\u001B[=>78MDEHc]")

    /** Убирает управляющие последовательности терминала, оставляя читаемый текст. */
    fun clean(text: String): String {
        val plain = csi.replace(text, "").replace("\r\n", "\n")
        val sb = StringBuilder()
        for (c in plain) when {
            c == '\b' -> if (sb.isNotEmpty() && sb.last() != '\n') sb.setLength(sb.length - 1)
            c == '\r' -> { }
            c == '\u0007' -> { }
            c < ' ' && c != '\n' && c != '\t' -> { }
            else -> sb.append(c)
        }
        return sb.toString()
    }
}
