package pro.sitescope.app;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Parses one user-supplied Xray profile without persisting its secret fields in plaintext. */
public final class XrayProfileParser {
    public static final class ParsedProfile {
        public String name = "Xray profile";
        public String type = "JSON";
        public String endpoint = "";
        public JSONObject outbound;
        public JSONObject fullConfig;
    }

    private XrayProfileParser() { }

    public static ParsedProfile parse(String value) throws Exception {
        String raw = value == null ? "" : value.trim();
        if (raw.length() == 0) throw new IllegalArgumentException("Профиль Xray пуст");
        if (raw.startsWith("{")) return parseJson(raw);
        String lower = raw.toLowerCase(Locale.US);
        if (lower.startsWith("vless://")) return parseVless(raw);
        if (lower.startsWith("vmess://")) return parseVmess(raw);
        if (lower.startsWith("trojan://")) return parseTrojan(raw);
        if (lower.startsWith("ss://")) return parseShadowsocks(raw);
        throw new IllegalArgumentException("Поддерживаются VLESS, VMess, Trojan, Shadowsocks и полный Xray JSON");
    }

    private static ParsedProfile parseJson(String raw) throws Exception {
        JSONObject config = new JSONObject(raw);
        JSONArray outbounds = config.optJSONArray("outbounds");
        if (outbounds == null || outbounds.length() == 0) throw new IllegalArgumentException("В Xray JSON отсутствует массив outbounds");
        ParsedProfile result = new ParsedProfile();
        result.fullConfig = config;
        JSONObject selected = findProxyOutbound(outbounds);
        result.type = selected.optString("protocol", "JSON").toUpperCase(Locale.US);
        result.name = "Xray JSON · " + result.type;
        result.endpoint = endpointFromOutbound(selected);
        return result;
    }

    private static ParsedProfile parseVless(String raw) throws Exception {
        URI uri = new URI(raw);
        String id = decode(uri.getRawUserInfo());
        String host = requiredHost(uri);
        int port = validPort(uri.getPort(), 443);
        Map<String,String> q = query(uri.getRawQuery());
        if (id.length() == 0) throw new IllegalArgumentException("VLESS UUID отсутствует");

        JSONObject user = new JSONObject();
        user.put("id", id);
        user.put("encryption", text(q, "encryption", "none"));
        user.put("level", 0);
        putIfNotEmpty(user, "flow", q.get("flow"));

        JSONObject server = new JSONObject();
        server.put("address", host); server.put("port", port);
        server.put("users", new JSONArray().put(user));

        JSONObject outbound = taggedOutbound("vless");
        outbound.put("settings", new JSONObject().put("vnext", new JSONArray().put(server)));
        outbound.put("streamSettings", streamSettings(q, text(q, "type", "tcp"), text(q, "security", "none"), host));

        ParsedProfile result = result("VLESS", fragment(uri), host, port, outbound);
        return result;
    }

    private static ParsedProfile parseTrojan(String raw) throws Exception {
        URI uri = new URI(raw);
        String password = decode(uri.getRawUserInfo());
        String host = requiredHost(uri);
        int port = validPort(uri.getPort(), 443);
        Map<String,String> q = query(uri.getRawQuery());
        if (password.length() == 0) throw new IllegalArgumentException("Пароль Trojan отсутствует");

        JSONObject server = new JSONObject();
        server.put("address", host); server.put("port", port); server.put("password", password); server.put("level", 0);
        putIfNotEmpty(server, "flow", q.get("flow"));
        JSONObject outbound = taggedOutbound("trojan");
        outbound.put("settings", new JSONObject().put("servers", new JSONArray().put(server)));
        String security = text(q, "security", "tls");
        outbound.put("streamSettings", streamSettings(q, text(q, "type", "tcp"), security, host));
        return result("TROJAN", fragment(uri), host, port, outbound);
    }

    private static ParsedProfile parseVmess(String raw) throws Exception {
        String body = raw.substring(raw.indexOf("://") + 3);
        int hash = body.indexOf('#'); if (hash >= 0) body = body.substring(0, hash);
        JSONObject vm = new JSONObject(new String(decodeBase64(body), StandardCharsets.UTF_8));
        String host = vm.optString("add", "").trim();
        if (host.length() == 0) throw new IllegalArgumentException("VMess address отсутствует");
        int port = validPort(intValue(vm.opt("port"), 443), 443);
        String id = vm.optString("id", "").trim();
        if (id.length() == 0) throw new IllegalArgumentException("VMess UUID отсутствует");

        JSONObject user = new JSONObject();
        user.put("id", id); user.put("alterId", intValue(vm.opt("aid"), 0)); user.put("security", vm.optString("scy", "auto")); user.put("level", 0);
        JSONObject server = new JSONObject();
        server.put("address", host); server.put("port", port); server.put("users", new JSONArray().put(user));

        Map<String,String> q = new LinkedHashMap<String,String>();
        q.put("type", vm.optString("net", "tcp"));
        q.put("headerType", vm.optString("type", "none"));
        q.put("host", vm.optString("host", ""));
        q.put("path", vm.optString("path", ""));
        q.put("security", vm.optString("tls", "").length() > 0 ? vm.optString("tls") : "none");
        q.put("sni", vm.optString("sni", ""));
        q.put("fp", vm.optString("fp", ""));
        q.put("alpn", vm.optString("alpn", ""));

        JSONObject outbound = taggedOutbound("vmess");
        outbound.put("settings", new JSONObject().put("vnext", new JSONArray().put(server)));
        outbound.put("streamSettings", streamSettings(q, q.get("type"), q.get("security"), host));
        String name = vm.optString("ps", "").trim();
        return result("VMESS", name, host, port, outbound);
    }

    private static ParsedProfile parseShadowsocks(String raw) throws Exception {
        String body = raw.substring(raw.indexOf("://") + 3);
        String name = "";
        int hash = body.indexOf('#');
        if (hash >= 0) { name = decode(body.substring(hash + 1)); body = body.substring(0, hash); }
        String query = "";
        int question = body.indexOf('?');
        if (question >= 0) { query = body.substring(question + 1); body = body.substring(0, question); }
        if (query.toLowerCase(Locale.US).contains("plugin=")) throw new IllegalArgumentException("Shadowsocks plugin пока не поддерживается комбинированным режимом");

        String credentials;
        String hostPort;
        int at = body.lastIndexOf('@');
        if (at >= 0) {
            credentials = new String(decodeBase64(body.substring(0, at)), StandardCharsets.UTF_8);
            hostPort = body.substring(at + 1);
        } else {
            String decoded = new String(decodeBase64(body), StandardCharsets.UTF_8);
            int decodedAt = decoded.lastIndexOf('@');
            if (decodedAt < 0) throw new IllegalArgumentException("Некорректная Shadowsocks-ссылка");
            credentials = decoded.substring(0, decodedAt); hostPort = decoded.substring(decodedAt + 1);
        }
        int colon = credentials.indexOf(':');
        if (colon <= 0) throw new IllegalArgumentException("В Shadowsocks отсутствуют метод или пароль");
        String method = credentials.substring(0, colon);
        String password = credentials.substring(colon + 1);
        HostPort hp = splitHostPort(hostPort, 8388);

        JSONObject server = new JSONObject();
        server.put("address", hp.host); server.put("port", hp.port); server.put("method", method); server.put("password", password); server.put("level", 0);
        JSONObject outbound = taggedOutbound("shadowsocks");
        outbound.put("settings", new JSONObject().put("servers", new JSONArray().put(server)));
        return result("SHADOWSOCKS", name, hp.host, hp.port, outbound);
    }

    static JSONObject findProxyOutbound(JSONArray outbounds) throws Exception {
        for (int i = 0; i < outbounds.length(); i++) {
            JSONObject o = outbounds.optJSONObject(i); if (o == null) continue;
            String protocol = o.optString("protocol", "").toLowerCase(Locale.US);
            if (protocol.length() > 0 && !"freedom".equals(protocol) && !"blackhole".equals(protocol) && !"dns".equals(protocol)) return o;
        }
        throw new IllegalArgumentException("В Xray JSON не найден прокси-outbound");
    }

    private static JSONObject taggedOutbound(String protocol) throws Exception {
        JSONObject o = new JSONObject(); o.put("tag", "sitescope-proxy"); o.put("protocol", protocol); return o;
    }

    private static ParsedProfile result(String type, String name, String host, int port, JSONObject outbound) {
        ParsedProfile result = new ParsedProfile();
        result.type = type; result.name = name == null || name.trim().length() == 0 ? type + " · " + host : name.trim();
        result.endpoint = host + ":" + port; result.outbound = outbound; return result;
    }

    private static JSONObject streamSettings(Map<String,String> q, String requestedNetwork, String requestedSecurity, String endpointHost) throws Exception {
        String network = requestedNetwork == null || requestedNetwork.length() == 0 ? "tcp" : requestedNetwork.toLowerCase(Locale.US);
        if ("http".equals(network)) network = "h2";
        String security = requestedSecurity == null || requestedSecurity.length() == 0 ? "none" : requestedSecurity.toLowerCase(Locale.US);
        JSONObject stream = new JSONObject(); stream.put("network", network); stream.put("security", security);

        if ("tls".equals(security)) {
            JSONObject tls = new JSONObject();
            tls.put("serverName", text(q, "sni", text(q, "host", endpointHost)));
            tls.put("allowInsecure", boolAny(q, "allowInsecure", "insecure", "allow_insecure"));
            putIfNotEmpty(tls, "fingerprint", q.get("fp"));
            JSONArray alpn = csv(q.get("alpn")); if (alpn.length() > 0) tls.put("alpn", alpn);
            putIfNotEmpty(tls, "echConfigList", q.get("ech"));
            putIfNotEmpty(tls, "verifyPeerCertByName", q.get("vcn"));
            putIfNotEmpty(tls, "pinnedPeerCertificateChainSha256", q.get("pcs"));
            stream.put("tlsSettings", tls);
        } else if ("reality".equals(security)) {
            JSONObject reality = new JSONObject();
            reality.put("serverName", text(q, "sni", endpointHost));
            putIfNotEmpty(reality, "fingerprint", text(q, "fp", "chrome"));
            putIfNotEmpty(reality, "publicKey", q.get("pbk"));
            putIfNotEmpty(reality, "shortId", q.get("sid"));
            putIfNotEmpty(reality, "spiderX", q.get("spx"));
            putIfNotEmpty(reality, "mldsa65Verify", q.get("pqv"));
            stream.put("realitySettings", reality);
        }

        if ("ws".equals(network)) {
            JSONObject ws = new JSONObject(); ws.put("path", text(q, "path", "/"));
            String host = q.get("host"); if (host != null && host.length() > 0) ws.put("headers", new JSONObject().put("Host", host));
            stream.put("wsSettings", ws);
        } else if ("grpc".equals(network)) {
            JSONObject grpc = new JSONObject(); grpc.put("serviceName", text(q, "serviceName", text(q, "path", "")));
            grpc.put("multiMode", "multi".equalsIgnoreCase(q.get("mode")));
            putIfNotEmpty(grpc, "authority", q.get("authority"));
            grpc.put("idle_timeout", 60); grpc.put("health_check_timeout", 20);
            stream.put("grpcSettings", grpc);
        } else if ("tcp".equals(network)) {
            String header = text(q, "headerType", "none");
            JSONObject headerObject = new JSONObject().put("type", header.length() == 0 ? "none" : header);
            if ("http".equalsIgnoreCase(header)) {
                JSONObject request = new JSONObject();
                request.put("version", "1.1"); request.put("method", "GET");
                request.put("path", csvOrDefault(q.get("path"), "/"));
                String host = q.get("host");
                if (host != null && host.trim().length() > 0) request.put("headers", new JSONObject().put("Host", csv(host)));
                headerObject.put("request", request);
            }
            stream.put("tcpSettings", new JSONObject().put("header", headerObject));
        } else if ("kcp".equals(network) || "mkcp".equals(network)) {
            stream.put("network", "kcp");
            JSONObject kcp = new JSONObject().put("header", new JSONObject().put("type", text(q, "headerType", "none")));
            putIntIfPresent(kcp, "mtu", q.get("mtu")); putIntIfPresent(kcp, "tti", q.get("tti")); putIfNotEmpty(kcp, "seed", q.get("seed"));
            stream.put("kcpSettings", kcp);
        } else if ("h2".equals(network)) {
            JSONObject h2 = new JSONObject(); h2.put("path", text(q, "path", "/"));
            JSONArray hosts = csv(q.get("host")); if (hosts.length() > 0) h2.put("host", hosts);
            stream.put("httpSettings", h2);
        } else if ("xhttp".equals(network) || "splithttp".equals(network)) {
            stream.put("network", "xhttp");
            JSONObject xhttp = new JSONObject(); xhttp.put("path", text(q, "path", "/")); putIfNotEmpty(xhttp, "host", q.get("host")); putIfNotEmpty(xhttp, "mode", q.get("mode"));
            String extra = q.get("extra"); if (extra != null && extra.trim().startsWith("{")) xhttp.put("extra", new JSONObject(extra));
            stream.put("xhttpSettings", xhttp);
        } else if ("httpupgrade".equals(network)) {
            JSONObject upgrade = new JSONObject(); upgrade.put("path", text(q, "path", "/")); putIfNotEmpty(upgrade, "host", q.get("host")); stream.put("httpupgradeSettings", upgrade);
        }
        return stream;
    }

    private static String endpointFromOutbound(JSONObject outbound) {
        try {
            JSONObject settings = outbound.optJSONObject("settings"); if (settings == null) return "";
            JSONArray vnext = settings.optJSONArray("vnext"); if (vnext != null && vnext.length() > 0) return endpoint(vnext.getJSONObject(0));
            JSONArray servers = settings.optJSONArray("servers"); if (servers != null && servers.length() > 0) return endpoint(servers.getJSONObject(0));
        } catch (Exception ignored) { }
        return "";
    }

    private static String endpoint(JSONObject server) { String host = server.optString("address", ""); int port = server.optInt("port", 0); return host.length() == 0 ? "" : host + (port > 0 ? ":" + port : ""); }
    private static String requiredHost(URI uri) { String host = uri.getHost(); if (host == null || host.trim().length() == 0) throw new IllegalArgumentException("Адрес сервера отсутствует"); return host; }
    private static int validPort(int port, int fallback) { int value = port > 0 ? port : fallback; if (value < 1 || value > 65535) throw new IllegalArgumentException("Некорректный порт сервера"); return value; }
    private static String fragment(URI uri) { return decode(uri.getRawFragment()); }
    private static String decode(String raw) { try { return raw == null ? "" : URLDecoder.decode(raw, "UTF-8"); } catch (Exception e) { return raw == null ? "" : raw; } }

    private static Map<String,String> query(String raw) {
        Map<String,String> out = new LinkedHashMap<String,String>(); if (raw == null || raw.length() == 0) return out;
        for (String part : raw.split("&")) { int eq = part.indexOf('='); String key = decode(eq < 0 ? part : part.substring(0, eq)); String value = decode(eq < 0 ? "" : part.substring(eq + 1)); if (key.length() > 0) out.put(key, value); }
        return out;
    }

    private static byte[] decodeBase64(String value) {
        String clean = value == null ? "" : value.trim();
        int mod = clean.length() % 4; if (mod != 0) clean += "====".substring(mod);
        try { return Base64.decode(clean, Base64.URL_SAFE | Base64.NO_WRAP); }
        catch (Exception first) { return Base64.decode(clean, Base64.DEFAULT); }
    }

    private static int intValue(Object value, int fallback) { try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); } catch (Exception e) { return fallback; } }
    private static String text(Map<String,String> map, String key, String fallback) { String value = map.get(key); return value == null || value.length() == 0 ? fallback : value; }
    private static void putIfNotEmpty(JSONObject object, String key, String value) throws Exception { if (value != null && value.trim().length() > 0) object.put(key, value.trim()); }
    private static boolean bool(String value) { return "1".equals(value) || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value); }
    private static boolean boolAny(Map<String,String> values, String... keys) { for (String key : keys) if (bool(values.get(key))) return true; return false; }
    private static JSONArray csv(String value) { JSONArray array = new JSONArray(); if (value != null) for (String item : value.split(",")) if (item.trim().length() > 0) array.put(item.trim()); return array; }
    private static JSONArray csvOrDefault(String value, String fallback) { JSONArray array = csv(value); if (array.length() == 0) array.put(fallback); return array; }
    private static void putIntIfPresent(JSONObject object, String key, String value) throws Exception { if (value == null || value.trim().length() == 0) return; try { object.put(key, Integer.parseInt(value.trim())); } catch (NumberFormatException ignored) { } }

    private static HostPort splitHostPort(String raw, int fallbackPort) {
        String value = raw.trim(); String host; int port = fallbackPort;
        if (value.startsWith("[")) { int end = value.indexOf(']'); if (end < 0) throw new IllegalArgumentException("Некорректный IPv6 адрес"); host = value.substring(1, end); if (end + 1 < value.length() && value.charAt(end + 1) == ':') port = intValue(value.substring(end + 2), fallbackPort); }
        else { int colon = value.lastIndexOf(':'); if (colon <= 0) { host = value; } else { host = value.substring(0, colon); port = intValue(value.substring(colon + 1), fallbackPort); } }
        if (host.length() == 0 || port < 1 || port > 65535) throw new IllegalArgumentException("Некорректный адрес Shadowsocks"); return new HostPort(host, port);
    }

    private static final class HostPort { final String host; final int port; HostPort(String host, int port) { this.host = host; this.port = port; } }
}
