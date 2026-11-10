package pro.sitescope.app;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Stores an ordered list of Xray profiles encrypted with Android Keystore. */
public final class XrayProfileStore {
    private static final String PREFS = "sitescope_xray_profiles_v2";
    private static final String LEGACY_PREFS = "sitescope_xray_profile_meta_v1";
    private static final String LEGACY_SECRET_KEY = "xray.profile.raw.v1";
    private static final String SECRET_PREFIX = "xray.profile.raw.v2.";
    private static final String KEY_IDS = "ids";
    private static final String KEY_ACTIVE = "activeId";

    private final SharedPreferences prefs;
    private final SharedPreferences legacyPrefs;
    private final SecureStore secure;

    public static final class Info {
        public String id = "";
        public boolean configured;
        public boolean active;
        public String name = "";
        public String type = "";
        public String endpoint = "";
        public long updatedAt;

        public String label() {
            String title = name == null || name.trim().length() == 0 ? "Профиль Xray" : name.trim();
            return (active ? "● " : "") + title;
        }
    }

    public XrayProfileStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        legacyPrefs = context.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE);
        secure = new SecureStore(context);
        migrateLegacy();
    }

    /** Backward-compatible save: replace selected profile, or create the first one. */
    public synchronized Info save(String raw) throws Exception {
        Info selected = info();
        return selected.configured ? replace(selected.id, raw) : add(raw);
    }

    public synchronized Info add(String raw) throws Exception {
        XrayProfileParser.ParsedProfile parsed = XrayProfileParser.parse(raw);
        String id = UUID.randomUUID().toString();
        secure.put(secretKey(id), raw.trim());
        writeMetadata(id, parsed.name, parsed.type, parsed.endpoint, System.currentTimeMillis());
        List<String> ids = ids();
        ids.add(id);
        prefs.edit().putString(KEY_IDS, encodeIds(ids)).putString(KEY_ACTIVE, id).apply();
        return info(id);
    }

    public synchronized Info replace(String id, String raw) throws Exception {
        if (!containsId(id)) throw new IllegalArgumentException("Профиль Xray не найден");
        XrayProfileParser.ParsedProfile parsed = XrayProfileParser.parse(raw);
        secure.put(secretKey(id), raw.trim());
        writeMetadata(id, parsed.name, parsed.type, parsed.endpoint, System.currentTimeMillis());
        return info(id);
    }

    public synchronized void select(String id) {
        if (!containsId(id) || raw(id).length() == 0) throw new IllegalArgumentException("Профиль Xray недоступен");
        prefs.edit().putString(KEY_ACTIVE, id).apply();
    }

    public synchronized void rename(String id, String name) {
        if (!containsId(id)) throw new IllegalArgumentException("Профиль Xray не найден");
        String clean = name == null ? "" : name.trim();
        if (clean.length() == 0) throw new IllegalArgumentException("Введите название профиля");
        prefs.edit().putString(metaKey(id, "name"), clean).putLong(metaKey(id, "updatedAt"), System.currentTimeMillis()).apply();
    }

    public synchronized void delete(String id) {
        if (!containsId(id)) return;
        secure.clear(secretKey(id));
        SharedPreferences.Editor editor = prefs.edit();
        editor.remove(metaKey(id, "name")).remove(metaKey(id, "type")).remove(metaKey(id, "endpoint")).remove(metaKey(id, "updatedAt")).remove(metaKey(id, "configured"));
        List<String> ids = ids();
        ids.remove(id);
        String active = prefs.getString(KEY_ACTIVE, "");
        if (id.equals(active)) active = ids.isEmpty() ? "" : ids.get(0);
        editor.putString(KEY_IDS, encodeIds(ids)).putString(KEY_ACTIVE, active).apply();
    }

    /** Backward-compatible clear: removes only the currently selected profile. */
    public synchronized void clear() {
        Info selected = info();
        if (selected.id.length() > 0) delete(selected.id);
    }

    public synchronized void clearAll() {
        List<String> ids = ids();
        for (String id : ids) secure.clear(secretKey(id));
        prefs.edit().clear().apply();
    }

    public synchronized String raw() {
        Info selected = info();
        return selected.id.length() == 0 ? "" : raw(selected.id);
    }

    public synchronized String raw(String id) {
        return id == null || id.length() == 0 ? "" : secure.get(secretKey(id));
    }

    public synchronized boolean configured() { return info().configured; }

    /** Selected profile. */
    public synchronized Info info() {
        List<Info> all = profiles();
        if (all.isEmpty()) return new Info();
        String active = prefs.getString(KEY_ACTIVE, "");
        for (Info value : all) if (value.id.equals(active) && value.configured) return value;
        for (Info value : all) if (value.configured) {
            prefs.edit().putString(KEY_ACTIVE, value.id).apply();
            value.active = true;
            return value;
        }
        return all.get(0);
    }

    public synchronized Info info(String id) {
        Info value = new Info();
        if (id == null || id.length() == 0 || !containsId(id)) return value;
        value.id = id;
        value.name = prefs.getString(metaKey(id, "name"), "");
        value.type = prefs.getString(metaKey(id, "type"), "");
        value.endpoint = prefs.getString(metaKey(id, "endpoint"), "");
        value.updatedAt = prefs.getLong(metaKey(id, "updatedAt"), 0L);
        value.configured = prefs.getBoolean(metaKey(id, "configured"), false);
        value.active = id.equals(prefs.getString(KEY_ACTIVE, ""));
        return value;
    }

    public synchronized List<Info> profiles() {
        List<Info> result = new ArrayList<Info>();
        String active = prefs.getString(KEY_ACTIVE, "");
        for (String id : ids()) {
            Info value = new Info();
            value.id = id;
            value.name = prefs.getString(metaKey(id, "name"), "");
            value.type = prefs.getString(metaKey(id, "type"), "");
            value.endpoint = prefs.getString(metaKey(id, "endpoint"), "");
            value.updatedAt = prefs.getLong(metaKey(id, "updatedAt"), 0L);
            value.configured = prefs.getBoolean(metaKey(id, "configured"), false);
            value.active = id.equals(active);
            result.add(value);
        }
        return result;
    }

    private synchronized void migrateLegacy() {
        if (!ids().isEmpty()) return;
        String raw = secure.get(LEGACY_SECRET_KEY);
        if (raw.length() == 0) return;
        try {
            XrayProfileParser.ParsedProfile parsed = XrayProfileParser.parse(raw);
            String id = "legacy-" + UUID.randomUUID().toString();
            secure.put(secretKey(id), raw.trim());
            String name = legacyPrefs.getString("name", parsed.name);
            String type = legacyPrefs.getString("type", parsed.type);
            String endpoint = legacyPrefs.getString("endpoint", parsed.endpoint);
            long updatedAt = legacyPrefs.getLong("updatedAt", System.currentTimeMillis());
            writeMetadata(id, name, type, endpoint, updatedAt);
            prefs.edit().putString(KEY_IDS, id).putString(KEY_ACTIVE, id).apply();
            secure.clear(LEGACY_SECRET_KEY);
            legacyPrefs.edit().clear().apply();
        } catch (Exception ignored) {
            // Keep the old encrypted profile untouched if migration cannot be completed.
        }
    }

    private void writeMetadata(String id, String name, String type, String endpoint, long updatedAt) {
        prefs.edit().putString(metaKey(id, "name"), name == null ? "" : name)
            .putString(metaKey(id, "type"), type == null ? "" : type)
            .putString(metaKey(id, "endpoint"), endpoint == null ? "" : endpoint)
            .putLong(metaKey(id, "updatedAt"), updatedAt).putBoolean(metaKey(id, "configured"), true).apply();
    }

    private boolean containsId(String id) { return id != null && ids().contains(id); }
    private List<String> ids() {
        List<String> result = new ArrayList<String>();
        String encoded = prefs.getString(KEY_IDS, "");
        if (encoded == null || encoded.length() == 0) return result;
        String[] split = encoded.split("\\n");
        for (String id : split) if (id != null && id.trim().length() > 0 && !result.contains(id.trim())) result.add(id.trim());
        return result;
    }
    private static String encodeIds(List<String> ids) {
        StringBuilder out = new StringBuilder();
        for (String id : ids) { if (out.length() > 0) out.append('\n'); out.append(id); }
        return out.toString();
    }
    private static String secretKey(String id) { return SECRET_PREFIX + id; }
    private static String metaKey(String id, String field) { return "p." + id + "." + field; }
}
