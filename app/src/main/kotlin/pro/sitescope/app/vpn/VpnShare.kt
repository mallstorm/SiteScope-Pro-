package pro.sitescope.app.vpn

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONArray
import org.json.JSONObject
import pro.sitescope.app.HostsVpnService
import pro.sitescope.app.XrayConfigBuilder
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * Раздача VPN без root: внутри Xray поднимаются два входа (SOCKS5 и HTTP) с паролями, по одному
 * логину на устройство. Трафик этих входов идёт тем же маршрутом, что и трафик самого телефона,
 * то есть через выбранный сервер (Австрия и т. д.). Устройства подключаются к адресу телефона в
 * точке доступа или в общей Wi-Fi сети.
 */
object VpnShare {
    private const val PREFS = "vpn_share"
    const val SOCKS_TAG = "sitescope-share-socks"
    const val HTTP_TAG = "sitescope-share-http"
    const val MAX_LIMIT = 64

    class Device(val id: String, val name: String, val kind: String, val user: String, val pass: String, val createdAt: Long) {
        val laptop get() = kind == "laptop"
    }
    class Address(val iface: String, val ip: String, val hotspot: Boolean)

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context) = p(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) { p(c).edit().putBoolean("enabled", v).apply(); reload(c) }
    fun limit(c: Context) = p(c).getInt("limit", 10).coerceIn(1, MAX_LIMIT)
    fun setLimit(c: Context, v: Int) { p(c).edit().putInt("limit", v.coerceIn(1, MAX_LIMIT)).apply() }
    fun socksPort(c: Context) = p(c).getInt("socksPort", 10809)
    fun httpPort(c: Context) = p(c).getInt("httpPort", 10810)

    fun devices(c: Context): List<Device> {
        val arr = try { JSONArray(p(c).getString("devices", "[]")) } catch (e: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            Device(o.optString("id"), o.optString("name"), o.optString("kind", "mobile"), o.optString("user"), o.optString("pass"), o.optLong("createdAt"))
        }
    }

    private fun save(c: Context, list: List<Device>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("kind", it.kind).put("user", it.user).put("pass", it.pass).put("createdAt", it.createdAt)) }
        p(c).edit().putString("devices", arr.toString()).apply()
    }

    private fun random(n: Int, alphabet: String): String { val r = SecureRandom(); return buildString { repeat(n) { append(alphabet[r.nextInt(alphabet.length)]) } } }

    /** Новое устройство с отдельным логином и паролем. Возвращает null, если достигнут лимит. */
    fun addDevice(c: Context, name: String, kind: String): Device? {
        val list = devices(c)
        if (list.size >= limit(c)) return null
        val a = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val d = Device(random(8, a), name.ifBlank { if (kind == "laptop") "Ноутбук ${list.size + 1}" else "Телефон ${list.size + 1}" }, kind,
            "d" + random(6, a), random(16, a), System.currentTimeMillis())
        save(c, list + d); reload(c)
        return d
    }

    fun removeDevice(c: Context, id: String) { save(c, devices(c).filter { it.id != id }); reload(c) }

    /** Адреса телефона в локальных сетях; точка доступа идёт первой. */
    fun addresses(): List<Address> {
        val out = ArrayList<Address>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback || nif.isVirtual) continue
                val name = nif.name.lowercase()
                if (name.startsWith("tun") || name.startsWith("rmnet") || name.startsWith("ccmni") || name.startsWith("dummy")) continue
                for (ia in nif.inetAddresses) {
                    if (ia !is Inet4Address || !ia.isSiteLocalAddress) continue
                    val ip = ia.hostAddress ?: continue
                    val hot = name.startsWith("ap") || name.startsWith("swlan") || name.startsWith("rndis") || name.startsWith("wlan1") || ip.startsWith("192.168.43.") || ip.startsWith("192.168.49.")
                    out += Address(nif.name, ip, hot)
                }
            }
        } catch (e: Exception) { }
        return out.sortedByDescending { it.hotspot }
    }

    /** Добавляет входы в конфиг Xray и направляет их по тому же маршруту, что и сам телефон. */
    @JvmStatic fun apply(context: Context, json: String): String {
        if (!enabled(context)) return json
        val list = devices(context)
        if (list.isEmpty()) return json
        return try {
            val root = JSONObject(json)
            val inbounds = root.getJSONArray("inbounds")
            val accounts = JSONArray(); list.forEach { accounts.put(JSONObject().put("user", it.user).put("pass", it.pass)) }
            val sniff = JSONObject().put("enabled", false)
            val lan = addresses().firstOrNull()?.ip
            val socksSettings = JSONObject().put("auth", "password").put("accounts", accounts).put("udp", true)
            if (lan != null) socksSettings.put("ip", lan)
            inbounds.put(JSONObject().put("tag", SOCKS_TAG).put("listen", "0.0.0.0").put("port", socksPort(context)).put("protocol", "socks").put("settings", socksSettings).put("sniffing", sniff))
            inbounds.put(JSONObject().put("tag", HTTP_TAG).put("listen", "0.0.0.0").put("port", httpPort(context)).put("protocol", "http")
                .put("settings", JSONObject().put("accounts", accounts).put("allowTransparent", false)).put("sniffing", sniff))
            val rules = root.getJSONObject("routing").getJSONArray("rules")
            var done = false
            for (i in 0 until rules.length()) {
                val r = rules.optJSONObject(i) ?: continue
                val tags = r.optJSONArray("inboundTag") ?: continue
                if (tags.length() == 1 && tags.optString(0) == XrayConfigBuilder.SOCKS_TAG && !r.has("port") && !r.has("domain") && !r.has("ip") && r.optString("outboundTag") != XrayConfigBuilder.DNS_TAG) {
                    tags.put(SOCKS_TAG).put(HTTP_TAG); done = true; break
                }
            }
            if (!done) {
                val main = rules.optJSONObject(rules.length() - 1)?.optString("outboundTag").orEmpty().ifEmpty { XrayConfigBuilder.PROXY_TAG }
                rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put(SOCKS_TAG).put(HTTP_TAG)).put("outboundTag", main))
            }
            root.toString()
        } catch (e: Exception) { json }
    }

    /** Применяет изменения на лету, если VPN уже работает (TUN не трогаем). */
    fun reload(c: Context) {
        try {
            val s = c.getSharedPreferences(HostsVpnService.PREFS, Context.MODE_PRIVATE)
            if (s.getString(HostsVpnService.KEY_STATE, "") == HostsVpnService.STATE_ACTIVE && s.getString("mode", "") == HostsVpnService.MODE_XRAY)
                c.startService(Intent(c, HostsVpnService::class.java).setAction(HostsVpnService.ACTION_RELOAD))
        } catch (e: Exception) { }
    }

    // ---------- ссылки ----------
    private fun b64(s: String) = Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP or Base64.NO_PADDING or Base64.URL_SAFE)
    private fun tag(d: Device) = java.net.URLEncoder.encode(d.name, "UTF-8").replace("+", "%20")

    /** Формат, который понимают v2rayNG, v2rayN, NekoBox, Hiddify. */
    fun socksLink(c: Context, d: Device, host: String) = "socks://" + b64(d.user + ":" + d.pass) + "@" + host + ":" + socksPort(c) + "#" + tag(d)
    fun socks5Url(c: Context, d: Device, host: String) = "socks5://${d.user}:${d.pass}@$host:${socksPort(c)}"
    fun httpUrl(c: Context, d: Device, host: String) = "http://${d.user}:${d.pass}@$host:${httpPort(c)}"

    fun windowsGuide(c: Context, d: Device, host: String): String = buildString {
        appendLine("Подключение Windows 10 к VPN телефона")
        appendLine("Условие: ноутбук в Wi-Fi точке доступа телефона или в одной сети с ним, на телефоне включена раздача.")
        appendLine()
        appendLine("Способ 1 (просто) — системный прокси:")
        appendLine("Параметры → Сеть и Интернет → Прокси → «Использовать прокси-сервер»")
        appendLine("Адрес: $host   Порт: ${httpPort(c)}")
        appendLine("Логин: ${d.user}   Пароль: ${d.pass}  (Chrome/Edge спросят при первом открытии сайта)")
        appendLine()
        appendLine("Способ 2 — весь трафик (v2rayN / Hiddify / Nekoray):")
        appendLine("Импорт из буфера, ссылка: " + socksLink(c, d, host))
        appendLine("Включите режим TUN, и будут работать все программы, включая игры и мессенджеры.")
        appendLine()
        appendLine("Проверка в PowerShell:")
        appendLine("curl.exe -x socks5h://${d.user}:${d.pass}@$host:${socksPort(c)} https://ipinfo.io/country")
    }

    fun qr(text: String, size: Int = 640): Bitmap {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1))
        val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
    }
}
