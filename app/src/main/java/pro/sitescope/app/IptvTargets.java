package pro.sitescope.app;

import java.util.*;

/** Compact address plan: memory depends on input ranges, never on the number of hosts. */
public final class IptvTargets implements Iterable<String> {
    private final List<long[]> ranges;
    private final List<String> names;
    private final long count;
    private IptvTargets(List<long[]> ranges, List<String> names) {
        this.ranges = ranges; this.names = names;
        long n = names.size();
        for (long[] range : ranges) n += range[1] - range[0] + 1;
        count = n;
    }
    public long count() { return count; }

    public static IptvTargets parse(String raw) throws Exception {
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException("Укажите IP, домен, CIDR или диапазон адресов");
        List<long[]> ranges = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (String token : raw.trim().split("[,;\\s]+")) {
            if (token.contains("/")) {
                String[] parts = token.split("/", -1);
                if (parts.length != 2) throw new IllegalArgumentException("Некорректный CIDR: " + token);
                long ip = ipv4(parts[0]); int prefix;
                if (parts[1].contains(".")) {
                    long mask = ipv4(parts[1]); prefix = Long.bitCount(mask);
                    if (mask != (prefix == 0 ? 0 : (0xffffffffL << (32 - prefix)) & 0xffffffffL))
                        throw new IllegalArgumentException("Некорректная маска: " + token);
                } else prefix = Integer.parseInt(parts[1]);
                if (prefix < 0 || prefix > 32) throw new IllegalArgumentException("Префикс CIDR должен быть от /0 до /32");
                long size = 1L << (32 - prefix);
                long network = ip & (prefix == 0 ? 0 : (0xffffffffL << (32 - prefix)) & 0xffffffffL);
                ranges.add(new long[]{prefix <= 30 ? network + 1 : network, network + size - (prefix <= 30 ? 2 : 1)});
            } else if (token.endsWith(".*")) {
                String base = token.substring(0, token.length() - 1);
                ranges.add(new long[]{ipv4(base + "1"), ipv4(base + "254")});
            } else if (token.matches("[0-9.]+-[0-9.]+")) {
                String[] parts = token.split("-", -1);
                if (parts.length != 2) throw new IllegalArgumentException("Некорректный диапазон: " + token);
                String end = parts[1].contains(".") ? parts[1] : parts[0].substring(0, parts[0].lastIndexOf('.') + 1) + parts[1];
                long first = ipv4(parts[0]), last = ipv4(end);
                if (last < first) throw new IllegalArgumentException("Конец диапазона меньше начала: " + token);
                ranges.add(new long[]{first, last});
            } else if (isIpv4(token)) {
                long ip = ipv4(token); ranges.add(new long[]{ip, ip});
            } else {
                // A single name/IPv6 literal does not expand into an address list.
                names.addAll(ScannerEngine.parseTargets(token));
            }
        }
        ranges.sort(Comparator.comparingLong(a -> a[0]));
        List<long[]> merged = new ArrayList<>();
        for (long[] range : ranges) {
            if (merged.isEmpty() || range[0] > merged.get(merged.size() - 1)[1] + 1) merged.add(range);
            else merged.get(merged.size() - 1)[1] = Math.max(merged.get(merged.size() - 1)[1], range[1]);
        }
        return new IptvTargets(merged, new ArrayList<>(names));
    }

    private static boolean isIpv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String part : parts) if (!part.matches("[0-9]{1,3}") || Integer.parseInt(part) > 255) return false;
        return true;
    }
    private static long ipv4(String text) {
        if (!isIpv4(text)) throw new IllegalArgumentException("Некорректный IPv4-адрес: " + text);
        long ip = 0; for (String part : text.split("\\.")) ip = (ip << 8) | Integer.parseInt(part);
        return ip;
    }
    private static String address(long ip) {
        return (ip >>> 24) + "." + ((ip >>> 16) & 255) + "." + ((ip >>> 8) & 255) + "." + (ip & 255);
    }
    @Override public Iterator<String> iterator() {
        return new Iterator<String>() {
            int range = 0, name = 0;
            long next = ranges.isEmpty() ? 0 : ranges.get(0)[0];
            public boolean hasNext() { return range < ranges.size() || name < names.size(); }
            public String next() {
                if (range < ranges.size()) {
                    String result = address(next++);
                    if (next > ranges.get(range)[1]) { range++; if (range < ranges.size()) next = ranges.get(range)[0]; }
                    return result;
                }
                if (name < names.size()) return names.get(name++);
                throw new NoSuchElementException();
            }
        };
    }
}
