package pro.sitescope.app.dpi

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection

/**
 * Разрешение имён мимо DNS провайдера (DoH по IP-адресу, имя сервера не нужно). Нужно подбору: если провайдер подменяет DNS,
 * ни одна стратегия не поможет, и тест покажет «не открылось» у всех — хотя дело не в DPI.
 */
object DpiDns {
    private val cache = ConcurrentHashMap<String, String>()
    private val endpoints = listOf("https://1.1.1.1/dns-query?name=%s&type=A", "https://8.8.8.8/resolve?name=%s&type=A", "https://9.9.9.9:5053/dns-query?name=%s&type=A")

    fun clear() = cache.clear()

    /** IPv4 через DoH или null (DoH недоступен / имя не найдено). */
    fun doh(host: String, timeoutMs: Int = 2500): String? {
        cache[host]?.let { return it }
        for (e in endpoints) {
            try {
                val c = URL(e.format(host)).openConnection() as HttpsURLConnection
                c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
                c.setRequestProperty("Accept", "application/dns-json")
                try {
                    if (c.responseCode != 200) continue
                    val answers = JSONObject(c.inputStream.bufferedReader().readText()).optJSONArray("Answer") ?: continue
                    for (i in 0 until answers.length()) {
                        val a = answers.getJSONObject(i)
                        if (a.optInt("type") == 1) { val ip = a.optString("data"); if (ip.isNotBlank()) { cache[host] = ip; return ip } }
                    }
                } finally { (c as HttpURLConnection).disconnect() }
            } catch (_: Exception) { }
        }
        return null
    }

    /** Ответ системного DNS (IPv4) с ограничением по времени. */
    fun system(host: String, timeoutMs: Long = 3000): String? {
        val ex = Executors.newSingleThreadExecutor()
        return try {
            ex.submit<String?> { InetAddress.getAllByName(host).firstOrNull { it.address.size == 4 }?.hostAddress }.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: Exception) { null } finally { ex.shutdownNow() }
    }

    /** Адрес из «чёрной дыры»: приватный, loopback, 0.0.0.0 — типичная заглушка провайдера. */
    fun bogus(ip: String?): Boolean {
        if (ip == null) return false
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 0 || p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || p[0] >= 224
    }
}
