package pro.sitescope.app.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import pro.sitescope.app.VpnDiagnostics
import pro.sitescope.app.XrayConfigBuilder
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.security.cert.X509Certificate

/**
 * Расшифровка TLS. Ядро Xray само принимает защищённое соединение приложения, предъявляя сертификат,
 * подписанный корневым сертификатом SiteScope, и заново шифрует его до настоящего сайта.
 * Ключи сеансов ядро пишет в журнал — они попадают в файл дампа.
 *
 * Схема проверена на Xray 26.6.27: вход SOCKS → правило для порта 443 → локальный вход с TLS
 * (сертификат «issue», только HTTP/1.1) → выход с TLS «fromMitm» → локальный ретранслятор → основной выход.
 */
object Mitm {
    const val IN_TAG = "sitescope-mitm-in"
    const val RELAY_TAG = "sitescope-mitm-relay"
    const val TO_TAG = "sitescope-to-mitm"
    const val OUT_TAG = "sitescope-mitm-out"
    const val BLOCK_TAG = "sitescope-mitm-block"

    fun dir(context: Context) = File(context.filesDir, "mitm").apply { if (!exists()) mkdirs() }
    @JvmStatic fun keyLogFile(context: Context) = File(dir(context), "keys.log")
    fun certificateFile(context: Context) = File(dir(context), "ca.crt")
    private fun keyFile(context: Context) = File(dir(context), "ca.key")

    /** Создаёт корневой сертификат при первом обращении. Закрытый ключ не покидает память приложения. */
    @Synchronized fun ensureCa(context: Context): File {
        val cert = certificateFile(context); val key = keyFile(context)
        if (!cert.isFile || !key.isFile || cert.length() < 100) {
            val material = CertAuthority.generate("SiteScope Pro CA " + android.os.Build.MODEL)
            cert.writeText(material.certificatePem); key.writeText(material.keyPem)
        }
        return cert
    }

    /** Установлен ли наш сертификат в системе как доверенный пользовательский. */
    fun caInstalled(context: Context): Boolean = try {
        val file = certificateFile(context)
        if (!file.isFile) false else {
            val mine = CertAuthority.derOf(file.readText())
            val store = KeyStore.getInstance("AndroidCAStore"); store.load(null)
            var found = false
            val aliases = store.aliases()
            while (aliases.hasMoreElements() && !found) {
                val alias = aliases.nextElement()
                if (!alias.startsWith("user:")) continue
                val c = store.getCertificate(alias) as? X509Certificate ?: continue
                if (c.encoded.contentEquals(mine)) found = true
            }
            found
        }
    } catch (e: Exception) { false }

    private fun freePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    /** Дописывает в конфигурацию ядра расшифровку TLS и сохранение правил «своих адресов». */
    @JvmStatic fun apply(context: Context, json: String): String {
        var result = json
        try { result = keepHostRules(result) } catch (e: Exception) { VpnDiagnostics.log(context, "HOSTS_SNIFF_SKIPPED", e.message ?: "") }
        if (!VpnSettings.mitm(context)) return result
        return try {
            val ca = ensureCa(context)
            val root = JSONObject(result)
            val routing = root.getJSONObject("routing"); val old = routing.getJSONArray("rules")
            var main = XrayConfigBuilder.PROXY_TAG
            for (i in 0 until old.length()) {
                val rule = old.optJSONObject(i) ?: continue
                val inbound = rule.optJSONArray("inboundTag")
                if (inbound != null && inbound.length() == 1 && inbound.optString(0) == XrayConfigBuilder.SOCKS_TAG && !rule.has("port") && !rule.has("network")) { main = rule.optString("outboundTag", main); break }
            }
            val mitmPort = freePort(); val relayPort = freePort()
            val inbounds = root.getJSONArray("inbounds")
            inbounds.put(JSONObject().put("tag", IN_TAG).put("listen", "127.0.0.1").put("port", mitmPort).put("protocol", "dokodemo-door")
                .put("settings", JSONObject().put("network", "tcp").put("port", 443).put("followRedirect", true))
                .put("streamSettings", JSONObject().put("security", "tls").put("tlsSettings", JSONObject().put("alpn", JSONArray().put("http/1.1"))
                    .put("masterKeyLog", keyLogFile(context).absolutePath)
                    .put("certificates", JSONArray().put(JSONObject().put("usage", "issue").put("certificateFile", ca.absolutePath).put("keyFile", keyFile(context).absolutePath))))))
            inbounds.put(JSONObject().put("tag", RELAY_TAG).put("listen", "127.0.0.1").put("port", relayPort).put("protocol", "dokodemo-door")
                .put("settings", JSONObject().put("address", "127.0.0.1").put("port", 443).put("network", "tcp"))
                .put("sniffing", JSONObject().put("enabled", true).put("destOverride", JSONArray().put("tls")).put("routeOnly", false)))
            val outbounds = root.getJSONArray("outbounds")
            outbounds.put(JSONObject().put("tag", TO_TAG).put("protocol", "freedom").put("settings", JSONObject().put("redirect", "127.0.0.1:$mitmPort")))
            outbounds.put(JSONObject().put("tag", OUT_TAG).put("protocol", "freedom").put("settings", JSONObject().put("redirect", "127.0.0.1:$relayPort"))
                .put("streamSettings", JSONObject().put("security", "tls").put("tlsSettings", JSONObject().put("serverName", "fromMitm").put("alpn", JSONArray().put("fromMitm")))))
            outbounds.put(JSONObject().put("tag", BLOCK_TAG).put("protocol", "blackhole"))

            val socks = JSONArray().put(XrayConfigBuilder.SOCKS_TAG)
            val rules = JSONArray()
            rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put(IN_TAG)).put("outboundTag", OUT_TAG))
            rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put(RELAY_TAG)).put("outboundTag", main))
            if (VpnSettings.blockQuic(context)) {
                // Без этого приложения уходят на QUIC (UDP) и закрытый DNS — их расшифровать нельзя.
                rules.put(JSONObject().put("type", "field").put("inboundTag", socks).put("network", "udp").put("port", "443").put("outboundTag", BLOCK_TAG))
                rules.put(JSONObject().put("type", "field").put("inboundTag", socks).put("network", "tcp").put("port", "853").put("outboundTag", BLOCK_TAG))
            }
            // Сайты со «своим адресом» идут мимо расшифровки, иначе подмена адреса потерялась бы.
            val own = ownAddresses(root)
            if (own.length() > 0) rules.put(JSONObject().put("type", "field").put("inboundTag", socks).put("ip", own).put("outboundTag", main))
            rules.put(JSONObject().put("type", "field").put("inboundTag", socks).put("network", "tcp").put("port", "443").put("outboundTag", TO_TAG))
            for (i in 0 until old.length()) rules.put(old.get(i))
            routing.put("rules", rules)
            VpnDiagnostics.log(context, "MITM_ON", "tls interception configured, ports=$mitmPort/$relayPort, quicBlocked=" + VpnSettings.blockQuic(context))
            root.toString()
        } catch (e: Exception) {
            VpnDiagnostics.log(context, "MITM_SKIPPED", e.message ?: e.javaClass.simpleName)
            result
        }
    }

    private fun ownAddresses(root: JSONObject): JSONArray {
        val out = JSONArray(); val hosts = root.optJSONObject("dns")?.optJSONObject("hosts") ?: return out
        val seen = HashSet<String>()
        for (name in hosts.keys()) {
            val value = hosts.opt(name)
            val list = if (value is JSONArray) (0 until value.length()).map { value.optString(it) } else listOf(value?.toString() ?: "")
            for (ip in list) if (ip.isNotEmpty() && (ip[0].isDigit() || ip.contains(':')) && seen.add(ip)) out.put(ip)
        }
        return out
    }

    /**
     * Для имён со «своим адресом» ядро не должно заменять адрес назначения на имя сайта:
     * иначе сервер VPN сам найдёт настоящий адрес, и правило не сработает.
     */
    private fun keepHostRules(json: String): String {
        val root = JSONObject(json)
        val hosts = root.optJSONObject("dns")?.optJSONObject("hosts") ?: return json
        val names = JSONArray(); for (name in hosts.keys()) names.put(name)
        if (names.length() == 0) return json
        val inbounds = root.optJSONArray("inbounds") ?: return json
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            if (inbound.optString("tag") == XrayConfigBuilder.SOCKS_TAG) inbound.optJSONObject("sniffing")?.put("domainsExcluded", names)
        }
        return root.toString()
    }
}
