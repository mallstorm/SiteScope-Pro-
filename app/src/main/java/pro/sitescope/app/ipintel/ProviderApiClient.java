package pro.sitescope.app.ipintel;

import org.json.JSONArray;
import org.json.JSONObject;

import pro.sitescope.app.RipeStatClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class ProviderApiClient {
    private static final int MAX_JSON = 3 * 1024 * 1024;
    private ProviderApiClient() { }

    public static final class PublicAddresses {
        public String ipv4 = "";
        public String ipv6 = "";
        public String ipv4Error = "";
        public String ipv6Error = "";
    }

    public static final class ProviderCandidate {
        public String asn = "";
        public String holder = "";
        public String asName = "";
        public String organizationId = "";
        public String organizationName = "";
        public String country = "";
        public String source = "";
        public boolean announced;
        public boolean hint;
        public String error = "";
        public final List<String> prefixes = new ArrayList<String>();

        public String displayName() {
            if (holder.length() > 0) return holder;
            if (organizationName.length() > 0) return organizationName;
            if (asName.length() > 0) return asName;
            return asn;
        }
    }

    public static final class CurrentProvider {
        public final PublicAddresses addresses = new PublicAddresses();
        public ProviderCandidate ipv4Provider;
        public ProviderCandidate ipv6Provider;
        public String error = "";
    }

    public static PublicAddresses publicAddresses() {
        PublicAddresses output = new PublicAddresses();
        try { output.ipv4 = publicIp("https://api.ipify.org?format=json"); }
        catch (Exception error) { output.ipv4Error = message(error); }
        try { output.ipv6 = publicIp("https://api6.ipify.org?format=json"); }
        catch (Exception error) { output.ipv6Error = message(error); }
        return output;
    }

    public static CurrentProvider currentProvider() {
        CurrentProvider output = new CurrentProvider();
        PublicAddresses values = publicAddresses();
        output.addresses.ipv4 = values.ipv4;
        output.addresses.ipv6 = values.ipv6;
        output.addresses.ipv4Error = values.ipv4Error;
        output.addresses.ipv6Error = values.ipv6Error;
        if (values.ipv4.length() > 0) output.ipv4Provider = lookupAsnForResource(values.ipv4, false);
        if (values.ipv6.length() > 0) output.ipv6Provider = lookupAsnForResource(values.ipv6, false);
        if (values.ipv4.length() == 0 && values.ipv6.length() == 0) {
            output.error = "Не удалось определить внешний IPv4 или IPv6";
        }
        return output;
    }

    public static List<ProviderCandidate> search(String query) {
        String value = query == null ? "" : query.trim();
        if (value.length() == 0) throw new IllegalArgumentException("Введите название провайдера, юридическое имя или ASN");
        LinkedHashMap<String, ProviderCandidate> output = new LinkedHashMap<String, ProviderCandidate>();

        for (String hint : ProviderCore.aliasCandidates(value)) {
            ProviderCandidate candidate = lookupAsnForResource(hint, true);
            candidate.hint = true;
            output.put(candidate.asn, candidate);
        }

        String upper = value.toUpperCase(Locale.US).replaceAll("\\s+", "");
        if (upper.matches("AS?\\d+")) {
            String asn = ProviderCore.normalizeAsn(upper);
            if (!output.containsKey(asn)) output.put(asn, lookupAsnForResource(asn, false));
        } else {
            try {
                SearchIndex index = searchDatabase(value);
                for (ProviderCandidate raw : index.autnums) {
                    if (raw.asn.length() == 0 || output.containsKey(raw.asn)) continue;
                    ProviderCandidate verified = lookupAsnForResource(raw.asn, false);
                    mergeMetadata(verified, raw);
                    output.put(verified.asn, verified);
                    if (output.size() >= 10) break;
                }
                if (output.size() < 10) {
                    for (OrgRecord org : index.organizations) {
                        List<ProviderCandidate> inverse = inverseAutnums(org);
                        for (ProviderCandidate raw : inverse) {
                            if (raw.asn.length() == 0 || output.containsKey(raw.asn)) continue;
                            ProviderCandidate verified = lookupAsnForResource(raw.asn, false);
                            mergeMetadata(verified, raw);
                            verified.organizationId = org.id;
                            verified.organizationName = org.name;
                            if (verified.country.length() == 0) verified.country = org.country;
                            output.put(verified.asn, verified);
                            if (output.size() >= 10) break;
                        }
                        if (output.size() >= 10) break;
                    }
                }
            } catch (Exception error) {
                if (output.isEmpty()) {
                    ProviderCandidate failed = new ProviderCandidate();
                    failed.error = message(error);
                    failed.organizationName = value;
                    output.put("ERROR", failed);
                }
            }
        }
        return new ArrayList<ProviderCandidate>(output.values());
    }

    private static ProviderCandidate lookupAsnForResource(String resource, boolean hint) {
        ProviderCandidate output = new ProviderCandidate();
        output.hint = hint;
        RipeStatClient.Lookup lookup = RipeStatClient.lookup(resource);
        output.asn = lookup.asn;
        if (output.asn.length() == 0 && resource.toUpperCase(Locale.US).matches("AS\\d+")) output.asn = resource.toUpperCase(Locale.US);
        output.holder = lookup.holder;
        output.announced = lookup.announced;
        output.prefixes.addAll(lookup.announcedPrefixes);
        output.error = lookup.error;
        output.source = lookup.rir;
        output.country = lookup.rdapCountry.length() > 0 ? lookup.rdapCountry : lookup.country;
        return output;
    }

    private static final class OrgRecord {
        String id = "";
        String name = "";
        String country = "";
        String source = "";
    }

    private static final class SearchIndex {
        final List<ProviderCandidate> autnums = new ArrayList<ProviderCandidate>();
        final List<OrgRecord> organizations = new ArrayList<OrgRecord>();
    }

    private static SearchIndex searchDatabase(String query) throws Exception {
        String address = "https://rest.db.ripe.net/search.json?query-string=" + encode(query) +
            "&type-filter=organisation&type-filter=aut-num&flags=no-filtering&flags=no-referenced";
        JSONObject root = getJson(address);
        SearchIndex output = new SearchIndex();
        JSONArray objects = root.optJSONObject("objects") == null ? null : root.optJSONObject("objects").optJSONArray("object");
        if (objects == null) return output;
        for (int i = 0; i < objects.length(); i++) {
            JSONObject object = objects.optJSONObject(i);
            if (object == null) continue;
            String type = object.optString("type", "");
            Map<String, List<String>> attributes = attributes(object);
            if ("aut-num".equalsIgnoreCase(type)) output.autnums.add(candidateFromAutnum(attributes));
            else if ("organisation".equalsIgnoreCase(type)) output.organizations.add(orgFrom(attributes));
        }
        return output;
    }

    private static List<ProviderCandidate> inverseAutnums(OrgRecord org) {
        List<ProviderCandidate> output = new ArrayList<ProviderCandidate>();
        if (org == null || org.id.length() == 0) return output;
        try {
            String address = "https://rest.db.ripe.net/search.json?query-string=" + encode(org.id) +
                "&inverse-attribute=org&type-filter=aut-num&flags=no-filtering&flags=no-referenced";
            JSONObject root = getJson(address);
            JSONArray objects = root.optJSONObject("objects") == null ? null : root.optJSONObject("objects").optJSONArray("object");
            if (objects == null) return output;
            for (int i = 0; i < objects.length(); i++) {
                JSONObject object = objects.optJSONObject(i);
                if (object == null || !"aut-num".equalsIgnoreCase(object.optString("type", ""))) continue;
                ProviderCandidate candidate = candidateFromAutnum(attributes(object));
                candidate.organizationId = org.id;
                candidate.organizationName = org.name;
                candidate.country = org.country;
                output.add(candidate);
            }
        } catch (Exception ignored) { }
        return output;
    }

    private static ProviderCandidate candidateFromAutnum(Map<String, List<String>> values) {
        ProviderCandidate output = new ProviderCandidate();
        output.asn = first(values, "aut-num").toUpperCase(Locale.US);
        output.asName = first(values, "as-name");
        output.holder = first(values, "descr");
        output.organizationId = first(values, "org");
        output.source = first(values, "source");
        return output;
    }

    private static OrgRecord orgFrom(Map<String, List<String>> values) {
        OrgRecord output = new OrgRecord();
        output.id = first(values, "organisation");
        output.name = first(values, "org-name");
        output.country = first(values, "country");
        output.source = first(values, "source");
        return output;
    }

    private static Map<String, List<String>> attributes(JSONObject object) {
        Map<String, List<String>> output = new LinkedHashMap<String, List<String>>();
        JSONObject attrs = object.optJSONObject("attributes");
        JSONArray list = attrs == null ? null : attrs.optJSONArray("attribute");
        if (list == null) return output;
        for (int i = 0; i < list.length(); i++) {
            JSONObject item = list.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name", "").toLowerCase(Locale.US);
            String value = item.optString("value", "");
            if (name.length() == 0 || value.length() == 0) continue;
            List<String> values = output.get(name);
            if (values == null) { values = new ArrayList<String>(); output.put(name, values); }
            values.add(value);
        }
        return output;
    }

    private static String first(Map<String, List<String>> values, String key) {
        List<String> list = values.get(key);
        return list == null || list.isEmpty() ? "" : list.get(0);
    }

    private static void mergeMetadata(ProviderCandidate target, ProviderCandidate source) {
        if (target.asName.length() == 0) target.asName = source.asName;
        if (target.holder.length() == 0) target.holder = source.holder;
        if (target.organizationId.length() == 0) target.organizationId = source.organizationId;
        if (target.organizationName.length() == 0) target.organizationName = source.organizationName;
        if (target.country.length() == 0) target.country = source.country;
        if (target.source.length() == 0) target.source = source.source;
    }

    private static String publicIp(String address) throws Exception {
        JSONObject root = getJson(address);
        String ip = root.optString("ip", "").trim();
        if (ip.length() == 0) throw new IllegalStateException("Сервис не вернул IP");
        return ip;
    }

    private static JSONObject getJson(String address) throws Exception {
        if (!address.toLowerCase(Locale.US).startsWith("https://")) throw new SecurityException("Разрешены только HTTPS-запросы");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "SiteScopePro/0.19 Provider-Intelligence");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + " от " + new URL(address).getHost());
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"), 32768);
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line);
                if (text.length() > MAX_JSON) throw new IllegalStateException("Слишком большой ответ API");
            }
            reader.close();
            return new JSONObject(text.toString());
        } finally { if (connection != null) connection.disconnect(); }
    }

    private static String encode(String value) throws Exception { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); }
    private static String message(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
}
