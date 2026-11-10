package pro.sitescope.app;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.HttpURLConnection;
import java.net.IDN;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Small dependency-free DNS diagnostic client with EDNS0 and bounded UDP queries. */
public final class DnsAnalyzer {
    private static final String[] RESOLVERS = {"1.1.1.1", "8.8.8.8"};
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private DnsAnalyzer() { }

    private static final class QuerySpec {
        final String name;
        final int type;
        QuerySpec(String name, int type) { this.name = name; this.type = type; }
    }

    private static final class QueryResult {
        final List<Models.DnsRecord> records = new ArrayList<Models.DnsRecord>();
        boolean authenticatedData;
        boolean truncated;
        int rcode;
        String resolver = "";
        String error = "";
    }

    public static void analyze(String host, Models.AuditResult output) {
        output.dnsRecords.clear();
        output.spfPresent = false;
        output.dmarcPresent = false;
        output.caaPresent = false;
        output.dnssecPresent = false;
        output.dnsResolversCompared = false;
        output.dnsResolversAgree = true;
        output.dnsCheckedAt = System.currentTimeMillis();
        output.dnsResolver = "Cloudflare 1.1.1.1 · Google 8.8.8.8";
        String domain = normalizeHost(host);
        if (domain.length() == 0) {
            output.dnsStatus = "Некорректное имя домена";
            return;
        }
        String apex = DomainRdapClient.registrableDomain(domain);
        if (apex.length() == 0) apex = domain;

        List<QuerySpec> specs = new ArrayList<QuerySpec>();
        specs.add(new QuerySpec(domain, 1));    // A for requested host
        specs.add(new QuerySpec(domain, 28));   // AAAA for requested host
        specs.add(new QuerySpec(domain, 5));    // CNAME for requested host
        specs.add(new QuerySpec(apex, 15));     // MX at zone apex
        specs.add(new QuerySpec(apex, 2));      // NS at zone apex
        specs.add(new QuerySpec(apex, 16));     // TXT/SPF at zone apex
        specs.add(new QuerySpec(apex, 6));      // SOA at zone apex
        specs.add(new QuerySpec(apex, 257));    // CAA at zone apex
        specs.add(new QuerySpec(apex, 48));     // DNSKEY at zone apex
        specs.add(new QuerySpec(apex, 43));     // DS indication
        specs.add(new QuerySpec("_dmarc." + apex, 16));
        specs.add(new QuerySpec("_sip._tcp." + apex, 33));
        specs.add(new QuerySpec("_submission._tcp." + apex, 33));
        specs.add(new QuerySpec("_autodiscover._tcp." + apex, 33));

        ExecutorService executor = Executors.newFixedThreadPool(Math.min(10, specs.size()));
        List<Future<QueryResult>> futures = new ArrayList<Future<QueryResult>>();
        for (final QuerySpec spec : specs) {
            futures.add(executor.submit(new Callable<QueryResult>() {
                @Override public QueryResult call() { return queryWithFallback(spec.name, spec.type); }
            }));
        }
        int successful = 0;
        boolean ad = false;
        List<String> errors = new ArrayList<String>();
        long deadline = System.currentTimeMillis() + 12500L;
        for (Future<QueryResult> future : futures) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) { future.cancel(true); continue; }
            try {
                QueryResult result = future.get(remaining, TimeUnit.MILLISECONDS);
                if (result.error.length() == 0) successful++;
                else errors.add(result.error);
                ad = ad || result.authenticatedData;
                merge(output.dnsRecords, result.records);
            } catch (Exception error) {
                future.cancel(true);
                errors.add(error.getClass().getSimpleName());
            }
        }
        executor.shutdownNow();
        Collections.sort(output.dnsRecords, new Comparator<Models.DnsRecord>() {
            @Override public int compare(Models.DnsRecord a, Models.DnsRecord b) {
                int type = order(a.type) - order(b.type);
                if (type != 0) return type;
                int name = a.name.compareToIgnoreCase(b.name);
                return name != 0 ? name : a.value.compareToIgnoreCase(b.value);
            }
        });

        for (Models.DnsRecord record : output.dnsRecords) {
            String value = record.value.toLowerCase(Locale.US);
            if ("TXT".equals(record.type) && value.startsWith("v=spf1")) output.spfPresent = true;
            if ("TXT".equals(record.type) && record.name.toLowerCase(Locale.US).startsWith("_dmarc.") && value.startsWith("v=dmarc1")) output.dmarcPresent = true;
            if ("CAA".equals(record.type)) output.caaPresent = true;
            if ("DNSKEY".equals(record.type) || "DS".equals(record.type)) output.dnssecPresent = true;
        }
        output.dnssecPresent = output.dnssecPresent || ad;
        boolean[] comparison = compareResolvers(domain, apex);
        output.dnsResolversCompared = comparison[0];
        output.dnsResolversAgree = comparison[1];
        if (successful == 0) output.dnsStatus = "DNS-серверы не ответили";
        else if (output.dnsRecords.isEmpty()) output.dnsStatus = "Ответ получен, записи не найдены";
        else if (!output.dnsResolversCompared) output.dnsStatus = "Записи получены · сравнение резолверов не завершено";
        else if (!output.dnsResolversAgree) output.dnsStatus = "Записи получены · ответы резолверов различаются";
        else output.dnsStatus = "Записи получены · ответы согласованы";
    }

    private static QueryResult queryWithFallback(String name, int type) {
        QueryResult last = new QueryResult();
        for (int i = 0; i < RESOLVERS.length; i++) {
            QueryResult result = queryProvider(name, type, i);
            if (result.error.length() == 0) return result;
            last = result;
        }
        return last;
    }

    private static QueryResult queryProvider(String name, int type, int provider) {
        QueryResult udp = query(name, type, RESOLVERS[provider]);
        if (udp.error.length() == 0) return udp;
        String endpoint = provider == 0 ? "https://cloudflare-dns.com/dns-query" : "https://dns.google/dns-query";
        String label = provider == 0 ? "Cloudflare DoH" : "Google DoH";
        QueryResult doh = queryDoh(name, type, endpoint, label);
        return doh.error.length() == 0 ? doh : udp;
    }

    private static QueryResult queryDoh(String name, int type, String endpoint, String label) {
        QueryResult result = new QueryResult();
        result.resolver = label;
        HttpURLConnection connection = null;
        try {
            int id = RANDOM.nextInt(65536);
            byte[] request = buildQuery(id, name, type);
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setConnectTimeout(2800);
            connection.setReadTimeout(3800);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/dns-message");
            connection.setRequestProperty("Accept", "application/dns-message");
            connection.setRequestProperty("User-Agent", "SiteScopePro/0.31 DNS-Analyzer");
            java.io.OutputStream requestBody = connection.getOutputStream();
            requestBody.write(request);
            requestBody.close();
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("DoH HTTP " + code);
            InputStream input = connection.getInputStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                bytes.write(buffer, 0, read);
                if (bytes.size() > 64 * 1024) throw new IllegalStateException("Слишком большой DoH-ответ");
            }
            input.close();
            parse(bytes.toByteArray(), id, result);
        } catch (Exception error) {
            result.error = label + ": " + error.getClass().getSimpleName();
        } finally {
            if (connection != null) connection.disconnect();
        }
        return result;
    }

    private static QueryResult query(String name, int type, String resolver) {
        QueryResult result = new QueryResult();
        result.resolver = resolver;
        DatagramSocket socket = null;
        try {
            int id = RANDOM.nextInt(65536);
            byte[] request = buildQuery(id, name, type);
            socket = new DatagramSocket();
            socket.setSoTimeout(1100);
            DatagramPacket packet = new DatagramPacket(request, request.length, InetAddress.getByName(resolver), 53);
            socket.send(packet);
            byte[] buffer = new byte[4096];
            DatagramPacket response = new DatagramPacket(buffer, buffer.length);
            socket.receive(response);
            parse(Arrays.copyOf(buffer, response.getLength()), id, result);
        } catch (Exception error) {
            result.error = resolver + ": " + error.getClass().getSimpleName();
        } finally {
            if (socket != null) socket.close();
        }
        return result;
    }

    private static byte[] buildQuery(int id, String name, int type) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(id);
        out.writeShort(0x0100); // recursion desired
        out.writeShort(1);
        out.writeShort(0);
        out.writeShort(0);
        out.writeShort(1); // EDNS0 OPT
        writeName(out, name);
        out.writeShort(type);
        out.writeShort(1);
        out.writeByte(0);      // root name
        out.writeShort(41);    // OPT
        out.writeShort(1232);  // UDP payload
        out.writeInt(0x00008000); // DNSSEC OK
        out.writeShort(0);
        out.flush();
        return bytes.toByteArray();
    }

    static List<Models.DnsRecord> parsePacketForTest(byte[] packet, int expectedId) throws Exception {
        QueryResult result = new QueryResult();
        parse(packet, expectedId, result);
        return result.records;
    }

    private static void parse(byte[] packet, int expectedId, QueryResult output) throws Exception {
        if (packet.length < 12) throw new IllegalStateException("Короткий DNS-ответ");
        int id = u16(packet, 0);
        if (id != expectedId) throw new IllegalStateException("DNS transaction mismatch");
        int flags = u16(packet, 2);
        output.authenticatedData = (flags & 0x0020) != 0;
        output.truncated = (flags & 0x0200) != 0;
        output.rcode = flags & 0x000f;
        int qd = u16(packet, 4);
        int an = u16(packet, 6);
        int ns = u16(packet, 8);
        int ar = u16(packet, 10);
        int[] pos = {12};
        for (int i = 0; i < qd; i++) {
            readName(packet, pos, 0);
            pos[0] += 4;
        }
        int total = an + ns + ar;
        for (int i = 0; i < total && pos[0] < packet.length; i++) {
            String name = readName(packet, pos, 0);
            if (pos[0] + 10 > packet.length) break;
            int type = u16(packet, pos[0]); pos[0] += 2;
            int clazz = u16(packet, pos[0]); pos[0] += 2;
            long ttl = u32(packet, pos[0]); pos[0] += 4;
            int length = u16(packet, pos[0]); pos[0] += 2;
            int dataStart = pos[0];
            if (dataStart + length > packet.length) break;
            if (type != 41 && clazz == 1) {
                String value = decodeRecord(packet, dataStart, length, type);
                if (value.length() > 0) {
                    Models.DnsRecord record = new Models.DnsRecord();
                    record.type = typeName(type);
                    record.name = trimDot(name);
                    record.value = trimDot(value);
                    record.ttl = ttl;
                    output.records.add(record);
                }
            }
            pos[0] = dataStart + length;
        }
    }

    private static String decodeRecord(byte[] packet, int start, int length, int type) throws Exception {
        if (type == 1 && length == 4) return InetAddress.getByAddress(Arrays.copyOfRange(packet, start, start + 4)).getHostAddress();
        if (type == 28 && length == 16) return InetAddress.getByAddress(Arrays.copyOfRange(packet, start, start + 16)).getHostAddress();
        if (type == 2 || type == 5 || type == 12) return readName(packet, new int[]{start}, 0);
        if (type == 15 && length >= 3) return "priority=" + u16(packet, start) + " " + readName(packet, new int[]{start + 2}, 0);
        if (type == 16) {
            StringBuilder text = new StringBuilder();
            int pos = start;
            int end = start + length;
            while (pos < end) {
                int size = packet[pos++] & 0xff;
                size = Math.min(size, end - pos);
                if (text.length() > 0) text.append("");
                text.append(new String(packet, pos, size, UTF8));
                pos += size;
            }
            return text.toString();
        }
        if (type == 6) {
            int[] pos = {start};
            String mname = readName(packet, pos, 0);
            String rname = readName(packet, pos, 0);
            if (pos[0] + 20 > start + length) return mname + " " + rname;
            return "mname=" + mname + " rname=" + rname + " serial=" + u32(packet, pos[0]) +
                " refresh=" + u32(packet, pos[0] + 4) + " retry=" + u32(packet, pos[0] + 8) +
                " expire=" + u32(packet, pos[0] + 12) + " minimum=" + u32(packet, pos[0] + 16);
        }
        if (type == 33 && length >= 7) {
            int priority = u16(packet, start);
            int weight = u16(packet, start + 2);
            int port = u16(packet, start + 4);
            String target = readName(packet, new int[]{start + 6}, 0);
            return "priority=" + priority + " weight=" + weight + " port=" + port + " target=" + target;
        }
        if (type == 257 && length >= 3) {
            int flags = packet[start] & 0xff;
            int tagLength = packet[start + 1] & 0xff;
            if (2 + tagLength > length) return "";
            String tag = new String(packet, start + 2, tagLength, UTF8);
            String value = new String(packet, start + 2 + tagLength, length - 2 - tagLength, UTF8);
            return "flags=" + flags + " " + tag + " " + value;
        }
        if (type == 48 && length >= 4) {
            int flags = u16(packet, start);
            int protocol = packet[start + 2] & 0xff;
            int algorithm = packet[start + 3] & 0xff;
            return "flags=" + flags + " protocol=" + protocol + " algorithm=" + algorithm + " key-bytes=" + (length - 4);
        }
        if (type == 43 && length >= 4) {
            int keyTag = u16(packet, start);
            int algorithm = packet[start + 2] & 0xff;
            int digestType = packet[start + 3] & 0xff;
            return "key-tag=" + keyTag + " algorithm=" + algorithm + " digest-type=" + digestType + " digest=" + hex(packet, start + 4, length - 4);
        }
        return "";
    }

    private static boolean[] compareResolvers(final String domain, final String apex) {
        ExecutorService executor = Executors.newFixedThreadPool(6);
        List<Future<QueryResult>> firstFutures = new ArrayList<Future<QueryResult>>();
        List<Future<QueryResult>> secondFutures = new ArrayList<Future<QueryResult>>();
        for (final int type : new int[]{1, 28, 2}) {
            firstFutures.add(executor.submit(new Callable<QueryResult>() { @Override public QueryResult call() { return queryProvider(type == 2 ? apex : domain, type, 0); } }));
            secondFutures.add(executor.submit(new Callable<QueryResult>() { @Override public QueryResult call() { return queryProvider(type == 2 ? apex : domain, type, 1); } }));
        }
        Set<String> first = new LinkedHashSet<String>();
        Set<String> second = new LinkedHashSet<String>();
        boolean complete = true;
        long deadline = System.currentTimeMillis() + 5200L;
        for (int i = 0; i < firstFutures.size(); i++) {
            for (Future<QueryResult> future : new Future[]{firstFutures.get(i), secondFutures.get(i)}) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) { future.cancel(true); complete = false; continue; }
                try {
                    QueryResult result = future.get(remaining, TimeUnit.MILLISECONDS);
                    Set<String> target = future == firstFutures.get(i) ? first : second;
                    for (Models.DnsRecord record : result.records) target.add(record.type + "=" + record.value.toLowerCase(Locale.US));
                    if (result.error.length() > 0) complete = false;
                } catch (Exception error) { future.cancel(true); complete = false; }
            }
        }
        executor.shutdownNow();
        boolean compared = complete && !first.isEmpty() && !second.isEmpty();
        return new boolean[]{compared, !compared || first.equals(second)};
    }

    private static void merge(List<Models.DnsRecord> target, List<Models.DnsRecord> incoming) {
        Set<String> known = new LinkedHashSet<String>();
        for (Models.DnsRecord record : target) known.add(key(record));
        for (Models.DnsRecord record : incoming) if (known.add(key(record))) target.add(record);
    }

    private static String key(Models.DnsRecord record) {
        return record.type + "|" + record.name.toLowerCase(Locale.US) + "|" + record.value.toLowerCase(Locale.US);
    }

    private static int order(String type) {
        String[] names = {"A", "AAAA", "CNAME", "MX", "NS", "TXT", "SOA", "CAA", "SRV", "DNSKEY", "DS"};
        for (int i = 0; i < names.length; i++) if (names[i].equals(type)) return i;
        return names.length;
    }

    private static String typeName(int type) {
        switch (type) {
            case 1: return "A";
            case 2: return "NS";
            case 5: return "CNAME";
            case 6: return "SOA";
            case 12: return "PTR";
            case 15: return "MX";
            case 16: return "TXT";
            case 28: return "AAAA";
            case 33: return "SRV";
            case 43: return "DS";
            case 48: return "DNSKEY";
            case 257: return "CAA";
            default: return "TYPE" + type;
        }
    }

    private static void writeName(DataOutputStream out, String name) throws Exception {
        String normalized = normalizeHost(name);
        for (String label : normalized.split("\\.")) {
            byte[] bytes = label.getBytes(UTF8);
            if (bytes.length == 0 || bytes.length > 63) throw new IllegalArgumentException("Некорректная DNS-метка");
            out.writeByte(bytes.length);
            out.write(bytes);
        }
        out.writeByte(0);
    }

    private static String readName(byte[] packet, int[] position, int depth) throws Exception {
        if (depth > 20) throw new IllegalStateException("DNS compression loop");
        StringBuilder name = new StringBuilder();
        int pos = position[0];
        boolean jumped = false;
        int consumed = pos;
        while (pos < packet.length) {
            int length = packet[pos] & 0xff;
            if (length == 0) {
                pos++;
                if (!jumped) consumed = pos;
                break;
            }
            if ((length & 0xc0) == 0xc0) {
                if (pos + 1 >= packet.length) throw new IllegalStateException("Broken DNS pointer");
                int pointer = ((length & 0x3f) << 8) | (packet[pos + 1] & 0xff);
                if (!jumped) consumed = pos + 2;
                int[] nested = {pointer};
                String suffix = readName(packet, nested, depth + 1);
                if (name.length() > 0 && suffix.length() > 0) name.append('.');
                name.append(suffix);
                jumped = true;
                break;
            }
            pos++;
            if (pos + length > packet.length) throw new IllegalStateException("Broken DNS label");
            if (name.length() > 0) name.append('.');
            name.append(new String(packet, pos, length, UTF8));
            pos += length;
            if (!jumped) consumed = pos;
        }
        position[0] = consumed;
        return name.toString();
    }

    private static int u16(byte[] value, int offset) { return ((value[offset] & 0xff) << 8) | (value[offset + 1] & 0xff); }
    private static long u32(byte[] value, int offset) {
        return ((long)(value[offset] & 0xff) << 24) | ((long)(value[offset + 1] & 0xff) << 16) |
            ((long)(value[offset + 2] & 0xff) << 8) | (long)(value[offset + 3] & 0xff);
    }

    private static String hex(byte[] data, int offset, int length) {
        StringBuilder out = new StringBuilder();
        int max = Math.min(length, 48);
        for (int i = 0; i < max; i++) out.append(String.format(Locale.US, "%02x", data[offset + i] & 0xff));
        if (length > max) out.append("…");
        return out.toString();
    }

    private static String normalizeHost(String host) {
        String value = host == null ? "" : host.trim().toLowerCase(Locale.US);
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        try { value = IDN.toASCII(value); } catch (Exception ignored) { }
        return value;
    }

    private static String trimDot(String value) {
        String out = value == null ? "" : value.trim();
        while (out.endsWith(".")) out = out.substring(0, out.length() - 1);
        return out;
    }
}
