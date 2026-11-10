package pro.sitescope.app;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.Locale;

/** Lightweight read-only availability probe for saved service endpoints. */
public final class EndpointProbe {
    public static final class Result {
        public boolean reachable;
        public long latencyMs;
        public String status = "UNKNOWN";
        public String message = "";
    }

    private EndpointProbe() { }

    public static Result probe(ServiceEndpoint endpoint, int timeoutMs) {
        long started = System.currentTimeMillis();
        Result result = new Result();
        try {
            String protocol = endpoint.protocol == null ? "" : endpoint.protocol.toUpperCase(Locale.US);
            if ("SNMP".equals(protocol) || "MDNS".equals(protocol)) {
                result.reachable = false;
                result.status = "MANUAL";
                result.message = "UDP-протокол проверяется только внутри специализированного клиента";
            } else if ("HTTP".equals(protocol) || "HTTPS".equals(protocol) || "WEBDAV".equals(protocol)) {
                String uri = endpoint.uri;
                if (uri == null || uri.length() == 0) uri = ServiceEndpoint.buildUri(protocol, endpoint.host, endpoint.port, endpoint.location);
                HttpURLConnection connection = (HttpURLConnection) new URL(uri).openConnection();
                connection.setConnectTimeout(timeoutMs);
                connection.setReadTimeout(timeoutMs);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestMethod("HEAD");
                connection.setRequestProperty("User-Agent", "SiteScopePro/0.24");
                int code = connection.getResponseCode();
                result.reachable = code > 0 && code < 600;
                result.status = result.reachable ? "ONLINE" : "ERROR";
                result.message = "HTTP " + code + (connection.getResponseMessage() == null ? "" : " " + connection.getResponseMessage());
                connection.disconnect();
            } else if ("UPNP".equals(protocol) && endpoint.location != null && endpoint.location.startsWith("http")) {
                HttpURLConnection connection = (HttpURLConnection) new URL(endpoint.location).openConnection();
                connection.setConnectTimeout(timeoutMs);
                connection.setReadTimeout(timeoutMs);
                connection.setRequestMethod("GET");
                int code = connection.getResponseCode();
                InputStream input = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                if (input != null) input.close();
                result.reachable = code > 0 && code < 500;
                result.status = result.reachable ? "ONLINE" : "ERROR";
                result.message = "UPnP description HTTP " + code;
                connection.disconnect();
            } else {
                int port = endpoint.port > 0 ? endpoint.port : defaultPort(protocol);
                if (endpoint.host == null || endpoint.host.trim().length() == 0 || port <= 0) throw new IllegalArgumentException("Не указан host/port");
                Socket socket = new Socket();
                try {
                    socket.connect(new InetSocketAddress(endpoint.host, port), timeoutMs);
                    result.reachable = socket.isConnected();
                    result.status = result.reachable ? "ONLINE" : "OFFLINE";
                    result.message = result.reachable ? "TCP-соединение установлено" : "TCP-соединение не установлено";
                } finally {
                    try { socket.close(); } catch (Exception ignored) { }
                }
            }
        } catch (Exception error) {
            result.reachable = false;
            result.status = "OFFLINE";
            String text = error.getMessage();
            result.message = text == null || text.length() == 0 ? error.getClass().getSimpleName() : text;
        }
        result.latencyMs = Math.max(0L, System.currentTimeMillis() - started);
        return result;
    }

    private static int defaultPort(String protocol) {
        if ("HTTP".equals(protocol) || "WEBDAV".equals(protocol)) return 80;
        if ("HTTPS".equals(protocol)) return 443;
        if ("SSH".equals(protocol) || "SFTP".equals(protocol)) return 22;
        if ("FTP".equals(protocol)) return 21;
        if ("FTPS".equals(protocol)) return 990;
        if ("RDP".equals(protocol)) return 3389;
        if ("VNC".equals(protocol)) return 5900;
        if ("SMB".equals(protocol)) return 445;
        if ("RTSP".equals(protocol)) return 554;
        if ("MQTT".equals(protocol)) return 1883;
        if ("SNMP".equals(protocol)) return 161;
        return 0;
    }
}
