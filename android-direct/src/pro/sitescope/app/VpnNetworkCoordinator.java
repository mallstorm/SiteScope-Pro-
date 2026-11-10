package pro.sitescope.app;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.provider.Settings;

import java.util.Locale;

/** Coordinates the single Android VPN slot and chooses a stable non-VPN underlay. */
public final class VpnNetworkCoordinator {
    private static final long STABLE_WINDOW_MS = 1200L;
    private static final long VALIDATED_GRACE_MS = 8000L;

    public interface Cancellation {
        void check() throws InterruptedException;
    }

    public interface StatusListener {
        void onStatus(String message);
    }

    public static final class Snapshot {
        public final boolean vpnPresent;
        public final int vpnCount;
        public final Network underlying;
        public final boolean validated;
        public final String description;

        Snapshot(boolean vpnPresent, int vpnCount, Network underlying, boolean validated, String description) {
            this.vpnPresent = vpnPresent;
            this.vpnCount = vpnCount;
            this.underlying = underlying;
            this.validated = validated;
            this.description = description == null ? "" : description;
        }
    }

    /** Signals a system VPN-slot / underlay problem for which BASIC fallback cannot help. */
    public static final class VpnSlotException extends IllegalStateException {
        public VpnSlotException(String message) { super(message); }
    }

    private VpnNetworkCoordinator() { }

    public static Snapshot snapshot(Context context) {
        ConnectivityManager cm = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return new Snapshot(false, 0, null, false, "сеть недоступна");
        int vpnCount = 0;
        Network bestValidated = null;
        Network bestInternet = null;
        Network active = null;
        try { active = cm.getActiveNetwork(); } catch (Throwable ignored) { }
        Network[] networks;
        try { networks = cm.getAllNetworks(); } catch (Throwable e) { networks = new Network[0]; }
        for (Network network : networks) {
            NetworkCapabilities caps;
            try { caps = cm.getNetworkCapabilities(network); } catch (Throwable e) { caps = null; }
            if (caps == null) continue;
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                vpnCount++;
                continue;
            }
            if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue;
            boolean validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            if (validated && (bestValidated == null || network.equals(active))) bestValidated = network;
            if (bestInternet == null || network.equals(active)) bestInternet = network;
        }
        Network selected = bestValidated != null ? bestValidated : bestInternet;
        boolean validated = false;
        String description = "нет базовой сети";
        if (selected != null) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(selected);
            validated = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            description = describe(caps, validated);
        }
        return new Snapshot(vpnCount > 0, vpnCount, selected, validated, description);
    }

    /**
     * Waits for a previous VPN interface to disappear and for a non-VPN network to
     * remain stable. This is important immediately after switching from another
     * VPN app, when Android can expose the old VPN network for several seconds.
     */
    public static Network awaitReady(Context context, long timeoutMs, Cancellation cancellation, StatusListener listener)
            throws InterruptedException {
        long started = System.currentTimeMillis();
        long deadline = started + Math.max(3000L, timeoutMs);
        Network candidate = null;
        long stableSince = 0L;
        boolean loggedVpn = false;
        while (System.currentTimeMillis() < deadline) {
            if (cancellation != null) cancellation.check();
            Snapshot state = snapshot(context);
            long elapsed = System.currentTimeMillis() - started;
            if (state.vpnPresent) {
                candidate = null;
                stableSince = 0L;
                if (!loggedVpn) {
                    loggedVpn = true;
                    VpnDiagnostics.log(context, "VPN_SLOT_WAIT", "vpnNetworks=" + state.vpnCount);
                }
                if (listener != null) listener.onStatus("Ожидание отключения предыдущего VPN…");
            } else if (state.underlying != null && (state.validated || elapsed >= VALIDATED_GRACE_MS)) {
                if (!state.underlying.equals(candidate)) {
                    candidate = state.underlying;
                    stableSince = System.currentTimeMillis();
                }
                if (listener != null) listener.onStatus(state.validated
                    ? "Базовая сеть готова · " + state.description
                    : "Базовая сеть без проверки Internet · " + state.description);
                if (System.currentTimeMillis() - stableSince >= STABLE_WINDOW_MS) {
                    VpnDiagnostics.log(context, "VPN_SLOT_READY", "underlying=" + state.description + ", validated=" + state.validated);
                    return state.underlying;
                }
            } else {
                candidate = null;
                stableSince = 0L;
                if (listener != null) listener.onStatus("Ожидание Wi‑Fi или мобильной сети…");
            }
            Thread.sleep(200L);
        }
        Snapshot finalState = snapshot(context);
        if (finalState.vpnPresent) {
            VpnDiagnostics.log(context, "VPN_SLOT_BUSY", "vpnNetworks=" + finalState.vpnCount);
            throw new VpnSlotException("Другой VPN ещё активен либо включён Always-on/«Блокировать без VPN». Отключите его в системных настройках VPN и повторите запуск.");
        }
        VpnDiagnostics.log(context, "VPN_UNDERLYING_MISSING", finalState.description);
        throw new VpnSlotException("Не найдена стабильная Wi‑Fi или мобильная сеть для запуска VPN");
    }


    public static String describe(Context context, Network network) {
        if (network == null) return "нет базовой сети";
        ConnectivityManager cm = (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return network.toString();
        try {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            boolean validated = caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
            return describe(caps, validated);
        } catch (Throwable ignored) {
            return network.toString();
        }
    }

    public static Intent settingsIntent() {
        return new Intent(Settings.ACTION_VPN_SETTINGS);
    }

    private static String describe(NetworkCapabilities caps, boolean validated) {
        if (caps == null) return "неизвестная сеть";
        String transport;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transport = "Wi‑Fi";
        else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transport = "мобильная сеть";
        else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transport = "Ethernet";
        else transport = "системная сеть";
        return transport + (validated ? " · Internet подтверждён" : " · Internet не подтверждён");
    }
}
