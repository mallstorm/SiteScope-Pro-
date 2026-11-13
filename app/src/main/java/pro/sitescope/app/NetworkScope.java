package pro.sitescope.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.LinkProperties;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.provider.Settings;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/** Network namespace, not an identity inferred from an IP prefix or SSID alone. */
public final class NetworkScope {
    private static final String PROCESS = UUID.randomUUID().toString();
    public final String id, label;
    public final boolean reliable;
    public final Network network;

    public NetworkScope(String id, String label, boolean reliable) { this(id, label, reliable, null); }
    private NetworkScope(String id, String label, boolean reliable, Network network) {
        this.id = id; this.label = label; this.reliable = reliable; this.network = network;
    }
    private static synchronized String installation(Context context) {
        android.content.SharedPreferences prefs = context.getSharedPreferences("network_scope_local", Context.MODE_PRIVATE);
        String id = prefs.getString("installation", "");
        if (id == null || id.isEmpty()) { id = UUID.randomUUID().toString(); prefs.edit().putString("installation", id).apply(); }
        return id;
    }
    public static NetworkScope current(Context context) {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        return capture(context, cm == null ? null : cm.getActiveNetwork());
    }
    public static NetworkScope capture(Context context, Network network) {
        if (network == null) return new NetworkScope("unknown:" + UUID.randomUUID(), "Сеть не определена", false);
        try {
            ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            String kind = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ? "VPN" :
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi" :
                caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet" : "Мобильная сеть";
            if ("Wi-Fi".equals(kind)) {
                WifiManager manager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                WifiInfo info = android.os.Build.VERSION.SDK_INT >= 29 && caps != null && caps.getTransportInfo() instanceof WifiInfo ? (WifiInfo) caps.getTransportInfo() :
                    network.equals(cm.getActiveNetwork()) && manager != null ? manager.getConnectionInfo() : null;
                String bssid = info == null ? "" : info.getBSSID();
                if (bssid != null && bssid.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}") && !bssid.equals("02:00:00:00:00:00") && !bssid.equals("00:00:00:00:00:00")) {
                    LinkProperties links = cm.getLinkProperties(network);
                    String gateway = "";
                    if (links != null) for (android.net.RouteInfo route : links.getRoutes()) if (route.isDefaultRoute() && route.getGateway() != null) gateway = route.getGateway().getHostAddress();
                    String ssid = info.getSSID();
                    return new NetworkScope("wifi:" + digest(bssid.toLowerCase(Locale.US) + "|" + gateway),
                        ssid == null || ssid.equals("<unknown ssid>") ? kind : kind + " " + ssid.replace("\"", ""), true, network);
                }
            }
            int boot = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
            String epoch = boot >= 0 ? Integer.toString(boot) : PROCESS;
            return new NetworkScope("session:" + digest(installation(context) + "|" + epoch + "|" + network.getNetworkHandle()), kind + " · сеанс сети", true, network);
        } catch (Exception unavailable) {
            return new NetworkScope("session:" + digest(PROCESS + "|" + network.getNetworkHandle()), "Текущий сеанс сети", false, network);
        }
    }
    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try { o.put("id", id); o.put("label", label); o.put("reliable", reliable); } catch (Exception ignored) { }
        return o;
    }
    public static NetworkScope fromJson(JSONObject o, String legacy) {
        return o == null ? new NetworkScope("legacy:" + legacy, "Ранее сохранённая сеть не определена", false) :
            new NetworkScope(o.optString("id", "legacy:" + legacy), o.optString("label", "Сеть не определена"), o.optBoolean("reliable"));
    }
    public static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (byte b : hash) out.append(String.format(Locale.US, "%02x", b & 255)); return out.toString();
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }
}
