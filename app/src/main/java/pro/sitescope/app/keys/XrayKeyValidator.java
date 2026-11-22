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
import java.net.URL;
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
            String[] urls = {"https://www.gstatic.com/generate_204", "https://cp.cloudflare.com/generate_204"};
            long started = System.nanoTime();
            for (String url : urls) {
                if (Thread.currentThread().isInterrupted())
                    return new Result(INCONCLUSIVE, -1, "Проверка прервана");
                HttpsURLConnection connection = null;
                try {
                    connection = (HttpsURLConnection) new URL(url).openConnection(proxy);
                    connection.setConnectTimeout(timeoutMs);
                    connection.setReadTimeout(timeoutMs);
                    connection.setUseCaches(false);
                    connection.setInstanceFollowRedirects(false);
                    connection.setRequestProperty("Connection", "close");
                    int response = connection.getResponseCode();
                    if (response == 204) {
                        long delay = (System.nanoTime() - started) / 1_000_000L;
                        return new Result(VERIFIED, delay, "HTTPS 204 через Xray");
                    }
                } catch (Exception ignored) {
                    // A second independent endpoint avoids regional false negatives.
                } finally {
                    if (connection != null) connection.disconnect();
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
