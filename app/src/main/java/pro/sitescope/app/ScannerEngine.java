package pro.sitescope.app;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.IDN;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Guarded, connect-only TCP inventory scanner for systems owned by the user or
 * covered by explicit authorization. It deliberately excludes stealth scans,
 * spoofing, authentication attempts and vulnerability exploitation.
 */
public final class ScannerEngine {
    /** Normal mobile inventory profile. */
    public static final int STANDARD_MAX_HOSTS = 4096;
    public static final int STANDARD_MAX_PORTS = 4096;
    public static final int STANDARD_MAX_PROBES = 2000000;
    /** Expanded profile is allowed only for numeric private/local targets. */
    public static final int EXTENDED_MAX_HOSTS = 65536;
    public static final int EXTENDED_MAX_PORTS = 65535;
    public static final int EXTENDED_MAX_PROBES = 10000000;
    /** Compatibility aliases used by older UI/tests. */
    public static final int MAX_HOSTS = STANDARD_MAX_HOSTS;
    public static final int MAX_PORTS = STANDARD_MAX_PORTS;
    public static final int MAX_PROBES = STANDARD_MAX_PROBES;
    public static final int MIN_CONCURRENCY = 2;
    public static final int MAX_CONCURRENCY = 250;
    public static final int MAX_DELAY_MS = 5000;
    private static final ThreadLocal<Task> CURRENT_TASK = new ThreadLocal<Task>();
    public static final int MIN_TIMEOUT_MS = 250;
    public static final int MAX_TIMEOUT_MS = 3000;

    private ScannerEngine() { }

    public static final class ScanException extends Exception {
        public ScanException(String message) { super(message); }
    }

    public static final class Config {
        public int timeoutMs = 650;
        public int concurrency = 32;
        public int delayMs = 0;
        public boolean detectServices = true;
        /** Higher limits for explicitly authorized private/local numeric ranges. */
        public boolean extendedPrivateMode = false;
        /** Resume from a deterministic probe index (host-major, then port). */
        public int startIndex = 0;

        public void normalize() {
            timeoutMs = Math.max(MIN_TIMEOUT_MS, Math.min(timeoutMs, MAX_TIMEOUT_MS));
            concurrency = Math.max(MIN_CONCURRENCY, Math.min(concurrency, MAX_CONCURRENCY));
            delayMs = Math.max(0, Math.min(delayMs, MAX_DELAY_MS));
            startIndex = Math.max(0, startIndex);
        }
    }

    public interface Listener {
        void onStarted(int hosts, int ports, int totalProbes);
        void onProgress(int completed, int total, int openCount, String currentTarget);
        void onOpen(Models.PortResult result);
        void onComplete(List<Models.PortResult> openResults, boolean cancelled, long durationMs, String error);
    }

    public static final class Task implements ProbeStartGate.Control {
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean paused = new AtomicBoolean(false);
        private final Object pauseLock = new Object();
        private volatile ExecutorService executor;
        private volatile Thread coordinator;
        private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();

        public void cancel() {
            cancelled.set(true);
            for (Socket socket : sockets) closeQuietly(socket);
            sockets.clear();
            ExecutorService pool = executor;
            if (pool != null) pool.shutdownNow();
            Thread thread = coordinator;
            if (thread != null) thread.interrupt();
        }

        public void pause() { paused.set(true); }

        public void resume() {
            paused.set(false);
            synchronized (pauseLock) { pauseLock.notifyAll(); }
        }

        private void awaitIfPaused() throws InterruptedException {
            while (paused.get() && !cancelled.get()) {
                synchronized (pauseLock) { pauseLock.wait(500L); }
            }
        }

        public boolean isPaused() { return paused.get(); }
        public boolean isCancelled() { return cancelled.get(); }
    }

    public static List<String> parseTargets(String raw) throws ScanException {
        return parseTargets(raw, STANDARD_MAX_HOSTS);
    }

    public static List<String> parseTargets(String raw, int maxHosts) throws ScanException {
        maxHosts = Math.max(1, Math.min(maxHosts, EXTENDED_MAX_HOSTS));
        if (raw == null || raw.trim().length() == 0) throw new ScanException("Укажите IP, домен, CIDR или диапазон адресов");
        Set<String> targets = new LinkedHashSet<String>();
        String[] tokens = raw.trim().split("[,;\\s]+?");
        for (String original : tokens) {
            String token = original.trim();
            if (token.length() == 0) continue;
            if (token.endsWith(".*")) {
                expandWildcard(token, targets);
            } else if (isIpv4Range(token)) {
                expandRange(token, targets, maxHosts);
            } else if (token.contains("/")) {
                expandCidr(token, targets, maxHosts);
            } else if (isIpv4(token)) {
                targets.add(token);
            } else if (token.contains(":")) {
                try {
                    InetAddress address = InetAddress.getByName(token);
                    targets.add(address.getHostAddress());
                } catch (Exception error) {
                    throw new ScanException("Некорректный IPv6-адрес: " + token);
                }
            } else {
                String ascii;
                try { ascii = IDN.toASCII(token, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.US); }
                catch (Exception error) { throw new ScanException("Некорректный домен: " + token); }
                if (!ascii.matches("(?i)^[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?$")) {
                    throw new ScanException("Некорректный домен: " + token);
                }
                targets.add(ascii);
            }
            if (targets.size() > maxHosts) throw new ScanException("Диапазон превышает лимит выбранного режима: " + maxHosts + " узлов");
        }
        if (targets.isEmpty()) throw new ScanException("Не удалось распознать цель сканирования");
        return new ArrayList<String>(targets);
    }

    public static List<Integer> parsePorts(String raw) throws ScanException {
        return parsePorts(raw, STANDARD_MAX_PORTS);
    }

    public static List<Integer> parsePorts(String raw, int maxPorts) throws ScanException {
        maxPorts = Math.max(1, Math.min(maxPorts, EXTENDED_MAX_PORTS));
        if (raw == null || raw.trim().length() == 0) throw new ScanException("Укажите порты, например 22,80,443 или 1-800");
        TreeSet<Integer> ports = new TreeSet<Integer>();
        for (String original : raw.trim().split("[,;\\s]+")) {
            String token = original.trim().toLowerCase(Locale.US);
            if (token.length() == 0) continue;
            if ("web".equals(token)) token = "80,443,8080,8443";
            else if ("remote".equals(token)) token = "22,3389,3390,5900";
            else if ("mail".equals(token)) token = "25,110,143,465,587,993,995";
            if (token.contains(",")) {
                for (String nested : token.split(",")) addPortToken(nested, ports, maxPorts);
            } else {
                addPortToken(token, ports, maxPorts);
            }
            if (ports.size() > maxPorts) throw new ScanException("Слишком много портов для выбранного режима: максимум " + maxPorts);
        }
        if (ports.isEmpty()) throw new ScanException("Не удалось распознать список портов");
        return new ArrayList<Integer>(ports);
    }

    private static void addPortToken(String token, Set<Integer> ports, int maxPorts) throws ScanException {
        if (token.contains("-")) {
            String[] parts = token.split("-", -1);
            if (parts.length != 2) throw new ScanException("Некорректный диапазон портов: " + token);
            int start = parsePort(parts[0]);
            int end = parsePort(parts[1]);
            if (end < start) throw new ScanException("Конец диапазона меньше начала: " + token);
            if (end - start + 1 > maxPorts) throw new ScanException("Диапазон портов превышает " + maxPorts);
            for (int port = start; port <= end; port++) {
                ports.add(port);
                if (ports.size() > maxPorts) throw new ScanException("Слишком много портов для выбранного режима: максимум " + maxPorts);
            }
        } else {
            ports.add(parsePort(token));
        }
    }

    private static int parsePort(String value) throws ScanException {
        try {
            int port = Integer.parseInt(value.trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
            return port;
        } catch (Exception error) {
            throw new ScanException("Некорректный порт: " + value);
        }
    }

    public static Task start(final List<String> hosts, final List<Integer> ports, final Config config, final Listener listener) throws ScanException {
        if (hosts == null || hosts.isEmpty()) throw new ScanException("Список узлов пуст");
        if (ports == null || ports.isEmpty()) throw new ScanException("Список портов пуст");
        config.normalize();
        final int maxHosts = config.extendedPrivateMode ? EXTENDED_MAX_HOSTS : STANDARD_MAX_HOSTS;
        final int maxPorts = config.extendedPrivateMode ? EXTENDED_MAX_PORTS : STANDARD_MAX_PORTS;
        final int maxProbes = config.extendedPrivateMode ? EXTENDED_MAX_PROBES : STANDARD_MAX_PROBES;
        if (hosts.size() > maxHosts) throw new ScanException("Максимум " + maxHosts + " узлов для выбранного режима");
        if (ports.size() > maxPorts) throw new ScanException("Максимум " + maxPorts + " портов для выбранного режима");
        final long totalLong = (long) hosts.size() * (long) ports.size();
        if (totalLong > maxProbes) throw new ScanException("Задание содержит " + totalLong + " проверок; предел выбранного режима — " + maxProbes);
        if (config.extendedPrivateMode && (hosts.size() > STANDARD_MAX_HOSTS || ports.size() > STANDARD_MAX_PORTS || totalLong > STANDARD_MAX_PROBES) && !allTargetsPrivateOrLocal(hosts)) {
            throw new ScanException("Расширенный объём разрешён только для числовых приватных/локальных IP-адресов. Для публичных адресов используйте обычный режим и отдельные задания.");
        }
        final int total = (int) totalLong;
        if (config.startIndex > total) config.startIndex = total;
        final Task task = new Task();
        task.coordinator = new Thread(new Runnable() {
            @Override public void run() {
                long started = System.currentTimeMillis();
                List<Models.PortResult> openResults = Collections.synchronizedList(new ArrayList<Models.PortResult>());
                AtomicInteger openCount = new AtomicInteger(0);
                String error = "";
                boolean cancelled = false;
                ExecutorService pool = Executors.newFixedThreadPool(config.concurrency);
                task.executor = pool;
                CompletionService<Models.PortResult> completion = new ExecutorCompletionService<Models.PortResult>(pool);
                final ProbeStartGate gate = new ProbeStartGate(config.delayMs);
                ConcurrentHashMap<String, HostInfo> hostCache = new ConcurrentHashMap<String, HostInfo>();
                int submitted = config.startIndex;
                int completed = config.startIndex;
                int window = config.concurrency;
                long lastUiUpdate = 0L;
                try {
                    if (listener != null) listener.onStarted(hosts.size(), ports.size(), total);
                    while (!task.cancelled.get() && (completed < total)) {
                        task.awaitIfPaused();
                        while (!task.cancelled.get() && !task.paused.get() && submitted < total && submitted - completed < window) {
                            final int index = submitted++;
                            final String host = hosts.get(index / ports.size());
                            final int port = ports.get(index % ports.size());
                            completion.submit(new java.util.concurrent.Callable<Models.PortResult>() {
                                @Override public Models.PortResult call() throws InterruptedException {
                                    gate.awaitTurn(task);
                                    CURRENT_TASK.set(task);
                                    try { return probe(host, port, config, hostCache); }
                                    finally { CURRENT_TASK.remove(); }
                                }
                            });
                        }
                        if (task.paused.get()) continue;
                        Future<Models.PortResult> future = completion.take();
                        Models.PortResult result = null;
                        try { result = future.get(); } catch (Exception ignored) { }
                        completed++;
                        if (result != null && result.open) {
                            openResults.add(result);
                            openCount.incrementAndGet();
                            if (listener != null) listener.onOpen(result);
                        }
                        long now = System.currentTimeMillis();
                        if (listener != null && (completed == total || completed % 20 == 0 || now - lastUiUpdate > 300)) {
                            int currentIndex = Math.min(Math.max(completed - 1, 0), total - 1);
                            String current = hosts.get(currentIndex / ports.size()) + ":" + ports.get(currentIndex % ports.size());
                            listener.onProgress(completed, total, openCount.get(), current);
                            lastUiUpdate = now;
                        }
                    }
                    cancelled = task.cancelled.get();
                } catch (InterruptedException interrupted) {
                    cancelled = true;
                    Thread.currentThread().interrupt();
                } catch (Exception unexpected) {
                    error = unexpected.getMessage() == null ? unexpected.getClass().getSimpleName() : unexpected.getMessage();
                } finally {
                    pool.shutdownNow();
                    task.executor = null;
                    Collections.sort(openResults, new Comparator<Models.PortResult>() {
                        @Override public int compare(Models.PortResult a, Models.PortResult b) {
                            int ip = compareAddress(a.ip, b.ip);
                            if (ip != 0) return ip;
                            return a.port - b.port;
                        }
                    });
                    if (listener != null) listener.onComplete(new ArrayList<Models.PortResult>(openResults), cancelled, System.currentTimeMillis() - started, error);
                }
            }
        }, "SiteScope-Scanner-Coordinator");
        task.coordinator.start();
        return task;
    }

    private static Models.PortResult probe(String target, int port, Config config, ConcurrentHashMap<String, HostInfo> hostCache) {
        Models.PortResult result = new Models.PortResult();
        result.target = target;
        result.host = target;
        result.port = port;
        long started = System.currentTimeMillis();
        Socket socket = null;
        byte[] passive = new byte[0];
        try {
            socket = connect(target, port, config.timeoutMs);
            result.open = true;
            result.latencyMs = Math.max(1, System.currentTimeMillis() - started);
            result.ip = socket.getInetAddress().getHostAddress();
            passive = readSome(socket, 512, 220);
        } catch (Exception closed) {
            result.open = false;
            result.latencyMs = Math.max(1, System.currentTimeMillis() - started);
            return result;
        } finally {
            closeQuietly(socket);
        }

        HostInfo info = hostCache.get(result.ip);
        if (info == null) {
            info = resolveHostInfo(result.ip);
            HostInfo previous = hostCache.putIfAbsent(result.ip, info);
            if (previous != null) info = previous;
        }
        result.hostname = info.hostname;

        Fingerprint fingerprint = classifyPassive(passive);
        if (fingerprint == null && config.detectServices) fingerprint = detectProtocol(target, port, config.timeoutMs);
        if (fingerprint == null) {
            fingerprint = new Fingerprint();
            fingerprint.service = serviceHint(port);
            fingerprint.confidence = "low";
            fingerprint.evidence = "TCP connect: порт принимает соединение; протокол не подтвердил сигнатуру";
        }
        result.service = fingerprint.service;
        result.banner = fingerprint.banner;
        result.tlsSubject = fingerprint.tlsSubject;
        result.evidence = fingerprint.evidence;
        result.confidence = fingerprint.confidence;
        result.resourceType = resourceType(result, info.publicAddress);
        result.description = serviceDescription(result.service);
        return result;
    }

    private static Fingerprint detectProtocol(String host, int port, int timeoutMs) {
        Fingerprint fingerprint;
        // A passive SSH/SMTP/etc. banner was already checked. RDP is tested early so
        // custom ports such as 3390 can be recognized by protocol, not by number.
        fingerprint = probeRdp(host, port, timeoutMs);
        if (fingerprint != null) return fingerprint;
        fingerprint = probeRtsp(host, port, timeoutMs);
        if (fingerprint != null) return fingerprint;
        fingerprint = probeSip(host, port, timeoutMs);
        if (fingerprint != null) return fingerprint;
        fingerprint = probeMqtt(host, port, timeoutMs);
        if (fingerprint != null) return fingerprint;
        fingerprint = probeMemcached(host, port, timeoutMs);
        if (fingerprint != null) return fingerprint;
        fingerprint = probeAmqp(host, port, timeoutMs);
        if (fingerprint != null) return fingerprint;
        fingerprint = probeUdpxyService(host, port, timeoutMs);
        if (fingerprint != null) return fingerprint;
        fingerprint = probeTls(host, port, timeoutMs);
        if (fingerprint != null) return fingerprint;
        fingerprint = probeHttp(host, port, timeoutMs, false);
        if (fingerprint != null) return fingerprint;
        fingerprint = probeRedis(host, port, timeoutMs);
        if (fingerprint != null) return fingerprint;
        fingerprint = probePostgres(host, port, timeoutMs);
        if (fingerprint != null) return fingerprint;
        return null;
    }

    private static Fingerprint classifyPassive(byte[] data) {
        if (data == null || data.length == 0) return null;
        String text = printable(data, 300).trim();
        String upper = text.toUpperCase(Locale.US);
        Fingerprint result = new Fingerprint();
        result.banner = text;
        result.confidence = "high";
        if (upper.startsWith("SSH-")) {
            result.service = "SSH";
            result.evidence = "Сервер передал стандартный SSH identification banner";
            return result;
        }
        if (upper.startsWith("RFB ")) {
            result.service = "VNC";
            result.evidence = "Обнаружена сигнатура RFB/VNC";
            return result;
        }
        if ((upper.startsWith("220") || upper.contains("ESMTP")) && (upper.contains("SMTP") || upper.contains("ESMTP") || upper.contains("MAIL"))) {
            result.service = "SMTP";
            result.evidence = "Почтовый сервер передал SMTP/ESMTP greeting";
            return result;
        }
        if (upper.startsWith("220") && upper.contains("FTP")) {
            result.service = "FTP";
            result.evidence = "Сервер передал FTP greeting";
            return result;
        }
        if (upper.startsWith("+OK")) {
            result.service = "POP3";
            result.evidence = "Обнаружен POP3 greeting +OK";
            return result;
        }
        if (upper.startsWith("* OK") || upper.contains("IMAP4")) {
            result.service = "IMAP";
            result.evidence = "Обнаружен IMAP greeting";
            return result;
        }
        if (data.length > 5 && (data[4] & 0xff) == 10) {
            result.service = "MySQL";
            result.evidence = "Получен MySQL initial handshake protocol 10";
            return result;
        }
        return null;
    }

    private static Fingerprint probeHttp(String host, int port, int timeoutMs, boolean tls) {
        Socket socket = null;
        try {
            if (tls) return null;
            socket = connect(host, port, timeoutMs);
            OutputStream output = socket.getOutputStream();
            output.write(("HEAD / HTTP/1.0\r\nHost: " + host + "\r\nUser-Agent: SiteScopePro/0.4\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
            output.flush();
            byte[] response = readSome(socket, 1200, Math.min(timeoutMs, 900));
            String text = printable(response, 1000).trim();
            if (!text.toUpperCase(Locale.US).startsWith("HTTP/")) return null;
            Fingerprint result = new Fingerprint();
            result.service = "HTTP";
            result.banner = firstLines(text, 4);
            result.evidence = "Получен корректный HTTP status line";
            result.confidence = "high";
            return result;
        } catch (Exception ignored) {
            return null;
        } finally { closeQuietly(socket); }
    }


    private static Fingerprint probeRtsp(String host, int port, int timeoutMs) {
        Socket socket = null;
        try {
            socket = connect(host, port, timeoutMs);
            String request = "OPTIONS rtsp://" + host + ":" + port + "/ RTSP/1.0\r\nCSeq: 1\r\nUser-Agent: SiteScopePro/0.5\r\n\r\n";
            socket.getOutputStream().write(request.getBytes("ISO-8859-1"));
            socket.getOutputStream().flush();
            String response = printable(readSome(socket, 1200, Math.min(timeoutMs, 850)), 1000).trim();
            if (response.toUpperCase(Locale.US).startsWith("RTSP/1.0")) {
                Fingerprint result = new Fingerprint();
                result.service = "RTSP";
                result.banner = firstLines(response, 5);
                result.evidence = "Получен корректный RTSP status line на безопасный OPTIONS";
                result.confidence = "high";
                return result;
            }
        } catch (Exception ignored) { }
        finally { closeQuietly(socket); }
        return null;
    }

    private static Fingerprint probeSip(String host, int port, int timeoutMs) {
        Socket socket = null;
        try {
            socket = connect(host, port, timeoutMs);
            String branch = "z9hG4bK-sitescope" + System.currentTimeMillis();
            String request = "OPTIONS sip:" + host + " SIP/2.0\r\n" +
                "Via: SIP/2.0/TCP 127.0.0.1;branch=" + branch + "\r\n" +
                "From: <sip:sitescope@localhost>;tag=probe\r\n" +
                "To: <sip:" + host + ">\r\n" +
                "Call-ID: " + System.nanoTime() + "@sitescope\r\n" +
                "CSeq: 1 OPTIONS\r\nMax-Forwards: 0\r\nContent-Length: 0\r\n\r\n";
            socket.getOutputStream().write(request.getBytes("ISO-8859-1"));
            socket.getOutputStream().flush();
            String response = printable(readSome(socket, 1400, Math.min(timeoutMs, 900)), 1200).trim();
            if (response.toUpperCase(Locale.US).startsWith("SIP/2.0")) {
                Fingerprint result = new Fingerprint();
                result.service = "SIP";
                result.banner = firstLines(response, 6);
                result.evidence = "SIP endpoint ответил на безопасный OPTIONS";
                result.confidence = "high";
                return result;
            }
        } catch (Exception ignored) { }
        finally { closeQuietly(socket); }
        return null;
    }

    private static Fingerprint probeMqtt(String host, int port, int timeoutMs) {
        Socket socket = null;
        try {
            socket = connect(host, port, timeoutMs);
            byte[] client = ("sitescope-" + Long.toHexString(System.nanoTime())).getBytes("UTF-8");
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(new byte[]{0x00,0x04,'M','Q','T','T',0x04,0x02,0x00,0x02});
            body.write((client.length >> 8) & 0xff); body.write(client.length & 0xff); body.write(client);
            ByteArrayOutputStream packet = new ByteArrayOutputStream();
            packet.write(0x10);
            int remaining = body.size();
            do { int encoded = remaining % 128; remaining /= 128; if (remaining > 0) encoded |= 128; packet.write(encoded); } while (remaining > 0);
            packet.write(body.toByteArray());
            socket.getOutputStream().write(packet.toByteArray());
            socket.getOutputStream().flush();
            byte[] response = readSome(socket, 16, Math.min(timeoutMs, 850));
            if (response.length >= 4 && (response[0] & 0xff) == 0x20 && (response[1] & 0xff) == 0x02) {
                Fingerprint result = new Fingerprint();
                result.service = "MQTT";
                result.banner = "CONNACK return-code=" + (response[3] & 0xff);
                result.evidence = "MQTT broker вернул CONNACK на чистую сессию без публикации/подписки";
                result.confidence = "high";
                return result;
            }
        } catch (Exception ignored) { }
        finally { closeQuietly(socket); }
        return null;
    }

    private static Fingerprint probeMemcached(String host, int port, int timeoutMs) {
        Socket socket = null;
        try {
            socket = connect(host, port, timeoutMs);
            socket.getOutputStream().write("version\r\n".getBytes("ISO-8859-1"));
            socket.getOutputStream().flush();
            String response = printable(readSome(socket, 256, Math.min(timeoutMs, 700)), 220).trim();
            if (response.toUpperCase(Locale.US).startsWith("VERSION ")) {
                Fingerprint result = new Fingerprint();
                result.service = "Memcached";
                result.banner = response;
                result.evidence = "Memcached ответил на безопасную команду version";
                result.confidence = "high";
                return result;
            }
        } catch (Exception ignored) { }
        finally { closeQuietly(socket); }
        return null;
    }

    private static Fingerprint probeAmqp(String host, int port, int timeoutMs) {
        Socket socket = null;
        try {
            socket = connect(host, port, timeoutMs);
            socket.getOutputStream().write(new byte[]{'A','M','Q','P',0x00,0x00,0x09,0x01});
            socket.getOutputStream().flush();
            byte[] response = readSome(socket, 16, Math.min(timeoutMs, 700));
            if (response.length >= 4 && response[0] == 'A' && response[1] == 'M' && response[2] == 'Q' && response[3] == 'P') {
                Fingerprint result = new Fingerprint();
                result.service = "AMQP";
                result.banner = hex(response, response.length);
                result.evidence = "AMQP broker вернул заголовок поддерживаемой версии протокола";
                result.confidence = "high";
                return result;
            }
        } catch (Exception ignored) { }
        finally { closeQuietly(socket); }
        return null;
    }

    private static Fingerprint probeUdpxyService(String host, int port, int timeoutMs) {
        Socket socket = null;
        try {
            socket = connect(host, port, timeoutMs);
            String request = "GET /status/ HTTP/1.0\r\nHost: " + host + "\r\nUser-Agent: SiteScopePro-IPTV/0.5\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes("ISO-8859-1"));
            socket.getOutputStream().flush();
            String response = printable(readSome(socket, 8192, Math.min(timeoutMs, 1000)), 7600).trim();
            String upper = response.toUpperCase(Locale.US);
            if (upper.contains("UDPXY") || upper.contains("UDP-TO-HTTP") || (upper.contains("ACTIVE CLIENTS") && upper.contains("MULTICAST"))) {
                Fingerprint result = new Fingerprint();
                result.service = "udpxy";
                result.banner = firstLines(response, 8);
                result.evidence = "HTTP status page содержит сигнатуру UDP-to-HTTP multicast relay";
                result.confidence = "high";
                return result;
            }
        } catch (Exception ignored) { }
        finally { closeQuietly(socket); }
        return null;
    }

    private static Fingerprint probeTls(String host, int port, int timeoutMs) {
        SSLSocket socket = null;
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new X509TrustManager() {
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new SecureRandom());
            SSLSocketFactory factory = context.getSocketFactory();
            socket = (SSLSocket) factory.createSocket();
            trackSocket(socket);
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(Math.min(timeoutMs, 1000));
            socket.startHandshake();
            SSLSession session = socket.getSession();
            String subject = "";
            try {
                Certificate[] certificates = session.getPeerCertificates();
                if (certificates.length > 0 && certificates[0] instanceof X509Certificate) {
                    subject = ((X509Certificate) certificates[0]).getSubjectX500Principal().getName();
                }
            } catch (Exception ignored) { }
            OutputStream output = socket.getOutputStream();
            output.write(("HEAD / HTTP/1.0\r\nHost: " + host + "\r\nUser-Agent: SiteScopePro/0.4\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
            output.flush();
            byte[] response = readSome(socket, 1000, 600);
            String text = printable(response, 900).trim();
            Fingerprint result = new Fingerprint();
            result.tlsSubject = subject;
            result.confidence = "high";
            if (text.toUpperCase(Locale.US).startsWith("HTTP/")) {
                result.service = "HTTPS";
                result.banner = firstLines(text, 4);
                result.evidence = "TLS handshake и HTTP status line подтверждены";
            } else {
                result.service = "TLS";
                result.banner = subject;
                result.evidence = "Успешный TLS handshake; доверие сертификату отдельно не подтверждалось";
            }
            return result;
        } catch (Exception ignored) {
            return null;
        } finally { closeQuietly(socket); }
    }

    private static Fingerprint probeRdp(String host, int port, int timeoutMs) {
        Socket socket = null;
        try {
            socket = connect(host, port, timeoutMs);
            byte[] request = new byte[]{
                0x03,0x00,0x00,0x13,0x0e,(byte)0xe0,0x00,0x00,0x00,0x00,0x00,
                0x01,0x00,0x08,0x00,0x03,0x00,0x00,0x00
            };
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            byte[] response = readSome(socket, 64, Math.min(timeoutMs, 850));
            if (response.length >= 7 && (response[0] & 0xff) == 0x03 && (response[1] & 0xff) == 0x00 && ((response[5] & 0xff) == 0xd0 || (response[5] & 0xff) == 0xf0)) {
                Fingerprint result = new Fingerprint();
                result.service = "RDP";
                result.banner = hex(response, Math.min(response.length, 24));
                result.evidence = "Сервер ответил на TPKT/X.224 RDP Negotiation Request";
                result.confidence = "high";
                return result;
            }
        } catch (Exception ignored) { }
        finally { closeQuietly(socket); }
        return null;
    }

    private static Fingerprint probeRedis(String host, int port, int timeoutMs) {
        Socket socket = null;
        try {
            socket = connect(host, port, timeoutMs);
            socket.getOutputStream().write("*1\r\n$4\r\nPING\r\n".getBytes("ISO-8859-1"));
            socket.getOutputStream().flush();
            String response = printable(readSome(socket, 128, Math.min(timeoutMs, 650)), 120).trim();
            if (response.toUpperCase(Locale.US).startsWith("+PONG") || response.toUpperCase(Locale.US).contains("REDIS")) {
                Fingerprint result = new Fingerprint();
                result.service = "Redis";
                result.banner = response;
                result.evidence = "Redis protocol ответил на безопасную команду PING";
                result.confidence = "high";
                return result;
            }
        } catch (Exception ignored) { }
        finally { closeQuietly(socket); }
        return null;
    }

    private static Fingerprint probePostgres(String host, int port, int timeoutMs) {
        Socket socket = null;
        try {
            socket = connect(host, port, timeoutMs);
            byte[] request = new byte[]{0x00,0x00,0x00,0x08,0x04,(byte)0xd2,0x16,0x2f};
            socket.getOutputStream().write(request);
            socket.getOutputStream().flush();
            byte[] response = readSome(socket, 8, Math.min(timeoutMs, 650));
            if (response.length > 0 && (response[0] == 'S' || response[0] == 'N')) {
                Fingerprint result = new Fingerprint();
                result.service = "PostgreSQL";
                result.banner = response[0] == 'S' ? "SSL supported" : "SSL not supported";
                result.evidence = "PostgreSQL server ответил на SSLRequest";
                result.confidence = "high";
                return result;
            }
        } catch (Exception ignored) { }
        finally { closeQuietly(socket); }
        return null;
    }

    private static Socket connect(String host, int port, int timeoutMs) throws Exception {
        Socket socket = new Socket();
        try {
            trackSocket(socket);
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            socket.setSoTimeout(Math.min(timeoutMs, 1000));
            return socket;
        } catch (Exception failure) {
            closeQuietly(socket);
            throw failure;
        }
    }

    private static void trackSocket(Socket socket) throws java.net.SocketException {
        Task task = CURRENT_TASK.get();
        if (task == null) return;
        task.sockets.add(socket);
        if (task.cancelled.get() || Thread.currentThread().isInterrupted()) {
            task.sockets.remove(socket);
            closeQuietly(socket);
            throw new java.net.SocketException("Проверка остановлена");
        }
    }

    private static byte[] readSome(Socket socket, int maxBytes, int timeoutMs) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            socket.setSoTimeout(Math.max(120, timeoutMs));
            InputStream input = socket.getInputStream();
            byte[] buffer = new byte[Math.min(512, maxBytes)];
            int read = input.read(buffer);
            if (read > 0) output.write(buffer, 0, read);
            while (output.size() < maxBytes && input.available() > 0) {
                read = input.read(buffer, 0, Math.min(buffer.length, maxBytes - output.size()));
                if (read <= 0) break;
                output.write(buffer, 0, read);
            }
        } catch (Exception ignored) { }
        return output.toByteArray();
    }

    private static HostInfo resolveHostInfo(String ip) {
        HostInfo info = new HostInfo();
        info.ip = ip;
        try {
            InetAddress address = InetAddress.getByName(ip);
            info.publicAddress = !(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress());
            String canonical = address.getCanonicalHostName();
            if (canonical != null && !canonical.equals(ip)) info.hostname = canonical;
        } catch (Exception ignored) { }
        return info;
    }

    private static String resourceType(Models.PortResult result, boolean publicAddress) {
        String service = result.service.toUpperCase(Locale.US);
        String host = result.hostname == null ? "" : result.hostname.toLowerCase(Locale.US);
        boolean serverNamed = host.contains("vps") || host.contains("server") || host.contains("cloud") || host.contains("node") || host.contains("host");
        if (service.equals("RDP") || service.equals("SSH") || service.equals("VNC")) {
            return publicAddress || serverNamed ? "VPS / удалённый сервер" : "Сервер локальной сети";
        }
        if (service.equals("UDPXY") || service.equals("RTSP")) return "IPTV / медиасервер";
        if (service.equals("SIP")) return "VoIP / SIP-сервер";
        if (service.equals("MQTT") || service.equals("AMQP")) return "Брокер сообщений / IoT";
        if (service.equals("MEMCACHED")) return "Кэш / инфраструктурный сервис";
        if (service.equals("HTTP") || service.equals("HTTPS") || service.equals("TLS")) return "Веб-ресурс / сервер";
        if (service.contains("SMTP") || service.contains("IMAP") || service.contains("POP3")) return "Почтовый сервер";
        if (service.equals("FTP")) return "Файловый сервер";
        if (service.equals("MYSQL") || service.equals("POSTGRESQL") || service.equals("MS SQL") || service.equals("ORACLE")) return "Сервер базы данных";
        if (service.equals("REDIS") || service.equals("ELASTICSEARCH")) return "Инфраструктурный сервис";
        if (service.equals("DNS")) return "DNS-сервер";
        return publicAddress ? "Публичный TCP-ресурс" : "Сетевой узел";
    }

    private static String serviceDescription(String service) {
        String value = service.toUpperCase(Locale.US);
        if (value.equals("RDP")) return "Удалённый рабочий стол Windows или совместимый RDP-шлюз";
        if (value.equals("SSH")) return "Защищённое удалённое управление сервером";
        if (value.equals("VNC")) return "Удалённый графический доступ";
        if (value.equals("HTTP")) return "Веб-сервис без TLS";
        if (value.equals("HTTPS")) return "Веб-сервис с TLS";
        if (value.equals("TLS")) return "Сервис использует TLS, прикладной протокол не определён";
        if (value.equals("SMTP")) return "Приём или передача электронной почты";
        if (value.equals("FTP")) return "Передача файлов по FTP";
        if (value.equals("MYSQL")) return "Сервер базы данных MySQL/MariaDB";
        if (value.equals("POSTGRESQL")) return "Сервер базы данных PostgreSQL";
        if (value.equals("REDIS")) return "Хранилище данных Redis";
        if (value.equals("UDPXY")) return "UDP/RTP multicast-to-HTTP relay для IPTV";
        if (value.equals("RTSP")) return "Сервер потокового видео по RTSP";
        if (value.equals("SIP")) return "Сигнальный сервер VoIP/SIP";
        if (value.equals("MQTT")) return "Брокер телеметрии и IoT MQTT";
        if (value.equals("AMQP")) return "Брокер сообщений AMQP";
        if (value.equals("MEMCACHED")) return "Распределённый in-memory кэш Memcached";
        return "Открытый TCP-сервис; назначение уточняется по баннеру и конфигурации владельца";
    }

    private static String serviceHint(int port) {
        switch (port) {
            case 20: return "FTP data";
            case 21: return "FTP";
            case 22: return "SSH";
            case 23: return "Telnet";
            case 25: return "SMTP";
            case 53: return "DNS";
            case 80: return "HTTP";
            case 81: return "HTTP-alt / IPTV";
            case 110: return "POP3";
            case 143: return "IMAP";
            case 443: return "HTTPS";
            case 554: return "RTSP";
            case 445: return "SMB";
            case 465: return "SMTPS";
            case 587: return "SMTP submission";
            case 993: return "IMAPS";
            case 995: return "POP3S";
            case 1433: return "MS SQL";
            case 1521: return "Oracle";
            case 1883: return "MQTT";
            case 2375: return "Docker API";
            case 4022: return "udpxy";
            case 5060: return "SIP";
            case 5672: return "AMQP";
            case 3000: return "Web app";
            case 3306: return "MySQL";
            case 3389: return "RDP";
            case 5432: return "PostgreSQL";
            case 5900: return "VNC";
            case 6379: return "Redis";
            case 11211: return "Memcached";
            case 8080: return "HTTP-alt";
            case 8443: return "HTTPS-alt";
            case 9200: return "Elasticsearch";
            default: return "Unknown TCP";
        }
    }

    private static void expandWildcard(String token, Set<String> output) throws ScanException {
        String prefix = token.substring(0, token.length() - 1);
        if (!prefix.matches("^(?:\\d{1,3}\\.){3}$")) throw new ScanException("Поддерживается маска вида 192.168.1.*");
        for (int i = 1; i <= 254; i++) {
            String ip = prefix + i;
            if (!isIpv4(ip)) throw new ScanException("Некорректная wildcard-маска: " + token);
            output.add(ip);
        }
    }

    private static boolean isIpv4Range(String token) {
        return token.matches("^(?:\\d{1,3}\\.){3}\\d{1,3}-(?:(?:\\d{1,3}\\.){3})?\\d{1,3}$");
    }

    private static void expandRange(String token, Set<String> output, int maxHosts) throws ScanException {
        String[] parts = token.split("-", 2);
        String startText = parts[0];
        String endText = parts[1];
        if (!endText.contains(".")) endText = startText.substring(0, startText.lastIndexOf('.') + 1) + endText;
        if (!isIpv4(startText) || !isIpv4(endText)) throw new ScanException("Некорректный IP-диапазон: " + token);
        long start = ipv4ToLong(startText);
        long end = ipv4ToLong(endText);
        if (end < start) throw new ScanException("Конец IP-диапазона меньше начала: " + token);
        if (end - start + 1 > maxHosts) throw new ScanException("IP-диапазон превышает " + maxHosts + " узлов для выбранного режима");
        for (long value = start; value <= end; value++) output.add(longToIpv4(value));
    }

    private static void expandCidr(String token, Set<String> output, int maxHosts) throws ScanException {
        String[] parts = token.split("/", 2);
        if (parts.length != 2 || !isIpv4(parts[0])) throw new ScanException("Мобильный CIDR-сканер поддерживает IPv4, например 192.168.1.0/24");
        int prefix;
        if (parts[1].contains(".")) prefix = prefixFromMask(parts[1]);
        else {
            try { prefix = Integer.parseInt(parts[1]); }
            catch (Exception error) { throw new ScanException("Некорректная маска: " + parts[1]); }
        }
        if (prefix < 0 || prefix > 32) throw new ScanException("Префикс CIDR должен быть от /0 до /32");
        long count = 1L << (32 - prefix);
        long usable = prefix <= 30 ? count - 2 : count;
        if (usable > maxHosts) throw new ScanException("CIDR содержит " + usable + " узлов; выбранный режим допускает " + maxHosts);
        long ip = ipv4ToLong(parts[0]);
        long mask = prefix == 0 ? 0 : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
        long network = ip & mask;
        long first = prefix <= 30 ? network + 1 : network;
        long last = prefix <= 30 ? network + count - 2 : network + count - 1;
        for (long value = first; value <= last; value++) output.add(longToIpv4(value));
    }

    public static boolean allTargetsPrivateOrLocal(List<String> hosts) {
        if (hosts == null || hosts.isEmpty()) return false;
        for (String host : hosts) {
            if (!isPrivateOrLocalLiteral(host)) return false;
        }
        return true;
    }

    private static boolean isPrivateOrLocalLiteral(String host) {
        if (host == null) return false;
        String value = host.trim().toLowerCase(Locale.US);
        if (isIpv4(value)) {
            try {
                long ip = ipv4ToLong(value);
                return inRange(ip, "10.0.0.0", 8)
                    || inRange(ip, "172.16.0.0", 12)
                    || inRange(ip, "192.168.0.0", 16)
                    || inRange(ip, "127.0.0.0", 8)
                    || inRange(ip, "169.254.0.0", 16)
                    || inRange(ip, "100.64.0.0", 10);
            } catch (ScanException ignored) {
                return false;
            }
        }
        return "::1".equals(value) || value.startsWith("fe80:") || value.startsWith("fc") || value.startsWith("fd");
    }

    private static boolean inRange(long ip, String networkText, int prefix) throws ScanException {
        long network = ipv4ToLong(networkText);
        long mask = prefix == 0 ? 0 : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
        return (ip & mask) == (network & mask);
    }

    private static int prefixFromMask(String maskText) throws ScanException {
        if (!isIpv4(maskText)) throw new ScanException("Некорректная маска сети: " + maskText);
        long mask = ipv4ToLong(maskText);
        boolean zeroSeen = false;
        int prefix = 0;
        for (int bit = 31; bit >= 0; bit--) {
            boolean one = ((mask >> bit) & 1L) == 1L;
            if (one && zeroSeen) throw new ScanException("Маска сети должна быть непрерывной: " + maskText);
            if (one) prefix++; else zeroSeen = true;
        }
        return prefix;
    }

    private static boolean isIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) {
            try {
                if (part.length() == 0 || part.length() > 3) return false;
                int number = Integer.parseInt(part);
                if (number < 0 || number > 255) return false;
            } catch (Exception error) { return false; }
        }
        return true;
    }

    private static long ipv4ToLong(String value) throws ScanException {
        if (!isIpv4(value)) throw new ScanException("Некорректный IPv4: " + value);
        long result = 0;
        for (String part : value.split("\\.")) result = (result << 8) | Integer.parseInt(part);
        return result & 0xffffffffL;
    }

    private static String longToIpv4(long value) {
        return ((value >> 24) & 255) + "." + ((value >> 16) & 255) + "." + ((value >> 8) & 255) + "." + (value & 255);
    }

    private static int compareAddress(String a, String b) {
        try {
            if (isIpv4(a) && isIpv4(b)) return Long.compare(ipv4ToLong(a), ipv4ToLong(b));
        } catch (Exception ignored) { }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private static String printable(byte[] bytes, int max) {
        StringBuilder result = new StringBuilder();
        int count = Math.min(bytes.length, max);
        for (int i = 0; i < count; i++) {
            int value = bytes[i] & 0xff;
            if (value == '\r' || value == '\n' || value == '\t') result.append((char) value);
            else if (value >= 32 && value < 127) result.append((char) value);
            else result.append('.');
        }
        return result.toString().replaceAll("[\\r\\n]+", " | ").replaceAll("\\s{2,}", " ").trim();
    }

    private static String firstLines(String text, int maxLines) {
        String[] lines = text.split("[\\r\\n]+");
        StringBuilder result = new StringBuilder();
        int used = 0;
        for (String line : lines) {
            if (line.trim().length() == 0) continue;
            if (result.length() > 0) result.append(" | ");
            result.append(line.trim());
            if (++used >= maxLines) break;
        }
        return result.length() > 360 ? result.substring(0, 360) : result.toString();
    }

    private static String hex(byte[] bytes, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) result.append(' ');
            result.append(String.format(Locale.US, "%02X", bytes[i] & 0xff));
        }
        return result.toString();
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        Task task = CURRENT_TASK.get();
        if (task != null) task.sockets.remove(socket);
        try { socket.close(); } catch (Exception ignored) { }
    }

    private static final class Fingerprint {
        String service = "Unknown TCP";
        String banner = "";
        String tlsSubject = "";
        String evidence = "";
        String confidence = "low";
    }

    private static final class HostInfo {
        String ip = "";
        String hostname = "";
        boolean publicAddress;
    }
}
