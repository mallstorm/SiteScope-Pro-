package pro.sitescope.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/** Read-only domain RDAP lookup through the IANA bootstrap proxy at rdap.org. */
public final class DomainRdapClient {
    private DomainRdapClient() { }

    public static final class Result {
        public String queryDomain = "";
        public String registrar = "";
        public String registered = "";
        public String expires = "";
        public String status = "";
        public String nameservers = "";
        public String error = "";
    }

    public static Result lookup(String host) {
        Result result = new Result();
        result.queryDomain = registrableDomain(host);
        if (result.queryDomain.length() == 0) return result;
        HttpURLConnection connection = null;
        try {
            String encoded = URLEncoder.encode(result.queryDomain, "UTF-8").replace("+", "%20");
            connection = (HttpURLConnection) new URL("https://rdap.org/domain/" + encoded).openConnection();
            connection.setConnectTimeout(9000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("Accept", "application/rdap+json, application/json");
            connection.setRequestProperty("User-Agent", "SiteScopePro/0.31 Domain-Inspector");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("RDAP HTTP " + code);
            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), "UTF-8"), 32768);
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                text.append(line);
                if (text.length() > 2 * 1024 * 1024) throw new IllegalStateException("Слишком большой RDAP-ответ");
            }
            reader.close();
            parse(new JSONObject(text.toString()), result);
        } catch (Exception error) {
            result.error = error.getClass().getSimpleName() + ": " + message(error);
        } finally {
            if (connection != null) connection.disconnect();
        }
        return result;
    }

    private static void parse(JSONObject root, Result output) {
        JSONArray events = root.optJSONArray("events");
        if (events != null) {
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event == null) continue;
                String action = event.optString("eventAction", "").toLowerCase(Locale.US);
                String date = displayDate(event.optString("eventDate", ""));
                if ((action.contains("registration") || action.contains("created")) && output.registered.length() == 0) output.registered = date;
                if ((action.contains("expiration") || action.contains("expiry")) && output.expires.length() == 0) output.expires = date;
            }
        }
        JSONArray status = root.optJSONArray("status");
        List<String> states = new ArrayList<String>();
        if (status != null) for (int i = 0; i < status.length(); i++) {
            String value = status.optString(i, "").trim();
            if (value.length() > 0 && !states.contains(value)) states.add(value);
        }
        output.status = join(states, ", ");

        JSONArray nameservers = root.optJSONArray("nameservers");
        List<String> ns = new ArrayList<String>();
        if (nameservers != null) for (int i = 0; i < nameservers.length(); i++) {
            JSONObject item = nameservers.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("ldhName", item.optString("unicodeName", "")).toLowerCase(Locale.US);
            if (name.length() > 0 && !ns.contains(name)) ns.add(name);
        }
        output.nameservers = join(ns, ", ");

        JSONArray entities = root.optJSONArray("entities");
        if (entities != null) {
            for (int i = 0; i < entities.length(); i++) {
                JSONObject entity = entities.optJSONObject(i);
                if (entity == null || !hasRole(entity.optJSONArray("roles"), "registrar")) continue;
                String name = vcardName(entity.optJSONArray("vcardArray"));
                if (name.length() == 0) name = entity.optString("handle", "");
                output.registrar = name;
                break;
            }
        }
    }

    private static boolean hasRole(JSONArray roles, String wanted) {
        if (roles == null) return false;
        for (int i = 0; i < roles.length(); i++) if (wanted.equalsIgnoreCase(roles.optString(i))) return true;
        return false;
    }

    private static String vcardName(JSONArray vcard) {
        if (vcard == null || vcard.length() < 2) return "";
        JSONArray rows = vcard.optJSONArray(1);
        if (rows == null) return "";
        for (int i = 0; i < rows.length(); i++) {
            JSONArray row = rows.optJSONArray(i);
            if (row == null || row.length() < 4) continue;
            String key = row.optString(0, "");
            if ("fn".equalsIgnoreCase(key) || "org".equalsIgnoreCase(key)) {
                Object value = row.opt(3);
                if (value instanceof JSONArray) return ((JSONArray) value).optString(0, "");
                return String.valueOf(value);
            }
        }
        return "";
    }

    static String registrableDomain(String host) {
        String value = host == null ? "" : host.trim().toLowerCase(Locale.US);
        while (value.endsWith(".")) value = value.substring(0, value.length() - 1);
        if (value.length() == 0 || value.matches("\\d{1,3}(\\.\\d{1,3}){3}") || value.contains(":")) return "";
        String[] labels = value.split("\\.");
        if (labels.length < 2) return value;
        String suffix2 = labels[labels.length - 2] + "." + labels[labels.length - 1];
        String[] compound = {"co.uk", "org.uk", "com.au", "net.au", "com.br", "com.ua", "net.ua", "org.ua", "co.jp", "co.nz", "co.za", "com.tr", "com.cn", "com.sg", "com.hk"};
        boolean compoundSuffix = false;
        for (String suffix : compound) if (suffix.equals(suffix2)) { compoundSuffix = true; break; }
        if (compoundSuffix && labels.length >= 3) return labels[labels.length - 3] + "." + suffix2;
        return suffix2;
    }

    private static String displayDate(String raw) {
        if (raw == null || raw.trim().length() == 0) return "";
        try {
            String normalized = raw.trim();
            if (normalized.length() >= 10) {
                Date date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(normalized.substring(0, 10));
                return new SimpleDateFormat("dd.MM.yyyy", Locale.US).format(date);
            }
        } catch (Exception ignored) { }
        return raw;
    }

    private static String message(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
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
