package pro.sitescope.app;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/** Coordinates technology, DNS, RDAP and IP/ASN enrichment without paid APIs. */
public final class DomainAnalysisEngine {
    private DomainAnalysisEngine() { }

    public static void enrich(Models.AuditResult result, HttpURLConnection connection, String html) {
        try { TechnologyAnalyzer.analyze(result, connection, html); }
        catch (Exception ignored) { }
        Map<String, String> headers = new LinkedHashMap<String, String>();
        if (connection != null && connection.getHeaderFields() != null) {
            for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) continue;
                headers.put(entry.getKey().toLowerCase(Locale.US), join(entry.getValue(), "; "));
            }
        }
        enrichNetwork(result, headers, html);
    }

    public static void enrich(Models.AuditResult result, Map<String, String> headers, String html) {
        try { TechnologyAnalyzer.analyze(result, headers, html); }
        catch (Exception ignored) { }
        enrichNetwork(result, headers, html);
    }

    private static void enrichNetwork(final Models.AuditResult result, Map<String, String> headers, String html) {
        ExecutorService executor = Executors.newFixedThreadPool(3);
        Future<Boolean> dns = executor.submit(new Callable<Boolean>() {
            @Override public Boolean call() { DnsAnalyzer.analyze(result.host, result); return true; }
        });
        Future<DomainRdapClient.Result> rdap = executor.submit(new Callable<DomainRdapClient.Result>() {
            @Override public DomainRdapClient.Result call() { return DomainRdapClient.lookup(result.host); }
        });
        Future<RipeStatClient.Lookup> network = executor.submit(new Callable<RipeStatClient.Lookup>() {
            @Override public RipeStatClient.Lookup call() {
                String ip = firstIp(result.ipAddresses);
                return ip.length() == 0 ? new RipeStatClient.Lookup() : RipeStatClient.lookupNetworkSummary(ip);
            }
        });
        try { dns.get(25, TimeUnit.SECONDS); } catch (Exception ignored) { }
        try {
            DomainRdapClient.Result domain = rdap.get(20, TimeUnit.SECONDS);
            result.domainRegistrar = domain.registrar;
            result.domainRegistered = domain.registered;
            result.domainExpires = domain.expires;
            result.domainStatus = domain.status;
            result.domainNameservers = domain.nameservers;
        } catch (Exception ignored) { }
        try {
            RipeStatClient.Lookup lookup = network.get(24, TimeUnit.SECONDS);
            result.infrastructureAsn = lookup.asn;
            result.infrastructureProvider = lookup.holder.length() > 0 ? lookup.holder : lookup.rdapName;
            result.reverseDns = lookup.reverseDns;
        } catch (Exception ignored) { }
        executor.shutdownNow();
        if (result.domainNameservers.length() == 0) result.domainNameservers = dnsValues(result, "NS");
    }

    private static String firstIp(String text) {
        if (text == null) return "";
        for (String part : text.split("[,\\s]+")) {
            String value = part.trim();
            if (value.length() == 0) continue;
            int zone = value.indexOf('%');
            if (zone > 0) value = value.substring(0, zone);
            try { InetAddress.getByName(value); return value; }
            catch (Exception ignored) { }
        }
        return "";
    }

    private static String dnsValues(Models.AuditResult result, String type) {
        List<String> values = new ArrayList<String>();
        for (Models.DnsRecord record : result.dnsRecords) if (type.equals(record.type) && !values.contains(record.value)) values.add(record.value);
        return join(values, ", ");
    }

    private static String join(List<String> values, String delimiter) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().length() == 0) continue;
            if (out.length() > 0) out.append(delimiter);
            out.append(value.trim());
        }
        return out.toString();
    }
}
