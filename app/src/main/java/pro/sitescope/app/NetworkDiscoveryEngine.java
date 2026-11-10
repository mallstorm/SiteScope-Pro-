package pro.sitescope.app;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Passive/read-only LAN discovery: SSDP/UPnP, ONVIF WS-Discovery and mDNS/DNS-SD. */
public final class NetworkDiscoveryEngine {
    private NetworkDiscoveryEngine() { }

    public interface Listener {
        void onStatus(String status);
        void onDevice(Models.DiscoveredDevice device);
        void onComplete(List<Models.DiscoveredDevice> devices, boolean cancelled, String error);
    }

    public static final class Task {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private volatile Thread thread;
        public void cancel() { cancelled.set(true); if (thread != null) thread.interrupt(); }
        public boolean isCancelled() { return cancelled.get(); }
    }

    public static Task start(final Context context, final Listener listener) {
        final Task task = new Task();
        task.thread = new Thread(new Runnable() {
            @Override public void run() {
                Map<String, Models.DiscoveredDevice> found = new LinkedHashMap<String, Models.DiscoveredDevice>();
                WifiManager.MulticastLock multicastLock = null;
                String error = "";
                try {
                    WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                    if (wifi != null) {
                        multicastLock = wifi.createMulticastLock("SiteScope-Discovery");
                        multicastLock.setReferenceCounted(false);
                        multicastLock.acquire();
                    }
                    if (!task.cancelled.get()) discoverSsdp(task, found, listener);
                    if (!task.cancelled.get()) discoverOnvif(task, found, listener);
                    if (!task.cancelled.get()) discoverMdns(task, found, listener);
                } catch (Exception unexpected) {
                    error = unexpected.getMessage() == null ? unexpected.getClass().getSimpleName() : unexpected.getMessage();
                } finally {
                    if (multicastLock != null && multicastLock.isHeld()) multicastLock.release();
                    if (listener != null) listener.onComplete(new ArrayList<Models.DiscoveredDevice>(found.values()), task.cancelled.get(), error);
                }
            }
        }, "SiteScope-LAN-Discovery");
        task.thread.start();
        return task;
    }

    private static void discoverSsdp(Task task, Map<String, Models.DiscoveredDevice> found, Listener listener) throws Exception {
        if (listener != null) listener.onStatus("SSDP/UPnP: поиск маршрутизаторов, медиасерверов и IoT…");
        String request = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: ssdp:all\r\nUSER-AGENT: SiteScopePro/0.7 UPnP/1.1\r\n\r\n";
        DatagramSocket socket = new DatagramSocket();
        try {
            socket.setSoTimeout(300);
            socket.send(new DatagramPacket(request.getBytes("UTF-8"), request.getBytes("UTF-8").length,
                InetAddress.getByName("239.255.255.250"), 1900));
            long until = System.currentTimeMillis() + 2600L;
            byte[] buffer = new byte[8192];
            while (!task.cancelled.get() && System.currentTimeMillis() < until) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try { socket.receive(packet); }
                catch (SocketTimeoutException timeout) { continue; }
                String text = new String(packet.getData(), packet.getOffset(), packet.getLength(), Charset.forName("ISO-8859-1"));
                Models.DiscoveredDevice device = new Models.DiscoveredDevice();
                device.ip = packet.getAddress().getHostAddress();
                device.port = packet.getPort();
                device.protocol = "SSDP/UPnP";
                device.location = header(text, "location");
                device.services = header(text, "st");
                String server = header(text, "server");
                String usn = header(text, "usn");
                device.name = friendlyFromUsn(usn);
                device.manufacturer = server;
                device.deviceType = classifySsdp(device.services + " " + usn + " " + server);
                device.evidence = firstLine(text) + " · ST=" + value(device.services) + " · SERVER=" + value(server);
                device.confidence = "high";
                add(found, device, listener);
            }
        } finally { socket.close(); }
    }

    private static void discoverOnvif(Task task, Map<String, Models.DiscoveredDevice> found, Listener listener) throws Exception {
        if (listener != null) listener.onStatus("ONVIF WS-Discovery: поиск камер и видеорегистраторов…");
        String uuid = UUID.randomUUID().toString();
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<e:Envelope xmlns:e=\"http://www.w3.org/2003/05/soap-envelope\" xmlns:w=\"http://schemas.xmlsoap.org/ws/2004/08/addressing\" xmlns:d=\"http://schemas.xmlsoap.org/ws/2005/04/discovery\" xmlns:dn=\"http://www.onvif.org/ver10/network/wsdl\">" +
            "<e:Header><w:MessageID>uuid:" + uuid + "</w:MessageID><w:To e:mustUnderstand=\"true\">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand=\"true\">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header>" +
            "<e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>";
        DatagramSocket socket = new DatagramSocket();
        try {
            socket.setSoTimeout(350);
            byte[] payload = xml.getBytes("UTF-8");
            socket.send(new DatagramPacket(payload, payload.length, InetAddress.getByName("239.255.255.250"), 3702));
            long until = System.currentTimeMillis() + 2300L;
            byte[] buffer = new byte[16384];
            while (!task.cancelled.get() && System.currentTimeMillis() < until) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try { socket.receive(packet); }
                catch (SocketTimeoutException timeout) { continue; }
                String response = new String(packet.getData(), packet.getOffset(), packet.getLength(), "UTF-8");
                if (!response.toLowerCase(Locale.US).contains("probematches")) continue;
                Models.DiscoveredDevice device = new Models.DiscoveredDevice();
                device.ip = packet.getAddress().getHostAddress();
                device.port = packet.getPort();
                device.protocol = "ONVIF WS-Discovery";
                device.deviceType = "IP-камера / видеорегистратор";
                device.location = xmlValue(response, "XAddrs");
                device.services = xmlValue(response, "Types");
                String scopes = xmlValue(response, "Scopes");
                device.name = scopeValue(scopes, "name");
                device.model = scopeValue(scopes, "hardware");
                device.evidence = "WS-Discovery ProbeMatch · XAddrs=" + value(device.location) + " · Scopes=" + truncate(scopes, 240);
                device.confidence = "high";
                add(found, device, listener);
            }
        } finally { socket.close(); }
    }

    private static void discoverMdns(Task task, Map<String, Models.DiscoveredDevice> found, Listener listener) throws Exception {
        if (listener != null) listener.onStatus("mDNS/DNS-SD: поиск локальных сервисов…");
        MulticastSocket socket = new MulticastSocket(5353);
        try {
            socket.setReuseAddress(true);
            socket.setSoTimeout(300);
            NetworkInterface network = chooseNetworkInterface();
            if (network != null) socket.setNetworkInterface(network);
            InetAddress group = InetAddress.getByName("224.0.0.251");
            if (network != null) socket.joinGroup(new InetSocketAddress(group, 5353), network);
            else socket.joinGroup(group);

            Set<String> serviceTypes = new LinkedHashSet<String>();
            byte[] metaQuery = dnsPtrQuery("_services._dns-sd._udp.local");
            socket.send(new DatagramPacket(metaQuery, metaQuery.length, group, 5353));
            long metaUntil = System.currentTimeMillis() + 1100L;
            byte[] buffer = new byte[12000];
            while (!task.cancelled.get() && System.currentTimeMillis() < metaUntil) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try { socket.receive(packet); }
                catch (SocketTimeoutException timeout) { continue; }
                try {
                    MdnsPacketParser.Result parsed = MdnsPacketParser.parse(packet.getData(), packet.getOffset(), packet.getLength());
                    serviceTypes.addAll(parsed.serviceTypes);
                    addMdnsRecords(parsed, packet.getAddress().getHostAddress(), found, listener);
                } catch (Exception ignored) { }
            }

            int sent = 0;
            for (String serviceType : serviceTypes) {
                if (task.cancelled.get() || sent++ >= 32) break;
                byte[] query = dnsPtrQuery(serviceType);
                socket.send(new DatagramPacket(query, query.length, group, 5353));
            }
            long until = System.currentTimeMillis() + 2600L;
            while (!task.cancelled.get() && System.currentTimeMillis() < until) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try { socket.receive(packet); }
                catch (SocketTimeoutException timeout) { continue; }
                try {
                    MdnsPacketParser.Result parsed = MdnsPacketParser.parse(packet.getData(), packet.getOffset(), packet.getLength());
                    addMdnsRecords(parsed, packet.getAddress().getHostAddress(), found, listener);
                } catch (Exception ignored) { }
            }
            try {
                if (network != null) socket.leaveGroup(new InetSocketAddress(group, 5353), network);
                else socket.leaveGroup(group);
            } catch (Exception ignored) { }
        } finally { socket.close(); }
    }

    private static void addMdnsRecords(MdnsPacketParser.Result parsed, String sourceIp, Map<String, Models.DiscoveredDevice> found, Listener listener) {
        boolean hasSrv = false;
        for (MdnsPacketParser.Record record : parsed.records) {
            if (record.type != MdnsPacketParser.TYPE_SRV || record.port <= 0) continue;
            hasSrv = true;
            Models.DiscoveredDevice device = new Models.DiscoveredDevice();
            String resolved = parsed.addressFor(record.target);
            device.ip = resolved.length() > 0 ? resolved : sourceIp;
            device.port = record.port;
            device.protocol = "mDNS/DNS-SD";
            device.name = MdnsPacketParser.instanceLabel(record.name);
            device.hostname = record.target;
            String serviceType = MdnsPacketParser.serviceTypeFromInstance(record.name);
            List<String> txt = parsed.txtFor(record.name);
            device.services = serviceType + (txt.isEmpty() ? "" : " · " + join(txt, "; ", 8));
            device.deviceType = classifyMdns(device.services);
            device.evidence = "DNS-SD SRV · " + record.target + ":" + record.port + (txt.isEmpty() ? "" : " · TXT=" + truncate(join(txt, "; ", 8), 260));
            device.confidence = "high";
            add(found, device, listener);
        }
        if (!hasSrv) {
            for (String target : parsed.ptrTargets) {
                if (target.startsWith("_") || (!target.contains("._tcp.local") && !target.contains("._udp.local"))) continue;
                Models.DiscoveredDevice device = new Models.DiscoveredDevice();
                device.ip = sourceIp;
                device.port = 5353;
                device.protocol = "mDNS/DNS-SD";
                device.name = MdnsPacketParser.instanceLabel(target);
                device.services = MdnsPacketParser.serviceTypeFromInstance(target);
                device.deviceType = classifyMdns(device.services);
                device.evidence = "DNS-SD PTR · " + target;
                device.confidence = "medium";
                add(found, device, listener);
            }
        }
    }

    private static void add(Map<String, Models.DiscoveredDevice> found, Models.DiscoveredDevice device, Listener listener) {
        String key = device.key();
        if (found.containsKey(key)) return;
        found.put(key, device);
        if (listener != null) listener.onDevice(device);
    }

    private static String classifySsdp(String raw) {
        String value = raw.toLowerCase(Locale.US);
        if (value.contains("mediaserver") || value.contains("renderer")) return "IPTV / медиасервер";
        if (value.contains("internetgateway") || value.contains("router") || value.contains("gateway")) return "Маршрутизатор / шлюз";
        if (value.contains("printer")) return "Сетевой принтер";
        if (value.contains("camera") || value.contains("video")) return "IP-камера / видеоустройство";
        return "UPnP-устройство";
    }

    private static String classifyMdns(String raw) {
        String value = raw.toLowerCase(Locale.US);
        if (value.contains("_airplay") || value.contains("_googlecast") || value.contains("_raop")) return "Медиаплеер / ТВ";
        if (value.contains("_ipp") || value.contains("_printer")) return "Сетевой принтер";
        if (value.contains("_ssh") || value.contains("_sftp")) return "Сервер / рабочая станция";
        if (value.contains("_http") || value.contains("_https")) return "Локальный веб-сервис";
        if (value.contains("_hap") || value.contains("_mqtt")) return "IoT / умный дом";
        return "mDNS-устройство";
    }

    private static String header(String text, String wanted) {
        for (String line : text.split("[\\r\\n]+")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(wanted)) return line.substring(colon + 1).trim();
        }
        return "";
    }

    private static String firstLine(String text) {
        int end = text.indexOf('\n');
        return (end < 0 ? text : text.substring(0, end)).trim();
    }

    private static String friendlyFromUsn(String usn) {
        if (usn == null) return "";
        int marker = usn.indexOf("::");
        return marker > 0 ? usn.substring(0, marker) : usn;
    }

    private static String xmlValue(String xml, String localName) {
        String lower = xml.toLowerCase(Locale.US);
        String needle = ":" + localName.toLowerCase(Locale.US) + ">";
        int start = lower.indexOf(needle);
        if (start < 0) {
            needle = "<" + localName.toLowerCase(Locale.US) + ">";
            start = lower.indexOf(needle);
        }
        if (start < 0) return "";
        start += needle.length();
        int end = lower.indexOf("</", start);
        if (end < 0) return "";
        return unescapeXml(xml.substring(start, end).trim());
    }

    private static String scopeValue(String scopes, String key) {
        if (scopes == null) return "";
        String marker = "/" + key + "/";
        for (String token : scopes.split("\\s+")) {
            int index = token.toLowerCase(Locale.US).indexOf(marker);
            if (index >= 0) return decodePercent(token.substring(index + marker.length())).replace('_', ' ');
        }
        return "";
    }

    private static byte[] dnsPtrQuery(String name) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[] {0,0,0,0,0,1,0,0,0,0,0,0});
        for (String label : name.split("\\.")) {
            byte[] bytes = label.getBytes("UTF-8");
            out.write(bytes.length); out.write(bytes);
        }
        out.write(0); out.write(0); out.write(12); out.write(0); out.write(1);
        return out.toByteArray();
    }

    private static List<String> extractDnsNames(byte[] data, int offset, int length) {
        List<String> names = new ArrayList<String>();
        int end = offset + length;
        StringBuilder current = new StringBuilder();
        for (int i = offset + 12; i < end; i++) {
            int size = data[i] & 0xff;
            if (size > 0 && size < 64 && i + size < end) {
                boolean printable = true;
                for (int j = 1; j <= size; j++) {
                    int c = data[i+j] & 0xff;
                    if (c < 32 || c > 126) { printable = false; break; }
                }
                if (printable) {
                    String label = new String(data, i + 1, size, Charset.forName("UTF-8"));
                    if (current.length() > 0) current.append('.');
                    current.append(label);
                    i += size;
                    if (label.equalsIgnoreCase("local") || label.equalsIgnoreCase("tcp") || label.equalsIgnoreCase("udp")) {
                        String name = current.toString();
                        if (name.contains("_") && !names.contains(name)) names.add(name);
                        current.setLength(0);
                    }
                    continue;
                }
            }
            if (size == 0) current.setLength(0);
        }
        return names;
    }

    private static NetworkInterface chooseNetworkInterface() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            List<NetworkInterface> list = Collections.list(interfaces);
            for (NetworkInterface item : list) {
                String name = item.getName().toLowerCase(Locale.US);
                if (item.isUp() && !item.isLoopback() && (name.startsWith("wlan") || name.startsWith("eth"))) return item;
            }
            for (NetworkInterface item : list) if (item.isUp() && !item.isLoopback() && item.supportsMulticast()) return item;
        } catch (Exception ignored) { }
        return null;
    }

    private static String bestMdnsName(List<String> names) {
        for (String name : names) if (!name.startsWith("_")) return name;
        return names.isEmpty() ? "" : names.get(0);
    }

    private static String join(List<String> values, String delimiter, int max) {
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (String value : values) {
            if (count++ >= max) break;
            if (out.length() > 0) out.append(delimiter);
            out.append(value);
        }
        return out.toString();
    }

    private static String truncate(String value, int max) { return value == null ? "" : (value.length() > max ? value.substring(0, max) : value); }
    private static String value(String value) { return value == null || value.length() == 0 ? "—" : value; }
    private static String unescapeXml(String value) { return value.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\""); }
    private static String decodePercent(String value) {
        try { return java.net.URLDecoder.decode(value, "UTF-8"); } catch (Exception ignored) { return value; }
    }
}
