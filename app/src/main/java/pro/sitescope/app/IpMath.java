package pro.sitescope.app;

import java.math.BigInteger;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class IpMath {
    private IpMath() { }

    public static final class Range {
        public final BigInteger start;
        public final BigInteger end;
        public final int bits;
        public Range(BigInteger start, BigInteger end, int bits) {
            this.start = start;
            this.end = end;
            this.bits = bits;
        }
        public String startIp() { return toIp(start, bits); }
        public String endIp() { return toIp(end, bits); }
        public long countSaturated() {
            BigInteger count = end.subtract(start).add(BigInteger.ONE);
            return count.bitLength() > 63 ? Long.MAX_VALUE : count.longValue();
        }
    }

    public static int familyBits(String value) {
        return value != null && value.trim().contains(":") ? 128 : 32;
    }

    public static BigInteger parseIp(String value) {
        try {
            byte[] bytes = InetAddress.getByName(value.trim()).getAddress();
            return new BigInteger(1, bytes);
        } catch (Exception error) {
            throw new IllegalArgumentException("Некорректный IP: " + value);
        }
    }

    public static String key(String value) {
        int bits = familyBits(value);
        BigInteger number = parseIp(value);
        int width = bits / 4;
        String hex = number.toString(16).toLowerCase(Locale.US);
        StringBuilder output = new StringBuilder(width);
        for (int i = hex.length(); i < width; i++) output.append('0');
        output.append(hex);
        return output.toString();
    }

    public static String toIp(BigInteger value, int bits) {
        try {
            int length = bits / 8;
            byte[] raw = value.toByteArray();
            byte[] bytes = new byte[length];
            int source = Math.max(0, raw.length - length);
            int count = Math.min(raw.length, length);
            System.arraycopy(raw, source, bytes, length - count, count);
            return InetAddress.getByAddress(bytes).getHostAddress();
        } catch (Exception error) {
            return value.toString();
        }
    }

    public static Range cidrToRange(String cidr) {
        if (cidr == null || !cidr.contains("/")) throw new IllegalArgumentException("Ожидается CIDR, например 192.168.1.0/24");
        String[] parts = cidr.trim().split("/", 2);
        int bits = familyBits(parts[0]);
        int prefix;
        try { prefix = Integer.parseInt(parts[1]); }
        catch (Exception error) { throw new IllegalArgumentException("Некорректная длина префикса"); }
        if (prefix < 0 || prefix > bits) throw new IllegalArgumentException("Префикс должен быть 0–" + bits);
        BigInteger ip = parseIp(parts[0]);
        int hostBits = bits - prefix;
        BigInteger mask = hostBits == 0 ? max(bits) : max(bits).shiftRight(hostBits).shiftLeft(hostBits);
        BigInteger start = ip.and(mask);
        BigInteger end = start.add(BigInteger.ONE.shiftLeft(hostBits).subtract(BigInteger.ONE));
        return new Range(start, end, bits);
    }

    public static List<String> rangeToCidrs(String startIp, String endIp) {
        int bits = familyBits(startIp);
        if (familyBits(endIp) != bits) throw new IllegalArgumentException("Начало и конец должны быть одной IP-семьи");
        return rangeToCidrs(parseIp(startIp), parseIp(endIp), bits);
    }

    public static List<String> rangeToCidrs(BigInteger start, BigInteger end, int bits) {
        if (start.compareTo(end) > 0) throw new IllegalArgumentException("Начало диапазона больше конца");
        List<String> output = new ArrayList<String>();
        BigInteger current = start;
        while (current.compareTo(end) <= 0) {
            int trailing = current.signum() == 0 ? bits : Math.min(bits, current.getLowestSetBit());
            BigInteger remaining = end.subtract(current).add(BigInteger.ONE);
            int maximumByRemaining = remaining.bitLength() - 1;
            int blockBits = Math.min(trailing, maximumByRemaining);
            int prefix = bits - blockBits;
            output.add(toIp(current, bits) + "/" + prefix);
            current = current.add(BigInteger.ONE.shiftLeft(blockBits));
        }
        return output;
    }

    public static List<Range> aggregate(List<Range> input) {
        if (input == null || input.isEmpty()) return new ArrayList<Range>();
        List<Range> sorted = new ArrayList<Range>(input);
        Collections.sort(sorted, new Comparator<Range>() {
            @Override public int compare(Range a, Range b) {
                int bits = a.bits - b.bits;
                return bits != 0 ? bits : a.start.compareTo(b.start);
            }
        });
        List<Range> output = new ArrayList<Range>();
        for (Range item : sorted) {
            if (output.isEmpty()) { output.add(item); continue; }
            Range last = output.get(output.size() - 1);
            if (last.bits == item.bits && item.start.compareTo(last.end.add(BigInteger.ONE)) <= 0) {
                output.set(output.size() - 1, new Range(last.start, last.end.max(item.end), last.bits));
            } else output.add(item);
        }
        return output;
    }

    public static Range parseRangeExpression(String value) {
        String input = value == null ? "" : value.trim();
        if (input.contains("/")) return cidrToRange(input);
        String separator = input.contains(" - ") ? " - " : (input.contains("-") ? "-" : null);
        if (separator == null) {
            int bits = familyBits(input);
            BigInteger number = parseIp(input);
            return new Range(number, number, bits);
        }
        String[] parts = input.split("\\s*-\\s*", 2);
        int bits = familyBits(parts[0]);
        if (familyBits(parts[1]) != bits) throw new IllegalArgumentException("IP-семьи диапазона не совпадают");
        BigInteger start = parseIp(parts[0]);
        BigInteger end = parseIp(parts[1]);
        if (start.compareTo(end) > 0) throw new IllegalArgumentException("Начало диапазона больше конца");
        return new Range(start, end, bits);
    }

    public static String subnetReport(String cidr) {
        Range range = cidrToRange(cidr);
        int prefix = Integer.parseInt(cidr.substring(cidr.indexOf('/') + 1));
        BigInteger size = range.end.subtract(range.start).add(BigInteger.ONE);
        StringBuilder out = new StringBuilder();
        out.append("Сеть: ").append(range.startIp()).append('/').append(prefix).append('\n');
        out.append("Начало: ").append(range.startIp()).append('\n');
        out.append("Конец: ").append(range.endIp()).append('\n');
        out.append("Адресов: ").append(size.toString());
        if (range.bits == 32) {
            out.append("\nМаска: ").append(prefixToMask(prefix));
            if (prefix <= 30) {
                out.append("\nПервый узел: ").append(toIp(range.start.add(BigInteger.ONE), 32));
                out.append("\nПоследний узел: ").append(toIp(range.end.subtract(BigInteger.ONE), 32));
                out.append("\nBroadcast: ").append(range.endIp());
            }
        }
        return out.toString();
    }

    public static String prefixToMask(int prefix) {
        if (prefix < 0 || prefix > 32) throw new IllegalArgumentException("IPv4-префикс должен быть 0–32");
        BigInteger mask = prefix == 0 ? BigInteger.ZERO : max(32).shiftRight(32 - prefix).shiftLeft(32 - prefix);
        return toIp(mask, 32);
    }

    public static String conversionReport(String input) {
        String value = input == null ? "" : input.trim();
        BigInteger number;
        int bits = 32;
        if (value.contains(".") || value.contains(":")) {
            bits = familyBits(value);
            number = parseIp(value);
        } else if (value.toLowerCase(Locale.US).startsWith("0x")) {
            number = new BigInteger(value.substring(2), 16);
        } else if (value.toLowerCase(Locale.US).startsWith("0b")) {
            number = new BigInteger(value.substring(2), 2);
        } else number = new BigInteger(value, 10);
        if (number.signum() < 0 || number.compareTo(max(bits)) > 0) throw new IllegalArgumentException("Число выходит за диапазон IPv" + bits);
        String binary = number.toString(2);
        StringBuilder padded = new StringBuilder();
        for (int i = binary.length(); i < bits; i++) padded.append('0');
        padded.append(binary);
        return "IP: " + toIp(number, bits) +
            "\nDecimal: " + number.toString(10) +
            "\nHex: 0x" + number.toString(16).toUpperCase(Locale.US) +
            "\nBinary: " + grouped(padded.toString(), 8);
    }

    public static String expandIpv6(String value) {
        BigInteger number = parseIp(value);
        String hex = number.toString(16);
        StringBuilder padded = new StringBuilder();
        for (int i = hex.length(); i < 32; i++) padded.append('0');
        padded.append(hex);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            if (i > 0) out.append(':');
            out.append(padded.substring(i * 4, i * 4 + 4));
        }
        return out.toString();
    }

    public static String compressIpv6(String value) {
        try { return InetAddress.getByName(value.trim()).getHostAddress(); }
        catch (Exception error) { throw new IllegalArgumentException("Некорректный IPv6"); }
    }

    private static String grouped(String value, int group) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            if (i > 0 && i % group == 0) out.append(' ');
            out.append(value.charAt(i));
        }
        return out.toString();
    }

    private static BigInteger max(int bits) {
        return BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE);
    }
}
