package pro.sitescope.app;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/** Pure Java helpers for parsing UDP/RTP multicast addresses and RTP payloads. */
public final class MulticastSupport {
    private MulticastSupport() { }

    public static final class Source {
        public final String original;
        public final String host;
        public final int port;
        public final boolean rtp;

        Source(String original, String host, int port, boolean rtp) {
            this.original = original;
            this.host = host;
            this.port = port;
            this.rtp = rtp;
        }

        public String label() { return (rtp ? "RTP" : "UDP") + " " + host + ":" + port; }
    }

    public static final class Segment {
        public final int offset;
        public final int length;
        public final int sequence;
        public final boolean valid;

        Segment(int offset, int length, int sequence, boolean valid) {
            this.offset = offset;
            this.length = length;
            this.sequence = sequence;
            this.valid = valid;
        }
    }

    public static Source parse(String value) throws Exception {
        if (value == null) throw new Exception("Адрес multicast отсутствует");
        String raw = value.trim();
        String lower = raw.toLowerCase(Locale.US);
        boolean rtp;
        if (lower.startsWith("udp://")) rtp = false;
        else if (lower.startsWith("rtp://")) rtp = true;
        else throw new Exception("Поддерживаются адреса udp://@group:port и rtp://@group:port");
        String normalized = raw.replaceFirst("(?i)^(udp|rtp)://@", "$1://");
        URI uri = new URI(normalized);
        String host = uri.getHost();
        int port = uri.getPort();
        if (host == null || port < 1 || port > 65535) throw new Exception("Некорректный multicast адрес");
        InetAddress address = InetAddress.getByName(host);
        if (!address.isMulticastAddress()) throw new Exception("Адрес " + host + " не является multicast");
        return new Source(raw, host, port, rtp);
    }

    /**
     * Returns media payload bounds. For UDP the entire datagram is media.
     * For RTP it validates v2 and skips CSRC/extension/padding headers.
     */
    public static Segment mediaPayload(byte[] packet, int offset, int length, boolean rtp) {
        if (packet == null || offset < 0 || length <= 0 || offset + length > packet.length) {
            return new Segment(0, 0, -1, false);
        }
        if (!rtp) return new Segment(offset, length, -1, true);
        if (length < 12) return new Segment(0, 0, -1, false);
        int first = packet[offset] & 0xff;
        int version = (first >> 6) & 0x03;
        if (version != 2) return new Segment(0, 0, -1, false);
        boolean padding = (first & 0x20) != 0;
        boolean extension = (first & 0x10) != 0;
        int csrc = first & 0x0f;
        int index = offset + 12 + csrc * 4;
        int end = offset + length;
        if (index > end) return new Segment(0, 0, -1, false);
        if (extension) {
            if (index + 4 > end) return new Segment(0, 0, -1, false);
            int words = ((packet[index + 2] & 0xff) << 8) | (packet[index + 3] & 0xff);
            index += 4 + words * 4;
            if (index > end) return new Segment(0, 0, -1, false);
        }
        if (padding) {
            int pad = packet[end - 1] & 0xff;
            if (pad <= 0 || pad > end - index) return new Segment(0, 0, -1, false);
            end -= pad;
        }
        int sequence = ((packet[offset + 2] & 0xff) << 8) | (packet[offset + 3] & 0xff);
        return new Segment(index, Math.max(0, end - index), sequence, end > index);
    }

    public static boolean looksLikeMpegTs(byte[] data, int offset, int length) {
        if (data == null || length < 188 * 2) return false;
        int end = Math.min(offset + length, data.length);
        int max = Math.min(offset + 188, end - 188 * 2);
        for (int i = offset; i <= max; i++) {
            if ((data[i] & 0xff) == 0x47 && (data[i + 188] & 0xff) == 0x47) return true;
        }
        return false;
    }
}
