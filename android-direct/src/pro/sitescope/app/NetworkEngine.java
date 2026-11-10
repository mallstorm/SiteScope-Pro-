package pro.sitescope.app;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public final class NetworkEngine {
    private NetworkEngine() { }

    public static String dnsLookup(String host) {
        StringBuilder output = new StringBuilder();
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (output.length() > 0) output.append("\n");
                output.append(address.getHostAddress());
                String reverse = address.getCanonicalHostName();
                if (reverse != null && !reverse.equals(address.getHostAddress())) output.append("  ·  ").append(reverse);
            }
        } catch (Exception error) {
            output.append("Ошибка DNS: ").append(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
        return output.toString();
    }

    public static List<Models.PortResult> scan(String host, List<Integer> ports, int timeoutMs) {
        List<Models.PortResult> results = new ArrayList<Models.PortResult>();
        for (int port : ports) {
            Models.PortResult result = new Models.PortResult();
            result.host = host;
            result.port = port;
            result.service = service(port);
            long started = System.currentTimeMillis();
            Socket socket = null;
            try {
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), timeoutMs);
                result.open = true;
                result.latencyMs = System.currentTimeMillis() - started;
                socket.setSoTimeout(300);
                if (port == 80 || port == 8080) {
                    socket.getOutputStream().write(("HEAD / HTTP/1.0\r\nHost: " + host + "\r\n\r\n").getBytes("UTF-8"));
                }
                try {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
                    String line = reader.readLine();
                    if (line != null) result.banner = line.length() > 120 ? line.substring(0, 120) : line;
                } catch (Exception ignored) { }
            } catch (Exception ignored) {
                result.open = false;
                result.latencyMs = System.currentTimeMillis() - started;
            } finally {
                try { if (socket != null) socket.close(); } catch (Exception ignored) { }
            }
            results.add(result);
        }
        return results;
    }

    public static String service(int port) {
        switch (port) {
            case 20: return "FTP data";
            case 21: return "FTP";
            case 22: return "SSH";
            case 25: return "SMTP";
            case 53: return "DNS";
            case 80: return "HTTP";
            case 110: return "POP3";
            case 143: return "IMAP";
            case 443: return "HTTPS";
            case 465: return "SMTPS";
            case 587: return "SMTP submission";
            case 993: return "IMAPS";
            case 995: return "POP3S";
            case 1433: return "MS SQL";
            case 1521: return "Oracle";
            case 2375: return "Docker API";
            case 3000: return "Web app";
            case 3306: return "MySQL";
            case 3389: return "RDP";
            case 5432: return "PostgreSQL";
            case 5900: return "VNC";
            case 6379: return "Redis";
            case 8080: return "HTTP-alt";
            case 8443: return "HTTPS-alt";
            case 9200: return "Elasticsearch";
            default: return "TCP";
        }
    }
}
