package pro.sitescope.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RipeStatClient {
    private static final String API = "https://stat.ripe.net/data/";
    private RipeStatClient() { }

    public static final class Lookup {
        public String resource = "";
        public String prefix = "";
        public String asn = "";
        public String holder = "";
        public String rir = "";
        public boolean announced;
        public String country = "";
        public String city = "";
        public String region = "";
        public double latitude;
        public double longitude;
        public String reverseDns = "";
        public final List<String> abuseContacts = new ArrayList<String>();
        public final List<String> announcedPrefixes = new ArrayList<String>();
        public final List<String> whois = new ArrayList<String>();
        public String rdapName = "";
        public String rdapHandle = "";
        public String rdapCountry = "";
        public String error = "";
    }

    public static final class CountryResources {
        public String countryCode = "";
        public final List<String> asns = new ArrayList<String>();
        public final List<String> ipv4 = new ArrayList<String>();
        public final List<String> ipv6 = new ArrayList<String>();
        public String error = "";
    }

    public static Lookup lookup(String resource) {
        Lookup output = new Lookup();
        output.resource = resource == null ? "" : resource.trim();
        boolean asQuery = isAsn(output.resource);
        try {
            if (asQuery) {
                output.asn = normalizeAsn(output.resource);
            } else {
                JSONObject network = safeEndpoint("network-info", output.resource);
                JSONObject nd = network == null ? null : network.optJSONObject("data");
                if (nd != null) {
                    output.prefix = nd.optString("prefix", "");
                    JSONArray asns = nd.optJSONArray("asns");
                    if (asns != null && asns.length() > 0) output.asn = "AS" + asns.optString(0).replace("AS", "");
                }

                JSONObject prefixOverview = safeEndpoint("prefix-overview", output.prefix.length() > 0 ? output.prefix : output.resource);
                JSONObject pd = prefixOverview == null ? null : prefixOverview.optJSONObject("data");
                if (pd != null) {
                    output.announced = pd.optBoolean("announced", false);
                    output.holder = pd.optString("holder", "");
                    JSONArray asns = pd.optJSONArray("asns");
                    if (output.asn.length() == 0 && asns != null && asns.length() > 0) output.asn = "AS" + asns.optString(0).replace("AS", "");
                }

                JSONObject geo = safeEndpoint("maxmind-geo-lite", output.resource);
                if (geo != null) parseGeo(geo, output);
                JSONObject reverse = safeEndpoint("reverse-dns-ip", stripPrefix(output.resource));
                if (reverse != null && !output.resource.contains("/")) parseReverse(reverse, output);
            }

            JSONObject whois = safeEndpoint("whois", output.resource);
            if (whois != null) parseWhois(whois, output);
            JSONObject abuse = safeEndpoint("abuse-contact-finder", output.resource);
            if (abuse != null) parseAbuse(abuse, output);

            if (output.asn.length() > 0) {
                JSONObject overview = safeEndpoint("as-overview", output.asn);
                JSONObject ad = overview == null ? null : overview.optJSONObject("data");
                if (ad != null) {
                    if (output.holder.length() == 0) output.holder = ad.optString("holder", "");
                    output.announced = output.announced || ad.optBoolean("announced", false);
                }
                JSONObject prefixes = safeEndpoint("announced-prefixes", output.asn);
                if (prefixes != null) parsePrefixes(prefixes, output);
            }
            parseRdap(output);
        } catch (Exception error) {
            output.error = message(error);
        }
        return output;
    }

    /** Lightweight lookup for SEO/domain infrastructure: prefix, ASN, holder and reverse DNS only. */
    public static Lookup lookupNetworkSummary(String resource) {
        Lookup output = new Lookup();
        output.resource = resource == null ? "" : resource.trim();
        try {
            JSONObject network = safeEndpoint("network-info", output.resource);
            JSONObject nd = network == null ? null : network.optJSONObject("data");
            if (nd != null) {
                output.prefix = nd.optString("prefix", "");
                JSONArray asns = nd.optJSONArray("asns");
                if (asns != null && asns.length() > 0) output.asn = "AS" + asns.optString(0).replace("AS", "");
            }
            JSONObject overview = safeEndpoint("prefix-overview", output.prefix.length() > 0 ? output.prefix : output.resource);
            JSONObject pd = overview == null ? null : overview.optJSONObject("data");
            if (pd != null) {
                output.announced = pd.optBoolean("announced", false);
                output.holder = pd.optString("holder", "");
                JSONArray asns = pd.optJSONArray("asns");
                if (output.asn.length() == 0 && asns != null && asns.length() > 0) output.asn = "AS" + asns.optString(0).replace("AS", "");
            }
            JSONObject reverse = safeEndpoint("reverse-dns-ip", stripPrefix(output.resource));
            if (reverse != null && !output.resource.contains("/")) parseReverse(reverse, output);
        } catch (Exception error) {
            output.error = message(error);
        }
        return output;
    }

    public static CountryResources countryResources(String countryCode) {
        CountryResources output = new CountryResources();
        output.countryCode = countryCode == null ? "" : countryCode.trim().toUpperCase(Locale.US);
        try {
            JSONObject root = getJson(API + "country-resource-list/data.json?resource=" + encode(output.countryCode) + "&v4_format=prefix");
            JSONObject data = root.optJSONObject("data");
            JSONObject resources = data == null ? null : data.optJSONObject("resources");
            if (resources == null) throw new IllegalStateException("RIPEstat не вернул ресурсы страны");
            append(resources.optJSONArray("asn"), output.asns, "AS");
            append(resources.optJSONArray("ipv4"), output.ipv4, "");
            append(resources.optJSONArray("ipv6"), output.ipv6, "");
        } catch (Exception error) { output.error = message(error); }
        return output;
    }

    public static List<String> announcedPrefixes(String asn) throws Exception {
        Lookup output = new Lookup();
        parsePrefixes(endpoint("announced-prefixes", normalizeAsn(asn)), output);
        return output.announcedPrefixes;
    }

    private static JSONObject endpoint(String name, String resource) throws Exception {
        return getJson(API + name + "/data.json?resource=" + encode(resource));
    }

    private static JSONObject safeEndpoint(String name, String resource) {
        try { return endpoint(name, resource); }
        catch (Exception ignored) { return null; }
    }

    private static JSONObject getJson(String address) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(18000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "SiteScopePro/0.19 IP-Intelligence");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + " от " + new URL(address).getHost());
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"), 32768);
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line);
                if (text.length() > 4 * 1024 * 1024) throw new IllegalStateException("Слишком большой ответ API");
            }
            reader.close();
            return new JSONObject(text.toString());
        } finally { if (connection != null) connection.disconnect(); }
    }

    private static void parseGeo(JSONObject root, Lookup output) {
        JSONObject data = root.optJSONObject("data");
        JSONArray located = data == null ? null : data.optJSONArray("located_resources");
        if (located == null || located.length() == 0) return;
        JSONObject item = located.optJSONObject(0);
        JSONArray locations = item == null ? null : item.optJSONArray("locations");
        if (locations == null || locations.length() == 0) return;
        JSONObject location = locations.optJSONObject(0);
        if (location == null) return;
        output.country = first(location, "country", "country_code");
        output.city = first(location, "city", "city_name");
        output.region = first(location, "region", "subdivision");
        output.latitude = location.optDouble("latitude", location.optDouble("lat", 0));
        output.longitude = location.optDouble("longitude", location.optDouble("lon", 0));
    }

    private static void parseWhois(JSONObject root, Lookup output) {
        JSONObject data = root.optJSONObject("data");
        JSONArray records = data == null ? null : data.optJSONArray("records");
        if (records == null) return;
        for (int i = 0; i < records.length(); i++) {
            JSONArray group = records.optJSONArray(i);
            if (group == null) continue;
            for (int j = 0; j < group.length(); j++) {
                JSONObject row = group.optJSONObject(j);
                if (row == null) continue;
                String key = row.optString("key", "");
                String value = row.optString("value", "");
                if (key.length() > 0 && value.length() > 0 && output.whois.size() < 80) output.whois.add(key + ": " + value);
                if (output.rir.length() == 0 && "source".equalsIgnoreCase(key)) output.rir = value;
            }
        }
    }

    private static void parseAbuse(JSONObject root, Lookup output) {
        JSONObject data = root.optJSONObject("data");
        JSONArray contacts = data == null ? null : data.optJSONArray("abuse_contacts");
        append(contacts, output.abuseContacts, "");
        if (data != null && output.rir.length() == 0) output.rir = data.optString("authoritative_rir", "");
    }

    private static void parseReverse(JSONObject root, Lookup output) {
        JSONObject data = root.optJSONObject("data");
        if (data == null) return;
        output.reverseDns = data.optString("result", "");
    }

    private static void parsePrefixes(JSONObject root, Lookup output) {
        JSONObject data = root.optJSONObject("data");
        JSONArray prefixes = data == null ? null : data.optJSONArray("prefixes");
        if (prefixes == null) return;
        for (int i = 0; i < prefixes.length() && output.announcedPrefixes.size() < 5000; i++) {
            JSONObject item = prefixes.optJSONObject(i);
            String prefix = item == null ? prefixes.optString(i, "") : item.optString("prefix", "");
            if (prefix.length() > 0) output.announcedPrefixes.add(prefix);
        }
    }

    private static void parseRdap(Lookup output) {
        try {
            String normalized = output.resource.toUpperCase(Locale.US);
            String path;
            if (normalized.startsWith("AS") || normalized.matches("\\d+")) path = "autnum/" + normalized.replace("AS", "");
            else if (normalized.contains("/") || normalized.contains(".") || normalized.contains(":")) path = "ip/" + output.resource;
            else return;
            JSONObject rdap = getJson("https://rdap.org/" + path);
            output.rdapName = rdap.optString("name", rdap.optString("title", ""));
            output.rdapHandle = rdap.optString("handle", "");
            output.rdapCountry = rdap.optString("country", "");
        } catch (Exception ignored) { }
    }

    private static void append(JSONArray array, List<String> output, String prefix) {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "");
            if (value.length() > 0) output.add(prefix + value.replace(prefix, ""));
        }
    }

    private static String first(JSONObject object, String... names) {
        for (String name : names) {
            String value = object.optString(name, "");
            if (value.length() > 0) return value;
        }
        return "";
    }

    private static boolean isAsn(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.US);
        return normalized.matches("AS\\d+") || normalized.matches("\\d+");
    }

    private static String stripPrefix(String value) {
        int slash = value == null ? -1 : value.indexOf('/');
        return slash >= 0 ? value.substring(0, slash) : value;
    }

    private static String normalizeAsn(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.US);
        return normalized.startsWith("AS") ? normalized : "AS" + normalized;
    }

    private static String encode(String value) throws Exception { return URLEncoder.encode(value == null ? "" : value, "UTF-8"); }
    private static String message(Exception error) { return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(); }
}
