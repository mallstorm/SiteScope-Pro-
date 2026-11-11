package pro.sitescope.app;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import com.v2ray.ang.service.TProxyService;

import java.io.EOFException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

/** Owns the embedded Xray core and the HEV TUN-to-SOCKS bridge. */
public final class XrayRuntime {
    private static final long PROBE_TOTAL_TIMEOUT_MS = 30000L;
    private CoreController controller;
    private TProxyService tunnel;
    private final Context context;
    private final Object probeLock = new Object();
    private volatile Socket activeProbeSocket;
    private volatile Thread activeProbeThread;
    private volatile boolean stopping;
    private volatile boolean started;
    private String lastConfig = "";

    public XrayRuntime(Context context) { this.context = context.getApplicationContext(); }

    public synchronized void start(ParcelFileDescriptor vpnInterface, String config) throws Exception {
        stop();
        stopping = false;
        started = false;
        XrayAssets.prepare(context);
        VpnDiagnostics.log(context, "XRAY_CORE_START", "initializing embedded core");
        controller = Libv2ray.newCoreController(callbacks());
        try {
            registerFinder(controller);
            controller.startLoop(config, 0);
            lastConfig = config;
            if (!controller.getIsRunning()) throw new IllegalStateException("Xray core не перешёл в рабочее состояние");
            VpnDiagnostics.log(context, "XRAY_CORE_READY", "embedded core is running");

            tunnel = new TProxyService(context, vpnInterface, XrayConfigBuilder.SOCKS_PORT);
            tunnel.startTun2Socks();
            try { Thread.sleep(250L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
            long[] stats = tunnel.stats();
            VpnDiagnostics.log(context, "HEV_READY", "jni started, stats=" + statsText(stats));
            started = true;
            VpnDiagnostics.log(context, "XRAY_RUNTIME_READY", "core and HEV bootstrap returned");
        } catch (Throwable e) {
            VpnDiagnostics.log(context, "XRAY_START_FAILED", safeMessage(e));
            stop();
            if (e instanceof Exception) throw (Exception)e;
            throw new IllegalStateException(safeMessage(e), e);
        }
    }

    /**
     * Performs the blocking SOCKS verification on its own cancellable worker with
     * an absolute deadline. The previous implementation relied only on SO_TIMEOUT;
     * on some Android builds a native/Xray handshake could outlive it and leave the
     * VpnService in STARTING indefinitely.
     */
    public long probeOutbound() throws Exception {
        if (!started || stopping) throw new IllegalStateException("Xray core не запущен");
        VpnDiagnostics.log(context, "XRAY_PROBE_ENTER", "deadline=" + PROBE_TOTAL_TIMEOUT_MS + "ms");
        cancelProbe();
        final FutureTask<Long> task = new FutureTask<Long>(new java.util.concurrent.Callable<Long>() {
            @Override public Long call() throws Exception { return probeOutboundInternal(); }
        });
        Thread probe = new Thread(task, "SiteScopeXrayOutboundProbe");
        synchronized (probeLock) {
            if (stopping) throw new IllegalStateException("Xray runtime останавливается");
            activeProbeThread = probe;
        }
        probe.start();
        try {
            return task.get(PROBE_TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            VpnDiagnostics.log(context, "XRAY_OUTBOUND_TIMEOUT", "deadline=" + PROBE_TOTAL_TIMEOUT_MS + "ms");
            task.cancel(true);
            cancelProbe();
            throw new SocketTimeoutException("Проверка Xray превысила 30 секунд");
        } catch (InterruptedException e) {
            task.cancel(true);
            cancelProbe();
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception)cause;
            throw new IllegalStateException(safeMessage(cause), cause);
        } finally {
            synchronized (probeLock) {
                if (activeProbeThread == probe) activeProbeThread = null;
            }
        }
    }

    private long probeOutboundInternal() throws Exception {
        Exception first = null;
        String[] targets = {"www.gstatic.com", "one.one.one.one"};
        for (String target : targets) {
            if (Thread.currentThread().isInterrupted() || stopping) throw new InterruptedException("Xray probe cancelled");
            try {
                long delay = socksConnect(target, 443, 12000);
                VpnDiagnostics.log(context, "XRAY_OUTBOUND_OK", "target=" + target + ":443, delay=" + delay + "ms");
                return delay;
            } catch (Exception e) {
                if (Thread.currentThread().isInterrupted() || stopping) throw new InterruptedException("Xray probe cancelled");
                if (first == null) first = e;
                VpnDiagnostics.log(context, "XRAY_OUTBOUND_RETRY", "target=" + target + ":443, error=" + safeMessage(e));
            }
        }
        throw new IllegalStateException("Профиль не установил исходящее соединение: " + safeMessage(first));
    }

    private void registerFinder(CoreController target) {
        try {
            target.registerProcessFinder(new libv2ray.ProcessFinder() {
                @Override public long findProcessByConnection(String network, String sourceIp, long sourcePort, String destinationIp, long destinationPort) {
                    try {
                        if (android.os.Build.VERSION.SDK_INT < 29) return -1L;
                        android.net.ConnectivityManager cm = (android.net.ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
                        if (cm == null) return -1L;
                        int protocol = network != null && network.toLowerCase(java.util.Locale.US).startsWith("udp") ? 17 : 6;
                        return cm.getConnectionOwnerUid(protocol, new InetSocketAddress(sourceIp, (int)sourcePort), new InetSocketAddress(destinationIp, (int)destinationPort));
                    } catch (Throwable ignored) { return -1L; }
                }
            });
        } catch (Throwable e) {
            VpnDiagnostics.log(context, "XRAY_FINDER_SKIPPED", safeMessage(e));
        }
    }

    private CoreCallbackHandler callbacks() {
        return new CoreCallbackHandler() {
            @Override public long onEmitStatus(long code, String status) {
                VpnDiagnostics.log(context, "XRAY_STATUS_" + code, status);
                return 0L;
            }
            @Override public long startup() {
                VpnDiagnostics.log(context, "XRAY_CALLBACK", "startup");
                return 0L;
            }
            @Override public long shutdown() {
                VpnDiagnostics.log(context, "XRAY_CALLBACK", "shutdown");
                return 0L;
            }
        };
    }

    /**
     * Replaces the running core configuration (hosts, DNS) without touching the Android TUN
     * or the HEV bridge: the VPN slot stays owned, only in-flight connections are re-established.
     * If the new configuration is rejected, the previous one is started again.
     */
    public synchronized void reloadCore(String config) throws Exception {
        if (!started || stopping || controller == null) throw new IllegalStateException("Xray core не запущен");
        String previous = lastConfig;
        try { if (controller.getIsRunning()) controller.stopLoop(); } catch (Throwable ignored) { }
        try {
            controller = Libv2ray.newCoreController(callbacks());
            registerFinder(controller);
            controller.startLoop(config, 0);
            if (!controller.getIsRunning()) throw new IllegalStateException("Xray core не принял новую конфигурацию");
            lastConfig = config;
            VpnDiagnostics.log(context, "XRAY_CORE_RELOADED", "configuration replaced, TUN and HEV untouched");
        } catch (Throwable e) {
            VpnDiagnostics.log(context, "XRAY_RELOAD_FAILED", safeMessage(e));
            try {
                controller = Libv2ray.newCoreController(callbacks());
                controller.startLoop(previous, 0);
                VpnDiagnostics.log(context, "XRAY_RELOAD_ROLLBACK", "previous configuration restored");
            } catch (Throwable rollback) {
                VpnDiagnostics.log(context, "XRAY_RELOAD_ROLLBACK_FAILED", safeMessage(rollback));
            }
            if (e instanceof Exception) throw (Exception)e;
            throw new IllegalStateException(safeMessage(e), e);
        }
    }

    /**
     * Restarts only the HEV bridge on another descriptor: the real TUN, or the capture tee's
     * socket end. Xray core and the Android VPN interface keep running.
     */
    public synchronized void restartBridge(ParcelFileDescriptor descriptor, String reason) throws Exception {
        if (!started || stopping) throw new IllegalStateException("Xray runtime не запущен");
        if (tunnel != null) tunnel.stopTun2Socks();
        try { Thread.sleep(200L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw e; }
        tunnel = new TProxyService(context, descriptor, XrayConfigBuilder.SOCKS_PORT);
        tunnel.startTun2Socks();
        VpnDiagnostics.log(context, "HEV_BRIDGE_RESTARTED", reason);
    }

    public synchronized boolean isRunning() {
        try {
            return !stopping && started && controller != null && controller.getIsRunning() && tunnel != null && tunnel.isStarted();
        } catch (Throwable ignored) {
            return false;
        }
    }

    public synchronized String healthSummary() {
        boolean core = false;
        try { core = controller != null && controller.getIsRunning(); } catch (Throwable ignored) { }
        boolean hev = tunnel != null && tunnel.isStarted();
        long[] stats = tunnel == null ? null : tunnel.stats();
        return "core=" + core + ", hev=" + hev + ", stats=" + statsText(stats);
    }

    public synchronized long[] stats() { return tunnel == null ? null : tunnel.stats(); }

    public void cancelProbe() {
        Socket socket;
        Thread thread;
        synchronized (probeLock) {
            socket = activeProbeSocket;
            activeProbeSocket = null;
            thread = activeProbeThread;
        }
        if (socket != null) try { socket.close(); } catch (Exception ignored) { }
        if (thread != null && thread != Thread.currentThread()) thread.interrupt();
    }

    public synchronized void stop() {
        stopping = true;
        started = false;
        cancelProbe();
        boolean hadRuntime = tunnel != null || controller != null;
        if (tunnel != null) {
            tunnel.stopTun2Socks();
            tunnel = null;
        }
        if (controller != null) {
            try { if (controller.getIsRunning()) controller.stopLoop(); } catch (Throwable ignored) { }
            controller = null;
        }
        if (hadRuntime) VpnDiagnostics.log(context, "XRAY_STOP", "runtime stopped");
    }

    private long socksConnect(String host, int port, int timeoutMs) throws Exception {
        Socket socket = new Socket();
        synchronized (probeLock) {
            if (stopping || Thread.currentThread().isInterrupted()) throw new InterruptedException("Xray probe cancelled");
            activeProbeSocket = socket;
        }
        long started = System.currentTimeMillis();
        try {
            socket.setSoTimeout(timeoutMs);
            VpnDiagnostics.log(context, "XRAY_PROBE_LOCAL_CONNECT", "127.0.0.1:" + XrayConfigBuilder.SOCKS_PORT);
            socket.connect(new InetSocketAddress("127.0.0.1", XrayConfigBuilder.SOCKS_PORT), 3000);
            VpnDiagnostics.log(context, "XRAY_PROBE_LOCAL_CONNECTED", "local SOCKS socket connected");
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            out.write(new byte[]{0x05, 0x01, 0x00});
            out.flush();
            byte[] greeting = readExact(in, 2);
            if ((greeting[0] & 0xff) != 5 || (greeting[1] & 0xff) != 0) {
                throw new IllegalStateException("локальный SOCKS не принял no-auth handshake");
            }
            VpnDiagnostics.log(context, "XRAY_PROBE_SOCKS_READY", "no-auth accepted");

            byte[] name = host.getBytes(StandardCharsets.US_ASCII);
            if (name.length == 0 || name.length > 255) throw new IllegalArgumentException("некорректная probe-цель");
            byte[] request = new byte[7 + name.length];
            request[0] = 0x05; request[1] = 0x01; request[2] = 0x00; request[3] = 0x03;
            request[4] = (byte)name.length;
            System.arraycopy(name, 0, request, 5, name.length);
            request[5 + name.length] = (byte)((port >>> 8) & 0xff);
            request[6 + name.length] = (byte)(port & 0xff);
            VpnDiagnostics.log(context, "XRAY_PROBE_REMOTE_REQUEST", "target=" + host + ":" + port);
            out.write(request);
            out.flush();

            byte[] response = readExact(in, 4);
            if ((response[0] & 0xff) != 5) throw new IllegalStateException("некорректный SOCKS-ответ");
            int reply = response[1] & 0xff;
            int atyp = response[3] & 0xff;
            if (reply != 0) throw new IllegalStateException("SOCKS reply " + reply + " (" + socksReply(reply) + ")");
            int addressLength;
            if (atyp == 1) addressLength = 4;
            else if (atyp == 4) addressLength = 16;
            else if (atyp == 3) addressLength = readExact(in, 1)[0] & 0xff;
            else throw new IllegalStateException("неизвестный SOCKS ATYP " + atyp);
            readExact(in, addressLength + 2);
            return System.currentTimeMillis() - started;
        } finally {
            synchronized (probeLock) { if (activeProbeSocket == socket) activeProbeSocket = null; }
            try { socket.close(); } catch (Exception ignored) { }
        }
    }

    private static byte[] readExact(InputStream in, int length) throws Exception {
        byte[] value = new byte[length];
        int offset = 0;
        while (offset < length) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Xray probe cancelled");
            int read = in.read(value, offset, length - offset);
            if (read < 0) throw new EOFException("SOCKS закрыл соединение");
            offset += read;
        }
        return value;
    }

    private static String socksReply(int code) {
        switch (code) {
            case 1: return "general failure";
            case 2: return "connection not allowed";
            case 3: return "network unreachable";
            case 4: return "host unreachable";
            case 5: return "connection refused";
            case 6: return "TTL expired";
            case 7: return "command not supported";
            case 8: return "address type not supported";
            default: return "unknown";
        }
    }

    private static String statsText(long[] stats) {
        return stats == null ? "unavailable" : Arrays.toString(stats);
    }

    private static String safeMessage(Throwable e) {
        if (e == null) return "unknown error";
        String value = e.getMessage();
        return value == null || value.length() == 0 ? e.getClass().getSimpleName() : value;
    }
}
