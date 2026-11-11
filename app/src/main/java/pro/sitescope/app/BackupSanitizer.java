package pro.sitescope.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.regex.Pattern;

/** Redacts credentials in structured data and embedded/legacy JSON without changing live data. */
public final class BackupSanitizer {
    private static final Pattern USERINFO = Pattern.compile("(?i)([a-z][a-z0-9+.-]*://)[^\\s/?#\"<>]*@");
    private static final Pattern QUERY = Pattern.compile("(?i)([?&](?:password|passwd|pass|pwd|token|access_token|refresh_token|api_?key|key|auth|authorization|secret|signature|sig|username|user|login)=)[^&#\\s\"<>]*");
    private static final Pattern XTREAM = Pattern.compile("(?i)(https?://[^/\\s\"<>]+/(?:live|movie|series|timeshift)/)[^/\\s]+/[^/\\s]+/");
    private BackupSanitizer() { }

    public static JSONObject clean(JSONObject source) throws Exception {
        return (JSONObject) copy(source);
    }

    private static Object copy(Object value) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject result = new JSONObject();
            JSONObject source = (JSONObject) value;
            java.util.Iterator<String> keys = source.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                String name = key.toLowerCase(Locale.US);
                boolean secret = name.matches("(?:password|passwd|pass|pwd|token|access_token|refresh_token|api_key|apikey|secret|authorization|cookie|set-cookie)");
                // Omit the whole entry: preference values are typed objects, not plain strings.
                if (!secret) result.put(key, copy(source.get(key)));
            }
            return result;
        }
        if (value instanceof JSONArray) {
            JSONArray result = new JSONArray(); JSONArray source = (JSONArray) value;
            for (int i = 0; i < source.length(); i++) result.put(copy(source.get(i)));
            return result;
        }
        if (value instanceof String) {
            String text = (String) value;
            String trimmed = text.trim();
            // Legacy preferences and database JSON columns can themselves contain serialized JSON.
            try {
                if (trimmed.startsWith("{")) return copy(new JSONObject(text)).toString();
                if (trimmed.startsWith("[")) return copy(new JSONArray(text)).toString();
            } catch (org.json.JSONException ignored) { }
            text = USERINFO.matcher(text).replaceAll("$1");
            text = QUERY.matcher(text).replaceAll("$1REDACTED");
            return XTREAM.matcher(text).replaceAll("$1REDACTED/REDACTED/");
        }
        return value;
    }
}
