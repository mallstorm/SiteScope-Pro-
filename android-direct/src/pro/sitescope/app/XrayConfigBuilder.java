package pro.sitescope.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Produces a locked-down single-profile Xray configuration for the SiteScope TUN. */
public final class XrayConfigBuilder {
    public static final int SOCKS_PORT = 10808;
    public static final String SOCKS_TAG = "sitescope-socks";
    public static final String DNS_TAG = "sitescope-dns";
    public static final String PROXY_TAG = "sitescope-proxy";

    public static final class ConfigResult {
        public String json;
        public String profileName;
        public String profileType;
        public String endpoint;
        public int hostsCount;
        public String transportSummary;
    }

    private XrayConfigBuilder() { }

    public static ConfigResult build(String rawProfile, Map<String,List<InetAddress>> overrides) throws Exception {
        XrayProfileParser.ParsedProfile parsed = XrayProfileParser.parse(rawProfile);
        JSONObject root;
        if (parsed.fullConfig != null) root = normalizeFullConfig(new JSONObject(parsed.fullConfig.toString()), overrides);
        else root = baseConfig(new JSONObject(parsed.outbound.toString()), overrides);
        ConfigResult result = new ConfigResult();
        result.json = root.toString();
        result.profileName = parsed.name; result.profileType = parsed.type; result.endpoint = parsed.endpoint;
        result.hostsCount = overrides == null ? 0 : overrides.size();
        result.transportSummary = safeTransportSummary(root);
        return result;
    }

    private static JSONObject baseConfig(JSONObject proxy, Map<String,List<InetAddress>> overrides) throws Exception {
        proxy.put("tag", PROXY_TAG);
        JSONObject root = new JSONObject();
        root.put("log", new JSONObject().put("loglevel", "warning"));
        root.put("dns", dnsConfig(null, overrides));
        root.put("inbounds", new JSONArray().put(socksInbound()));
        JSONArray outbounds = new JSONArray();
        outbounds.put(proxy);
        outbounds.put(dnsOutbound());
        outbounds.put(new JSONObject().put("tag", "sitescope-direct").put("protocol", "freedom"));
        outbounds.put(new JSONObject().put("tag", "sitescope-block").put("protocol", "blackhole"));
        root.put("outbounds", outbounds);
        root.put("routing", routingConfig(null, PROXY_TAG));
        return root;
    }

    private static JSONObject normalizeFullConfig(JSONObject root, Map<String,List<InetAddress>> overrides) throws Exception {
        JSONArray source = root.optJSONArray("outbounds");
        if (source == null || source.length() == 0) throw new IllegalArgumentException("В Xray JSON отсутствует рабочий outbound");
        JSONObject selected = XrayProfileParser.findProxyOutbound(source);
        String mainTag = selected.optString("tag", "").trim();
        if (mainTag.length() == 0 || DNS_TAG.equals(mainTag)) mainTag = PROXY_TAG;
        selected.put("tag", mainTag);

        JSONArray outbounds = new JSONArray();
        outbounds.put(selected);
        for (int i = 0; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i); if (item == null || item == selected) continue;
            if (DNS_TAG.equals(item.optString("tag"))) continue;
            outbounds.put(item);
        }
        outbounds.put(dnsOutbound());
        root.put("outbounds", outbounds);
        root.put("inbounds", new JSONArray().put(socksInbound()));
        root.put("dns", dnsConfig(root.optJSONObject("dns"), overrides));
        root.put("routing", routingConfig(root.optJSONObject("routing"), mainTag));
        if (!root.has("log")) root.put("log", new JSONObject().put("loglevel", "warning"));
        return root;
    }

    private static JSONObject socksInbound() throws Exception {
        JSONObject settings = new JSONObject();
        settings.put("auth", "noauth"); settings.put("udp", true); settings.put("ip", "127.0.0.1");
        JSONObject sniffing = new JSONObject();
        sniffing.put("enabled", true);
        sniffing.put("destOverride", new JSONArray().put("http").put("tls").put("quic"));
        sniffing.put("routeOnly", false);
        JSONObject inbound = new JSONObject();
        inbound.put("tag", SOCKS_TAG); inbound.put("listen", "127.0.0.1"); inbound.put("port", SOCKS_PORT);
        inbound.put("protocol", "socks"); inbound.put("settings", settings); inbound.put("sniffing", sniffing);
        return inbound;
    }

    private static JSONObject dnsOutbound() throws Exception {
        // Mirrors v2rayNG's embedded-core configuration: the DNS outbound delegates to
        // Xray's built-in DNS module and must not force a non-standard combined network value.
        return new JSONObject().put("tag", DNS_TAG).put("protocol", "dns");
    }

    private static JSONObject dnsConfig(JSONObject existing, Map<String,List<InetAddress>> overrides) throws Exception {
        JSONObject dns = existing == null ? new JSONObject() : new JSONObject(existing.toString());
        JSONObject hosts = dns.optJSONObject("hosts"); if (hosts == null) hosts = new JSONObject();
        if (overrides != null) {
            for (Map.Entry<String,List<InetAddress>> entry : overrides.entrySet()) {
                List<InetAddress> addresses = entry.getValue(); if (addresses == null || addresses.isEmpty()) continue;
                if (addresses.size() == 1) hosts.put(entry.getKey(), addresses.get(0).getHostAddress());
                else { JSONArray values = new JSONArray(); for (InetAddress address : addresses) values.put(address.getHostAddress()); hosts.put(entry.getKey(), values); }
            }
        }
        dns.put("hosts", hosts);
        if (!dns.has("servers")) dns.put("servers", new JSONArray().put("1.1.1.1").put("8.8.8.8").put("2606:4700:4700::1111"));
        if (!dns.has("queryStrategy")) dns.put("queryStrategy", "UseIP");
        if (!dns.has("disableCache")) dns.put("disableCache", false);
        return dns;
    }

    private static JSONObject routingConfig(JSONObject existing, String mainTag) throws Exception {
        JSONObject routing = existing == null ? new JSONObject() : new JSONObject(existing.toString());
        JSONArray oldRules = routing.optJSONArray("rules");
        JSONArray rules = new JSONArray();
        JSONObject dnsRule = new JSONObject();
        dnsRule.put("type", "field"); dnsRule.put("inboundTag", new JSONArray().put(SOCKS_TAG));
        dnsRule.put("network", "tcp,udp"); dnsRule.put("port", "53"); dnsRule.put("outboundTag", DNS_TAG);
        rules.put(dnsRule);
        JSONObject allRule = new JSONObject();
        allRule.put("type", "field"); allRule.put("inboundTag", new JSONArray().put(SOCKS_TAG)); allRule.put("outboundTag", mainTag);
        rules.put(allRule);
        if (oldRules != null) for (int i = 0; i < oldRules.length(); i++) rules.put(oldRules.get(i));
        routing.put("domainStrategy", routing.optString("domainStrategy", "IPIfNonMatch"));
        routing.put("rules", rules);
        return routing;
    }
    /** Non-secret profile description used by diagnostics. */
    private static String safeTransportSummary(JSONObject root) {
        try {
            JSONObject proxy = XrayProfileParser.findProxyOutbound(root.getJSONArray("outbounds"));
            String protocol = proxy.optString("protocol", "unknown");
            JSONObject stream = proxy.optJSONObject("streamSettings");
            String network = stream == null ? "default" : stream.optString("network", "tcp");
            String security = stream == null ? "none" : stream.optString("security", "none");
            StringBuilder out = new StringBuilder();
            out.append("protocol=").append(protocol).append(", network=").append(network).append(", security=").append(security);

            JSONObject settings = proxy.optJSONObject("settings");
            if (settings != null) {
                JSONArray vnext = settings.optJSONArray("vnext");
                if (vnext != null && vnext.length() > 0) {
                    JSONArray users = vnext.optJSONObject(0) == null ? null : vnext.optJSONObject(0).optJSONArray("users");
                    JSONObject user = users == null ? null : users.optJSONObject(0);
                    if (user != null && user.optString("flow", "").length() > 0) out.append(", flow=").append(user.optString("flow"));
                }
                JSONArray servers = settings.optJSONArray("servers");
                JSONObject server = servers == null ? null : servers.optJSONObject(0);
                if (server != null && server.optString("flow", "").length() > 0) out.append(", flow=").append(server.optString("flow"));
            }

            if (stream != null && "tls".equalsIgnoreCase(security)) {
                JSONObject tls = stream.optJSONObject("tlsSettings");
                if (tls != null) {
                    appendSafe(out, "sni", tls.optString("serverName", ""));
                    appendSafe(out, "fp", tls.optString("fingerprint", ""));
                    if (tls.optBoolean("allowInsecure", false)) out.append(", insecure=true");
                    if (tls.optString("echConfigList", "").length() > 0) out.append(", ech=present");
                }
            } else if (stream != null && "reality".equalsIgnoreCase(security)) {
                JSONObject reality = stream.optJSONObject("realitySettings");
                if (reality != null) {
                    appendSafe(out, "sni", reality.optString("serverName", ""));
                    appendSafe(out, "fp", reality.optString("fingerprint", ""));
                    if (reality.optString("publicKey", "").length() > 0) out.append(", pbk=present");
                    if (reality.optString("shortId", "").length() > 0) out.append(", sid=present");
                    if (reality.optString("mldsa65Verify", "").length() > 0) out.append(", pqv=present");
                }
            }

            if (stream != null && "grpc".equalsIgnoreCase(network)) {
                JSONObject grpc = stream.optJSONObject("grpcSettings");
                if (grpc != null) {
                    appendSafe(out, "service", grpc.optString("serviceName", ""));
                    appendSafe(out, "authority", grpc.optString("authority", ""));
                }
            } else if (stream != null && "ws".equalsIgnoreCase(network)) {
                JSONObject ws = stream.optJSONObject("wsSettings");
                if (ws != null) appendSafe(out, "path", ws.optString("path", ""));
            } else if (stream != null && "h2".equalsIgnoreCase(network)) {
                JSONObject h2 = stream.optJSONObject("httpSettings");
                if (h2 != null) appendSafe(out, "path", h2.optString("path", ""));
            } else if (stream != null && "xhttp".equalsIgnoreCase(network)) {
                JSONObject xhttp = stream.optJSONObject("xhttpSettings");
                if (xhttp != null) {
                    appendSafe(out, "path", xhttp.optString("path", ""));
                    appendSafe(out, "mode", xhttp.optString("mode", ""));
                    if (xhttp.has("extra")) out.append(", extra=present");
                }
            }
            return out.toString();
        } catch (Exception e) {
            return "transport=unavailable";
        }
    }

    private static void appendSafe(StringBuilder out, String key, String value) {
        if (value != null && value.trim().length() > 0) out.append(", ").append(key).append("=").append(value.trim());
    }

}
