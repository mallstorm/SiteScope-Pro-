package pro.sitescope.app;

import android.content.Context;
import android.net.wifi.WifiManager;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;

/** Bounded direct multicast probe used only for streams authorized by the user. */
public final class MulticastProbe {
    private MulticastProbe() { }

    public static IptvEngine.ProbeResult probe(Context context, String playbackUrl, int timeoutMs) {
        IptvEngine.ProbeResult result = new IptvEngine.ProbeResult();
        MulticastSocket socket = null;
        WifiManager.MulticastLock lock = null;
        long started = System.currentTimeMillis();
        try {
            MulticastSupport.Source source = MulticastSupport.parse(playbackUrl);
            NetworkInterface networkInterface = MulticastRelay.chooseInterface(context);
            if (networkInterface == null) throw new Exception("Не найден интерфейс с поддержкой multicast");
            try {
                WifiManager manager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (manager != null) {
                    lock = manager.createMulticastLock("sitescope-probe-multicast");
                    lock.setReferenceCounted(false);
                    lock.acquire();
                }
            } catch (Exception ignored) { }
            socket = new MulticastSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(source.port));
            socket.setNetworkInterface(networkInterface);
            socket.setReceiveBufferSize(1024 * 1024);
            socket.setSoTimeout(Math.max(350, Math.min(timeoutMs, 2500)));
            socket.joinGroup(new InetSocketAddress(source.host, source.port), networkInterface);
            ByteArrayOutputStream collected = new ByteArrayOutputStream(128 * 1024);
            byte[] packetBuffer = new byte[65535];
            long firstPacketAt = 0L;
            long until = started + Math.max(1800L, Math.min(6000L, timeoutMs * 2L));
            int packets = 0;
            while (System.currentTimeMillis() < until && collected.size() < IptvEngine.MAX_PROBE_BYTES) {
                try {
                    DatagramPacket packet = new DatagramPacket(packetBuffer, packetBuffer.length);
                    socket.receive(packet);
                    if (firstPacketAt == 0L) firstPacketAt = System.currentTimeMillis();
                    MulticastSupport.Segment media = MulticastSupport.mediaPayload(packet.getData(), packet.getOffset(), packet.getLength(), source.rtp);
                    if (!media.valid || media.length == 0) continue;
                    int available = Math.min(media.length, IptvEngine.MAX_PROBE_BYTES - collected.size());
                    collected.write(packet.getData(), media.offset, available);
                    packets++;
                } catch (java.net.SocketTimeoutException ignored) { }
            }
            try { socket.leaveGroup(new InetSocketAddress(source.host, source.port), networkInterface); } catch (Exception ignored) { }
            byte[] data = collected.toByteArray();
            if (data.length == 0) throw new Exception("Multicast-пакеты не получены. Проверьте IGMP, Wi-Fi multicast и выбранную сеть");
            long elapsed = Math.max(1L, System.currentTimeMillis() - (firstPacketAt == 0L ? started : firstPacketAt));
            result.latencyMs = Math.max(1L, (firstPacketAt == 0L ? System.currentTimeMillis() : firstPacketAt) - started);
            result.bitrateKbps = Math.max(0L, (data.length * 8L) / elapsed);
            IptvEngine.fillMpegTsProbe(result, data, source.rtp ? "RTP / MPEG-TS" : "UDP / MPEG-TS",
                "Прямой multicast · IGMP join · " + packets + " пакетов · " + networkInterface.getName());
        } catch (Exception error) {
            result.error = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            result.evidence = "Прямой multicast не подтверждён";
        } finally {
            if (socket != null) socket.close();
            try { if (lock != null && lock.isHeld()) lock.release(); } catch (Exception ignored) { }
        }
        return result;
    }
}
