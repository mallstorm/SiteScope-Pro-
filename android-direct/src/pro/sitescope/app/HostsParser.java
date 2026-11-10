package pro.sitescope.app;

import java.net.IDN;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Strict hosts parser, canonicalizer and conflict validator. */
public final class HostsParser {
    public static final String BEGIN = "# BEGIN SITESCOPE PRO MANAGED HOSTS";
    public static final String END = "# END SITESCOPE PRO MANAGED HOSTS";

    public static final class Issue {
        public final int line;
        public final boolean error;
        public final String message;
        Issue(int line, boolean error, String message) { this.line = line; this.error = error; this.message = message; }
        @Override public String toString() { return (error ? "Ошибка" : "Предупреждение") + (line > 0 ? " в строке " + line : "") + ": " + message; }
    }

    public static final class Result {
        public final List<HostsEntry> entries = new ArrayList<HostsEntry>();
        public final List<Issue> issues = new ArrayList<Issue>();
        public final List<String> passthrough = new ArrayList<String>();
        public boolean hasErrors() { for (Issue i : issues) if (i.error) return true; return false; }
        public int enabledCount() { int n = 0; for (HostsEntry e : entries) if (e.enabled) n++; return n; }
    }

    private HostsParser() { }

    public static Result parse(String raw, String profileId) {
        Result result = new Result();
        String[] lines = (raw == null ? "" : raw.replace("\r\n", "\n").replace('\r', '\n')).split("\n", -1);
        int position = 0;
        for (int index = 0; index < lines.length; index++) {
            String original = lines[index];
            String trimmed = original.trim();
            if (trimmed.length() == 0 || trimmed.equals(BEGIN) || trimmed.equals(END)) { result.passthrough.add(original); continue; }
            boolean disabled = false;
            if (trimmed.startsWith("#")) {
                String candidate = trimmed.substring(1).trim();
                if (!looksLikeMapping(candidate)) { result.passthrough.add(original); continue; }
                disabled = true; trimmed = candidate;
            }
            String comment = "";
            int hash = trimmed.indexOf('#');
            if (hash >= 0) { comment = trimmed.substring(hash + 1).trim(); trimmed = trimmed.substring(0, hash).trim(); }
            String[] parts = trimmed.split("\\s+");
            if (parts.length < 2) { result.issues.add(new Issue(index + 1, true, "нужны IP-адрес и хотя бы одно имя узла")); continue; }
            String normalizedAddress = normalizeAddress(parts[0]);
            if (normalizedAddress == null) { result.issues.add(new Issue(index + 1, true, "некорректный IPv4/IPv6: " + parts[0])); continue; }
            if (!normalizedAddress.equals(parts[0])) result.issues.add(new Issue(index + 1, false, "IP нормализован: " + parts[0] + " → " + normalizedAddress));
            HostsEntry entry = new HostsEntry(); entry.profileId = profileId; entry.position = position++; entry.enabled = !disabled; entry.address = normalizedAddress; entry.comment = comment; entry.source = "import";
            Set<String> seen = new LinkedHashSet<String>();
            for (int i = 1; i < parts.length; i++) {
                String host = normalizeHostname(parts[i]);
                if (host == null) result.issues.add(new Issue(index + 1, true, "некорректное имя узла: " + parts[i]));
                else if (!seen.add(host)) result.issues.add(new Issue(index + 1, false, "дубликат имени в одной строке: " + host));
                else entry.hostnames.add(host);
            }
            if (!entry.hostnames.isEmpty()) result.entries.add(entry);
        }
        validateConflicts(result);
        return result;
    }

    private static boolean looksLikeMapping(String value) {
        String[] p = value.split("\\s+"); return p.length >= 2 && normalizeAddress(p[0]) != null;
    }

    public static void validateConflicts(Result result) {
        Map<String,String> owner = new LinkedHashMap<String,String>();
        for (HostsEntry entry : result.entries) {
            if (!entry.enabled) continue;
            for (String host : entry.hostnames) {
                String previous = owner.get(host);
                if (previous == null) owner.put(host, entry.address);
                else if (!previous.equals(entry.address)) result.issues.add(new Issue(0, false, "имя " + host + " имеет несколько адресов: " + previous + " и " + entry.address + "; порядок ответов может отличаться"));
                else result.issues.add(new Issue(0, false, "повторная одинаковая запись: " + entry.address + " " + host));
            }
        }
    }

    public static String buildManagedBlock(List<HostsEntry> entries) {
        StringBuilder out = new StringBuilder(); out.append(BEGIN).append('\n');
        for (HostsEntry entry : entries) out.append(entry.canonicalLine()).append('\n');
        out.append(END).append('\n'); return out.toString();
    }

    public static String mergeManagedBlock(String current, List<HostsEntry> entries) {
        String raw = current == null ? "" : current.replace("\r\n", "\n").replace('\r', '\n');
        int start = raw.indexOf(BEGIN); int end = raw.indexOf(END);
        String base;
        if (start >= 0 && end >= start) {
            int after = end + END.length(); if (after < raw.length() && raw.charAt(after) == '\n') after++;
            base = raw.substring(0, start) + raw.substring(after);
        } else base = raw;
        base = removeExactManagedDuplicates(base, entries);
        base = trimTrailingNewlines(base);
        if (base.length() == 0) base = "127.0.0.1 localhost\n::1 localhost";
        return base + "\n\n" + buildManagedBlock(entries);
    }


    private static String removeExactManagedDuplicates(String base, List<HostsEntry> entries) {
        Set<String> managed = new LinkedHashSet<String>();
        for (HostsEntry entry : entries) if (entry.enabled) {
            for (String host : entry.hostnames) managed.add(entry.address + " " + host);
        }
        StringBuilder out = new StringBuilder();
        String[] lines = base.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.length() == 0 || trimmed.startsWith("#")) { out.append(line).append('\n'); continue; }
            String comment = "";
            int hash = trimmed.indexOf('#');
            if (hash >= 0) { comment = trimmed.substring(hash).trim(); trimmed = trimmed.substring(0, hash).trim(); }
            String[] parts = trimmed.split("\\s+");
            String address = parts.length > 0 ? normalizeAddress(parts[0]) : null;
            if (address == null || parts.length < 2) { out.append(line).append('\n'); continue; }
            List<String> remaining = new ArrayList<String>();
            for (int i = 1; i < parts.length; i++) {
                String host = normalizeHostname(parts[i]);
                if (host == null || !managed.contains(address + " " + host)) remaining.add(parts[i]);
            }
            if (!remaining.isEmpty()) {
                out.append(address);
                for (String host : remaining) out.append(' ').append(host);
                if (comment.length() > 0) out.append(' ').append(comment);
                out.append('\n');
            }
        }
        return out.toString();
    }

    private static String trimTrailingNewlines(String value) {
        int end = value.length(); while (end > 0 && (value.charAt(end - 1) == '\n' || value.charAt(end - 1) == '\r')) end--; return value.substring(0, end);
    }

    public static String normalizeAddress(String input) {
        if (input == null) return null;
        String value = input.trim(); if (value.length() == 0) return null;
        if (value.indexOf(':') >= 0) {
            try {
                InetAddress address = InetAddress.getByName(value);
                if (!(address instanceof Inet6Address)) return null;
                return address.getHostAddress().replaceAll("%.*$", "").toLowerCase(Locale.US);
            } catch (Exception ignored) { return null; }
        }
        String[] octets = value.split("\\.", -1); if (octets.length != 4) return null;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            if (!octets[i].matches("[0-9]{1,3}")) return null;
            int n; try { n = Integer.parseInt(octets[i]); } catch (Exception e) { return null; }
            if (n < 0 || n > 255) return null;
            if (i > 0) out.append('.'); out.append(n);
        }
        return out.toString();
    }

    public static String normalizeHostname(String input) {
        if (input == null) return null;
        String value = input.trim().toLowerCase(Locale.US);
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (value.length() == 0 || value.contains("://") || value.contains("/") || value.contains("*") || value.contains(" ")) return null;
        try { value = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.US); }
        catch (Exception e) { return null; }
        if (value.length() > 253) return null;
        String[] labels = value.split("\\.", -1);
        for (String label : labels) {
            if (label.length() == 0 || label.length() > 63) return null;
            if (!label.matches("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?")) return null;
        }
        return value;
    }
}
