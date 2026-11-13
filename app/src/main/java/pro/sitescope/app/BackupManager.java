package pro.sitescope.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.database.sqlite.SQLiteDatabase;
import pro.sitescope.app.tv.TvStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Portable, versioned backup for user-created non-secret application data. */
public final class BackupManager {
    public static final int SCHEMA_VERSION = 13;
    private static final long MAX_IMPORT_BYTES = 64L * 1024L * 1024L;
    private static final List<String> PREF_FILES = Arrays.asList(
        "sitescope_data_v1",
        "sitescope_iptv",
        "ip_intelligence",
        "connection_profiles",
        "protocol_router_preferences",
        ConnectionSessionStore.PREFS,
        DeviceInventoryStore.PREFS,
        SettingsStore.PREFS,
        "site_monitor",
        "ip_recent",
        "wake_on_lan",
        "speed_test",
        "tv_mask",
        "net_monitor",
        "vpn_settings"
    );

    private BackupManager() { }

    public static String suggestedFileName() {
        return "SiteScopePro-backup-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date()) + ".json";
    }

    public static JSONObject createBackup(Context context) throws Exception {
        JSONObject root = new JSONObject();
        root.put("format", "SiteScopeProBackup");
        root.put("schemaVersion", SCHEMA_VERSION);
        root.put("createdAt", System.currentTimeMillis());
        root.put("package", context.getPackageName());
        root.put("appVersion", context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName);
        root.put("secretsIncluded", false);
        root.put("note", "Credentials in URLs and known token fields are redacted. Protected credentials and downloaded GeoIP databases are excluded. Re-enter credentials after restoring.");
        root.put("database", AppDatabase.get(context).exportPortable());
        root.put("tv", TvStore.INSTANCE.exportState(context));

        JSONObject prefsRoot = new JSONObject();
        for (String file : PREF_FILES) {
            SharedPreferences prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE);
            JSONObject values = new JSONObject();
            for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                if ("sitescope_data_v1".equals(file) && ("active_scan_v2".equals(entry.getKey()) || "audits".equals(entry.getKey()) || "projects".equals(entry.getKey()) || "scan_sessions".equals(entry.getKey()) || "iptv_history".equals(entry.getKey()))) continue;
                if (ConnectionProfileStore.PREFS.equals(file) && "profiles_json".equals(entry.getKey())) continue;
                if (ConnectionSessionStore.PREFS.equals(file) && "events_json".equals(entry.getKey())) continue;
                if ("vpn_settings".equals(file) && "basic_upstream".equals(entry.getKey())) continue;   // может содержать адрес с логином
                if (DeviceInventoryStore.PREFS.equals(file) && ("snapshots_json".equals(entry.getKey()) || "aliases_json".equals(entry.getKey()))) continue;
                values.put(entry.getKey(), encodeValue(entry.getValue()));
            }
            prefsRoot.put(file, values);
        }
        root.put("preferences", prefsRoot);
        root.put("summary", summaryJson(context));
        return BackupSanitizer.clean(root);
    }

    public static void exportTo(Context context, Uri uri) throws Exception {
        JSONObject backup = createBackup(context);
        OutputStream output = context.getContentResolver().openOutputStream(uri, "w");
        if (output == null) throw new IllegalStateException("Не удалось открыть файл для записи");
        try {
            byte[] data = backup.toString(2).getBytes(StandardCharsets.UTF_8);
            output.write(data);
            output.flush();
        } finally {
            output.close();
        }
    }

    public static JSONObject readBackup(Context context, Uri uri) throws Exception {
        InputStream input = context.getContentResolver().openInputStream(uri);
        if (input == null) throw new IllegalStateException("Не удалось открыть файл резервной копии");
        StringBuilder out = new StringBuilder();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            char[] buffer = new char[8192];
            long total = 0;
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_IMPORT_BYTES) throw new IllegalArgumentException("Файл резервной копии превышает 64 МБ");
                out.append(buffer, 0, read);
            }
        } finally {
            input.close();
        }
        JSONObject root = new JSONObject(out.toString());
        validate(root);
        return root;
    }

    public static synchronized void restore(Context context, JSONObject root) throws Exception {
        validate(root);
        JSONObject prefsRoot = root.getJSONObject("preferences");

        // Decode everything before touching current data.
        List<DecodedPrefs> decoded = new ArrayList<DecodedPrefs>();
        for (String file : PREF_FILES) {
            JSONObject values = prefsRoot.optJSONObject(file);
            if (values == null) continue;
            DecodedPrefs item = new DecodedPrefs(file);
            JSONArray names = values.names();
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    String key = names.getString(i);
                    item.values.put(key, decodeValue(values.getJSONObject(key)));
                }
            }
            decoded.add(item);
        }

        // TvStore writes can also update SQLite. Keep the same lock order: TV, then database.
        synchronized (TvStore.INSTANCE) {
            List<DecodedPrefs> before = new ArrayList<DecodedPrefs>();
            for (DecodedPrefs item : decoded) {
                DecodedPrefs old = new DecodedPrefs(item.file);
                for (Map.Entry<String, ?> entry : context.getSharedPreferences(item.file, Context.MODE_PRIVATE).getAll().entrySet()) {
                    Object value = entry.getValue();
                    old.values.put(entry.getKey(), value instanceof Set ? new HashSet<Object>((Set<?>) value) : value);
                }
                before.add(old);
            }
            JSONObject oldTv = TvStore.INSTANCE.exportState(context);
            SQLiteDatabase db = AppDatabase.get(context).write();
            boolean open = true;
            boolean tvChanged = false;
            db.beginTransaction();
            try {
                JSONObject database = root.optJSONObject("database");
                if (database != null) AppDatabase.get(context).restorePortable(database);
                writePreferences(context, decoded);
                if (database == null) AppDatabase.get(context).remigrateLegacy();
                tvChanged = true;
                if (root.has("tv")) TvStore.INSTANCE.restoreState(context, root.getJSONObject("tv"));
                else if (prefsRoot.has("sitescope_iptv")) TvStore.INSTANCE.restoreLegacy(context);
                db.setTransactionSuccessful();
                try { db.endTransaction(); } finally { open = false; }
            } catch (Exception failure) {
                if (open) {
                    try { db.endTransaction(); } catch (Exception rollback) { failure.addSuppressed(rollback); }
                }
                try { writePreferences(context, before); } catch (Exception rollback) { failure.addSuppressed(rollback); }
                if (tvChanged) try { TvStore.INSTANCE.restoreState(context, oldTv); } catch (Exception rollback) { failure.addSuppressed(rollback); }
                throw failure;
            }
        }
        pro.sitescope.app.data.RestoredState.reload(context);
    }

    private static void writePreferences(Context context, List<DecodedPrefs> items) {
        for (DecodedPrefs item : items) {
            SharedPreferences.Editor editor = context.getSharedPreferences(item.file, Context.MODE_PRIVATE).edit().clear();
            for (Map.Entry<String, Object> entry : item.values.entrySet()) put(editor, entry.getKey(), entry.getValue());
            if (!editor.commit()) throw new IllegalStateException("Не удалось восстановить раздел " + item.file);
        }
    }

    public static String describe(JSONObject root) {
        JSONObject summary = root.optJSONObject("summary");
        long createdAt = root.optLong("createdAt");
        String date = createdAt > 0 ? new SimpleDateFormat("dd.MM.yyyy HH:mm", new Locale("ru", "RU")).format(new Date(createdAt)) : "неизвестно";
        if (summary == null) return "Создана: " + date;
        return "Создана: " + date + "\nПроекты: " + summary.optInt("projects") +
            " · SEO-аудиты: " + summary.optInt("audits") +
            " · IP-сканирования: " + summary.optInt("scans") +
            " · IPTV-снимки: " + summary.optInt("iptvSnapshots") +
            " · Каналы: " + summary.optInt("iptvChannels") +
            " · Подключения: " + summary.optInt("connections") +
            " · LAN-снимки: " + summary.optInt("inventorySnapshots") +
            " · Hosts-профили: " + summary.optInt("hostsProfiles");
    }

    public static String currentSummary(Context context) {
        JSONObject summary = summaryJson(context);
        return "Проекты: " + summary.optInt("projects") +
            " · SEO: " + summary.optInt("audits") +
            " · IP: " + summary.optInt("scans") +
            " · IPTV: " + summary.optInt("iptvChannels") + " каналов" +
            " · Подключения: " + summary.optInt("connections") +
            " · LAN-снимки: " + summary.optInt("inventorySnapshots") +
            " · Hosts-профили: " + summary.optInt("hostsProfiles");
    }

    private static JSONObject summaryJson(Context context) {
        JSONObject result = new JSONObject();
        try {
            DataStore store = new DataStore(context);
            result.put("projects", store.projects().size());
            result.put("audits", store.audits().size());
            result.put("scans", store.scans().size());
            result.put("iptvSnapshots", store.iptvHistory().size());
            TvStore.INSTANCE.load(context);
            result.put("iptvChannels", TvStore.INSTANCE.snapshot().size());
            result.put("connections", ConnectionProfileStore.list(context).size());
            result.put("inventorySnapshots", DeviceInventoryStore.list(context).size());
            result.put("connectionEvents", ConnectionSessionStore.list(context).size());
            result.put("hostsProfiles", new HostsStore(context).profiles().size());
        } catch (Exception ignored) { }
        return result;
    }

    private static void validate(JSONObject root) throws Exception {
        if (!"SiteScopeProBackup".equals(root.optString("format"))) throw new IllegalArgumentException("Это не резервная копия SiteScope Pro");
        int schema = root.optInt("schemaVersion", 0);
        if (schema < 1 || schema > SCHEMA_VERSION) throw new IllegalArgumentException("Неподдерживаемая версия резервной копии: " + schema);
        JSONObject prefs = root.optJSONObject("preferences");
        if (prefs == null) throw new IllegalArgumentException("В резервной копии отсутствуют данные");
        if (schema >= 5 && root.optJSONObject("database") == null) throw new IllegalArgumentException("В резервной копии отсутствует SQLite-раздел");
        if (root.has("database")) AppDatabase.validatePortable(root.getJSONObject("database"));
        if (schema >= 7 || root.has("tv")) TvStore.INSTANCE.validateState(root.getJSONObject("tv"));
        for (String file : PREF_FILES) {
            JSONObject values = prefs.optJSONObject(file);
            if (values == null) continue;
            JSONArray names = values.names();
            if (names == null) continue;
            for (int i = 0; i < names.length(); i++) decodeValue(values.getJSONObject(names.getString(i)));
        }
    }

    private static JSONObject encodeValue(Object value) throws Exception {
        JSONObject out = new JSONObject();
        if (value instanceof String) { out.put("type", "string"); out.put("value", value); }
        else if (value instanceof Integer) { out.put("type", "int"); out.put("value", value); }
        else if (value instanceof Long) { out.put("type", "long"); out.put("value", value); }
        else if (value instanceof Float) { out.put("type", "float"); out.put("value", String.valueOf(value)); }
        else if (value instanceof Boolean) { out.put("type", "boolean"); out.put("value", value); }
        else if (value instanceof Set) {
            out.put("type", "stringSet");
            JSONArray array = new JSONArray();
            for (Object item : (Set<?>) value) array.put(String.valueOf(item));
            out.put("value", array);
        } else throw new IllegalArgumentException("Неподдерживаемый тип данных: " + value.getClass().getName());
        return out;
    }

    private static Object decodeValue(JSONObject encoded) throws Exception {
        String type = encoded.getString("type");
        if ("string".equals(type)) return encoded.optString("value", "");
        if ("int".equals(type)) return encoded.getInt("value");
        if ("long".equals(type)) return encoded.getLong("value");
        if ("float".equals(type)) return Float.valueOf(encoded.getString("value"));
        if ("boolean".equals(type)) return encoded.getBoolean("value");
        if ("stringSet".equals(type)) {
            Set<String> values = new HashSet<String>();
            JSONArray array = encoded.getJSONArray("value");
            for (int i = 0; i < array.length(); i++) values.add(array.getString(i));
            return values;
        }
        throw new IllegalArgumentException("Неизвестный тип данных: " + type);
    }

    @SuppressWarnings("unchecked")
    private static void put(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof String) editor.putString(key, (String) value);
        else if (value instanceof Integer) editor.putInt(key, (Integer) value);
        else if (value instanceof Long) editor.putLong(key, (Long) value);
        else if (value instanceof Float) editor.putFloat(key, (Float) value);
        else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else if (value instanceof Set) editor.putStringSet(key, (Set<String>) value);
    }

    private static final class DecodedPrefs {
        final String file;
        final java.util.LinkedHashMap<String, Object> values = new java.util.LinkedHashMap<String, Object>();
        DecodedPrefs(String file) { this.file = file; }
    }
}
