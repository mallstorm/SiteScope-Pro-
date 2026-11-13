package pro.sitescope.app;

import org.json.*;
import java.net.URI;
import java.util.*;

/** Saved API evidence, scoped to an endpoint and network; never a liveness check. */
public final class RouterComparison {
    private RouterComparison() { }
    public static void attach(JSONObject snapshot, String endpoint, NetworkScope scope) throws Exception {
        snapshot.put("endpoint", RouterStore.address(endpoint, true).toASCIIString());
        snapshot.put("scope", scope.toJson());
        validateMetadata(snapshot);
    }
    static void validateMetadata(JSONObject snapshot) throws Exception {
        if (!snapshot.has("endpoint") && !snapshot.has("scope")) return; // beta20
        String endpoint = snapshot.getString("endpoint");
        if (endpoint.length() > 2048 || !RouterStore.address(endpoint, true).toASCIIString().equals(endpoint)) throw new IllegalArgumentException("Неверный адрес источника снимка");
        if (!new URI(endpoint).getHost().equalsIgnoreCase(snapshot.getString("host"))) throw new IllegalArgumentException("Адрес источника не совпадает со снимком");
        JSONObject scope = snapshot.getJSONObject("scope");
        Set<String> keys = new HashSet<>(Arrays.asList("id", "label", "reliable"));
        Iterator<String> iterator = scope.keys();
        while (iterator.hasNext()) if (!keys.contains(iterator.next())) throw new IllegalArgumentException("Неверная область сети");
        for (String key : Arrays.asList("id", "label")) if (!(scope.get(key) instanceof String) || scope.getString(key).isEmpty() || scope.getString(key).length() > 1024) throw new IllegalArgumentException("Неверная область сети");
        if (!(scope.get("reliable") instanceof Boolean)) throw new IllegalArgumentException("Неверная область сети");
    }
    public static String origin(JSONObject snapshot) {
        try {
            validateMetadata(snapshot);
            JSONObject scope = snapshot.getJSONObject("scope");
            if (!scope.getBoolean("reliable")) return "";
            return NetworkScope.digest(snapshot.getString("adapter") + "|" + snapshot.getString("endpoint") + "|" + scope.getString("id"));
        } catch (Exception e) { return ""; }
    }
    public static String mac(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.ROOT);
        if (!s.matches("(?:[0-9a-f]{12}|(?:[0-9a-f]{2}:){5}[0-9a-f]{2}|(?:[0-9a-f]{2}-){5}[0-9a-f]{2}|[0-9a-f]{4}(?:\\.[0-9a-f]{4}){2})")) return "";
        s = s.replace(":", "").replace("-", "").replace(".", "");
        if (s.equals("000000000000") || (Integer.parseInt(s.substring(0, 2), 16) & 1) != 0) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < 12; i += 2) { if (i > 0) result.append(':'); result.append(s, i, i + 2); }
        return result.toString();
    }
    public static JSONObject uniqueLease(JSONObject snapshot, String address) throws Exception {
        if (origin(snapshot).isEmpty()) throw new IllegalArgumentException("Для привязки нужен новый снимок с определённой сетью");
        String key = mac(address); if (key.isEmpty()) throw new IllegalArgumentException("Нужен корректный индивидуальный MAC");
        JSONObject found = null; JSONArray rows = snapshot.getJSONArray("rows");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i);
            if (row.optString("kind").equals("lease") && mac(row.optString("mac")).equals(key)) {
                if (found != null) throw new IllegalArgumentException("Этот MAC встречается несколько раз: привязка неоднозначна");
                found = row;
            }
        }
        if (found == null) throw new IllegalArgumentException("DHCP-запись больше не найдена");
        return found;
    }
    public static final class Change {
        public final String kind, key, type, field, before, after;
        Change(String kind, String key, String type, String field, String before, String after) { this.kind=kind; this.key=key; this.type=type; this.field=field; this.before=before; this.after=after; }
    }
    public static final class Result {
        public final List<Change> changes = new ArrayList<>();
        public boolean partial;
        public int ambiguous, unkeyed;
    }
    private static final List<String> FIELDS = Arrays.asList("name", "address", "model", "manufacturer", "software", "interface_type", "status");
    private static final class Index {
        final Map<String, JSONObject> rows = new TreeMap<>();
        final Set<String> duplicate = new HashSet<>();
        int unkeyed;
    }
    private static Index index(JSONObject snapshot) throws Exception {
        Index out = new Index(); JSONObject system = new JSONObject(); Set<String> conflict = new HashSet<>();
        JSONArray rows = snapshot.getJSONArray("rows");
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.getJSONObject(i); String kind = row.getString("kind");
            if (kind.equals("system")) {
                for (String field : FIELDS) {
                    String value = row.optString(field);
                    if (value.isEmpty() || conflict.contains(field)) continue;
                    if (system.has(field) && !system.getString(field).equals(value)) { system.remove(field); conflict.add(field); }
                    else system.put(field, value);
                }
                continue;
            }
            String key = kind.equals("lease") ? mac(row.optString("mac")) : row.optString("name");
            if (key.isEmpty()) { out.unkeyed++; continue; }
            key = kind + "|" + key;
            if (out.rows.containsKey(key)) out.duplicate.add(key); else out.rows.put(key, row);
        }
        if (system.length() > 0) out.rows.put("system|Система", system);
        if (!conflict.isEmpty()) out.duplicate.add("system|Система");
        return out;
    }
    public static Result compare(JSONObject before, JSONObject after) throws Exception {
        String origin = origin(before);
        if (origin.isEmpty() || !origin.equals(origin(after))) throw new IllegalArgumentException("Выберите снимки одного API-адреса, адаптера и сети. Для старых снимков выполните новый опрос.");
        if (before.getString("id").equals(after.getString("id")) || before.getLong("time") > after.getLong("time")) throw new IllegalArgumentException("Выберите разные снимки: сначала более ранний");
        Index a = index(before), b = index(after); Result result = new Result();
        result.partial = !before.has("partial") || !after.has("partial") || before.optBoolean("partial") || after.optBoolean("partial");
        if(!before.optString("dhcpSource").equals(after.optString("dhcpSource")))result.partial=true;
        result.unkeyed = a.unkeyed + b.unkeyed;
        Set<String> duplicates = new HashSet<>(a.duplicate); duplicates.addAll(b.duplicate); result.ambiguous = duplicates.size();
        Set<String> keys = new TreeSet<>(a.rows.keySet()); keys.addAll(b.rows.keySet());
        for (String key : keys) {
            if (duplicates.contains(key)) continue;
            String kind = key.substring(0, key.indexOf('|')), identity = key.substring(key.indexOf('|') + 1);
            JSONObject x = a.rows.get(key), y = b.rows.get(key);
            if (x == null || y == null) {
                if (!result.partial) result.changes.add(new Change(kind, identity, x == null ? "added" : "removed", "", summary(x), summary(y)));
                continue;
            }
            for (String field : FIELDS) {
                String oldValue=x.optString(field), newValue=y.optString(field);
                // Missing fields are unknown measurements, not deletions.
                if (!oldValue.isEmpty() && !newValue.isEmpty() && !oldValue.equals(newValue)) result.changes.add(new Change(kind, identity, "changed", field, oldValue, newValue));
            }
        }
        return result;
    }
    private static String summary(JSONObject row) {
        if (row == null) return "";
        List<String> parts=new ArrayList<>(); for(String field:FIELDS) if(!row.optString(field).isEmpty()) parts.add(field+"="+row.optString(field));
        return String.join(" · ",parts);
    }
}
