package pro.sitescope.app.keys

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

/** 1.14.x sing-box configuration; the only inbound is local SOCKS, never TUN. */
object SingBoxConfig {
    val types = setOf("hysteria2", "tuic", "wireguard", "openvpn", "anytls")
    data class Imported(val type: String, val host: String, val port: Int, val link: String)

    fun importProfile(text: String): Imported {
        val raw = text.trim()
        require(raw.length in 20..262144) { "Неверный размер конфигурации" }
        val type = if (raw.contains("[Interface]", true) && raw.contains("[Peer]", true)) "wireguard" else "openvpn"
        val target = if (type == "wireguard") {
            val peer = sections(raw)["peer"]?.firstOrNull()
                ?: throw IllegalArgumentException("Нет секции [Peer]")
            hostPort(peer["endpoint"].orEmpty(), 51820)
        } else {
            val remote = Regex("(?im)^\\s*remote\\s+(\\S+)(?:\\s+(\\d+))?").find(raw)
                ?: throw IllegalArgumentException("Нет директивы remote")
            remote.groupValues[1] to (remote.groupValues[2].toIntOrNull() ?: 1194)
        }
        val encoded = Base64.encodeToString(raw.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return Imported(type, target.first, target.second, type + "://profile/" + encoded)
    }

    fun build(link: String, port: Int): String {
        require(port in 1..65535)
        val scheme = link.substringBefore("://").lowercase()
        val type = when (scheme) { "hy2" -> "hysteria2"; "wg" -> "wireguard"; else -> scheme }
        require(type in types) { "Неизвестный sing-box протокол: $type" }
        val candidate = when (type) {
            "wireguard" -> wireguard(decodeProfile(link, "wireguard"))
            "openvpn" -> openvpn(decodeProfile(link, "openvpn"))
            else -> share(type, link)
        }
        val config = JSONObject().put("log", JSONObject().put("level", "error"))
            .put("inbounds", JSONArray().put(JSONObject()
                .put("type", "socks").put("listen", "127.0.0.1")
                .put("listen_port", port).put("tag", "test-in")))
        if (type == "wireguard" || type == "openvpn")
            config.put("endpoints", JSONArray().put(candidate))
        else config.put("outbounds", JSONArray().put(candidate))
        config.put("route", JSONObject().put("final", "test-out").put("auto_detect_interface", true))
        return config.toString()
    }

    private fun decodeProfile(link: String, scheme: String): String {
        if (!link.startsWith(scheme + "://profile/")) throw UnsupportedOperationException("Для $scheme требуется полный файл конфигурации")
        val encoded = link.substringAfter("://profile/").substringBefore('#')
        require(encoded.length < 350000)
        return String(Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP))
    }

    private fun share(type: String, link: String): JSONObject {
        val uri = URI(link.substringBefore('#'))
        val host = uri.host?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Не задан сервер")
        require(uri.port in 1..65535) { "Неверный порт" }
        val opts = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            decode(it.substringBefore('=')).lowercase() to decode(it.substringAfter('=', ""))
        }
        val user = decode(uri.rawUserInfo.orEmpty())
        require(user.isNotBlank()) { "Отсутствуют учётные данные" }
        val tls = JSONObject().put("enabled", true)
            .put("server_name", opts["sni"] ?: opts["peer"] ?: host)
        if (opts["insecure"] in setOf("1", "true")) tls.put("insecure", true)
        opts["alpn"]?.takeIf { it.isNotBlank() }?.let {
            tls.put("alpn", JSONArray(it.split(',').map { entry -> entry.trim() }))
        }
        val out = JSONObject().put("tag", "test-out").put("type", type)
            .put("server", host).put("server_port", uri.port).put("tls", tls)
        when (type) {
            "hysteria2" -> {
                out.put("password", user)
                opts["obfs"]?.takeIf { it.isNotBlank() }?.let { obfs ->
                    require(obfs in setOf("salamander", "gecko"))
                    out.put("obfs", JSONObject().put("type", obfs)
                        .put("password", opts["obfs-password"] ?: ""))
                }
                opts["mport"]?.takeIf { it.isNotBlank() }?.let { out.put("server_ports", JSONArray().put(it)) }
                opts["upmbps"]?.toIntOrNull()?.takeIf { it > 0 }?.let { out.put("up_mbps", it) }
                opts["downmbps"]?.toIntOrNull()?.takeIf { it > 0 }?.let { out.put("down_mbps", it) }
            }
            "tuic" -> {
                // Both share-link dialects are common: uuid:password@host
                // and uuid@host?password=...; don't discard either.
                val uuid = user.substringBefore(':')
                val password = opts["password"].orEmpty().ifBlank {
                    if (':' in user) user.substringAfter(':') else ""
                }
                require(uuid.isNotBlank() && password.isNotBlank()) { "В TUIC отсутствует UUID или пароль" }
                out.put("uuid", uuid).put("password", password)
                opts["congestion_control"]?.let { out.put("congestion_control", it) }
                opts["udp_relay_mode"]?.let { out.put("udp_relay_mode", it) }
            }
            "anytls" -> out.put("password", user)
        }
        return out
    }

    private fun wireguard(raw: String): JSONObject {
        val ini = sections(raw)
        val iface = ini["interface"]?.firstOrNull() ?: throw IllegalArgumentException("Нет Interface")
        val key = iface["privatekey"].orEmpty()
        val address = csv(iface["address"].orEmpty())
        require(key.isNotBlank() && address.isNotEmpty()) { "Нет WireGuard PrivateKey/Address" }
        val peers = JSONArray()
        for (peer in ini["peer"].orEmpty()) {
            val (host, port) = hostPort(peer["endpoint"].orEmpty(), 51820)
            val publicKey = peer["publickey"].orEmpty()
            val allowed = csv(peer["allowedips"].orEmpty())
            require(publicKey.isNotBlank() && allowed.isNotEmpty()) { "Неполный WireGuard Peer" }
            val p = JSONObject().put("address", host).put("port", port)
                .put("public_key", publicKey).put("allowed_ips", JSONArray(allowed))
            peer["presharedkey"]?.let { p.put("pre_shared_key", it) }
            peer["persistentkeepalive"]?.toIntOrNull()?.let { p.put("persistent_keepalive_interval", it) }
            peers.put(p)
        }
        require(peers.length() > 0) { "Нет WireGuard Peer" }
        val out = JSONObject().put("type", "wireguard").put("tag", "test-out")
            .put("system", false).put("private_key", key)
            .put("address", JSONArray(address)).put("peers", peers)
        iface["mtu"]?.toIntOrNull()?.let { out.put("mtu", it) }
        return out
    }

    private fun openvpn(raw: String): JSONObject {
        val remote = Regex("(?im)^\\s*remote\\s+(\\S+)(?:\\s+(\\d+))?").find(raw)
            ?: throw IllegalArgumentException("Нет OpenVPN remote")
        val ca = block(raw, "ca")
        require(ca.isNotBlank()) { "Нужен встроенный <ca> в .ovpn" }
        val tls = JSONObject().put("certificate", JSONArray().put(ca))
        val cert = block(raw, "cert"); val key = block(raw, "key")
        if (cert.isNotBlank() && key.isNotBlank()) {
            tls.put("client_certificate", JSONArray().put(cert))
            tls.put("client_key", JSONArray().put(key))
        }
        val controls = listOf("tls-auth" to "tls_auth", "tls-crypt" to "tls_crypt", "tls-crypt-v2" to "tls_crypt_v2")
        for ((tag, type) in controls) {
            val data = block(raw, tag)
            if (data.isNotBlank()) {
                val wrap = JSONObject().put("type", type).put("key", JSONArray().put(data))
                if (type == "tls_auth") wrap.put("direction", "client")
                tls.put("control_wrap", wrap)
                break
            }
        }
        val lines = raw.lineSequence().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") }.toList()
        require(lines.none { it.startsWith("auth-user-pass") }) { "Авторизация OpenVPN по логину ещё требует отдельного интерфейса" }
        require(lines.none { it.startsWith("ca ") || it.startsWith("cert ") || it.startsWith("key ") }) {
            "Внешние файлы сертификатов: нужен единый inline .ovpn"
        }
        val proto = lines.firstOrNull { it.startsWith("proto ") }?.substringAfter("proto ") ?: "udp"
        val port = remote.groupValues[2].toIntOrNull() ?: 1194
        val out = JSONObject().put("type", "openvpn-client").put("tag", "test-out")
            .put("mode", "tls").put("system", false)
            .put("server", remote.groupValues[1]).put("server_port", port)
            .put("network", if (proto.startsWith("tcp")) "tcp" else "udp")
            .put("tls", tls)
        lines.firstOrNull { it.startsWith("cipher ") }?.substringAfter("cipher ")?.let { out.put("cipher", it) }
        lines.firstOrNull { it.startsWith("data-ciphers ") }?.substringAfter("data-ciphers ")?.let {
            out.put("data_ciphers", JSONArray(it.split(':')))
        }
        return out
    }

    private fun block(raw: String, name: String): String {
        val start = Regex("(?is)<" + Regex.escape(name) + ">\\s*").find(raw) ?: return ""
        val end = Regex("(?is)</" + Regex.escape(name) + ">").find(raw, start.range.last + 1) ?: return ""
        return raw.substring(start.range.last + 1, end.range.first).trim()
    }

    private fun sections(raw: String): Map<String, List<Map<String, String>>> {
        val out = linkedMapOf<String, MutableList<MutableMap<String, String>>>()
        var current: MutableMap<String, String>? = null
        for (str in raw.lineSequence()) {
            val line = str.trim()
            if (line.startsWith('[') && line.endsWith(']')) {
                current = linkedMapOf()
                out.getOrPut(line.substring(1, line.length - 1).lowercase()) { mutableListOf() }.add(current)
            } else if (current != null && '=' in line)
                current[line.substringBefore('=').trim().lowercase()] = line.substringAfter('=').trim()
        }
        return out
    }

    private fun hostPort(input: String, defaultPort: Int): Pair<String, Int> {
        val host: String
        val port: Int
        val source = input.trim()
        if (source.startsWith("[")) {
            val end = source.indexOf(']')
            require(end > 1) { "Неверный IPv6 адрес" }
            host = source.substring(1, end)
            port = source.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
        } else {
            host = source.substringBeforeLast(':', source)
            port = source.substringAfterLast(':', "").toIntOrNull() ?: defaultPort
        }
        require(host.isNotBlank() && port in 1..65535)
        return host to port
    }
    private fun csv(input: String) = input.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    private fun decode(raw: String) = URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
}
