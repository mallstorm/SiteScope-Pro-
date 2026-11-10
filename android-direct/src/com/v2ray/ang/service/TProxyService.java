package com.v2ray.ang.service;

import android.content.Context;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Minimal Java bridge matching the JNI registration contract bundled by v2rayNG. */
public final class TProxyService {
    private static native void TProxyStartService(String configPath, int fd);
    private static native void TProxyStopService();
    private static native long[] TProxyGetStats();

    static { System.loadLibrary("hev-socks5-tunnel"); }

    private final Context context;
    private final ParcelFileDescriptor vpnInterface;
    private final int socksPort;
    private boolean started;
    private long startedAt;

    public TProxyService(Context context, ParcelFileDescriptor vpnInterface, int socksPort) {
        this.context = context.getApplicationContext();
        this.vpnInterface = vpnInterface;
        this.socksPort = socksPort;
    }

    public void startTun2Socks() throws Exception {
        synchronized (this) { if (started) return; }
        File file = new File(context.getFilesDir(), "sitescope-hev-socks5-tunnel.yaml");
        String config = "tunnel:\n" +
            "  mtu: 1500\n" +
            "  ipv4: 10.10.14.1\n" +
            "  ipv6: 'fc00::10:10:14:1'\n" +
            "socks5:\n" +
            "  port: " + socksPort + "\n" +
            "  address: 127.0.0.1\n" +
            "  udp: 'udp'\n" +
            "misc:\n" +
            "  tcp-read-write-timeout: 300000\n" +
            "  udp-read-write-timeout: 60000\n" +
            "  log-level: debug\n";
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(config.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } finally {
            out.close();
        }
        try {
            TProxyStartService(file.getAbsolutePath(), vpnInterface.getFd());
            synchronized (this) { started = true; startedAt = System.currentTimeMillis(); }
        } catch (Throwable e) {
            synchronized (this) { started = false; startedAt = 0L; }
            throw new IllegalStateException("HEV JNI start failed: " + safeMessage(e), e);
        }
    }

    public void stopTun2Socks() {
        synchronized (this) { if (!started) return; started = false; startedAt = 0L; }
        try { TProxyStopService(); } catch (Throwable ignored) { }
    }

    public synchronized boolean isStarted() { return started; }
    public synchronized long startedAt() { return startedAt; }

    public long[] stats() {
        synchronized (this) { if (!started) return null; }
        try { return TProxyGetStats(); } catch (Throwable ignored) { return null; }
    }

    private static String safeMessage(Throwable e) {
        String value = e.getMessage();
        return value == null || value.length() == 0 ? e.getClass().getSimpleName() : value;
    }
}
