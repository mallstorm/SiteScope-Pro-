package pro.sitescope.app;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Minimal safe parser for mDNS PTR/SRV/TXT/A/AAAA records, including DNS name compression. */
public final class MdnsPacketParser {
    public static final int TYPE_A = 1;
    public static final int TYPE_PTR = 12;
    public static final int TYPE_TXT = 16;
    public static final int TYPE_AAAA = 28;
    public static final int TYPE_SRV = 33;

    public static final class Record {
        public String name = "";
        public int type;
        public long ttl;
        public String value = "";
        public String target = "";
        public int port;
        public String address = "";
        public final List<String> txt = new ArrayList<String>();
    }

    public static final class Result {
        public final List<Record> records = new ArrayList<Record>();
        public final Map<String, Record> srvByInstance = new LinkedHashMap<String, Record>();
        public final Map<String, List<String>> addressesByHost = new LinkedHashMap<String, List<String>>();
        public final Map<String, List<String>> txtByInstance = new LinkedHashMap<String, List<String>>();
        public final Set<String> ptrTargets = new LinkedHashSet<String>();
        public final Set<String> serviceTypes = new LinkedHashSet<String>();

        public String addressFor(String host) {
            List<String> values = addressesByHost.get(normalize(host));
            return values == null || values.isEmpty() ? "" : values.get(0);
        }

        public List<String> txtFor(String instance) {
            List<String> values = txtByInstance.get(normalize(instance));
            return values == null ? new ArrayList<String>() : values;
        }
    }

    private MdnsPacketParser() { }

    public static Result parse(byte[] data, int offset, int length) throws Exception {
        if (data == null || length < 12 || offset < 0 || offset + length > data.length) throw new IllegalArgumentException("Некорректный DNS-пакет");
        int end = offset + length;
        int qd = u16(data, offset + 4);
        int an = u16(data, offset + 6);
        int ns = u16(data, offset + 8);
        int ar = u16(data, offset + 10);
        int cursor = offset + 12;
        for (int i = 0; i < qd; i++) {
            Name name = readName(data, cursor, offset, end);
            cursor = name.next;
            if (cursor + 4 > end) throw new IllegalArgumentException("Обрезан DNS question");
            cursor += 4;
        }
        Result result = new Result();
        int total = an + ns + ar;
        for (int i = 0; i < total; i++) {
            Name owner = readName(data, cursor, offset, end);
            cursor = owner.next;
            if (cursor + 10 > end) throw new IllegalArgumentException("Обрезан DNS record");
            int type = u16(data, cursor);
            long ttl = u32(data, cursor + 4);
            int rdLength = u16(data, cursor + 8);
            int rdata = cursor + 10;
            int next = rdata + rdLength;
            if (next > end) throw new IllegalArgumentException("Обрезаны DNS RDATA");
            Record record = new Record();
            record.name = owner.value;
            record.type = type;
            record.ttl = ttl;
            if (type == TYPE_A && rdLength == 4) {
                record.address = InetAddress.getByAddress(copy(data, rdata, 4)).getHostAddress();
                record.value = record.address;
                addAddress(result, record.name, record.address);
            } else if (type == TYPE_AAAA && rdLength == 16) {
                record.address = InetAddress.getByAddress(copy(data, rdata, 16)).getHostAddress();
                record.value = record.address;
                addAddress(result, record.name, record.address);
            } else if (type == TYPE_PTR) {
                Name value = readName(data, rdata, offset, end);
                record.value = value.value;
                if (record.value.length() > 0) result.ptrTargets.add(record.value);
                if (isServiceType(record.name)) result.serviceTypes.add(record.name);
                if (isServiceType(record.value)) result.serviceTypes.add(record.value);
            } else if (type == TYPE_SRV && rdLength >= 6) {
                record.port = u16(data, rdata + 4);
                Name target = readName(data, rdata + 6, offset, end);
                record.target = target.value;
                record.value = target.value + ":" + record.port;
                result.srvByInstance.put(normalize(record.name), record);
                String serviceType = serviceTypeFromInstance(record.name);
                if (serviceType.length() > 0) result.serviceTypes.add(serviceType);
            } else if (type == TYPE_TXT) {
                int pos = rdata;
                while (pos < next) {
                    int size = data[pos++] & 0xff;
                    if (pos + size > next) break;
                    String value = new String(data, pos, size, "UTF-8");
                    record.txt.add(value);
                    pos += size;
                }
                record.value = join(record.txt, "; ");
                result.txtByInstance.put(normalize(record.name), new ArrayList<String>(record.txt));
            }
            result.records.add(record);
            cursor = next;
        }
        return result;
    }

    public static String serviceTypeFromInstance(String name) {
        String value = normalize(name);
        int tcp = value.indexOf("._tcp.local");
        int udp = value.indexOf("._udp.local");
        int end = tcp >= 0 ? tcp + "._tcp.local".length() : (udp >= 0 ? udp + "._udp.local".length() : -1);
        if (end < 0) return "";
        if (value.startsWith("_")) return value.substring(0, end);
        int marker = value.indexOf("._");
        if (marker >= 0) return value.substring(marker + 1, end);
        int first = value.indexOf('_');
        return first >= 0 ? value.substring(first, end) : value.substring(0, end);
    }

    public static String instanceLabel(String instance) {
        String value = trimDot(instance);
        int marker = value.indexOf("._");
        return marker > 0 ? value.substring(0, marker) : value;
    }

    private static boolean isServiceType(String name) {
        String value = normalize(name);
        return value.startsWith("_") && (value.contains("._tcp.local") || value.contains("._udp.local"));
    }

    private static void addAddress(Result result, String host, String address) {
        String key = normalize(host);
        List<String> values = result.addressesByHost.get(key);
        if (values == null) { values = new ArrayList<String>(); result.addressesByHost.put(key, values); }
        if (!values.contains(address)) values.add(address);
    }

    private static Name readName(byte[] data, int position, int base, int end) throws Exception {
        StringBuilder out = new StringBuilder();
        int cursor = position;
        int next = position;
        boolean jumped = false;
        int jumps = 0;
        Set<Integer> visited = new LinkedHashSet<Integer>();
        while (cursor < end) {
            int size = data[cursor] & 0xff;
            if (size == 0) {
                cursor++;
                if (!jumped) next = cursor;
                return new Name(out.toString(), next);
            }
            if ((size & 0xc0) == 0xc0) {
                if (cursor + 1 >= end) throw new IllegalArgumentException("Обрезан DNS pointer");
                int pointer = ((size & 0x3f) << 8) | (data[cursor + 1] & 0xff);
                int absolute = base + pointer;
                if (absolute < base || absolute >= end || !visited.add(absolute) || ++jumps > 32) throw new IllegalArgumentException("DNS pointer loop");
                if (!jumped) next = cursor + 2;
                jumped = true;
                cursor = absolute;
                continue;
            }
            if ((size & 0xc0) != 0 || size > 63 || cursor + 1 + size > end) throw new IllegalArgumentException("Некорректная DNS label");
            if (out.length() > 0) out.append('.');
            out.append(new String(data, cursor + 1, size, "UTF-8"));
            cursor += 1 + size;
            if (!jumped) next = cursor;
        }
        throw new IllegalArgumentException("DNS name без окончания");
    }

    private static int u16(byte[] data, int pos) { return ((data[pos] & 0xff) << 8) | (data[pos + 1] & 0xff); }
    private static long u32(byte[] data, int pos) { return ((long)(data[pos] & 0xff) << 24) | ((long)(data[pos+1] & 0xff) << 16) | ((long)(data[pos+2] & 0xff) << 8) | (long)(data[pos+3] & 0xff); }
    private static byte[] copy(byte[] data, int pos, int size) { byte[] out = new byte[size]; System.arraycopy(data, pos, out, 0, size); return out; }
    private static String normalize(String value) { return trimDot(value == null ? "" : value.trim().toLowerCase()); }
    private static String trimDot(String value) { while (value.endsWith(".")) value = value.substring(0, value.length()-1); return value; }
    private static String join(List<String> values, String delimiter) { StringBuilder out = new StringBuilder(); for (String value : values) { if (out.length()>0) out.append(delimiter); out.append(value); } return out.toString(); }

    private static final class Name {
        final String value; final int next;
        Name(String value, int next) { this.value = trimDot(value); this.next = next; }
    }
}
