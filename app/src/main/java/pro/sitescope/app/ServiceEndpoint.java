package pro.sitescope.app;

import android.content.Intent;
import android.net.Uri;

import java.util.Locale;

/** Normalized endpoint passed between scanner, LAN discovery and protocol clients. */
public final class ServiceEndpoint {
    public static final String EXTRA_PREFIX = "service_endpoint_";

    public String host = "";
    public NetworkScope scope;
    public DeviceIdentity identity = new DeviceIdentity();
    public String tlsTrust = "not_applicable";
    public int port;
    public String protocol = "UNKNOWN";
    public String service = "";
    public String displayName = "";
    public String resourceType = "";
    public String uri = "";
    public String location = "";
    public String services = "";
    public String evidence = "";
    public String banner = "";
    public String tlsSubject = "";
    public String confidence = "low";
    public String description = "";
    public long latencyMs;

    public static ServiceEndpoint from(Models.PortResult result) {
        ServiceEndpoint endpoint = new ServiceEndpoint();
        endpoint.scope = result.scope;
        endpoint.host = value(result.ip).length() > 0 ? value(result.ip) : value(result.target);
        endpoint.port = result.port;
        endpoint.service = value(result.service);
        endpoint.protocol = detect(endpoint.service, endpoint.port, result.banner + " " + result.evidence, "");
        endpoint.displayName = endpoint.host + (endpoint.port > 0 ? ":" + endpoint.port : "");
        endpoint.resourceType = value(result.resourceType);
        endpoint.evidence = value(result.evidence);
        endpoint.banner = value(result.banner);
        endpoint.tlsSubject = value(result.tlsSubject);
        endpoint.confidence = value(result.confidence);
        endpoint.identity = result.identity;
        endpoint.tlsTrust = result.tlsTrust;
        endpoint.description = value(result.description);
        endpoint.latencyMs = result.latencyMs;
        endpoint.uri = buildUri(endpoint.protocol, endpoint.host, endpoint.port, "");
        return endpoint;
    }

    public static ServiceEndpoint from(Models.DiscoveredDevice device) {
        ServiceEndpoint endpoint = new ServiceEndpoint();
        endpoint.scope = device.scope;
        endpoint.host = value(device.ip);
        endpoint.port = device.port;
        endpoint.service = value(device.protocol);
        endpoint.protocol = detect(device.protocol + " " + device.services, device.port, device.evidence, device.location);
        endpoint.displayName = value(device.name).length() > 0 ? value(device.name) : endpoint.host + (endpoint.port > 0 ? ":" + endpoint.port : "");
        endpoint.resourceType = value(device.deviceType);
        endpoint.description = value(device.hostname);
        endpoint.location = value(device.location);
        endpoint.services = value(device.services);
        endpoint.evidence = value(device.evidence);
        endpoint.confidence = value(device.confidence);
        endpoint.identity = device.identity;
        endpoint.latencyMs = device.latencyMs;
        endpoint.uri = buildUri(endpoint.protocol, endpoint.host, endpoint.port, endpoint.location);
        return endpoint;
    }

    public void put(Intent intent) {
        if (scope != null) intent.putExtra(EXTRA_PREFIX + "scope", scope.toJson().toString());
        intent.putExtra(EXTRA_PREFIX + "identity", identity.toJson().toString());
        intent.putExtra(EXTRA_PREFIX + "tlsTrust", tlsTrust);
        intent.putExtra(EXTRA_PREFIX + "host", host);
        intent.putExtra(EXTRA_PREFIX + "port", port);
        intent.putExtra(EXTRA_PREFIX + "protocol", protocol);
        intent.putExtra(EXTRA_PREFIX + "service", service);
        intent.putExtra(EXTRA_PREFIX + "displayName", displayName);
        intent.putExtra(EXTRA_PREFIX + "resourceType", resourceType);
        intent.putExtra(EXTRA_PREFIX + "uri", uri);
        intent.putExtra(EXTRA_PREFIX + "location", location);
        intent.putExtra(EXTRA_PREFIX + "services", services);
        intent.putExtra(EXTRA_PREFIX + "evidence", evidence);
        intent.putExtra(EXTRA_PREFIX + "banner", banner);
        intent.putExtra(EXTRA_PREFIX + "tlsSubject", tlsSubject);
        intent.putExtra(EXTRA_PREFIX + "confidence", confidence);
        intent.putExtra(EXTRA_PREFIX + "description", description);
        intent.putExtra(EXTRA_PREFIX + "latencyMs", latencyMs);
    }

    public static ServiceEndpoint from(Intent intent) {
        ServiceEndpoint endpoint = new ServiceEndpoint();
        try { String raw = intent.getStringExtra(EXTRA_PREFIX + "scope"); if (raw != null) endpoint.scope = NetworkScope.fromJson(new org.json.JSONObject(raw), "endpoint"); } catch (Exception ignored) { }
        try { endpoint.identity = DeviceIdentity.fromJson(new org.json.JSONObject(value(intent.getStringExtra(EXTRA_PREFIX + "identity")))); } catch (Exception ignored) { }
        endpoint.tlsTrust = intent.getStringExtra(EXTRA_PREFIX + "tlsTrust");
        if (endpoint.tlsTrust == null) endpoint.tlsTrust = "not_applicable";
        endpoint.host = value(intent.getStringExtra(EXTRA_PREFIX + "host"));
        endpoint.port = intent.getIntExtra(EXTRA_PREFIX + "port", 0);
        endpoint.protocol = value(intent.getStringExtra(EXTRA_PREFIX + "protocol"));
        endpoint.service = value(intent.getStringExtra(EXTRA_PREFIX + "service"));
        endpoint.displayName = value(intent.getStringExtra(EXTRA_PREFIX + "displayName"));
        endpoint.resourceType = value(intent.getStringExtra(EXTRA_PREFIX + "resourceType"));
        endpoint.uri = value(intent.getStringExtra(EXTRA_PREFIX + "uri"));
        endpoint.location = value(intent.getStringExtra(EXTRA_PREFIX + "location"));
        endpoint.services = value(intent.getStringExtra(EXTRA_PREFIX + "services"));
        endpoint.evidence = value(intent.getStringExtra(EXTRA_PREFIX + "evidence"));
        endpoint.banner = value(intent.getStringExtra(EXTRA_PREFIX + "banner"));
        endpoint.tlsSubject = value(intent.getStringExtra(EXTRA_PREFIX + "tlsSubject"));
        endpoint.confidence = value(intent.getStringExtra(EXTRA_PREFIX + "confidence"));
        endpoint.description = value(intent.getStringExtra(EXTRA_PREFIX + "description"));
        endpoint.latencyMs = intent.getLongExtra(EXTRA_PREFIX + "latencyMs", 0L);
        return endpoint;
    }

    public String endpointText() {
        if (uri.length() > 0) return uri;
        return host + (port > 0 ? ":" + port : "");
    }

    public static String detect(String service, int port, String evidence, String location) {
        String text = (value(service) + " " + value(evidence) + " " + value(location)).toLowerCase(Locale.US);
        if (containsAny(text, "ssdp", "upnp", "rootdevice", "gatedesc.xml", "device.xml")) return "UPNP";
        if (containsAny(text, "mdns", "dns-sd", "_services._dns-sd", "._tcp.local", "._udp.local")) return "MDNS";
        if (containsAny(text, "onvif", "ws-discovery")) return "ONVIF";
        if (containsAny(text, "https", "ssl/http", "http over tls") || port == 443 || port == 8443) return "HTTPS";
        if (containsAny(text, "http", "web app", "udpxy") || port == 80 || port == 81 || port == 8080 || port == 8000) return "HTTP";
        if (containsAny(text, "sftp")) return "SFTP";
        if (containsAny(text, "ssh") || port == 22) return "SSH";
        if (containsAny(text, "rdp", "remote desktop") || port == 3389 || port == 3390) return "RDP";
        if (containsAny(text, "vnc", "rfb") || (port >= 5900 && port <= 5999)) return "VNC";
        if (containsAny(text, "rtsp") || port == 554) return "RTSP";
        if (containsAny(text, "ftps")) return "FTPS";
        if (containsAny(text, "ftp") || port == 20 || port == 21) return "FTP";
        if (containsAny(text, "smb", "cifs") || port == 445 || port == 139) return "SMB";
        if (containsAny(text, "webdav")) return "WEBDAV";
        if (containsAny(text, "mqtt") || port == 1883 || port == 8883) return "MQTT";
        if (containsAny(text, "snmp") || port == 161 || port == 162) return "SNMP";
        if (containsAny(text, "sip") || port == 5060 || port == 5061) return "SIP";
        if (containsAny(text, "telnet") || port == 23) return "TELNET";
        if (containsAny(text, "smtp") || port == 25 || port == 465 || port == 587) return "SMTP";
        if (containsAny(text, "imap") || port == 143 || port == 993) return "IMAP";
        if (containsAny(text, "pop3") || port == 110 || port == 995) return "POP3";
        if (containsAny(text, "mysql") || port == 3306) return "MYSQL";
        if (containsAny(text, "postgres") || port == 5432) return "POSTGRESQL";
        if (containsAny(text, "redis") || port == 6379) return "REDIS";
        if (containsAny(text, "amqp") || port == 5672) return "AMQP";
        if (containsAny(text, "tls", "ssl")) return "TLS";
        return "UNKNOWN";
    }

    public static String buildUri(String protocol, String host, int port, String location) {
        if (value(location).contains("://")) return value(location);
        String h = uriHost(host);
        if (h.length() == 0) return "";
        String p = port > 0 ? ":" + port : "";
        if ("HTTPS".equals(protocol)) return "https://" + h + p + "/";
        if ("HTTP".equals(protocol) || "UPNP".equals(protocol)) return "http://" + h + p + "/";
        if ("RTSP".equals(protocol)) return "rtsp://" + h + p + "/";
        if ("SSH".equals(protocol)) return "ssh://" + h + p;
        if ("SFTP".equals(protocol)) return "sftp://" + h + p + "/";
        if ("RDP".equals(protocol)) return "rdp://" + h + p;
        if ("VNC".equals(protocol)) return "vnc://" + h + p;
        if ("FTP".equals(protocol)) return "ftp://" + h + p + "/";
        if ("FTPS".equals(protocol)) return "ftps://" + h + p + "/";
        if ("SMB".equals(protocol)) return "smb://" + h + p + "/";
        if ("WEBDAV".equals(protocol)) return "webdav://" + h + p + "/";
        if ("MQTT".equals(protocol)) return "mqtt://" + h + p;
        if ("SNMP".equals(protocol)) return "snmp://" + h + p;
        if ("SIP".equals(protocol)) return "sip:" + h + (port > 0 ? ":" + port : "");
        if ("TELNET".equals(protocol)) return "telnet://" + h + p;
        return h + p;
    }

    private static String uriHost(String host) {
        String value = value(host).trim();
        if (value.startsWith("[") && value.endsWith("]")) return value;
        if (value.indexOf(':') >= 0 && !value.contains(".")) return "[" + value + "]";
        return value;
    }

    private static boolean containsAny(String text, String... values) {
        for (String value : values) if (text.contains(value)) return true;
        return false;
    }

    private static String value(String value) { return value == null ? "" : value; }
}
