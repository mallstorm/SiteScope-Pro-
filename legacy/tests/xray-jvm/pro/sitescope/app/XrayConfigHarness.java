package pro.sitescope.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class XrayConfigHarness {
    public static void main(String[] args) throws Exception {
        testVlessRealityTcp();
        testVlessWsTls();
        testTrojanGrpc();
        testVlessHttp2Compatibility();
        testXhttpCompatibility();
        testTlsAndRealityExtendedFields();
        testVmess();
        testShadowsocks();
        testFullJsonAndHosts();
        testRejectDirectOnlyJson();
        testRejectBadVmessPort();
        System.out.println("XRAY_CONFIG_HARNESS_PASS");
    }

    private static void testVlessRealityTcp() throws Exception {
        String raw = "vless://11111111-1111-4111-8111-111111111111@example.com:443?encryption=none&security=reality&sni=cdn.example.com&fp=chrome&pbk=PUBKEY&sid=abcd&type=tcp#Finland";
        XrayConfigBuilder.ConfigResult result = XrayConfigBuilder.build(raw, overrides());
        JSONObject root = new JSONObject(result.json);
        JSONObject proxy = root.getJSONArray("outbounds").getJSONObject(0);
        JSONObject stream = proxy.getJSONObject("streamSettings");
        eq("vless", proxy.getString("protocol"), "VLESS protocol");
        eq("reality", stream.getString("security"), "Reality security");
        eq("none", stream.getJSONObject("tcpSettings").getJSONObject("header").getString("type"), "TCP header must not inherit query type=tcp");
        eq("PUBKEY", stream.getJSONObject("realitySettings").getString("publicKey"), "Reality public key");
        eq("Finland", result.profileName, "VLESS fragment name");
    }

    private static void testVlessWsTls() throws Exception {
        String raw = "vless://22222222-2222-4222-8222-222222222222@edge.example:8443?encryption=none&security=tls&sni=site.example&type=ws&host=site.example&path=%2Fws#WS";
        JSONObject proxy = new JSONObject(XrayConfigBuilder.build(raw, overrides()).json).getJSONArray("outbounds").getJSONObject(0);
        JSONObject stream = proxy.getJSONObject("streamSettings");
        eq("ws", stream.getString("network"), "WS network");
        eq("/ws", stream.getJSONObject("wsSettings").getString("path"), "WS path");
        eq("site.example", stream.getJSONObject("tlsSettings").getString("serverName"), "TLS SNI");
    }

    private static void testTrojanGrpc() throws Exception {
        String raw = "trojan://secret-pass@trojan.example:443?security=tls&sni=trojan.example&type=grpc&serviceName=grpc-service#Trojan";
        JSONObject proxy = new JSONObject(XrayConfigBuilder.build(raw, overrides()).json).getJSONArray("outbounds").getJSONObject(0);
        JSONObject stream = proxy.getJSONObject("streamSettings");
        eq("trojan", proxy.getString("protocol"), "Trojan protocol");
        eq("grpc-service", stream.getJSONObject("grpcSettings").getString("serviceName"), "gRPC service");
    }


    private static void testVlessHttp2Compatibility() throws Exception {
        String raw = "vless://44444444-4444-4444-8444-444444444444@h2.example:443?encryption=none&security=tls&sni=h2.example&type=http&host=h2.example&path=%2Fedge#H2";
        XrayConfigBuilder.ConfigResult result = XrayConfigBuilder.build(raw, overrides());
        JSONObject stream = new JSONObject(result.json).getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings");
        eq("h2", stream.getString("network"), "Legacy type=http must map to h2, not xhttp");
        eq("/edge", stream.getJSONObject("httpSettings").getString("path"), "H2 path");
        check(result.transportSummary.contains("network=h2"), "Safe transport summary contains h2");
    }

    private static void testXhttpCompatibility() throws Exception {
        String extra = java.net.URLEncoder.encode("{\"noGRPCHeader\":true}", "UTF-8");
        String raw = "vless://55555555-5555-4555-8555-555555555555@xhttp.example:443?encryption=none&security=tls&sni=xhttp.example&type=xhttp&host=xhttp.example&path=%2Fsplit&mode=auto&extra=" + extra + "#XHTTP";
        XrayConfigBuilder.ConfigResult result = XrayConfigBuilder.build(raw, overrides());
        JSONObject xhttp = new JSONObject(result.json).getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings").getJSONObject("xhttpSettings");
        eq("auto", xhttp.getString("mode"), "XHTTP mode");
        check(xhttp.has("extra"), "XHTTP extra object");
        check(result.transportSummary.contains("extra=present"), "Safe summary marks XHTTP extra without exposing it");
    }

    private static void testTlsAndRealityExtendedFields() throws Exception {
        String tlsRaw = "vless://66666666-6666-4666-8666-666666666666@tls.example:443?encryption=none&security=tls&sni=tls.example&type=grpc&serviceName=svc&authority=auth.example&insecure=1&ech=ECHDATA&vcn=peer.example&pcs=PIN#TLS";
        JSONObject tlsStream = new JSONObject(XrayConfigBuilder.build(tlsRaw, overrides()).json).getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings");
        JSONObject tls = tlsStream.getJSONObject("tlsSettings");
        check(tls.getBoolean("allowInsecure"), "TLS insecure alias");
        eq("auth.example", tlsStream.getJSONObject("grpcSettings").getString("authority"), "gRPC authority");
        eq("ECHDATA", tls.getString("echConfigList"), "TLS ECH");

        String realityRaw = "vless://77777777-7777-4777-8777-777777777777@reality.example:443?encryption=none&security=reality&sni=cdn.example&fp=chrome&pbk=PUB&sid=SID&pqv=PQVERIFY&type=tcp#Reality";
        JSONObject reality = new JSONObject(XrayConfigBuilder.build(realityRaw, overrides()).json).getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings").getJSONObject("realitySettings");
        eq("PQVERIFY", reality.getString("mldsa65Verify"), "Reality ML-DSA verify field");
    }

    private static void testVmess() throws Exception {
        JSONObject vm = new JSONObject();
        vm.put("v", "2"); vm.put("ps", "VMess test"); vm.put("add", "vm.example"); vm.put("port", "443");
        vm.put("id", "33333333-3333-4333-8333-333333333333"); vm.put("aid", "0"); vm.put("scy", "auto");
        vm.put("net", "ws"); vm.put("type", "none"); vm.put("host", "cdn.vm.example"); vm.put("path", "/vm"); vm.put("tls", "tls"); vm.put("sni", "cdn.vm.example");
        String encoded = Base64.getEncoder().withoutPadding().encodeToString(vm.toString().getBytes(StandardCharsets.UTF_8));
        XrayConfigBuilder.ConfigResult result = XrayConfigBuilder.build("vmess://" + encoded, overrides());
        eq("VMESS", result.profileType, "VMess type");
        JSONObject proxy = new JSONObject(result.json).getJSONArray("outbounds").getJSONObject(0);
        eq("vmess", proxy.getString("protocol"), "VMess protocol");
    }

    private static void testShadowsocks() throws Exception {
        String user = Base64.getUrlEncoder().withoutPadding().encodeToString("aes-256-gcm:ss-pass".getBytes(StandardCharsets.UTF_8));
        XrayConfigBuilder.ConfigResult result = XrayConfigBuilder.build("ss://" + user + "@ss.example:8388#SS", overrides());
        eq("SHADOWSOCKS", result.profileType, "SS type");
        JSONObject server = new JSONObject(result.json).getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("servers").getJSONObject(0);
        eq("aes-256-gcm", server.getString("method"), "SS method");
    }

    private static void testFullJsonAndHosts() throws Exception {
        JSONObject proxy = new JSONObject().put("tag", "proxy-main").put("protocol", "trojan")
            .put("settings", new JSONObject().put("servers", new JSONArray().put(new JSONObject().put("address", "json.example").put("port", 443).put("password", "secret"))));
        JSONObject direct = new JSONObject().put("tag", "direct").put("protocol", "freedom");
        JSONObject config = new JSONObject().put("outbounds", new JSONArray().put(direct).put(proxy))
            .put("dns", new JSONObject().put("hosts", new JSONObject().put("existing.example", "192.0.2.1")));
        JSONObject built = new JSONObject(XrayConfigBuilder.build(config.toString(), overrides()).json);
        JSONObject hosts = built.getJSONObject("dns").getJSONObject("hosts");
        eq("192.0.2.1", hosts.getString("existing.example"), "Existing DNS host preserved");
        eq("128.0.3.22", hosts.getString("sige.ru"), "SiteScope hosts override injected");
        JSONObject dnsOutbound = findByTag(built.getJSONArray("outbounds"), XrayConfigBuilder.DNS_TAG);
        check(dnsOutbound != null && !dnsOutbound.has("settings"), "DNS outbound follows embedded v2rayNG form");
        JSONArray rules = built.getJSONObject("routing").getJSONArray("rules");
        eq(XrayConfigBuilder.DNS_TAG, rules.getJSONObject(0).getString("outboundTag"), "DNS routing first");
        eq("proxy-main", rules.getJSONObject(1).getString("outboundTag"), "All SOCKS traffic forced through selected profile");
        check(built.getJSONArray("inbounds").length() == 1, "Only one private SOCKS inbound");
    }

    private static void testRejectDirectOnlyJson() throws Exception {
        JSONObject config = new JSONObject().put("outbounds", new JSONArray().put(new JSONObject().put("protocol", "freedom")));
        expectFailure(config.toString(), "Direct-only JSON must be rejected");
    }

    private static void testRejectBadVmessPort() throws Exception {
        JSONObject vm = new JSONObject().put("add", "vm.example").put("port", "70000")
            .put("id", "33333333-3333-4333-8333-333333333333").put("net", "tcp");
        String encoded = Base64.getEncoder().withoutPadding().encodeToString(vm.toString().getBytes(StandardCharsets.UTF_8));
        expectFailure("vmess://" + encoded, "Invalid VMess port must be rejected");
    }

    private static Map<String,List<InetAddress>> overrides() throws Exception {
        Map<String,List<InetAddress>> out = new LinkedHashMap<String,List<InetAddress>>();
        List<InetAddress> values = new ArrayList<InetAddress>(); values.add(InetAddress.getByName("128.0.3.22"));
        out.put("sige.ru", values); return out;
    }

    private static JSONObject findByTag(JSONArray array, String tag) throws Exception {
        for (int i = 0; i < array.length(); i++) { JSONObject value = array.getJSONObject(i); if (tag.equals(value.optString("tag"))) return value; }
        return null;
    }

    private static void expectFailure(String raw, String message) throws Exception {
        try { XrayConfigBuilder.build(raw, overrides()); throw new AssertionError(message); }
        catch (IllegalArgumentException expected) { }
    }

    private static void eq(Object expected, Object actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) throw new AssertionError(label + ": expected=" + expected + " actual=" + actual);
    }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
