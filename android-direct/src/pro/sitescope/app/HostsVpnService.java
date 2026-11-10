package pro.sitescope.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** One Android VpnService providing either BASIC Hosts DNS or combined Hosts + Xray full tunnel. */
public final class HostsVpnService extends VpnService {
    public static final String ACTION_START = "pro.sitescope.app.hosts.START";
    public static final String ACTION_STOP = "pro.sitescope.app.hosts.STOP";
    public static final String ACTION_RELOAD = "pro.sitescope.app.hosts.RELOAD";
    public static final String EXTRA_MODE = "mode";
    public static final String MODE_BASIC = "BASIC";
    public static final String MODE_XRAY = "XRAY";
    public static final String PREFS = "hosts_manager_state";
    public static final String KEY_STATE = "lifecycleState";
    public static final String STATE_OFF = "OFF";
    public static final String STATE_STARTING = "STARTING";
    public static final String STATE_ACTIVE = "ACTIVE";
    public static final String STATE_STOPPING = "STOPPING";
    public static final String STATE_ERROR = "ERROR";

    private static final int NOTIFICATION_ID = 32030;
    private static final String CHANNEL = "sitescope_hosts";

    private volatile boolean running;
    private volatile String activeMode = "";
    private volatile int generation;
    private ParcelFileDescriptor tunnel;
    private Thread worker;
    private Thread watchdog;
    private Thread heartbeat;
    private Thread healthProbe;
    private Thread servicePulse;
    private volatile boolean servicePulseRunning;
    private final Object startupLock = new Object();
    private volatile Thread starter;
    private volatile int startRequest;
    private XrayRuntime xrayRuntime;
    private volatile Map<String,List<InetAddress>> overrides = new LinkedHashMap<String,List<InetAddress>>();
    private InetAddress upstream;
    private Network underlyingNetwork;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        servicePulseRunning = true;
        servicePulse = new Thread(new Runnable() { @Override public void run() {
            try {
                while (servicePulseRunning) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                        .putLong("serviceHeartbeatAt", System.currentTimeMillis())
                        .putInt("servicePid", android.os.Process.myPid()).apply();
                    Thread.sleep(1500L);
                }
            } catch (InterruptedException ignored) { }
        } }, "SiteScopeVpnServicePulse");
        servicePulse.start();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            updateState(STATE_STOPPING, false, activeMode, "Остановка VPN…", "", "", "", "");
            cancelPendingStart();
            stopVpn("Остановлено", true);
            stopSelf();
            return START_NOT_STICKY;
        }
        String requested = normalizeMode(intent == null ? null : intent.getStringExtra(EXTRA_MODE));
        if (requested.length() == 0) requested = normalizeMode(getSharedPreferences(PREFS, MODE_PRIVATE).getString("desiredMode", MODE_BASIC));
        if (requested.length() == 0) requested = MODE_BASIC;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("desiredMode", requested).apply();

        if (ACTION_RELOAD.equals(action) && running && MODE_BASIC.equals(activeMode)) {
            loadOverrides(); updateState(STATE_ACTIVE, true, MODE_BASIC, "BASIC · профиль hosts обновлён", "", "", "", ""); return START_STICKY;
        }

        final String requestedMode = requested;
        XrayProfilePreflight.cancel();
        final int request = ++startRequest;
        Thread previous = starter;
        if (previous != null && previous != Thread.currentThread()) previous.interrupt();
        startForeground(NOTIFICATION_ID, notification("Запуск " + requestedMode + "…"));
        updateState(STATE_STARTING, false, requestedMode, "Запуск " + requestedMode + "…", "", "", "", "");
        starter = new Thread(new Runnable() {
            @Override public void run() { startRequestedMode(requestedMode, request); }
        }, "SiteScopeVpnStarter");
        starter.start();
        return START_STICKY;
    }

    /**
     * VpnService.onStartCommand runs on Android's main thread. Socket probes and the
     * embedded-core bootstrap must stay off that thread or Android throws
     * NetworkOnMainThreadException before the remote profile can be tested.
     */
    private void startRequestedMode(String requested, int request) {
        synchronized (startupLock) {
            try {
                ensureStartCurrent(request);
                if (running || tunnel != null || xrayRuntime != null) stopVpn("Перезапуск VPN", false);
                ensureStartCurrent(request);
                startMode(requested, request);
            } catch (Throwable e) {
                if (request != startRequest || Thread.currentThread().isInterrupted()) {
                    VpnDiagnostics.log(this, "VPN_START_CANCELLED", "request=" + request);
                    return;
                }
                String message = "Ошибка запуска " + requested + ": " + safeMessage(e);
                VpnDiagnostics.log(this, "VPN_START_FAILED", message);
                if (MODE_XRAY.equals(requested) && !(e instanceof VpnNetworkCoordinator.VpnSlotException)) {
                    stopVpn(message, false);
                    startForeground(NOTIFICATION_ID, notification("XRAY недоступен · возврат в BASIC…"));
                    try {
                        ensureStartCurrent(request);
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString("desiredMode", MODE_BASIC).apply();
                        startMode(MODE_BASIC, request);
                        updateState(STATE_ACTIVE, true, MODE_BASIC, "Fallback BASIC · " + message, "", "", "", "");
                        VpnDiagnostics.log(this, "XRAY_START_FALLBACK", "BASIC restored after startup failure");
                        return;
                    } catch (Throwable fallback) {
                        String fallbackMessage = message + "; BASIC также не запущен: " + safeMessage(fallback);
                        VpnDiagnostics.log(this, "VPN_FALLBACK_FAILED", fallbackMessage);
                        stopVpn(fallbackMessage, true, STATE_ERROR); stopSelf();
                        return;
                    }
                }
                stopVpn(message, true, STATE_ERROR); stopSelf();
            } finally {
                if (starter == Thread.currentThread()) starter = null;
            }
        }
    }

    private void startMode(final String mode, final int request) throws Exception {
        ensureStartCurrent(request);
        if (VpnService.prepare(this) != null) {
            VpnDiagnostics.log(this, "VPN_PERMISSION_REQUIRED", "another VPN app owns the prepared slot");
            throw new VpnNetworkCoordinator.VpnSlotException("Системное разрешение VPN передано другому приложению. Вернитесь в Hosts Manager, нажмите «Применить» и подтвердите системный запрос.");
        }
        updateState(STATE_STARTING, false, mode, "Проверка системного VPN-слота…", "", "", "", "");
        final Network underlay = VpnNetworkCoordinator.awaitReady(this, 15000L, new VpnNetworkCoordinator.Cancellation() {
            @Override public void check() throws InterruptedException { ensureStartCurrent(request); }
        }, new VpnNetworkCoordinator.StatusListener() {
            @Override public void onStatus(String message) {
                updateState(STATE_STARTING, false, mode, message, "", "", "", "");
            }
        });
        ensureStartCurrent(request);
        underlyingNetwork = underlay;
        loadOverrides(); generation++;
        updateState(STATE_STARTING, false, mode, "VPN-слот свободен · запуск " + mode + "…", "", "", "", "");
        if (MODE_XRAY.equals(mode)) {
            VpnDiagnostics.log(this, "VPN_UNDERLAY_READY", VpnNetworkCoordinator.describe(this, underlay));
            startXrayVpn(generation, request, underlay);
        } else startBasicVpn(generation, "BASIC · локальная hosts/DNS-подмена", underlay);
        ensureStartCurrent(request);
    }

    private void ensureStartCurrent(int request) throws InterruptedException {
        if (request != startRequest || Thread.currentThread().isInterrupted()) throw new InterruptedException("VPN start request superseded");
    }

    private void startBasicVpn(final int token, String status, Network underlay) throws Exception {
        upstream = findUpstreamDns(underlay);
        Builder builder = new Builder().setSession("SiteScope Hosts BASIC").setMtu(1500)
            .addAddress("10.203.0.2", 32).addRoute("10.203.0.1", 32).addDnsServer("10.203.0.1");
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
        if (underlay != null) builder.setUnderlyingNetworks(new Network[]{underlay});
        try { builder.addDisallowedApplication(getPackageName()); } catch (Exception ignored) { }
        ParcelFileDescriptor local = builder.establish();
        if (local == null) throw new IllegalStateException("Android не создал VPN-интерфейс");
        tunnel = local; activeMode = MODE_BASIC; running = true;
        if (underlay != null) {
            boolean set = setUnderlyingNetworks(new Network[]{underlay});
            VpnDiagnostics.log(this, "VPN_UNDERLYING_SET", "mode=BASIC, success=" + set);
        }
        updateNotification("BASIC · Hosts активен");
        updateState(STATE_ACTIVE, true, MODE_BASIC, status + " · upstream " + upstream.getHostAddress(), "", "", "", "");
        startBasicHeartbeat(token);
        VpnDiagnostics.log(this, "BASIC_READY", "upstream=" + upstream.getHostAddress() + ", hosts=" + overrides.size());
        worker = new Thread(new Runnable() { @Override public void run() { packetLoop(local, token); } }, "SiteScopeHostsBasic");
        worker.start();
    }

    private void startXrayVpn(final int token, final int request, Network underlay) throws Exception {
        if (!supportsArm64()) throw new IllegalStateException("XRAY-режим этой сборки доступен только на arm64-v8a");
        XrayProfileStore profileStore = new XrayProfileStore(this);
        XrayProfileStore.Info selectedProfile = profileStore.info();
        String raw = selectedProfile.id.length() == 0 ? "" : profileStore.raw(selectedProfile.id);
        if (raw.length() == 0) throw new IllegalStateException("Сначала выберите профиль Xray");
        XrayConfigBuilder.ConfigResult config = XrayConfigBuilder.build(raw, overrides);
        VpnDiagnostics.log(this, "XRAY_PROFILE", "type=" + config.profileType + ", endpoint=" + config.endpoint + ", " + config.transportSummary);
        updateState(STATE_STARTING, false, MODE_XRAY, "Проверка сервера через базовую сеть…", config.profileName, selectedProfile.id, config.endpoint, "");
        try {
            XrayProfilePreflight.Result endpointCheck = XrayProfilePreflight.check(config.endpoint);
            VpnDiagnostics.log(this, "XRAY_ENDPOINT_READY", "endpoint=" + endpointCheck.host + ":" + endpointCheck.port + ", delay=" + endpointCheck.delayMs + "ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            // Some advanced profiles rely on custom DNS. Keep this a warning; the
            // real SOCKS/Reality/gRPC probe below remains authoritative.
            VpnDiagnostics.log(this, "XRAY_ENDPOINT_WARNING", safeMessage(e));
        }
        ensureStartCurrent(request);

        Builder builder = new Builder().setSession("SiteScope Hosts + Xray").setMtu(1500)
            .addAddress("10.10.14.1", 30).addRoute("0.0.0.0", 0).addDnsServer("1.1.1.1")
            .addAddress("fc00::10:10:14:1", 126).addRoute("::", 0).addDnsServer("2606:4700:4700::1111");
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false);
        if (underlay != null) builder.setUnderlyingNetworks(new Network[]{underlay});
        try { builder.addDisallowedApplication(getPackageName()); } catch (Exception ignored) { }
        ParcelFileDescriptor local = builder.establish();
        if (local == null) throw new IllegalStateException("Android не создал полный TUN-интерфейс");
        tunnel = local;
        if (underlay != null) {
            boolean set = setUnderlyingNetworks(new Network[]{underlay});
            VpnDiagnostics.log(this, "VPN_UNDERLYING_SET", "mode=XRAY, success=" + set);
        }
        VpnDiagnostics.log(this, "VPN_TUN_ESTABLISHED", "fd=" + local.getFd() + ", ipv4=10.10.14.1/30, ipv6=fc00::10:10:14:1/126");
        // Give Android a short moment to finish switching away from a previous VPN.
        Thread.sleep(750L);
        ensureStartCurrent(request);
        updateState(STATE_STARTING, false, MODE_XRAY, "TUN создан · запуск Xray core…", config.profileName, selectedProfile.id, config.endpoint, "");
        ensureStartCurrent(request);

        xrayRuntime = new XrayRuntime(this);
        xrayRuntime.start(local, config.json);
        VpnDiagnostics.log(this, "XRAY_RUNTIME_RETURN", "bootstrap returned to service");
        ensureStartCurrent(request);
        final String bundledVersion = XrayAssets.version(this);
        boolean vpnVisible = waitForVpnNetwork(3000L);
        VpnDiagnostics.log(this, "VPN_NETWORK_CHECK", "visible=" + vpnVisible + ", own=true");
        if (!vpnVisible) throw new IllegalStateException("Android не подтвердил VPN-сеть SiteScope");

        activeMode = MODE_XRAY;
        running = true;
        updateNotification("XRAY · " + config.profileName + " · проверка Интернета");
        updateState(STATE_ACTIVE, true, MODE_XRAY,
            "XRAY · туннель поднят · проверка Интернета выполняется в фоне",
            config.profileName, selectedProfile.id, config.endpoint, bundledVersion);
        VpnDiagnostics.log(this, "XRAY_READY", "bootstrap confirmed, health=pending, type=" + config.profileType + ", endpoint=" + config.endpoint + ", hosts=" + config.hostsCount + ", core=" + bundledVersion);
        watchdog = new Thread(new Runnable() { @Override public void run() { watchdogLoop(token); } }, "SiteScopeXrayWatchdog");
        watchdog.start();
        startXrayHealthProbe(token, xrayRuntime, config, selectedProfile, bundledVersion);
    }


    private void startXrayHealthProbe(final int token, final XrayRuntime runtime,
                                      final XrayConfigBuilder.ConfigResult config,
                                      final XrayProfileStore.Info selectedProfile,
                                      final String version) {
        Thread previous = healthProbe;
        if (previous != null && previous != Thread.currentThread()) previous.interrupt();
        healthProbe = new Thread(new Runnable() { @Override public void run() {
            VpnDiagnostics.log(HostsVpnService.this, "XRAY_HEALTH_PROBE_START", "non-fatal background verification");
            try {
                long delay = runtime.probeOutbound();
                if (!isCurrentXraySession(token, runtime)) return;
                updateNotification("XRAY · " + config.profileName + " · " + delay + " мс");
                updateState(STATE_ACTIVE, true, MODE_XRAY,
                    "XRAY · Интернет подтверждён · " + delay + " мс",
                    config.profileName, selectedProfile.id, config.endpoint, version);
                VpnDiagnostics.log(HostsVpnService.this, "XRAY_OUTBOUND_CONFIRMED",
                    "endpoint=" + config.endpoint + ", delay=" + delay + "ms");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                VpnDiagnostics.log(HostsVpnService.this, "XRAY_HEALTH_PROBE_CANCELLED", "session changed or VPN stopped");
            } catch (Throwable e) {
                if (!isCurrentXraySession(token, runtime)) return;
                String warning = safeMessage(e);
                updateNotification("XRAY активен · Интернет не подтверждён");
                updateState(STATE_ACTIVE, true, MODE_XRAY,
                    "XRAY · туннель активен · проверка Интернета не прошла: " + warning,
                    config.profileName, selectedProfile.id, config.endpoint, version);
                VpnDiagnostics.log(HostsVpnService.this, "XRAY_HEALTH_WARNING", warning);
            } finally {
                if (healthProbe == Thread.currentThread()) healthProbe = null;
            }
        } }, "SiteScopeXrayHealthProbe");
        healthProbe.start();
    }

    private boolean isCurrentXraySession(int token, XrayRuntime runtime) {
        return running && token == generation && MODE_XRAY.equals(activeMode) && xrayRuntime == runtime;
    }

    private void packetLoop(ParcelFileDescriptor localTunnel, int token) {
        byte[] buffer = new byte[32767];
        try {
            FileInputStream input = new FileInputStream(localTunnel.getFileDescriptor());
            FileOutputStream output = new FileOutputStream(localTunnel.getFileDescriptor());
            while (running && token == generation && localTunnel == tunnel) {
                int length = input.read(buffer); if (length <= 0) continue;
                HostsVpnPacket.QueryPacket query = HostsVpnPacket.parse(buffer, length); if (query == null) continue;
                byte[] dns = HostsVpnPacket.localDnsResponse(query, overrides); if (dns == null) dns = forward(query.dns);
                if (dns != null) { byte[] response = HostsVpnPacket.responsePacket(query, dns); output.write(response); output.flush(); }
            }
        } catch (Exception e) {
            if (running && token == generation) {
                String message = "Ошибка DNS-VPN: " + safeMessage(e);
                VpnDiagnostics.log(this, "BASIC_PACKET_ERROR", safeMessage(e));
                stopVpn(message, false, STATE_ERROR);
            }
        } finally {
            if (token == generation && localTunnel == tunnel && running && MODE_BASIC.equals(activeMode)) { stopVpn("BASIC остановлен", false); stopSelf(); }
        }
    }

    private void watchdogLoop(int token) {
        int cycle = 0;
        try {
            while (running && token == generation && MODE_XRAY.equals(activeMode)) {
                Thread.sleep(4000L);
                XrayRuntime runtime = xrayRuntime;
                if (runtime == null || !runtime.isRunning()) { fallbackToBasic(token, "Xray core или HEV неожиданно остановился"); return; }
                boolean vpnVisible = isVpnNetworkVisible();
                if (!vpnVisible) { fallbackToBasic(token, "Android VPN-сеть исчезла"); return; }
                getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong("heartbeatAt", System.currentTimeMillis()).apply();
                cycle++;
                if (cycle % 3 == 0) VpnDiagnostics.log(this, "XRAY_HEARTBEAT", runtime.healthSummary() + ", vpnVisible=true");
            }
        } catch (InterruptedException ignored) { }
    }

    private synchronized void fallbackToBasic(int token, String reason) {
        if (!running || token != generation || !MODE_XRAY.equals(activeMode)) return;
        VpnDiagnostics.log(this, "XRAY_FALLBACK", reason);
        stopVpn(reason, false);
        startForeground(NOTIFICATION_ID, notification("Аварийный возврат в BASIC…"));
        try {
            Network underlay = VpnNetworkCoordinator.awaitReady(this, 10000L, null, null);
            generation++; loadOverrides(); startBasicVpn(generation, "Fallback BASIC · " + reason, underlay);
        }
        catch (Exception e) { stopVpn("Не удалось восстановить BASIC: " + safeMessage(e), true, STATE_ERROR); stopSelf(); }
    }

    private byte[] forward(byte[] request) {
        DatagramSocket socket = null;
        try {
            socket = new DatagramSocket(); protect(socket); socket.setSoTimeout(3500);
            DatagramPacket out = new DatagramPacket(request, request.length, upstream, 53); socket.send(out);
            byte[] response = new byte[65535]; DatagramPacket in = new DatagramPacket(response, response.length); socket.receive(in);
            byte[] exact = new byte[in.getLength()]; System.arraycopy(in.getData(), in.getOffset(), exact, 0, in.getLength()); return exact;
        } catch (Exception ignored) { return null; }
        finally { if (socket != null) socket.close(); }
    }

    private void loadOverrides() {
        Map<String,List<InetAddress>> map = new LinkedHashMap<String,List<InetAddress>>();
        try {
            HostsStore store = new HostsStore(this); HostsStore.Profile profile = store.activeProfile();
            if (profile != null) for (HostsEntry entry : store.entries(profile.id)) {
                if (!entry.enabled) continue; InetAddress address = InetAddress.getByName(entry.address);
                for (String rawHost : entry.hostnames) { String host = rawHost.toLowerCase(Locale.US); List<InetAddress> values = map.get(host); if (values == null) { values = new ArrayList<InetAddress>(); map.put(host, values); } values.add(address); }
            }
        } catch (Exception e) { VpnDiagnostics.log(this, "HOSTS_LOAD_ERROR", safeMessage(e)); }
        overrides = map;
    }

    private InetAddress findUpstreamDns(Network preferred) throws Exception {
        ConnectivityManager cm = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm != null && preferred != null) {
            LinkProperties link = cm.getLinkProperties(preferred);
            if (link != null && !link.getDnsServers().isEmpty()) return link.getDnsServers().get(0);
        }
        if (cm != null) for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network); if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            LinkProperties link = cm.getLinkProperties(network); if (link != null && !link.getDnsServers().isEmpty()) return link.getDnsServers().get(0);
        }
        return InetAddress.getByName("1.1.1.1");
    }

    private boolean waitForVpnNetwork(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        do {
            if (isVpnNetworkVisible()) return true;
            try { Thread.sleep(100L); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        } while (System.currentTimeMillis() < deadline);
        return isVpnNetworkVisible();
    }

    private boolean isVpnNetworkVisible() {
        try {
            ConnectivityManager cm = (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                if (Build.VERSION.SDK_INT >= 29 && caps.getOwnerUid() == android.os.Process.myUid()) return true;
                LinkProperties link = cm.getLinkProperties(network);
                if (link != null) {
                    String text = link.toString();
                    if (text.contains("10.10.14.1") || text.contains("fc00::10:10:14:1") || text.contains("10.203.0.2")) return true;
                }
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private Notification notification(String text) {
        Intent open = new Intent(this, HostsActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL) : new Notification.Builder(this);
        b.setContentTitle("SiteScope Hosts / Xray").setContentText(text).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pending).setOngoing(true).setCategory(Notification.CATEGORY_SERVICE);
        return b.build();
    }

    private void updateNotification(String text) { ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, notification(text)); }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel c = new NotificationChannel(CHANNEL, "Hosts + Xray", NotificationManager.IMPORTANCE_LOW);
            c.setDescription("Локальные hosts-правила и комбинированный Xray VPN");
            ((NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE)).createNotificationChannel(c);
        }
    }

    private void updateState(String lifecycle, boolean active, String mode, String message, String profile, String profileId, String endpoint, String coreVersion) {
        long now = System.currentTimeMillis();
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_STATE, lifecycle == null ? STATE_OFF : lifecycle)
            .putBoolean("active", active).putString("mode", mode == null ? "" : mode)
            .putString("message", message == null ? "" : message).putString("profile", profile == null ? "" : profile)
            .putString("profileId", profileId == null ? "" : profileId)
            .putString("endpoint", endpoint == null ? "" : endpoint).putString("coreVersion", coreVersion == null ? "" : coreVersion)
            .putLong("changedAt", now).putLong("heartbeatAt", active ? now : 0L)
            .putLong("serviceHeartbeatAt", now).putInt("servicePid", android.os.Process.myPid()).apply();
    }

    private void startBasicHeartbeat(final int token) {
        if (heartbeat != null && heartbeat != Thread.currentThread()) heartbeat.interrupt();
        heartbeat = new Thread(new Runnable() { @Override public void run() {
            try {
                while (running && token == generation && MODE_BASIC.equals(activeMode)) {
                    getSharedPreferences(PREFS, MODE_PRIVATE).edit().putLong("heartbeatAt", System.currentTimeMillis()).apply();
                    Thread.sleep(4000L);
                }
            } catch (InterruptedException ignored) { }
        } }, "SiteScopeBasicHeartbeat");
        heartbeat.start();
    }

    private synchronized void stopVpn(String message, boolean removeNotification) {
        stopVpn(message, removeNotification, STATE_OFF);
    }

    private synchronized void stopVpn(String message, boolean removeNotification, String lifecycle) {
        generation++; running = false; activeMode = ""; underlyingNetwork = null;
        if (worker != null) { worker.interrupt(); worker = null; }
        if (watchdog != null && watchdog != Thread.currentThread()) watchdog.interrupt(); watchdog = null;
        if (heartbeat != null && heartbeat != Thread.currentThread()) heartbeat.interrupt(); heartbeat = null;
        if (healthProbe != null && healthProbe != Thread.currentThread()) healthProbe.interrupt(); healthProbe = null;
        if (xrayRuntime != null) { xrayRuntime.stop(); xrayRuntime = null; }
        if (tunnel != null) { try { tunnel.close(); } catch (Exception ignored) { } tunnel = null; }
        updateState(lifecycle, false, "", message, "", "", "", "");
        stopForeground(removeNotification);
    }

    private void cancelPendingStart() {
        startRequest++;
        XrayProfilePreflight.cancel();
        XrayRuntime runtime = xrayRuntime;
        if (runtime != null) runtime.cancelProbe();
        Thread pending = starter;
        starter = null;
        if (pending != null && pending != Thread.currentThread()) pending.interrupt();
    }

    private static boolean supportsArm64() { for (String abi : Build.SUPPORTED_ABIS) if ("arm64-v8a".equalsIgnoreCase(abi)) return true; return false; }
    private static String normalizeMode(String mode) { if (MODE_XRAY.equalsIgnoreCase(mode)) return MODE_XRAY; if (MODE_BASIC.equalsIgnoreCase(mode)) return MODE_BASIC; return ""; }
    private static String safeMessage(Throwable e) { String value = e.getMessage(); return value == null || value.length() == 0 ? e.getClass().getSimpleName() : value; }

    @Override public void onRevoke() { cancelPendingStart(); stopVpn("VPN отозван системой", true); stopSelf(); super.onRevoke(); }
    @Override public void onDestroy() {
        servicePulseRunning = false;
        if (servicePulse != null && servicePulse != Thread.currentThread()) servicePulse.interrupt();
        servicePulse = null;
        cancelPendingStart();
        android.content.SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String lifecycle = prefs.getString(KEY_STATE, STATE_OFF);
        String message = prefs.getString("message", "Служба остановлена");
        stopVpn(STATE_ERROR.equals(lifecycle) ? message : "Служба остановлена", true, STATE_ERROR.equals(lifecycle) ? STATE_ERROR : STATE_OFF);
        super.onDestroy();
    }
}
