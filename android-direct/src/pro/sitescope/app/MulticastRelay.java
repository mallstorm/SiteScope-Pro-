package pro.sitescope.app;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.wifi.WifiManager;

import java.io.BufferedOutputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Joins one authorized multicast group and exposes its MPEG-TS payload through
 * a loopback HTTP endpoint for Android's media stack. It performs no discovery
 * and no authorization bypass; the caller provides the exact stream address.
 */
public final class MulticastRelay {
    public interface Listener {
        void onStatus(String status);
        void onStats(long packets, long bytes, long lostPackets, String networkInterface);
        void onError(String message);
    }

    private final Context context;
    private final MulticastSupport.Source source;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket server;
    private MulticastSocket multicast;
    private Socket client;
    private Thread thread;
    private WifiManager.MulticastLock wifiLock;
    private NetworkInterface networkInterface;

    public MulticastRelay(Context context, String url, Listener listener) throws Exception {
        this.context = context.getApplicationContext();
        this.source = MulticastSupport.parse(url);
        this.listener = listener;
    }

    public String start() throws Exception {
        if (running.get()) return localUrl();
        networkInterface = chooseInterface(context);
        if (networkInterface == null) throw new Exception("Не найден активный интерфейс с поддержкой multicast");
        acquireWifiLock();
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 1);
        running.set(true);
        thread = new Thread(new Runnable() { @Override public void run() { serve(); } }, "SiteScope-MulticastRelay");
        thread.start();
        notifyStatus("Ожидание плеера · " + source.label() + " · " + networkInterface.getName());
        return localUrl();
    }

    public String localUrl() {
        return server == null ? "" : "http://127.0.0.1:" + server.getLocalPort() + "/live.ts";
    }

    public void stop() {
        running.set(false);
        closeQuietly(client);
        if (multicast != null) {
            try { multicast.leaveGroup(new InetSocketAddress(source.host, source.port), networkInterface); } catch (Exception ignored) { }
            multicast.close();
        }
        if (server != null) try { server.close(); } catch (Exception ignored) { }
        if (thread != null) thread.interrupt();
        releaseWifiLock();
    }

    private void serve() {
        long packets = 0L;
        long bytes = 0L;
        long lost = 0L;
        int previousSequence = -1;
        try {
            client = server.accept();
            client.setTcpNoDelay(true);
            client.setSendBufferSize(512 * 1024);
            OutputStream raw = new BufferedOutputStream(client.getOutputStream(), 256 * 1024);
            String header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: video/mp2t\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n";
            raw.write(header.getBytes(Charset.forName("US-ASCII")));
            raw.flush();

            multicast = new MulticastSocket(null);
            multicast.setReuseAddress(true);
            multicast.bind(new InetSocketAddress(source.port));
            multicast.setNetworkInterface(networkInterface);
            multicast.setReceiveBufferSize(2 * 1024 * 1024);
            multicast.setSoTimeout(1200);
            multicast.joinGroup(new InetSocketAddress(source.host, source.port), networkInterface);
            notifyStatus("IGMP join выполнен · " + source.label() + " · relay " + localUrl());

            byte[] buffer = new byte[65535];
            long lastStats = System.currentTimeMillis();
            while (running.get()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    multicast.receive(packet);
                    MulticastSupport.Segment media = MulticastSupport.mediaPayload(packet.getData(), packet.getOffset(), packet.getLength(), source.rtp);
                    if (!media.valid || media.length == 0) continue;
                    if (source.rtp && previousSequence >= 0) {
                        int expected = (previousSequence + 1) & 0xffff;
                        if (media.sequence != expected) lost += (media.sequence - expected + 65536) & 0xffff;
                    }
                    if (source.rtp) previousSequence = media.sequence;
                    raw.write(packet.getData(), media.offset, media.length);
                    packets++;
                    bytes += media.length;
                    if ((packets & 31L) == 0L) raw.flush();
                    if (System.currentTimeMillis() - lastStats >= 1000L) {
                        notifyStats(packets, bytes, lost, networkInterface.getName());
                        lastStats = System.currentTimeMillis();
                    }
                } catch (java.net.SocketTimeoutException timeout) {
                    notifyStatus("Ожидание multicast-пакетов · " + source.label());
                }
            }
            raw.flush();
        } catch (Exception error) {
            if (running.get()) notifyError(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        } finally {
            stop();
        }
    }

    public static NetworkInterface chooseInterface(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                Network network = cm.getActiveNetwork();
                LinkProperties lp = network == null ? null : cm.getLinkProperties(network);
                if (lp != null && lp.getInterfaceName() != null) {
                    NetworkInterface candidate = NetworkInterface.getByName(lp.getInterfaceName());
                    if (usable(candidate)) return candidate;
                }
            }
        } catch (Exception ignored) { }
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return null;
            for (NetworkInterface candidate : Collections.list(interfaces)) if (usable(candidate)) return candidate;
        } catch (Exception ignored) { }
        return null;
    }

    private static boolean usable(NetworkInterface value) throws SocketException {
        if (value == null || !value.isUp() || value.isLoopback() || !value.supportsMulticast()) return false;
        Enumeration<java.net.InetAddress> addresses = value.getInetAddresses();
        while (addresses.hasMoreElements()) if (addresses.nextElement() instanceof java.net.Inet4Address) return true;
        return false;
    }

    private void acquireWifiLock() {
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            if (wifi != null) {
                wifiLock = wifi.createMulticastLock("sitescope-direct-multicast");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Exception ignored) { }
    }

    private void releaseWifiLock() {
        try { if (wifiLock != null && wifiLock.isHeld()) wifiLock.release(); } catch (Exception ignored) { }
        wifiLock = null;
    }

    private void notifyStatus(String value) { if (listener != null) listener.onStatus(value); }
    private void notifyStats(long packets, long bytes, long lost, String iface) { if (listener != null) listener.onStats(packets, bytes, lost, iface); }
    private void notifyError(String value) { if (listener != null) listener.onError(value); }
    private static void closeQuietly(Socket socket) { if (socket != null) try { socket.close(); } catch (Exception ignored) { } }
}
