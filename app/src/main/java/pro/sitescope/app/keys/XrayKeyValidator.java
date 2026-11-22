package pro.sitescope.app.keys;

import android.content.Context;
import pro.sitescope.app.HostsVpnService;
import pro.sitescope.app.XrayAssets;
import pro.sitescope.app.XrayConfigBuilder;
import pro.sitescope.app.XrayProfileParser;
import org.json.JSONObject;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.HttpsURLConnection;
import java.util.concurrent.atomic.AtomicBoolean;
import libv2ray.CoreCallbackHandler;
import libv2ray.CoreController;
import libv2ray.Libv2ray;

/**
 * Validates the ACTUAL profile/credentials using the bundled Xray core.
 * TCP connect / SNI TLS handshake must never be treated as a successful key.
 * Runs one disposable loopback-only core at a time; never changes global SOCKS_PORT.
 */
public final class XrayKeyValidator {
    public static final int UNTESTED = 0;
    public static final int VERIFIED = 1;
    public static final int FAILED = 2;
    public static final int INCONCLUSIVE = 3;
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    public static final class Result {
        public final int status;
        public final long delayMs;
        public final String reason;
        public Result(int status, long delayMs, String reason) {
            this.status = status; this.delayMs = delayMs; this.reason = reason;
        }
    }

    private XrayKeyValidator() {}

    public static Result check(Context context, String profile, int timeoutMs) {
        if (!BUSY.compareAndSet(false, true))
            return new Result(INCONCLUSIVE, -1, "Валидатор занят");
        CoreController controller = null;
        try {
            // libv2ray uses a process-wide native runtime. Competing with SiteScope's
            // live VPN core could interrupt a connected user or deadlock gomobile.
            String state = context.getSharedPreferences(HostsVpnService.PREFS, Context.MODE_PRIVATE)
                    .getString(HostsVpnService.KEY_STATE, HostsVpnService.STATE_OFF);
            String mode = context.getSharedPreferences(HostsVpnService.PREFS, Context.MODE_PRIVATE)
                    .getString("mode", "");
            if ((HostsVpnService.STATE_ACTIVE.equals(state) || HostsVpnService.STATE_STARTING.equals(state))
                    && HostsVpnService.MODE_XRAY.equals(mode))
                return new Result(INCONCLUSIVE, -1, "Остановите VPN SiteScope для глубокой проверки");

            XrayProfileParser.ParsedProfile parsed = XrayProfileParser.parse(profile);
            if (parsed.fullConfig != null)
                return new Result(INCONCLUSIVE, -1, "Для полного JSON требуется отдельная проверка");
            if ("SHADOWSOCKS".equals(parsed.type)) {
                JSONObject servers = parsed.outbound.optJSONObject("settings");
                String cipher = servers == null ? "" : servers.getJSONArray("servers").getJSONObject(0).optString("method");
                if (cipher.toLowerCase(java.util.Locale.US).endsWith("-cfb"))
                    return new Result(FAILED, -1, "Устаревший Shadowsocks-шифр " + cipher + " не поддерживается Xray");
            }

            int port;
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                port = socket.getLocalPort();
            }
            XrayAssets.prepare(context);
            JSONObject cfg = new JSONObject(XrayConfigBuilder.build(profile, null).json);
            cfg.getJSONArray("inbounds").getJSONObject(0).put("port", port);
            // The only inbound is local SOCKS and its first routing rule sends
            // all non-DNS traffic through the candidate outbound (never direct).
            controller = Libv2ray.newCoreController(new CoreCallbackHandler() {
                @Override public long onEmitStatus(long code, String status) { return 0; }
                @Override public long startup() { return 0; }
                @Override public long shutdown() { return 0; }
            });
            controller.startLoop(cfg.toString(), 0);
            if (!controller.getIsRunning())
                return new Result(FAILED, -1, "Xray-core отклонил конфигурацию");
            Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", port));
            String[] targets = {"www.gstatic.com", "cp.cloudflare.com"};
            for (String target : targets) {
                if (Thread.currentThread().isInterrupted())
                    return new Result(INCONCLUSIVE, -1, "Проверка прервана");
                // Proxy-aware Socket forces a SOCKS5 CONNECT with remote DNS.
                // TLS certificate and HTTP status are verified AFTER routing through the key.
                try (Socket plain = new Socket(proxy)) {
                    long started = System.nanoTime();
                    plain.connect(InetSocketAddress.createUnresolved(target, 443), timeoutMs);
                    plain.setSoTimeout(timeoutMs);
                    try (SSLSocket ssl = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                            .createSocket(plain, target, 443, true)) {
                        ssl.setSoTimeout(timeoutMs);
                        ssl.startHandshake();
                        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(target, ssl.getSession()))
                            throw new java.io.IOException("TLS hostname mismatch");
                        OutputStream output = ssl.getOutputStream();
                        output.write(("GET /generate_204 HTTP/1.1\r\nHost: " + target +
                                "\r\nUser-Agent: SiteScope-KeyValidator/1\r\nConnection: close\r\n\r\n")
                                .getBytes(StandardCharsets.US_ASCII));
                        output.flush();
                        BufferedReader reader = new BufferedReader(
                                new InputStreamReader(ssl.getInputStream(), StandardCharsets.US_ASCII));
                        String status = reader.readLine();
                        if (status != null && status.matches("HTTP/\\d(?:\\.\\d)? 204(?:\\s.*)?")) {
                            return new Result(VERIFIED,
                                    (System.nanoTime() - started) / 1_000_000L, "TLS + HTTP 204 через Xray");
                        }
                    }
                } catch (Exception ignored) {
                    // Do not mark live based on an open port, local SOCKS or TLS alone.
                }
            }
            return new Result(FAILED, -1, "HTTPS через профиль не работает (оба тестовых адреса)");
        } catch (Exception e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(java.util.Locale.US);
            if (message.contains("unknown cipher") || message.contains("unknown method"))
                return new Result(FAILED, -1, "Xray не поддерживает метод шифрования");
            if (e instanceof IllegalArgumentException ||
                message.contains("failed to parse") || message.contains("failed to build outbound"))
                return new Result(FAILED, -1, "Некорректная или несовместимая конфигурация");
            return new Result(INCONCLUSIVE, -1, "Ошибка движка Xray или сетевого окружения");
        } catch (Throwable e) {
            return new Result(INCONCLUSIVE, -1, "Движок Xray недоступен");
        } finally {
            if (controller != null) {
                try { if (controller.getIsRunning()) controller.stopLoop(); } catch (Throwable ignored) {}
            }
            BUSY.set(false);
        }
    }
}
