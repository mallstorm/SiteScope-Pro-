package pro.sitescope.app.ip

import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Сетевые проверки адреса: обратное имя, чёрные списки, ping, трассировка. */
object IpTools {

    class BlacklistEntry(val zone: String, val title: String, val listed: Boolean?, val code: String)
    class PingResult(val sent: Int, val received: Int, val minMs: Double, val avgMs: Double, val maxMs: Double,
                     val method: String, val error: String) {
        val lossPercent: Int get() = if (sent == 0) 100 else (sent - received) * 100 / sent
        val ok: Boolean get() = received > 0
    }
    class Hop(val ttl: Int, val address: String, val ms: Double, val reached: Boolean)

    private val zones = listOf(
        "zen.spamhaus.org" to "Spamhaus ZEN", "bl.spamcop.net" to "SpamCop",
        "b.barracudacentral.org" to "Barracuda", "dnsbl.dronebl.org" to "DroneBL",
        "psbl.surriel.com" to "PSBL", "all.s5h.net" to "S5H"
    )

    fun reverseDns(ip: String): String = try {
        val name = InetAddress.getByName(ip).canonicalHostName
        if (name == ip) "" else name
    } catch (e: Exception) { "" }

    fun reversedV4(ip: String): String = ip.trim().split('.').reversed().joinToString(".")

    /** Проверка IPv4 по публичным DNS-чёрным спискам. listed=null — список не ответил однозначно. */
    fun blacklists(ip: String, timeoutMs: Long = 6000): List<BlacklistEntry> {
        if (!Subnet.isIpv4(ip)) return emptyList()
        val reversed = reversedV4(ip)
        val pool = Executors.newFixedThreadPool(zones.size)
        try {
            val futures = zones.map { (zone, title) ->
                pool.submit(Callable {
                    try {
                        val answers = InetAddress.getAllByName("$reversed.$zone").map { it.hostAddress ?: "" }
                        val codes = answers.joinToString(", ")
                        // 127.255.255.x у Spamhaus означает «запрос отклонён», а не попадание в список
                        when {
                            answers.any { it.startsWith("127.255.255.") } -> BlacklistEntry(zone, title, null, codes)
                            answers.any { it.startsWith("127.") } -> BlacklistEntry(zone, title, true, codes)
                            else -> BlacklistEntry(zone, title, null, codes)
                        }
                    } catch (e: java.net.UnknownHostException) { BlacklistEntry(zone, title, false, "") }
                    catch (e: Exception) { BlacklistEntry(zone, title, null, "") }
                })
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            return futures.mapIndexed { i, f ->
                try { f.get(maxOf(1L, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS) }
                catch (e: Exception) { BlacklistEntry(zones[i].first, zones[i].second, null, "") }
            }
        } finally { pool.shutdownNow() }
    }

    fun ping(host: String, count: Int = 4, timeoutSec: Int = 2): PingResult {
        val target = host.trim()
        if (!target.matches(Regex("[A-Za-z0-9а-яА-ЯёЁ.:_-]{1,253}"))) return PingResult(0, 0, 0.0, 0.0, 0.0, "", "Неверный адрес")
        try {
            val binary = if (java.io.File("/system/bin/ping").exists()) "/system/bin/ping" else "ping"
            val process = ProcessBuilder(binary, "-c", count.toString(), "-W", timeoutSec.toString(), target).redirectErrorStream(true).start()
            val times = ArrayList<Double>()
            BufferedReader(InputStreamReader(process.inputStream)).useLines { lines ->
                for (line in lines) Regex("time[=<]([0-9.]+)").find(line)?.let { times.add(it.groupValues[1].toDouble()) }
            }
            if (!process.waitFor((count * (timeoutSec + 1) + 3).toLong(), TimeUnit.SECONDS)) process.destroy()
            if (times.isNotEmpty()) return PingResult(count, times.size, times.min(), times.average(), times.max(), "ICMP", "")
        } catch (e: Exception) { /* нет ping — пробуем TCP */ }
        return tcpPing(target, count, timeoutSec * 1000)
    }

    /** Запасной способ, когда ICMP закрыт: время установки TCP-соединения. */
    fun tcpPing(host: String, count: Int, timeoutMs: Int, ports: List<Int> = listOf(443, 80, 22, 53)): PingResult {
        val address = try { InetAddress.getByName(host) } catch (e: Exception) {
            return PingResult(count, 0, 0.0, 0.0, 0.0, "TCP", "Адрес не найден")
        }
        for (port in ports) {
            val times = ArrayList<Double>()
            for (i in 0 until count) {
                val t0 = System.nanoTime()
                try { Socket().use { it.connect(InetSocketAddress(address, port), timeoutMs) }; times.add((System.nanoTime() - t0) / 1e6) }
                catch (e: java.net.ConnectException) {
                    // Отказ в соединении тоже доказывает, что узел жив
                    val ms = (System.nanoTime() - t0) / 1e6
                    if (ms < timeoutMs - 50) times.add(ms) else break
                } catch (e: Exception) { break }
            }
            if (times.isNotEmpty()) return PingResult(count, times.size, times.min(), times.average(), times.max(), "TCP:$port", "")
        }
        return PingResult(count, 0, 0.0, 0.0, 0.0, "TCP", "Узел не отвечает")
    }

    /** Трассировка через ping с растущим TTL (работает без root). */
    fun traceroute(host: String, maxHops: Int = 24, onHop: (Hop) -> Unit, cancelled: () -> Boolean = { false }) {
        val target = host.trim()
        if (!target.matches(Regex("[A-Za-z0-9.:_-]{1,253}"))) return
        val binary = if (java.io.File("/system/bin/ping").exists()) "/system/bin/ping" else "ping"
        var silent = 0
        for (ttl in 1..maxHops) {
            if (cancelled()) return
            var address = ""; var ms = 0.0; var reached = false
            try {
                val t0 = System.nanoTime()
                val process = ProcessBuilder(binary, "-c", "1", "-W", "2", "-t", ttl.toString(), target).redirectErrorStream(true).start()
                val text = process.inputStream.bufferedReader().readText()
                process.waitFor(4, TimeUnit.SECONDS)
                ms = (System.nanoTime() - t0) / 1e6
                val from = Regex("(?i)from ([0-9a-f.:]+)[: ]").find(text)
                if (from != null) {
                    address = from.groupValues[1].trimEnd(':')
                    reached = text.contains("bytes from", true) && !text.contains("exceeded", true)
                    Regex("time[=<]([0-9.]+)").find(text)?.let { ms = it.groupValues[1].toDouble() }
                }
            } catch (e: Exception) { return }
            onHop(Hop(ttl, address, ms, reached))
            if (reached) return
            silent = if (address.isEmpty()) silent + 1 else 0
            if (silent >= 6) return
        }
    }

    private val commonPorts = listOf(21 to "FTP", 22 to "SSH", 25 to "SMTP", 53 to "DNS", 80 to "HTTP", 110 to "POP3",
        143 to "IMAP", 443 to "HTTPS", 465 to "SMTPS", 587 to "SMTP", 993 to "IMAPS", 3306 to "MySQL", 3389 to "RDP", 8080 to "HTTP-alt")

    /** Быстрая проверка популярных сервисов: какие порты отвечают. */
    fun openServices(host: String, timeoutMs: Int = 900): List<Pair<Int, String>> {
        val address = try { InetAddress.getByName(host) } catch (e: Exception) { return emptyList() }
        val pool = Executors.newFixedThreadPool(commonPorts.size)
        try {
            val futures = commonPorts.map { (port, name) ->
                pool.submit(Callable {
                    try { Socket().use { it.connect(InetSocketAddress(address, port), timeoutMs) }; port to name } catch (e: Exception) { null }
                })
            }
            return futures.mapNotNull { try { it.get((timeoutMs + 500).toLong(), TimeUnit.MILLISECONDS) } catch (e: Exception) { null } }
        } finally { pool.shutdownNow() }
    }
}
