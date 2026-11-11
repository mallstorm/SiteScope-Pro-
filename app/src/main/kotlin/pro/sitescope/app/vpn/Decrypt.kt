package pro.sitescope.app.vpn

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Минимальный кодировщик DER — ровно столько, сколько нужно для корневого сертификата. */
internal object Der {
    fun tlv(tag: Int, value: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(); out.write(tag)
        val n = value.size
        when { n < 128 -> out.write(n); n < 256 -> { out.write(0x81); out.write(n) }; else -> { out.write(0x82); out.write(n shr 8); out.write(n and 255) } }
        out.write(value); return out.toByteArray()
    }
    fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })
    fun int(value: BigInteger) = tlv(0x02, value.toByteArray())
    fun oid(text: String): ByteArray {
        val p = text.split('.').map { it.toLong() }; val out = ByteArrayOutputStream(); out.write((p[0] * 40 + p[1]).toInt())
        for (v in p.drop(2)) { val stack = ArrayList<Int>(); var x = v; stack.add((x and 0x7F).toInt()); x = x shr 7; while (x > 0) { stack.add(((x and 0x7F) or 0x80).toInt()); x = x shr 7 }; for (b in stack.reversed()) out.write(b) }
        return tlv(0x06, out.toByteArray())
    }
    fun utf8(text: String) = tlv(0x0C, text.toByteArray(Charsets.UTF_8))
    fun bits(value: ByteArray, unused: Int = 0) = tlv(0x03, byteArrayOf(unused.toByte()) + value)
    fun octets(value: ByteArray) = tlv(0x04, value)
    fun bool(value: Boolean) = tlv(0x01, byteArrayOf(if (value) 0xFF.toByte() else 0))
    fun utcTime(date: Date) = tlv(0x17, SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(date).toByteArray(Charsets.US_ASCII))
    fun explicit(number: Int, value: ByteArray) = tlv(0xA0 or number, value)
}

/** Собственный корневой сертификат приложения: им подписываются сертификаты сайтов при расшифровке TLS. */
object CertAuthority {
    class Material(val certificatePem: String, val keyPem: String, val certificateDer: ByteArray)

    fun generate(commonName: String, now: Date = Date()): Material {
        val generator = KeyPairGenerator.getInstance("EC"); generator.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
        val pair: KeyPair = generator.generateKeyPair()
        val algorithm = Der.seq(Der.oid("1.2.840.10045.4.3.2"))                         // ecdsa-with-SHA256
        val name = Der.seq(Der.set(Der.seq(Der.oid("2.5.4.10"), Der.utf8("SiteScope Pro"))), Der.set(Der.seq(Der.oid("2.5.4.3"), Der.utf8(commonName))))
        val spki = pair.public.encoded
        val keyBits = spki.copyOfRange(spki.size - 65, spki.size)                       // несжатая точка P-256
        val keyId = MessageDigest.getInstance("SHA-1").digest(keyBits)
        val extensions = Der.seq(
            Der.seq(Der.oid("2.5.29.19"), Der.bool(true), Der.octets(Der.seq(Der.bool(true)))),                // basicConstraints: CA
            Der.seq(Der.oid("2.5.29.15"), Der.bool(true), Der.octets(Der.bits(byteArrayOf(0x06), 1))),          // keyUsage: keyCertSign, cRLSign
            Der.seq(Der.oid("2.5.29.14"), Der.octets(Der.octets(keyId))))                                       // subjectKeyIdentifier
        val serial = BigInteger(1, ByteArray(15).also { SecureRandom().nextBytes(it) }).add(BigInteger.ONE)
        val tbs = Der.seq(Der.explicit(0, Der.int(BigInteger.valueOf(2))), Der.int(serial), algorithm, name,
            Der.seq(Der.utcTime(Date(now.time - 86_400_000L)), Der.utcTime(Date(now.time + 3650L * 86_400_000L))), name, spki, Der.explicit(3, extensions))
        val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(tbs) }.sign()
        val certificate = Der.seq(tbs, algorithm, Der.bits(signature))
        return Material(pem("CERTIFICATE", certificate), pem("PRIVATE KEY", pair.private.encoded), certificate)
    }

    fun pem(label: String, der: ByteArray): String = "-----BEGIN $label-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der) + "\n-----END $label-----\n"
    fun derOf(pem: String): ByteArray = Base64.getMimeDecoder().decode(pem.lineSequence().filter { !it.startsWith("-----") }.joinToString(""))
}

/**
 * Файл дампа в формате pcapng. В отличие от pcap, в него можно вложить ключи сеансов TLS,
 * и тогда Wireshark покажет расшифрованное содержимое без дополнительных файлов.
 */
object PcapNg {
    private fun le32(o: OutputStream, v: Long) { for (i in 0 until 4) o.write(((v shr (8 * i)) and 255).toInt()) }
    private fun le16(o: OutputStream, v: Int) { o.write(v and 255); o.write((v shr 8) and 255) }
    private fun pad(n: Int) = (4 - n % 4) % 4

    private fun block(out: OutputStream, type: Long, body: ByteArray) { val total = 12L + body.size; le32(out, type); le32(out, total); out.write(body); le32(out, total) }

    fun header(out: OutputStream) {
        val shb = ByteArrayOutputStream(); le32(shb, 0x1A2B3C4DL); le16(shb, 1); le16(shb, 0); for (i in 0 until 8) shb.write(0xFF)
        block(out, 0x0A0D0D0AL, shb.toByteArray())
        val idb = ByteArrayOutputStream(); le16(idb, 101); le16(idb, 0); le32(idb, 0)       // тип канала 101: «сырые» IP-пакеты
        block(out, 1, idb.toByteArray())
    }

    /** Блок с ключами TLS в формате журнала ключей (NSS key log). */
    fun secrets(out: OutputStream, keyLog: ByteArray) {
        if (keyLog.isEmpty()) return
        val b = ByteArrayOutputStream(); le32(b, 0x544C534BL); le32(b, keyLog.size.toLong()); b.write(keyLog); repeat(pad(keyLog.size)) { b.write(0) }
        block(out, 0x0A, b.toByteArray())
    }

    fun packet(out: OutputStream, data: ByteArray, length: Int, micros: Long) {
        val b = ByteArrayOutputStream(length + 24); le32(b, 0); le32(b, micros ushr 32); le32(b, micros and 0xFFFFFFFFL); le32(b, length.toLong()); le32(b, length.toLong())
        b.write(data, 0, length); repeat(pad(length)) { b.write(0) }
        block(out, 6, b.toByteArray())
    }

    /** Превращает обычный pcap в pcapng, поставив ключи перед пакетами. Возвращает число пакетов. */
    fun convert(pcap: InputStream, keyLog: ByteArray, out: OutputStream): Int {
        val input = DataInputStream(pcap.buffered())
        val head = ByteArray(24); input.readFully(head)
        header(out); secrets(out, keyLog)
        var count = 0; val record = ByteArray(16)
        while (true) {
            try { input.readFully(record) } catch (e: EOFException) { break }
            fun u32(o: Int) = (record[o].toLong() and 255) or ((record[o + 1].toLong() and 255) shl 8) or ((record[o + 2].toLong() and 255) shl 16) or ((record[o + 3].toLong() and 255) shl 24)
            val length = u32(8).toInt(); if (length < 0 || length > 262144) break
            val data = ByteArray(length); try { input.readFully(data) } catch (e: EOFException) { break }
            packet(out, data, length, u32(0) * 1_000_000L + u32(4)); count++
        }
        out.flush(); return count
    }

    /** Ключи, появившиеся в журнале после отметки [offset]. */
    fun keysSince(file: File?, offset: Long): ByteArray = try {
        if (file == null || !file.isFile || file.length() <= offset) ByteArray(0) else file.inputStream().use { it.skip(offset); it.readBytes() }
    } catch (e: Exception) { ByteArray(0) }
}
