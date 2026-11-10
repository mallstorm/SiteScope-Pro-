package pro.sitescope.app;

public final class MulticastSupportHarness {
    public static void main(String[] args) throws Exception {
        MulticastSupport.Source udp = MulticastSupport.parse("udp://@239.10.20.30:1234");
        check(!udp.rtp && udp.port == 1234 && "239.10.20.30".equals(udp.host), "UDP parse");
        MulticastSupport.Source rtp = MulticastSupport.parse("rtp://239.1.1.1:5000");
        check(rtp.rtp && rtp.port == 5000, "RTP parse");
        byte[] ts = new byte[376]; ts[0] = 0x47; ts[188] = 0x47;
        check(MulticastSupport.looksLikeMpegTs(ts, 0, ts.length), "TS sync");
        byte[] packet = new byte[12 + ts.length];
        packet[0] = (byte) 0x80; packet[1] = 33; packet[2] = 0x12; packet[3] = 0x34;
        System.arraycopy(ts, 0, packet, 12, ts.length);
        MulticastSupport.Segment segment = MulticastSupport.mediaPayload(packet, 0, packet.length, true);
        check(segment.valid && segment.offset == 12 && segment.length == ts.length && segment.sequence == 0x1234, "RTP strip");
        check(MulticastSupport.looksLikeMpegTs(packet, segment.offset, segment.length), "RTP TS payload");
        boolean rejected = false;
        try { MulticastSupport.parse("udp://192.168.1.5:1234"); } catch (Exception expected) { rejected = true; }
        check(rejected, "unicast reject");
        System.out.println("MULTICAST_SUPPORT_HARNESS_PASS");
    }
    private static void check(boolean value, String name) {
        if (!value) throw new IllegalStateException("FAIL: " + name);
        System.out.println("PASS: " + name);
    }
}
