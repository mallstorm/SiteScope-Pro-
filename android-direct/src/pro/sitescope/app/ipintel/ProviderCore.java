package pro.sitescope.app.ipintel;

import java.math.BigInteger;
import java.net.InetAddress;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ProviderCore {
    private ProviderCore() { }

    public static final class CidrRange {
        public final String cidr;
        public final String first;
        public final String last;
        public final BigInteger count;
        public final int bits;
        private final BigInteger startValue;
        private final BigInteger endValue;

        CidrRange(String cidr, String first, String last, BigInteger count, int bits, BigInteger startValue, BigInteger endValue) {
            this.cidr = cidr;
            this.first = first;
            this.last = last;
            this.count = count;
            this.bits = bits;
            this.startValue = startValue;
            this.endValue = endValue;
        }

        public boolean contains(String address) {
            try {
                byte[] bytes = InetAddress.getByName(stripZone(address)).getAddress();
                if (bytes.length * 8 != bits) return false;
                BigInteger value = unsigned(bytes);
                return value.compareTo(startValue) >= 0 && value.compareTo(endValue) <= 0;
            } catch (Exception ignored) { return false; }
        }
    }

    public static String normalizeProviderQuery(String input) {
        String value = input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
        value = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        value = value
            .replace('і', 'и').replace('ї', 'и').replace('є', 'е').replace('ґ', 'г')
            .replace("ё", "е")
            .replaceAll("[^a-zа-я0-9]+", "");
        return value;
    }

    public static List<String> aliasCandidates(String input) {
        String normalized = normalizeProviderQuery(input);
        Set<String> output = new LinkedHashSet<String>();
        String upper = input == null ? "" : input.trim().toUpperCase(Locale.US);
        if (upper.matches("AS?\\d+")) output.add(normalizeAsn(upper));

        if (normalized.equals("интертелеком") || normalized.equals("intertelecom") ||
            normalized.equals("intertelekom") || normalized.equals("intertelecomua") ||
            normalized.equals("интертелекомua") || normalized.equals("intertelecomukraine")) {
            output.add("AS31343");
        }
        return new ArrayList<String>(output);
    }

    public static String normalizeAsn(String input) {
        String value = input == null ? "" : input.trim().toUpperCase(Locale.US).replaceAll("\\s+", "");
        if (value.startsWith("AS")) value = value.substring(2);
        if (!value.matches("\\d+")) throw new IllegalArgumentException("Некорректный ASN: " + input);
        return "AS" + value;
    }

    public static CidrRange range(String cidr) {
        try {
            String value = cidr == null ? "" : cidr.trim();
            int slash = value.indexOf('/');
            if (slash <= 0 || slash == value.length() - 1) throw new IllegalArgumentException("Ожидается CIDR, например 192.0.2.0/24");
            String host = stripZone(value.substring(0, slash));
            InetAddress address = InetAddress.getByName(host);
            byte[] bytes = address.getAddress();
            int bits = bytes.length * 8;
            int prefix = Integer.parseInt(value.substring(slash + 1));
            if (prefix < 0 || prefix > bits) throw new IllegalArgumentException("Префикс должен быть 0–" + bits);
            BigInteger addressValue = unsigned(bytes);
            int hostBits = bits - prefix;
            BigInteger all = BigInteger.ONE.shiftLeft(bits).subtract(BigInteger.ONE);
            BigInteger hostMask = hostBits == 0 ? BigInteger.ZERO : BigInteger.ONE.shiftLeft(hostBits).subtract(BigInteger.ONE);
            BigInteger networkMask = all.xor(hostMask);
            BigInteger firstValue = addressValue.and(networkMask);
            BigInteger lastValue = firstValue.add(hostMask);
            String first = format(firstValue, bytes.length);
            String last = format(lastValue, bytes.length);
            String canonical = first + "/" + prefix;
            return new CidrRange(canonical, first, last, hostMask.add(BigInteger.ONE), bits, firstValue, lastValue);
        } catch (IllegalArgumentException error) { throw error; }
        catch (Exception error) { throw new IllegalArgumentException(error.getMessage() == null ? "Некорректный CIDR" : error.getMessage()); }
    }

    public static List<CidrRange> ranges(List<String> cidrs, String currentIp) {
        if (cidrs == null) return Collections.emptyList();
        List<CidrRange> output = new ArrayList<CidrRange>();
        Set<String> seen = new LinkedHashSet<String>();
        for (String cidr : cidrs) {
            if (cidr == null || cidr.trim().length() == 0) continue;
            try {
                CidrRange range = range(cidr.trim());
                if (seen.add(range.cidr)) output.add(range);
            } catch (Exception ignored) { }
        }
        Collections.sort(output, new java.util.Comparator<CidrRange>() {
            @Override public int compare(CidrRange a, CidrRange b) {
                if (a.bits != b.bits) return a.bits < b.bits ? -1 : 1;
                int first = a.startValue.compareTo(b.startValue);
                if (first != 0) return first;
                return a.endValue.compareTo(b.endValue);
            }
        });
        return output;
    }

    public static String csvEscape(String value) {
        String text = value == null ? "" : value;
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }

    private static BigInteger unsigned(byte[] bytes) { return new BigInteger(1, bytes); }

    private static String format(BigInteger value, int byteLength) throws Exception {
        byte[] raw = value.toByteArray();
        byte[] bytes = new byte[byteLength];
        int sourceStart = Math.max(0, raw.length - byteLength);
        int length = Math.min(raw.length, byteLength);
        System.arraycopy(raw, sourceStart, bytes, byteLength - length, length);
        return InetAddress.getByAddress(bytes).getHostAddress();
    }

    private static String stripZone(String address) {
        if (address == null) return "";
        int percent = address.indexOf('%');
        return percent >= 0 ? address.substring(0, percent) : address;
    }
}
