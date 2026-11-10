package pro.sitescope.app.ip

import java.math.BigInteger
import java.net.InetAddress

/** Калькулятор подсетей IPv4/IPv6: маски, деление, VLSM, суммаризация, ACL. Чистая логика без Android. */
object Subnet {

    data class V4(val address: Long, val prefix: Int) {
        init { require(prefix in 0..32) { "Префикс должен быть от 0 до 32" } }
        val maskLong: Long get() = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val network: Long get() = address and maskLong
        val broadcast: Long get() = network or (maskLong.inv() and 0xFFFFFFFFL)
        val total: Long get() = 1L shl (32 - prefix)
        val usable: Long get() = when (prefix) { 32 -> 1L; 31 -> 2L; else -> total - 2 }
        val firstHost: Long get() = if (prefix >= 31) network else network + 1
        val lastHost: Long get() = if (prefix >= 31) broadcast else broadcast - 1
        val mask: String get() = toIp(maskLong)
        val wildcard: String get() = toIp(maskLong.inv() and 0xFFFFFFFFL)
        val cidr: String get() = toIp(network) + "/" + prefix
        fun contains(ip: Long): Boolean = (ip and maskLong) == network
        fun contains(other: V4): Boolean = other.prefix >= prefix && contains(other.network)
        fun normalized(): V4 = V4(network, prefix)
    }

    data class Allocation(val name: String, val needed: Int, val net: V4?)

    data class V6Info(
        val compressed: String, val expanded: String, val prefix: Int,
        val network: String, val last: String, val count: BigInteger, val kind: String
    )

    // ---------- Разбор и преобразования ----------

    fun ipToLong(text: String): Long {
        val parts = text.trim().split('.')
        require(parts.size == 4) { "Нужен IPv4-адрес вида 192.168.1.1" }
        var out = 0L
        for (p in parts) {
            val n = p.toIntOrNull() ?: throw IllegalArgumentException("Неверный октет: $p")
            require(n in 0..255) { "Октет вне диапазона 0–255: $p" }
            out = (out shl 8) or n.toLong()
        }
        return out
    }

    fun toIp(value: Long): String {
        val v = value and 0xFFFFFFFFL
        return "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"
    }

    fun isIpv4(text: String): Boolean = try { ipToLong(text); true } catch (e: Exception) { false }

    /** Маска 255.255.255.0 → 24. Бросает исключение для «дырявых» масок. */
    fun maskToPrefix(mask: String): Int {
        val m = ipToLong(mask)
        val prefix = java.lang.Long.bitCount(m)
        val expected = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        require(m == expected) { "Маска $mask не непрерывная" }
        return prefix
    }

    fun wildcardToPrefix(wildcard: String): Int =
        maskToPrefix(toIp(ipToLong(wildcard).inv() and 0xFFFFFFFFL))

    fun prefixToMask(prefix: Int): String = V4(0, prefix).mask

    /**
     * Понимает: 10.0.0.5/24 · 10.0.0.5 255.255.255.0 · 10.0.0.5/255.255.255.0 ·
     * 10.0.0.0 0.0.0.255 (wildcard) · 10.0.0.5 (как /32).
     */
    fun parse(input: String): V4 {
        val text = input.trim().replace(Regex("\\s+"), " ")
        require(text.isNotEmpty()) { "Введите адрес сети" }
        val parts = text.split('/', ' ').filter { it.isNotEmpty() }
        val ip = ipToLong(parts[0])
        if (parts.size == 1) return V4(ip, 32)
        val second = parts[1]
        val prefix = if (second.contains('.')) {
            try { maskToPrefix(second) } catch (e: IllegalArgumentException) { wildcardToPrefix(second) }
        } else second.toIntOrNull() ?: throw IllegalArgumentException("Неверный префикс: $second")
        return V4(ip, prefix)
    }

    fun binary(value: Long): String {
        val v = value and 0xFFFFFFFFL
        return (3 downTo 0).joinToString(".") { i ->
            ((v shr (i * 8)) and 255).toString(2).padStart(8, '0')
        }
    }

    fun hex(value: Long): String = "0x" + (value and 0xFFFFFFFFL).toString(16).uppercase().padStart(8, '0')

    /** Понятный тип адреса. */
    fun kind(ip: Long): String {
        fun inNet(cidr: String) = parse(cidr).contains(ip)
        return when {
            inNet("10.0.0.0/8") || inNet("172.16.0.0/12") || inNet("192.168.0.0/16") -> "Частная сеть (RFC 1918)"
            inNet("100.64.0.0/10") -> "Адреса оператора (CGNAT)"
            inNet("127.0.0.0/8") -> "Локальная петля (loopback)"
            inNet("169.254.0.0/16") -> "Автонастройка (link-local)"
            inNet("224.0.0.0/4") -> "Multicast"
            inNet("192.0.2.0/24") || inNet("198.51.100.0/24") || inNet("203.0.113.0/24") -> "Для документации"
            inNet("198.18.0.0/15") -> "Для тестов производительности"
            inNet("240.0.0.0/4") -> "Зарезервировано"
            inNet("0.0.0.0/8") -> "«Эта» сеть"
            else -> "Публичный адрес"
        }
    }

    fun classOf(ip: Long): String = when (((ip shr 24) and 255).toInt()) {
        in 0..127 -> "A"; in 128..191 -> "B"; in 192..223 -> "C"; in 224..239 -> "D"; else -> "E"
    }

    // ---------- Деление ----------

    /** Делит сеть на подсети с новым префиксом. limit защищает от гигантских списков. */
    fun split(net: V4, newPrefix: Int, limit: Int = 1024): List<V4> {
        require(newPrefix in net.prefix..32) { "Новый префикс должен быть от /${net.prefix} до /32" }
        val count = 1L shl (newPrefix - net.prefix)
        val step = 1L shl (32 - newPrefix)
        val out = ArrayList<V4>()
        var i = 0L
        while (i < count && out.size < limit) { out.add(V4(net.network + i * step, newPrefix)); i++ }
        return out
    }

    fun splitCount(net: V4, newPrefix: Int): Long = 1L shl (newPrefix - net.prefix)

    /** Минимальный префикс, при котором сеть делится хотя бы на [parts] частей. */
    fun prefixForParts(net: V4, parts: Int): Int {
        require(parts >= 1) { "Количество частей должно быть положительным" }
        var p = net.prefix
        while ((1L shl (p - net.prefix)) < parts) { p++; require(p <= 32) { "Сеть слишком мала для $parts частей" } }
        return p
    }

    /** Префикс, вмещающий [hosts] узлов (с учётом адреса сети и broadcast). */
    fun prefixForHosts(hosts: Int): Int {
        require(hosts >= 1) { "Нужен хотя бы один узел" }
        if (hosts == 1) return 32
        if (hosts == 2) return 31
        var p = 30
        while (((1L shl (32 - p)) - 2) < hosts) { p--; require(p >= 0) { "Слишком много узлов" } }
        return p
    }

    /** VLSM: раскладывает потребности (имя → число узлов) по сети, начиная с самых больших. */
    fun vlsm(net: V4, needs: List<Pair<String, Int>>): List<Allocation> {
        val sorted = needs.withIndex().sortedByDescending { it.value.second }
        val result = arrayOfNulls<Allocation>(needs.size)
        var cursor = net.network
        val end = net.broadcast
        for ((index, need) in sorted) {
            // VLSM-подсети делаем минимум /30, чтобы были и шлюз, и узел
            val prefix = maxOf(net.prefix, minOf(prefixForHosts(maxOf(need.second, 2)), 30))
            val size = 1L shl (32 - prefix)
            val start = if (cursor % size == 0L) cursor else (cursor / size + 1) * size
            if (prefix < net.prefix || start + size - 1 > end || need.second > V4(start, prefix).usable) {
                result[index] = Allocation(need.first, need.second, null)
            } else {
                result[index] = Allocation(need.first, need.second, V4(start, prefix))
                cursor = start + size
            }
        }
        return result.map { it!! }
    }

    // ---------- Суммаризация ----------

    fun rangeToCidrs(startIn: Long, endIn: Long): List<V4> {
        var start = startIn
        val out = ArrayList<V4>()
        require(start <= endIn) { "Начало диапазона больше конца" }
        while (start <= endIn) {
            var prefix = 32
            while (prefix > 0) {
                val candidate = V4(start, prefix - 1)
                if (candidate.network != start || candidate.broadcast > endIn) break
                prefix--
            }
            out.add(V4(start, prefix))
            start = V4(start, prefix).broadcast + 1
            if (start > 0xFFFFFFFFL) break
        }
        return out
    }

    /** Точная суммаризация: минимальный список CIDR, покрывающий ровно те же адреса. */
    fun summarize(nets: List<V4>): List<V4> {
        if (nets.isEmpty()) return emptyList()
        val ranges = nets.map { it.network to it.broadcast }.sortedBy { it.first }
        val merged = ArrayList<Pair<Long, Long>>()
        for (r in ranges) {
            if (merged.isNotEmpty() && r.first <= merged.last().second + 1) {
                val last = merged.removeAt(merged.size - 1)
                merged.add(last.first to maxOf(last.second, r.second))
            } else merged.add(r)
        }
        return merged.flatMap { rangeToCidrs(it.first, it.second) }
    }

    /** Одна общая надсеть (может включать лишние адреса). */
    fun supernet(nets: List<V4>): V4 {
        require(nets.isNotEmpty()) { "Список сетей пуст" }
        val min = nets.minOf { it.network }
        val max = nets.maxOf { it.broadcast }
        var prefix = 32
        while (prefix > 0 && !(V4(min, prefix).contains(min) && V4(min, prefix).contains(max))) prefix--
        return V4(min, prefix).normalized()
    }

    /** Разбирает список: по строкам/запятым; CIDR, маска или диапазон a-b. */
    fun parseList(text: String): List<V4> {
        val out = ArrayList<V4>()
        for (raw in text.split('\n', ',', ';')) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.contains('-')) {
                val (a, b) = line.split('-', limit = 2).map { it.trim() }
                out.addAll(rangeToCidrs(ipToLong(a), ipToLong(b)))
            } else out.add(parse(line).normalized())
        }
        return out
    }

    // ---------- ACL ----------

    enum class AclFormat(val title: String) {
        PLAIN("Список CIDR"), CISCO("Cisco ACL"), MIKROTIK("MikroTik address-list"),
        IPTABLES("iptables / ipset"), NGINX("nginx"), ROUTES("Маршруты (route add)")
    }

    fun acl(format: AclFormat, nets: List<V4>, name: String = "SITESCOPE", allow: Boolean = true): String {
        val safeName = name.trim().ifEmpty { "SITESCOPE" }.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val sb = StringBuilder()
        when (format) {
            AclFormat.PLAIN -> nets.forEach { sb.append(it.cidr).append('\n') }
            AclFormat.CISCO -> {
                sb.append("ip access-list extended ").append(safeName).append('\n')
                nets.forEach {
                    sb.append(if (allow) " permit ip " else " deny ip ")
                    if (it.prefix == 32) sb.append("host ").append(toIp(it.network)) else sb.append(toIp(it.network)).append(' ').append(it.wildcard)
                    sb.append(" any\n")
                }
            }
            AclFormat.MIKROTIK -> nets.forEach {
                sb.append("/ip firewall address-list add list=").append(safeName).append(" address=").append(it.cidr).append('\n')
            }
            AclFormat.IPTABLES -> {
                sb.append("ipset create ").append(safeName).append(" hash:net -exist\n")
                nets.forEach { sb.append("ipset add ").append(safeName).append(' ').append(it.cidr).append(" -exist\n") }
                sb.append("iptables -A INPUT -m set --match-set ").append(safeName).append(" src -j ")
                    .append(if (allow) "ACCEPT" else "DROP").append('\n')
            }
            AclFormat.NGINX -> {
                nets.forEach { sb.append(if (allow) "allow " else "deny ").append(it.cidr).append(";\n") }
                if (allow) sb.append("deny all;\n")
            }
            AclFormat.ROUTES -> nets.forEach {
                sb.append("route add ").append(toIp(it.network)).append(" mask ").append(it.mask).append(" <шлюз>\n")
            }
        }
        return sb.toString().trimEnd()
    }

    // ---------- IPv6 ----------

    fun isIpv6(text: String): Boolean = text.contains(':') && try {
        InetAddress.getByName(text.substringBefore('/').trim()).address.size == 16
    } catch (e: Exception) { false }

    fun v6(input: String): V6Info {
        val text = input.trim()
        val addrText = text.substringBefore('/').trim()
        require(addrText.contains(':')) { "Нужен IPv6-адрес" }
        require(addrText.all { it.isLetterOrDigit() || it == ':' || it == '.' }) { "Неверный IPv6-адрес" }
        val prefix = if (text.contains('/')) text.substringAfter('/').trim().toIntOrNull()
            ?: throw IllegalArgumentException("Неверный префикс") else 128
        require(prefix in 0..128) { "Префикс должен быть от 0 до 128" }
        val bytes = InetAddress.getByName(addrText).address
        require(bytes.size == 16) { "Нужен IPv6-адрес" }
        val value = BigInteger(1, bytes)
        val full = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)
        val mask = if (prefix == 0) BigInteger.ZERO else full.shiftRight(128 - prefix).shiftLeft(128 - prefix)
        val network = value.and(mask)
        val last = network.or(full.xor(mask))
        val kind = when {
            value == BigInteger.ONE -> "Локальная петля (::1)"
            value.shiftRight(118) == BigInteger.valueOf(0x3FA) -> "Link-local (fe80::/10)"
            value.shiftRight(121) == BigInteger.valueOf(0x7E) -> "Уникальный локальный (fc00::/7)"
            value.shiftRight(120) == BigInteger.valueOf(0xFF) -> "Multicast (ff00::/8)"
            value.shiftRight(96) == BigInteger.valueOf(0x20010DB8L) -> "Для документации (2001:db8::/32)"
            value.shiftRight(125) == BigInteger.ONE -> "Глобальный (2000::/3)"
            else -> "Специальный / зарезервированный"
        }
        return V6Info(compress6(value), expand6(value), prefix, compress6(network), compress6(last),
            BigInteger.ONE.shiftLeft(128 - prefix), kind)
    }

    private fun groups6(value: BigInteger): List<Int> =
        (7 downTo 0).map { value.shiftRight(it * 16).and(BigInteger.valueOf(0xFFFF)).toInt() }

    fun expand6(value: BigInteger): String = groups6(value).joinToString(":") { it.toString(16).padStart(4, '0') }

    fun compress6(value: BigInteger): String {
        val g = groups6(value)
        var bestStart = -1; var bestLen = 0; var i = 0
        while (i < 8) {
            if (g[i] == 0) {
                var j = i
                while (j < 8 && g[j] == 0) j++
                if (j - i > bestLen) { bestStart = i; bestLen = j - i }
                i = j
            } else i++
        }
        if (bestLen < 2) return g.joinToString(":") { it.toString(16) }
        val head = g.subList(0, bestStart).joinToString(":") { it.toString(16) }
        val tail = g.subList(bestStart + bestLen, 8).joinToString(":") { it.toString(16) }
        return "$head::$tail"
    }
}
