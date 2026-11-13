package pro.sitescope.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Central application database for user-created, non-secret data.
 * Large collections are no longer rewritten as monolithic SharedPreferences JSON blobs.
 */
public final class AppDatabase extends SQLiteOpenHelper {
    private static final String NAME = "sitescope_app.db";
    private static final int VERSION = 5;
    private static volatile AppDatabase INSTANCE;
    private final Context context;

    public static AppDatabase get(Context context) {
        if (INSTANCE == null) {
            synchronized (AppDatabase.class) {
                if (INSTANCE == null) INSTANCE = new AppDatabase(context.getApplicationContext());
            }
        }
        INSTANCE.migrateLegacyOnce();
        return INSTANCE;
    }

    private AppDatabase(Context context) {
        super(context, NAME, null, VERSION);
        this.context = context;
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        db.setForeignKeyConstraintsEnabled(true);
        db.enableWriteAheadLogging();
    }

    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE app_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        db.execSQL("CREATE TABLE projects (project_key TEXT PRIMARY KEY, type TEXT NOT NULL, target TEXT NOT NULL, updated_at INTEGER NOT NULL, pinned INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0, json TEXT NOT NULL)");
        db.execSQL("CREATE INDEX projects_sort_idx ON projects(archived, pinned DESC, updated_at DESC)");
        db.execSQL("CREATE TABLE audits (row_id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, json TEXT NOT NULL)");
        db.execSQL("CREATE INDEX audits_time_idx ON audits(timestamp DESC)");
        db.execSQL("CREATE TABLE scan_sessions (row_id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, target TEXT, ports TEXT, json TEXT NOT NULL)");
        db.execSQL("CREATE INDEX scans_time_idx ON scan_sessions(timestamp DESC)");
        db.execSQL("CREATE TABLE scan_results (session_id INTEGER NOT NULL, host TEXT NOT NULL, port INTEGER NOT NULL, protocol TEXT, json TEXT NOT NULL, PRIMARY KEY(session_id, host, port), FOREIGN KEY(session_id) REFERENCES scan_sessions(row_id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX scan_results_host_idx ON scan_results(host, port)");
        db.execSQL("CREATE TABLE active_scan_results (host TEXT NOT NULL, port INTEGER NOT NULL, json TEXT NOT NULL, PRIMARY KEY(host, port))");
        db.execSQL("CREATE TABLE iptv_history (row_id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, json TEXT NOT NULL)");
        db.execSQL("CREATE INDEX iptv_time_idx ON iptv_history(timestamp DESC)");
        db.execSQL("CREATE TABLE connection_profiles (id TEXT PRIMARY KEY, pinned INTEGER NOT NULL DEFAULT 0, sort_at INTEGER NOT NULL, json TEXT NOT NULL)");
        db.execSQL("CREATE INDEX connection_profiles_sort_idx ON connection_profiles(pinned DESC, sort_at DESC)");
        db.execSQL("CREATE TABLE connection_events (row_id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, json TEXT NOT NULL)");
        db.execSQL("CREATE INDEX connection_events_time_idx ON connection_events(timestamp DESC)");
        db.execSQL("CREATE TABLE inventory_snapshots (id TEXT PRIMARY KEY, timestamp INTEGER NOT NULL, json TEXT NOT NULL)");
        db.execSQL("CREATE INDEX inventory_time_idx ON inventory_snapshots(timestamp DESC)");
        db.execSQL("CREATE TABLE inventory_aliases (ip TEXT PRIMARY KEY, alias TEXT NOT NULL)");
        createHostsTables(db);
        DeviceRegistryStore.createTables(db);
        DeviceHistoryStore.createTables(db);
        ExposureStore.createTables(db);
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) createHostsTables(db);
        if (oldVersion < 3) DeviceRegistryStore.createTables(db);
        if (oldVersion < 4) DeviceHistoryStore.createTables(db);
        if (oldVersion < 5) ExposureStore.createTables(db);
    }

    private void createHostsTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS hosts_profiles (id TEXT PRIMARY KEY, name TEXT NOT NULL, active INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, mode TEXT NOT NULL DEFAULT 'AUTO')");
        db.execSQL("CREATE INDEX IF NOT EXISTS hosts_profiles_sort_idx ON hosts_profiles(active DESC, updated_at DESC)");
        db.execSQL("CREATE TABLE IF NOT EXISTS hosts_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, profile_id TEXT NOT NULL, position INTEGER NOT NULL, enabled INTEGER NOT NULL DEFAULT 1, address TEXT NOT NULL, hostnames TEXT NOT NULL, comment TEXT NOT NULL DEFAULT '', source TEXT NOT NULL DEFAULT 'manual', FOREIGN KEY(profile_id) REFERENCES hosts_profiles(id) ON DELETE CASCADE)");
        db.execSQL("CREATE INDEX IF NOT EXISTS hosts_entries_profile_idx ON hosts_entries(profile_id, position, id)");
        db.execSQL("CREATE TABLE IF NOT EXISTS hosts_backups (id TEXT PRIMARY KEY, timestamp INTEGER NOT NULL, mode TEXT NOT NULL, path TEXT NOT NULL, sha256 TEXT NOT NULL, size INTEGER NOT NULL, source_path TEXT NOT NULL)");
        db.execSQL("CREATE INDEX IF NOT EXISTS hosts_backups_time_idx ON hosts_backups(timestamp DESC)");
        db.execSQL("CREATE TABLE IF NOT EXISTS hosts_apply_events (row_id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp INTEGER NOT NULL, profile_id TEXT, mode TEXT NOT NULL, success INTEGER NOT NULL, message TEXT NOT NULL, before_hash TEXT, after_hash TEXT)");
        db.execSQL("CREATE INDEX IF NOT EXISTS hosts_apply_events_time_idx ON hosts_apply_events(timestamp DESC)");
    }

    SQLiteDatabase read() { return getReadableDatabase(); }
    SQLiteDatabase write() { return getWritableDatabase(); }

    public String meta(String key, String fallback) {
        Cursor cursor = read().query("app_meta", new String[]{"value"}, "key=?", new String[]{key}, null, null, null);
        try { return cursor.moveToFirst() ? cursor.getString(0) : fallback; }
        finally { cursor.close(); }
    }

    public void putMeta(String key, String value) {
        ContentValues values = new ContentValues();
        values.put("key", key); values.put("value", value == null ? "" : value);
        write().insertWithOnConflict("app_meta", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private synchronized void migrateLegacyOnce() {
        if ("1".equals(meta("legacy_migrated_v1", "0"))) return;
        SQLiteDatabase db = write();
        db.beginTransaction();
        try {
            migrateDataStore(db);
            migrateConnections(db);
            migrateSessions(db);
            migrateInventory(db);
            ContentValues done = new ContentValues(); done.put("key", "legacy_migrated_v1"); done.put("value", "1");
            db.insertWithOnConflict("app_meta", null, done, SQLiteDatabase.CONFLICT_REPLACE);
            db.setTransactionSuccessful();
        } catch (Exception ignored) {
            // Keep legacy preferences untouched; migration will be retried on next launch.
        } finally { db.endTransaction(); }
    }

    private void migrateDataStore(SQLiteDatabase db) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences("sitescope_data_v1", Context.MODE_PRIVATE);
        JSONArray projects = new JSONArray(prefs.getString("projects", "[]"));
        for (int i = 0; i < projects.length(); i++) {
            JSONObject o = projects.optJSONObject(i); if (o == null) continue;
            ContentValues v = new ContentValues();
            String type = o.optString("type", "SEO").toUpperCase();
            String target = o.optString("target");
            v.put("project_key", projectKey(type, target)); v.put("type", type); v.put("target", target);
            v.put("updated_at", o.optLong("updatedAt", System.currentTimeMillis()));
            v.put("pinned", o.optBoolean("pinned") ? 1 : 0); v.put("archived", o.optBoolean("archived") ? 1 : 0);
            v.put("json", o.toString());
            db.insertWithOnConflict("projects", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        }
        JSONArray audits = new JSONArray(prefs.getString("audits", "[]"));
        for (int i = 0; i < audits.length(); i++) {
            JSONObject o = audits.optJSONObject(i); if (o == null) continue;
            ContentValues v = new ContentValues(); v.put("timestamp", o.optLong("timestamp", System.currentTimeMillis())); v.put("json", o.toString());
            db.insert("audits", null, v);
        }
        JSONArray scans = new JSONArray(prefs.getString("scan_sessions", "[]"));
        for (int i = 0; i < scans.length(); i++) {
            JSONObject o = scans.optJSONObject(i); if (o == null) continue;
            insertScanJson(db, o);
        }
        JSONArray iptv = new JSONArray(prefs.getString("iptv_history", "[]"));
        for (int i = 0; i < iptv.length(); i++) {
            JSONObject o = iptv.optJSONObject(i); if (o == null) continue;
            ContentValues v = new ContentValues(); v.put("timestamp", o.optLong("timestamp", System.currentTimeMillis())); v.put("json", o.toString());
            db.insert("iptv_history", null, v);
        }
    }

    private void migrateConnections(SQLiteDatabase db) throws Exception {
        String raw = context.getSharedPreferences(ConnectionProfileStore.PREFS, Context.MODE_PRIVATE).getString("profiles_json", "[]");
        JSONArray array = new JSONArray(raw);
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.optJSONObject(i); if (o == null) continue;
            ContentValues v = new ContentValues();
            String id = o.optString("id"); if (id.length() == 0) continue;
            long sortAt = Math.max(o.optLong("savedAt"), o.optLong("lastOpenedAt"));
            v.put("id", id); v.put("pinned", o.optBoolean("pinned") ? 1 : 0); v.put("sort_at", sortAt); v.put("json", o.toString());
            db.insertWithOnConflict("connection_profiles", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    private void migrateSessions(SQLiteDatabase db) throws Exception {
        String raw = context.getSharedPreferences(ConnectionSessionStore.PREFS, Context.MODE_PRIVATE).getString("events_json", "[]");
        JSONArray array = new JSONArray(raw);
        for (int i = array.length() - 1; i >= 0; i--) {
            JSONObject o = array.optJSONObject(i); if (o == null) continue;
            ContentValues v = new ContentValues(); v.put("timestamp", o.optLong("timestamp", System.currentTimeMillis())); v.put("json", o.toString());
            db.insert("connection_events", null, v);
        }
    }

    private void migrateInventory(SQLiteDatabase db) throws Exception {
        SharedPreferences prefs = context.getSharedPreferences(DeviceInventoryStore.PREFS, Context.MODE_PRIVATE);
        JSONArray snapshots = new JSONArray(prefs.getString("snapshots_json", "[]"));
        for (int i = 0; i < snapshots.length(); i++) {
            JSONObject o = snapshots.optJSONObject(i); if (o == null) continue;
            String id = o.optString("id"); if (id.length() == 0) continue;
            ContentValues v = new ContentValues(); v.put("id", id); v.put("timestamp", o.optLong("timestamp")); v.put("json", o.toString());
            db.insertWithOnConflict("inventory_snapshots", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        }
        JSONObject aliases = new JSONObject(prefs.getString("aliases_json", "{}"));
        JSONArray names = aliases.names();
        if (names != null) for (int i = 0; i < names.length(); i++) {
            String ip = names.getString(i); ContentValues v = new ContentValues(); v.put("ip", ip); v.put("alias", aliases.optString(ip));
            db.insertWithOnConflict("inventory_aliases", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    long insertScanJson(SQLiteDatabase db, JSONObject o) throws Exception {
        ContentValues v = new ContentValues();
        v.put("timestamp", o.optLong("timestamp", System.currentTimeMillis()));
        v.put("target", o.optString("targetExpression")); v.put("ports", o.optString("portExpression")); v.put("json", o.toString());
        long id = db.insert("scan_sessions", null, v);
        JSONArray rows = o.optJSONArray("openResults");
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i); if (row == null) continue;
            ContentValues rv = new ContentValues();
            rv.put("session_id", id); rv.put("host", row.optString("host")); rv.put("port", row.optInt("port"));
            rv.put("protocol", row.optString("protocol")); rv.put("json", row.toString());
            db.insertWithOnConflict("scan_results", null, rv, SQLiteDatabase.CONFLICT_REPLACE);
        }
        return id;
    }

    static String projectKey(String type, String target) {
        return (type == null ? "SEO" : type.trim().toUpperCase()) + "|" + (target == null ? "" : target.trim().toLowerCase());
    }


    public synchronized void remigrateLegacy() {
        SQLiteDatabase db = write();
        db.beginTransaction();
        try {
            db.delete("active_scan_results", null, null); db.delete("scan_results", null, null); db.delete("scan_sessions", null, null); db.delete("audits", null, null);
            db.delete("projects", null, null); db.delete("iptv_history", null, null); db.delete("connection_profiles", null, null);
            db.delete("connection_events", null, null); db.delete("inventory_snapshots", null, null); db.delete("inventory_aliases", null, null);
            db.delete("device_observations", null, null); db.delete("device_cards", null, null);
            db.delete("exposure_targets", null, null); db.delete("exposure_runs", null, null);
            db.delete("app_meta", "key=?", new String[]{"legacy_migrated_v1"});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        migrateLegacyOnce();
    }

    public JSONObject exportPortable() throws Exception {
        JSONObject root = new JSONObject();
        root.put("projects", exportTable("projects", "json", "updated_at DESC", 0));
        root.put("audits", exportTable("audits", "json", "timestamp DESC", 0));
        root.put("scans", exportTable("scan_sessions", "json", "timestamp DESC", 0));
        root.put("iptvHistory", exportTable("iptv_history", "json", "timestamp DESC", 0));
        root.put("connectionProfiles", exportTable("connection_profiles", "json", "sort_at DESC", 0));
        root.put("connectionEvents", exportTable("connection_events", "json", "timestamp DESC", 0));
        root.put("inventorySnapshots", exportTable("inventory_snapshots", "json", "timestamp DESC", 0));
        JSONArray aliases = new JSONArray();
        Cursor cursor = read().query("inventory_aliases", new String[]{"ip", "alias"}, null, null, null, null, "ip");
        try { while (cursor.moveToNext()) { JSONObject o = new JSONObject(); o.put("ip", cursor.getString(0)); o.put("alias", cursor.getString(1)); aliases.put(o); } }
        finally { cursor.close(); }
        root.put("inventoryAliases", aliases);
        root.put("hostsProfiles", new HostsStore(context).exportProfiles());
        root.put("deviceRegistry", DeviceRegistryStore.exportGraph(read()));
        root.put("exposure", ExposureStore.exportState(read()));
        return root;
    }

    private JSONArray exportTable(String table, String column, String order, int limit) throws Exception {
        JSONArray array = new JSONArray();
        Cursor cursor = read().query(table, new String[]{column}, null, null, null, null, order, limit > 0 ? String.valueOf(limit) : null);
        try { while (cursor.moveToNext()) array.put(new JSONObject(cursor.getString(0))); }
        finally { cursor.close(); }
        return array;
    }

    public void restorePortable(JSONObject root) throws Exception {
        validatePortable(root);
        SQLiteDatabase db = write(); db.beginTransaction();
        try {
            db.delete("active_scan_results", null, null); db.delete("scan_results", null, null); db.delete("scan_sessions", null, null); db.delete("audits", null, null);
            db.delete("projects", null, null); db.delete("iptv_history", null, null); db.delete("connection_profiles", null, null);
            db.delete("connection_events", null, null); db.delete("inventory_snapshots", null, null); db.delete("inventory_aliases", null, null);
            DeviceRegistryStore.restoreGraph(db, root.optJSONObject("deviceRegistry"));
            ExposureStore.restore(db, root.optJSONObject("exposure"));
            importProjects(db, root.optJSONArray("projects"));
            importSimple(db, "audits", root.optJSONArray("audits"), "timestamp");
            JSONArray scans = root.optJSONArray("scans"); if (scans != null) for (int i = scans.length() - 1; i >= 0; i--) insertScanJson(db, scans.getJSONObject(i));
            importSimple(db, "iptv_history", root.optJSONArray("iptvHistory"), "timestamp");
            importProfiles(db, root.optJSONArray("connectionProfiles"));
            importSimple(db, "connection_events", root.optJSONArray("connectionEvents"), "timestamp");
            importSnapshots(db, root.optJSONArray("inventorySnapshots"));
            JSONArray aliases = root.optJSONArray("inventoryAliases"); if (aliases != null) for (int i = 0; i < aliases.length(); i++) {
                JSONObject o = aliases.getJSONObject(i); ContentValues v = new ContentValues(); v.put("ip", o.optString("ip")); v.put("alias", o.optString("alias"));
                db.insertWithOnConflict("inventory_aliases", null, v, SQLiteDatabase.CONFLICT_REPLACE);
            }
            if (root.has("hostsProfiles")) new HostsStore(context).restoreProfiles(root.getJSONArray("hostsProfiles"));
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    /** Validate all row containers before deleting any current data. */
    public static void validatePortable(JSONObject root) throws Exception {
        if (root.has("exposure")) ExposureStore.validate(root.getJSONObject("exposure"));
        if (root.has("deviceRegistry")) DeviceRegistryStore.validateGraph(root.getJSONObject("deviceRegistry"));
        String[] fields = {"projects", "audits", "scans", "iptvHistory", "connectionProfiles", "connectionEvents", "inventorySnapshots", "inventoryAliases", "hostsProfiles"};
        for (String field : fields) {
            if (!root.has(field)) continue;
            JSONArray rows = root.getJSONArray(field);
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String nested = "hostsProfiles".equals(field) ? "entries" : "inventorySnapshots".equals(field) ? "devices" : "scans".equals(field) ? "openResults" : "";
                if (!nested.isEmpty() && row.has(nested)) {
                    JSONArray children = row.getJSONArray(nested);
                    for (int j = 0; j < children.length(); j++) children.getJSONObject(j);
                }
            }
        }
    }

    private void importProjects(SQLiteDatabase db, JSONArray array) throws Exception {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i); String type = o.optString("type", "SEO").toUpperCase(); String target = o.optString("target");
            ContentValues v = new ContentValues(); v.put("project_key", projectKey(type, target)); v.put("type", type); v.put("target", target);
            v.put("updated_at", o.optLong("updatedAt")); v.put("pinned", o.optBoolean("pinned") ? 1 : 0); v.put("archived", o.optBoolean("archived") ? 1 : 0); v.put("json", o.toString());
            db.insertWithOnConflict("projects", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    private void importSimple(SQLiteDatabase db, String table, JSONArray array, String timestampField) throws Exception {
        if (array == null) return;
        for (int i = array.length() - 1; i >= 0; i--) {
            JSONObject o = array.getJSONObject(i); ContentValues v = new ContentValues(); v.put("timestamp", o.optLong(timestampField, System.currentTimeMillis())); v.put("json", o.toString()); db.insertOrThrow(table, null, v);
        }
    }

    private void importProfiles(SQLiteDatabase db, JSONArray array) throws Exception {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i); String id = o.optString("id"); if (id.length() == 0) continue;
            ContentValues v = new ContentValues(); v.put("id", id); v.put("pinned", o.optBoolean("pinned") ? 1 : 0);
            v.put("sort_at", Math.max(o.optLong("savedAt"), o.optLong("lastOpenedAt"))); v.put("json", o.toString());
            db.insertWithOnConflict("connection_profiles", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    private void importSnapshots(SQLiteDatabase db, JSONArray array) throws Exception {
        if (array == null) return;
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i); String id = o.optString("id"); if (id.length() == 0) continue;
            ContentValues v = new ContentValues(); v.put("id", id); v.put("timestamp", o.optLong("timestamp")); v.put("json", o.toString());
            db.insertWithOnConflict("inventory_snapshots", null, v, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }
}
