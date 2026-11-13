package pro.sitescope.app;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.DatagramPacket;
import java.net.MulticastSocket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * IPTV inventory helpers for networks owned by the user or explicitly covered
 * by authorization. The engine parses user-provided M3U/XMLTV data, detects
 * udpxy endpoints in private networks and passively inspects a bounded prefix
 * of selected streams. It never bypasses authentication or DRM.
 */
public final class IptvEngine {
    public static final int MAX_CHANNELS = 5000;
    public static final int MAX_DISCOVERY_PORTS = 16;
    public static final int MAX_PLAYLIST_BYTES = 4 * 1024 * 1024;
    public static final int MAX_PROBE_BYTES = 768 * 1024;

    private static final Pattern ATTR = Pattern.compile("([A-Za-z0-9_-]+)\\s*=\\s*\"([^\"]*)\"");
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    private IptvEngine() { }

    public static final class ProbeResult {
        public boolean playable;
        public boolean mpegTs;
        public boolean hls;
        public String serviceName = "";
        public String providerName = "";
        public String videoCodec = "";
        public String audioCodec = "";
        public String container = "";
        public String evidence = "";
        public String error = "";
        public long latencyMs;
        public long bitrateKbps;
        public byte[] sample;        // собранный MPEG-TS (для снятия кадра), заполняется MulticastProbe
        public int httpCode;
        public String contentType = "";
    }

    public interface SapListener {
        void onChannel(Models.IptvChannel channel);
        void onStatus(String status);
    }

    public interface DiscoveryListener {
        void onProgress(long completed, long total, String current);
        void onFound(Models.UdpxyEndpoint endpoint);
        default boolean isCancelled() { return Thread.currentThread().isInterrupted(); }
    }

    public static List<Models.IptvChannel> parseM3u(String text) throws Exception {
        if (text == null || text.trim().length() == 0) throw new Exception("Плейлист пуст");
        List<Models.IptvChannel> channels = new ArrayList<Models.IptvChannel>();
        String pendingName = "";
        String pendingId = "";
        String pendingLogo = "";
        String pendingGroup = "";
        String[] lines = text.replace("\r", "").split("\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.length() == 0) continue;
            if (line.regionMatches(true, 0, "#EXTINF", 0, 7)) {
                pendingId = attribute(line, "tvg-id");
                pendingLogo = attribute(line, "tvg-logo");
                pendingGroup = attribute(line, "group-title");
                String tvgName = attribute(line, "tvg-name");
                int comma = line.lastIndexOf(',');
                pendingName = comma >= 0 ? line.substring(comma + 1).trim() : tvgName;
                if (pendingName.length() == 0) pendingName = tvgName;
            } else if (line.regionMatches(true, 0, "#EXTGRP:", 0, 8)) {
                pendingGroup = line.substring(8).trim();
            } else if (!line.startsWith("#")) {
                if (!isSupportedStreamUrl(line)) continue;
                Models.IptvChannel channel = new Models.IptvChannel();
                channel.id = pendingId;
                channel.name = pendingName.length() > 0 ? pendingName : "Канал " + (channels.size() + 1);
                channel.logoUrl = pendingLogo;
                channel.group = pendingGroup;
                channel.sourceUrl = normalizeStreamUrl(line);
                channel.playbackUrl = channel.sourceUrl;
                channel.sourceType = sourceType(channel.sourceUrl);
                channels.add(channel);
                pendingName = pendingId = pendingLogo = pendingGroup = "";
                if (channels.size() >= MAX_CHANNELS) break;
            }
        }
        if (channels.isEmpty()) throw new Exception("В плейлисте не найдено поддерживаемых HTTP/HLS/UDP/RTP потоков");
        return channels;
    }

    public static void applyXmlTv(String xml, List<Models.IptvChannel> channels) throws Exception {
        if (xml == null || xml.trim().length() == 0 || channels == null || channels.isEmpty()) return;
        Map<String, XmlTvChannel> index = new HashMap<String, XmlTvChannel>();
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new java.io.StringReader(xml));
        int event = parser.getEventType();
        XmlTvChannel current = null;
        String activeTag = "";
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                activeTag = parser.getName();
                if ("channel".equals(activeTag)) {
                    current = new XmlTvChannel();
                    current.id = safe(parser.getAttributeValue(null, "id"));
                } else if (current != null && "icon".equals(activeTag)) {
                    current.icon = safe(parser.getAttributeValue(null, "src"));
                }
            } else if (event == XmlPullParser.TEXT && current != null && "display-name".equals(activeTag)) {
                String value = parser.getText() == null ? "" : parser.getText().trim();
                if (current.name.length() == 0 && value.length() > 0) current.name = value;
            } else if (event == XmlPullParser.END_TAG) {
                String name = parser.getName();
                if ("channel".equals(name) && current != null) {
                    if (current.id.length() > 0) index.put(current.id, current);
                    current = null;
                }
                activeTag = "";
            }
            event = parser.next();
        }
        for (Models.IptvChannel channel : channels) {
            XmlTvChannel meta = index.get(channel.id);
            if (meta == null) continue;
            if ((channel.name.length() == 0 || channel.name.startsWith("Канал ")) && meta.name.length() > 0) channel.name = meta.name;
            if (channel.logoUrl.length() == 0 && meta.icon.length() > 0) channel.logoUrl = meta.icon;
        }
    }

    public static String downloadText(String url, int timeoutMs) throws Exception {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) throw new Exception("Поддерживаются только HTTP/HTTPS URL плейлистов");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(timeoutMs * 2);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "SiteScopePro-IPTV/0.5");
            connection.setRequestProperty("Accept", "application/x-mpegURL,application/xml,text/xml,text/plain,*/*");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 400) throw new Exception("HTTP " + code);
            byte[] bytes = readLimited(connection.getInputStream(), MAX_PLAYLIST_BYTES, 8000L);
            return decodeText(bytes, connection.getContentType());
        } finally {
            if (connection != null) connection.disconnect();
        }
    }


    public static List<Models.IptvChannel> discoverSap(int durationSeconds, SapListener listener) throws Exception {
        int seconds = Math.max(2, Math.min(durationSeconds, 30));
        Map<String, Models.IptvChannel> found = new LinkedHashMap<String, Models.IptvChannel>();
        MulticastSocket socket = new MulticastSocket(9875);
        InetAddress group = InetAddress.getByName("224.2.127.254");
        try {
            socket.setReuseAddress(true);
            socket.setSoTimeout(650);
            socket.joinGroup(group);
            long until = System.currentTimeMillis() + seconds * 1000L;
            if (listener != null) listener.onStatus("Прослушивание SAP/SDP " + seconds + " сек.");
            byte[] buffer = new byte[65507];
            while (System.currentTimeMillis() < until) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);
                    Models.IptvChannel channel = parseSapPacket(packet.getData(), packet.getOffset(), packet.getLength());
                    if (channel != null) {
                        String key = channel.sourceUrl + "|" + channel.name;
                        if (!found.containsKey(key)) {
                            found.put(key, channel);
                            if (listener != null) listener.onChannel(channel);
                        }
                    }
                } catch (java.net.SocketTimeoutException ignored) { }
            }
        } finally {
            try { socket.leaveGroup(group); } catch (Exception ignored) { }
            socket.close();
        }
        if (listener != null) listener.onStatus("SAP завершён: " + found.size() + " объявлений");
        return new ArrayList<Models.IptvChannel>(found.values());
    }

    private static Models.IptvChannel parseSapPacket(byte[] data, int offset, int length) {
        try {
            if (length < 12) return null;
            int first = data[offset] & 0xff;
            int version = (first >> 5) & 0x07;
            if (version != 1) return null;
            boolean ipv6 = (first & 0x10) != 0;
            boolean encrypted = (first & 0x02) != 0;
            if (encrypted) return null;
            int authWords = data[offset + 1] & 0xff;
            int originLength = ipv6 ? 16 : 4;
            int payload = offset + 4 + originLength + authWords * 4;
            int end = offset + length;
            if (payload >= end) return null;
            int zero = -1;
            for (int i = payload; i < Math.min(end, payload + 64); i++) if (data[i] == 0) { zero = i; break; }
            if (zero > payload) {
                String mime = new String(data, payload, zero - payload, LATIN1).trim().toLowerCase(Locale.US);
                if (!mime.contains("application/sdp")) return null;
                payload = zero + 1;
            }
            if (payload >= end) return null;
            String sdp = new String(data, payload, end - payload, Charset.forName("UTF-8"));
            String name = "";
            String address = "";
            int mediaPort = -1;
            String logo = "";
            String groupName = "SAP/SDP";
            for (String raw : sdp.replace("\r", "").split("\n")) {
                String line = raw.trim();
                if (line.startsWith("s=")) name = line.substring(2).trim();
                else if (line.startsWith("c=")) {
                    Matcher match = Pattern.compile("(?i)c=IN\\s+IP[46]\\s+([^/\\s]+)").matcher(line);
                    if (match.find()) address = match.group(1);
                } else if (line.startsWith("m=video") || line.startsWith("m=audio")) {
                    Matcher match = Pattern.compile("m=(?:video|audio)\\s+(\\d+)").matcher(line);
                    if (match.find() && mediaPort < 0) mediaPort = Integer.parseInt(match.group(1));
                } else if (line.startsWith("a=x-logo:") || line.startsWith("a=logo:")) logo = line.substring(line.indexOf(':') + 1).trim();
                else if (line.startsWith("a=cat:")) groupName = line.substring(6).trim();
            }
            if (address.length() == 0 || mediaPort < 1 || !isMulticastAddress(address)) return null;
            Models.IptvChannel channel = new Models.IptvChannel();
            channel.name = name.length() > 0 ? name : "SAP " + address + ":" + mediaPort;
            channel.group = groupName;
            channel.logoUrl = logo;
            channel.sourceUrl = "rtp://@" + address + ":" + mediaPort;
            channel.playbackUrl = channel.sourceUrl;
            channel.sourceType = "SAP/SDP RTP multicast";
            channel.status = "объявлен в локальной сети";
            channel.evidence = "SAP/SDP announcement";
            return channel;
        } catch (Exception ignored) { return null; }
    }

    public static List<Models.UdpxyEndpoint> discoverUdpxy(List<String> hosts, List<Integer> ports, int timeoutMs, DiscoveryListener listener) throws Exception {
        if (hosts == null || hosts.isEmpty()) throw new Exception("Список узлов пуст");
        return discoverUdpxy(hosts, hosts.size(), ports, timeoutMs, listener);
    }

    public static List<Models.UdpxyEndpoint> discoverUdpxy(IptvTargets hosts, List<Integer> ports, int timeoutMs, DiscoveryListener listener) throws Exception {
        if (hosts == null || hosts.count() == 0) throw new Exception("Список узлов пуст");
        return discoverUdpxy(hosts, hosts.count(), ports, timeoutMs, listener);
    }

    public static List<Models.UdpxyEndpoint> discoverUdpxy(IptvTargets hosts, List<Integer> ports, int timeoutMs, int threads, long delayMs, DiscoveryListener listener) throws Exception {
        if (hosts == null || hosts.count() == 0) throw new IllegalArgumentException("Список узлов пуст");
        return discoverUdpxy(hosts, hosts.count(), ports, timeoutMs, threads, delayMs, listener);
    }

    private static List<Models.UdpxyEndpoint> discoverUdpxy(Iterable<String> hosts, long hostCount, List<Integer> ports, int timeoutMs, DiscoveryListener listener) throws Exception {
        return discoverUdpxy(hosts, hostCount, ports, timeoutMs, 8, 0, listener);
    }

    private static boolean cancelled(DiscoveryListener listener) {
        return Thread.currentThread().isInterrupted() || (listener != null && listener.isCancelled());
    }

    private static List<Models.UdpxyEndpoint> discoverUdpxy(Iterable<String> hosts, long hostCount, List<Integer> ports, int timeoutMs, int threads, long delayMs, DiscoveryListener listener) throws Exception {
        if (ports == null || ports.isEmpty()) throw new IllegalArgumentException("Список портов пуст");
        if (ports.size() > MAX_DISCOVERY_PORTS) throw new IllegalArgumentException("IPTV-обнаружение ограничено " + MAX_DISCOVERY_PORTS + " портами");
        if (threads < 1 || threads > ScannerEngine.MAX_CONCURRENCY || delayMs < 0) throw new IllegalArgumentException("Проверьте число потоков и задержку");
        List<Models.UdpxyEndpoint> results = new ArrayList<>();
        long total = Math.multiplyExact(hostCount, (long) ports.size());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        java.util.concurrent.CompletionService<DiscoveryResult> finished = new java.util.concurrent.ExecutorCompletionService<>(pool);
        final ProbeStartGate gate = new ProbeStartGate(delayMs);
        final ProbeStartGate.Control control = new ProbeStartGate.Control() {
            @Override public boolean isCancelled() { return cancelled(listener); }
            @Override public boolean isPaused() { return false; }
        };
        java.util.Iterator<String> iterator = hosts.iterator();
        long submitted = 0, completed = 0;
        int inFlight = 0, portIndex = 0;
        String host = null;
        try {
            while (completed < total && !cancelled(listener)) {
                // At most one job per worker is retained; never enqueue the whole range.
                while (inFlight < threads && submitted < total && !cancelled(listener)) {
                    if (portIndex == 0) {
                        host = iterator.next();
                        if (!isPrivateOrLocalHost(host)) throw new IllegalArgumentException("Автоматическое обнаружение udpxy разрешено только в локальных/частных сетях: " + host);
                    }
                    final String target = host;
                    final int port = ports.get(portIndex);
                    portIndex = (portIndex + 1) % ports.size();
                    finished.submit(() -> {
                        gate.awaitTurn(control);
                        return new DiscoveryResult(target, port, probeUdpxy(target, port, timeoutMs, listener));
                    });
                    inFlight++; submitted++;
                }
                if (cancelled(listener)) break;
                java.util.concurrent.Future<DiscoveryResult> future = finished.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (future == null) continue;
                DiscoveryResult result = future.get();
                inFlight--; completed++;
                if (cancelled(listener)) break;
                if (listener != null) listener.onProgress(completed, total, result.host + ":" + result.port);
                if (result.endpoint != null && result.endpoint.confirmed) {
                    results.add(result.endpoint);
                    if (listener != null) listener.onFound(result.endpoint);
                }
            }
        } finally { pool.shutdownNow(); }
        return results;
    }

    private static final class DiscoveryResult {
        final String host; final int port; final Models.UdpxyEndpoint endpoint;
        DiscoveryResult(String host, int port, Models.UdpxyEndpoint endpoint) { this.host = host; this.port = port; this.endpoint = endpoint; }
    }

    public static Models.UdpxyEndpoint probeUdpxy(String host, int port, int timeoutMs) {
        return probeUdpxy(host, port, timeoutMs, null);
    }

    private static Models.UdpxyEndpoint probeUdpxy(String host, int port, int timeoutMs, DiscoveryListener listener) {
        String[] paths = {"/status/", "/status", "/"};
        for (String path : paths) {
            if (cancelled(listener)) return null;
            HttpURLConnection connection = null;
            long started = System.currentTimeMillis();
            try {
                URL url = new URL("http", host, port, path);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(timeoutMs);
                connection.setReadTimeout(timeoutMs);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "SiteScopePro-IPTV/0.5");
                int code = connection.getResponseCode();
                String server = safe(connection.getHeaderField("Server"));
                byte[] body = readLimited(code >= 400 ? connection.getErrorStream() : connection.getInputStream(), 96 * 1024, timeoutMs + 300L);
                String text = decodeText(body, connection.getContentType());
                String haystack = (server + "\n" + text).toLowerCase(Locale.US);
                boolean signature = haystack.contains("udpxy") || haystack.contains("udp-to-http") ||
                    (haystack.contains("active clients") && haystack.contains("multicast"));
                if (signature) {
                    Models.UdpxyEndpoint endpoint = new Models.UdpxyEndpoint();
                    endpoint.host = host;
                    endpoint.port = port;
                    endpoint.baseUrl = "http://" + formatHost(host) + ":" + port;
                    endpoint.statusUrl = endpoint.baseUrl + path;
                    endpoint.server = server;
                    endpoint.version = extractVersion(text + " " + server);
                    endpoint.evidence = "HTTP " + code + " · сигнатура udpxy на " + path;
                    endpoint.latencyMs = Math.max(1L, System.currentTimeMillis() - started);
                    endpoint.confirmed = true;
                    return endpoint;
                }
            } catch (Exception ignored) { }
            finally { if (connection != null) connection.disconnect(); }
        }
        return null;
    }

    public static String buildPlaybackUrl(String udpxyBase, String sourceUrl) throws Exception {
        if (sourceUrl == null) throw new Exception("Поток не задан");
        String source = normalizeStreamUrl(sourceUrl.trim());
        String lower = source.toLowerCase(Locale.US);
        if (lower.startsWith("http://") || lower.startsWith("https://")) return source;
        if (!(lower.startsWith("udp://") || lower.startsWith("rtp://"))) throw new Exception("Неизвестный протокол потока");
        MulticastSupport.Source multicast = MulticastSupport.parse(source);
        if (udpxyBase == null || udpxyBase.trim().length() == 0) return source;
        String cmd = multicast.rtp ? "rtp" : "udp";
        String base = udpxyBase.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base + "/" + cmd + "/" + multicast.host + ":" + multicast.port;
    }

    public static ProbeResult probeChannel(String playbackUrl, int timeoutMs) {
        ProbeResult result = new ProbeResult();
        HttpURLConnection connection = null;
        long started = System.currentTimeMillis();
        try {
            if (playbackUrl == null || !(playbackUrl.startsWith("http://") || playbackUrl.startsWith("https://"))) {
                throw new Exception("Для проверки требуется HTTP/HTTPS URL");
            }
            connection = (HttpURLConnection) new URL(playbackUrl).openConnection();
            connection.setConnectTimeout(timeoutMs);
            connection.setReadTimeout(Math.max(timeoutMs, 2500));
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "SiteScopePro-IPTV/0.5");
            connection.setRequestProperty("Accept", "application/vnd.apple.mpegurl,video/mp2t,video/*,*/*");
            int code = connection.getResponseCode();
            result.httpCode = code;
            result.contentType = safe(connection.getContentType());
            if (code < 200 || code >= 400) throw new Exception("HTTP " + code);
            long readStarted = System.currentTimeMillis();
            byte[] data = readLimited(connection.getInputStream(), MAX_PROBE_BYTES, 4200L);
            long elapsed = Math.max(1L, System.currentTimeMillis() - readStarted);
            result.latencyMs = Math.max(1L, readStarted - started);
            result.bitrateKbps = Math.max(0L, (data.length * 8L) / elapsed);
            String prefix = new String(data, 0, Math.min(data.length, 4096), LATIN1).trim();
            boolean hls = prefix.startsWith("#EXTM3U") || result.contentType.toLowerCase(Locale.US).contains("mpegurl");
            if (hls) {
                result.playable = true;
                result.hls = true;
                result.container = "HLS";
                result.evidence = "Получен HLS manifest · HTTP " + code;
                return result;
            }
            int sync = findTsSync(data);
            if (sync >= 0) {
                result.playable = true;
                result.mpegTs = true;
                result.container = "MPEG-TS";
                TsInfo info = parseTs(data, sync);
                result.serviceName = info.serviceName;
                result.providerName = info.providerName;
                result.videoCodec = info.videoCodec;
                result.audioCodec = info.audioCodec;
                result.evidence = "MPEG-TS sync 0x47 подтверждён" + (info.sdtFound ? " · DVB-SDT найден" : "");
                return result;
            }
            if (result.contentType.toLowerCase(Locale.US).startsWith("video/") || result.contentType.toLowerCase(Locale.US).startsWith("audio/")) {
                result.playable = true;
                result.container = result.contentType;
                result.evidence = "Медиапоток подтверждён Content-Type";
                return result;
            }
            throw new Exception("Ответ не похож на HLS или MPEG-TS");
        } catch (Exception error) {
            result.error = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            result.evidence = "Поток не подтверждён";
        } finally {
            if (connection != null) connection.disconnect();
        }
        return result;
    }


    public static void fillMpegTsProbe(ProbeResult result, byte[] data, String container, String evidencePrefix) throws Exception {
        int sync = findTsSync(data);
        if (sync < 0) throw new Exception("Получены UDP/RTP пакеты, но MPEG-TS sync 0x47 не подтверждён");
        result.playable = true;
        result.mpegTs = true;
        result.container = container;
        TsInfo info = parseTs(data, sync);
        result.serviceName = info.serviceName;
        result.providerName = info.providerName;
        result.videoCodec = info.videoCodec;
        result.audioCodec = info.audioCodec;
        result.evidence = evidencePrefix + " · MPEG-TS sync" + (info.sdtFound ? " · DVB-SDT" : "");
    }

    public static void applyProbe(Models.IptvChannel channel, ProbeResult probe) {
        channel.playable = probe.playable;
        channel.mpegTs = probe.mpegTs;
        channel.status = probe.playable ? "доступен" : "ошибка";
        channel.serviceName = probe.serviceName;
        channel.providerName = probe.providerName;
        channel.videoCodec = probe.videoCodec;
        channel.audioCodec = probe.audioCodec;
        channel.container = probe.container;
        channel.evidence = probe.evidence;
        channel.error = probe.error;
        channel.latencyMs = probe.latencyMs;
        channel.bitrateKbps = probe.bitrateKbps;
        if ((channel.name.length() == 0 || channel.name.startsWith("Канал ")) && probe.serviceName.length() > 0) channel.name = probe.serviceName;
    }

    public static boolean isPrivateOrLocalHost(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) return false;
            for (InetAddress address : addresses) {
                if (!(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress())) return false;
            }
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private static boolean isMulticastAddress(String host) {
        try { return InetAddress.getByName(host).isMulticastAddress(); }
        catch (Exception error) { return false; }
    }

    private static String sourceType(String url) {
        String lower = url.toLowerCase(Locale.US);
        if (lower.startsWith("udp://")) return "UDP multicast";
        if (lower.startsWith("rtp://")) return "RTP multicast";
        if (lower.contains(".m3u8")) return "HLS";
        return "HTTP stream";
    }

    private static boolean isSupportedStreamUrl(String value) {
        String lower = value.toLowerCase(Locale.US);
        return lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("udp://") || lower.startsWith("rtp://");
    }

    private static String normalizeStreamUrl(String value) {
        String result = value.trim();
        if (result.regionMatches(true, 0, "udp://", 0, 6) && !result.regionMatches(true, 0, "udp://@", 0, 7)) {
            result = "udp://@" + result.substring(6);
        } else if (result.regionMatches(true, 0, "rtp://", 0, 6) && !result.regionMatches(true, 0, "rtp://@", 0, 7)) {
            result = "rtp://@" + result.substring(6);
        }
        return result;
    }

    private static String attribute(String line, String key) {
        Matcher matcher = ATTR.matcher(line);
        while (matcher.find()) if (key.equalsIgnoreCase(matcher.group(1))) return matcher.group(2).trim();
        return "";
    }

    private static String extractVersion(String value) {
        Matcher matcher = Pattern.compile("(?i)udpxy(?:[/\\s-]+)([0-9][A-Za-z0-9._-]*)").matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static byte[] readLimited(InputStream input, int limit, long maxDurationMs) throws Exception {
        if (input == null) return new byte[0];
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 64 * 1024));
        byte[] buffer = new byte[8192];
        long started = System.currentTimeMillis();
        while (output.size() < limit && System.currentTimeMillis() - started < maxDurationMs) {
            int count = input.read(buffer, 0, Math.min(buffer.length, limit - output.size()));
            if (count < 0) break;
            if (count == 0) continue;
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String decodeText(byte[] bytes, String contentType) {
        if (bytes == null) return "";
        String type = contentType == null ? "" : contentType.toLowerCase(Locale.US);
        try {
            Matcher charset = Pattern.compile("charset=([^; ]+)").matcher(type);
            if (charset.find()) return new String(bytes, Charset.forName(charset.group(1).replace("\"", "")));
        } catch (Exception ignored) { }
        try { return new String(bytes, "UTF-8"); }
        catch (Exception ignored) { return new String(bytes, LATIN1); }
    }

    private static int findTsSync(byte[] data) {
        if (data == null || data.length < 188 * 4) return -1;
        int maxStart = Math.min(188, data.length - 188 * 4);
        for (int offset = 0; offset < maxStart; offset++) {
            boolean good = true;
            for (int i = 0; i < 4; i++) if ((data[offset + i * 188] & 0xff) != 0x47) { good = false; break; }
            if (good) return offset;
        }
        return -1;
    }

    private static TsInfo parseTs(byte[] data, int syncOffset) {
        Map<Integer, SectionAssembler> sections = new LinkedHashMap<Integer, SectionAssembler>();
        for (int offset = syncOffset; offset + 188 <= data.length; offset += 188) {
            if ((data[offset] & 0xff) != 0x47) continue;
            boolean payloadStart = (data[offset + 1] & 0x40) != 0;
            int pid = ((data[offset + 1] & 0x1f) << 8) | (data[offset + 2] & 0xff);
            int adaptation = (data[offset + 3] >> 4) & 0x03;
            if (adaptation == 0 || adaptation == 2) continue;
            int index = offset + 4;
            if (adaptation == 3) {
                int length = data[index] & 0xff;
                index += 1 + length;
            }
            if (index >= offset + 188) continue;
            SectionAssembler assembler = sections.get(pid);
            if (assembler == null) { assembler = new SectionAssembler(); sections.put(pid, assembler); }
            assembler.feed(data, index, offset + 188, payloadStart);
        }
        TsInfo info = new TsInfo();
        byte[] pat = section(sections, 0);
        Map<Integer, Integer> programs = parsePat(pat);
        byte[] sdt = section(sections, 0x11);
        parseSdt(sdt, info);
        for (Integer pmtPid : programs.values()) parsePmt(section(sections, pmtPid), info);
        return info;
    }

    private static byte[] section(Map<Integer, SectionAssembler> map, int pid) {
        SectionAssembler assembler = map.get(pid);
        return assembler == null ? null : assembler.section();
    }

    private static Map<Integer, Integer> parsePat(byte[] section) {
        Map<Integer, Integer> programs = new LinkedHashMap<Integer, Integer>();
        if (section == null || section.length < 12 || (section[0] & 0xff) != 0x00) return programs;
        int sectionLength = ((section[1] & 0x0f) << 8) | (section[2] & 0xff);
        int end = Math.min(section.length, 3 + sectionLength - 4);
        for (int pos = 8; pos + 4 <= end; pos += 4) {
            int program = ((section[pos] & 0xff) << 8) | (section[pos + 1] & 0xff);
            int pid = ((section[pos + 2] & 0x1f) << 8) | (section[pos + 3] & 0xff);
            if (program != 0) programs.put(program, pid);
        }
        return programs;
    }

    private static void parseSdt(byte[] section, TsInfo info) {
        if (section == null || section.length < 16) return;
        int table = section[0] & 0xff;
        if (table != 0x42 && table != 0x46) return;
        int sectionLength = ((section[1] & 0x0f) << 8) | (section[2] & 0xff);
        int end = Math.min(section.length, 3 + sectionLength - 4);
        int pos = 11;
        while (pos + 5 <= end) {
            int descriptorLength = ((section[pos + 3] & 0x0f) << 8) | (section[pos + 4] & 0xff);
            int dpos = pos + 5;
            int dend = Math.min(end, dpos + descriptorLength);
            while (dpos + 2 <= dend) {
                int tag = section[dpos] & 0xff;
                int len = section[dpos + 1] & 0xff;
                if (dpos + 2 + len > dend) break;
                if (tag == 0x48 && len >= 3) {
                    int p = dpos + 3;
                    int providerLength = section[p] & 0xff;
                    p++;
                    if (p + providerLength <= dpos + 2 + len) {
                        info.providerName = decodeDvb(section, p, providerLength);
                        p += providerLength;
                        if (p < dpos + 2 + len) {
                            int serviceLength = section[p] & 0xff;
                            p++;
                            if (p + serviceLength <= dpos + 2 + len) info.serviceName = decodeDvb(section, p, serviceLength);
                        }
                    }
                    info.sdtFound = true;
                }
                dpos += 2 + len;
            }
            pos = dend;
        }
    }

    private static void parsePmt(byte[] section, TsInfo info) {
        if (section == null || section.length < 16 || (section[0] & 0xff) != 0x02) return;
        int sectionLength = ((section[1] & 0x0f) << 8) | (section[2] & 0xff);
        int end = Math.min(section.length, 3 + sectionLength - 4);
        int programInfoLength = ((section[10] & 0x0f) << 8) | (section[11] & 0xff);
        int pos = 12 + programInfoLength;
        while (pos + 5 <= end) {
            int streamType = section[pos] & 0xff;
            int infoLength = ((section[pos + 3] & 0x0f) << 8) | (section[pos + 4] & 0xff);
            String codec = streamTypeName(streamType);
            if (isVideoType(streamType) && info.videoCodec.length() == 0) info.videoCodec = codec;
            else if (isAudioType(streamType) && info.audioCodec.length() == 0) info.audioCodec = codec;
            pos += 5 + infoLength;
        }
    }

    private static boolean isVideoType(int type) {
        return type == 0x01 || type == 0x02 || type == 0x10 || type == 0x1b || type == 0x24 || type == 0x42;
    }

    private static boolean isAudioType(int type) {
        return type == 0x03 || type == 0x04 || type == 0x0f || type == 0x11 || type == 0x81 || type == 0x87;
    }

    private static String streamTypeName(int type) {
        switch (type) {
            case 0x01: return "MPEG-1 Video";
            case 0x02: return "MPEG-2 Video";
            case 0x03: return "MPEG-1 Audio";
            case 0x04: return "MPEG-2 Audio";
            case 0x0f: return "AAC";
            case 0x10: return "MPEG-4 Video";
            case 0x11: return "AAC LATM";
            case 0x1b: return "H.264/AVC";
            case 0x24: return "H.265/HEVC";
            case 0x42: return "AVS";
            case 0x81: return "AC-3";
            case 0x87: return "E-AC-3";
            default: return "stream type 0x" + Integer.toHexString(type).toUpperCase(Locale.US);
        }
    }

    private static String decodeDvb(byte[] data, int offset, int length) {
        if (length <= 0) return "";
        try {
            int start = offset;
            Charset charset = LATIN1;
            if ((data[offset] & 0xff) == 0x15 && length > 1) { charset = Charset.forName("UTF-8"); start++; length--; }
            String value = new String(data, start, length, charset);
            return value.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
        } catch (Exception error) { return ""; }
    }

    private static String formatHost(String host) { return host != null && host.contains(":") ? "[" + host + "]" : host; }
    private static String safe(String value) { return value == null ? "" : value.trim(); }

    private static final class XmlTvChannel { String id = ""; String name = ""; String icon = ""; }
    private static final class TsInfo { String serviceName = ""; String providerName = ""; String videoCodec = ""; String audioCodec = ""; boolean sdtFound; }

    private static final class SectionAssembler {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private int expected = -1;
        private boolean complete;

        void feed(byte[] packet, int start, int end, boolean payloadStart) {
            if (complete || start >= end) return;
            int index = start;
            if (payloadStart) {
                int pointer = packet[index] & 0xff;
                index = start + 1 + pointer;
                output.reset();
                expected = -1;
            }
            while (index < end && !complete) {
                if (output.size() == 0 && (packet[index] & 0xff) == 0xff) return;
                output.write(packet[index++]);
                byte[] current = output.toByteArray();
                if (expected < 0 && current.length >= 3) {
                    int sectionLength = ((current[1] & 0x0f) << 8) | (current[2] & 0xff);
                    expected = 3 + sectionLength;
                    if (expected < 3 || expected > 4096) { output.reset(); expected = -1; return; }
                }
                if (expected > 0 && output.size() >= expected) complete = true;
            }
        }

        byte[] section() {
            if (!complete) return null;
            byte[] all = output.toByteArray();
            if (expected > 0 && all.length > expected) {
                byte[] exact = new byte[expected];
                System.arraycopy(all, 0, exact, 0, expected);
                return exact;
            }
            return all;
        }
    }
}
