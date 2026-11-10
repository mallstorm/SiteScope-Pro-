package pro.sitescope.app;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Safe profile preflight that never enters libv2ray.
 *
 * The old UI test used Libv2ray.measureOutboundDelay(). On some devices that
 * native call remained alive after leaving the screen and held the gomobile
 * core, so a later VPN start stalled after HEV_READY while checkVersionX()
 * waited for the same native runtime. This preflight only validates syntax and
 * TCP reachability of the configured endpoint. The full Reality/gRPC/VLESS
 * verification remains the real SOCKS probe performed by HostsVpnService.
 */
public final class XrayProfilePreflight {
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final long TOTAL_TIMEOUT_MS = 12000L;
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile Socket activeSocket;
    private static volatile Thread activeThread;

    public static final class Result {
        public final String host;
        public final int port;
        public final long delayMs;
        Result(String host, int port, long delayMs) { this.host = host; this.port = port; this.delayMs = delayMs; }
    }

    private XrayProfilePreflight() { }

    public static boolean isRunning() { return RUNNING.get(); }

    public static Result check(String endpoint) throws Exception {
        if (!RUNNING.compareAndSet(false, true)) throw new IllegalStateException("Проверка профиля уже выполняется");
        final HostPort target = parseEndpoint(endpoint);
        final FutureTask<Result> task = new FutureTask<Result>(new Callable<Result>() {
            @Override public Result call() throws Exception { return connect(target); }
        });
        Thread worker = new Thread(task, "SiteScopeXrayPreflight");
        activeThread = worker;
        worker.start();
        try {
            return task.get(TOTAL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            task.cancel(true);
            cancelSocket();
            throw new SocketTimeoutException("Предпроверка сервера превысила 12 секунд");
        } catch (InterruptedException e) {
            task.cancel(true);
            cancelSocket();
            Thread.currentThread().interrupt();
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception)cause;
            throw new IllegalStateException(safeMessage(cause), cause);
        } finally {
            activeThread = null;
            RUNNING.set(false);
        }
    }

    public static void cancel() {
        Thread thread = activeThread;
        cancelSocket();
        if (thread != null && thread != Thread.currentThread()) thread.interrupt();
        RUNNING.set(false);
    }

    private static Result connect(HostPort target) throws Exception {
        Socket socket = new Socket();
        activeSocket = socket;
        long started = System.currentTimeMillis();
        try {
            socket.connect(new InetSocketAddress(target.host, target.port), CONNECT_TIMEOUT_MS);
            return new Result(target.host, target.port, System.currentTimeMillis() - started);
        } finally {
            if (activeSocket == socket) activeSocket = null;
            try { socket.close(); } catch (Exception ignored) { }
        }
    }

    private static void cancelSocket() {
        Socket socket = activeSocket;
        activeSocket = null;
        if (socket != null) try { socket.close(); } catch (Exception ignored) { }
    }

    private static HostPort parseEndpoint(String endpoint) {
        String value = endpoint == null ? "" : endpoint.trim();
        if (value.length() == 0) throw new IllegalArgumentException("В профиле не найден адрес сервера");
        String host;
        int port;
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            if (end < 0 || end + 2 > value.length() || value.charAt(end + 1) != ':') throw new IllegalArgumentException("Некорректный IPv6 endpoint");
            host = value.substring(1, end);
            port = parsePort(value.substring(end + 2));
        } else {
            int colon = value.lastIndexOf(':');
            if (colon <= 0 || colon == value.length() - 1) throw new IllegalArgumentException("Некорректный endpoint сервера");
            host = value.substring(0, colon).trim();
            port = parsePort(value.substring(colon + 1));
        }
        if (host.length() == 0) throw new IllegalArgumentException("Адрес сервера отсутствует");
        return new HostPort(host, port);
    }

    private static int parsePort(String raw) {
        try {
            int port = Integer.parseInt(raw.trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Некорректный порт сервера");
        }
    }

    private static String safeMessage(Throwable e) {
        if (e == null) return "unknown error";
        String value = e.getMessage();
        return value == null || value.length() == 0 ? e.getClass().getSimpleName() : value;
    }

    private static final class HostPort {
        final String host;
        final int port;
        HostPort(String host, int port) { this.host = host; this.port = port; }
    }
}
