package pro.sitescope.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** SQLite-backed store for projects, audit history, scan sessions and IPTV history. */
public final class DataStore {
    private static final String PREFS = "sitescope_data_v1";
    private static final String ACTIVE_SCAN = "active_scan_v2";
    private final Context context;
    private final AppDatabase database;
    private final SharedPreferences checkpointPreferences;

    public DataStore(Context context) {
        this.context = context.getApplicationContext();
        this.database = AppDatabase.get(this.context);
        this.checkpointPreferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized List<Models.AuditResult> audits() {
        List<Models.AuditResult> items = new ArrayList<Models.AuditResult>();
        Cursor cursor = database.read().query("audits", new String[]{"json"}, null, null, null, null, "timestamp DESC");
        try {
            while (cursor.moveToNext()) {
                try { items.add(Models.AuditResult.fromJson(new JSONObject(cursor.getString(0)))); }
                catch (Exception ignored) { }
            }
        } finally { cursor.close(); }
        return items;
    }

    public synchronized void saveAudit(Models.AuditResult result) {
        SQLiteDatabase db = database.write();
        ContentValues values = new ContentValues();
        values.put("timestamp", result.timestamp);
        values.put("json", result.toJson().toString());
        db.insert("audits", null, values);
        trimTable(db, "audits", 40, "timestamp DESC");
        upsertProject("SEO", result.host.length() > 0 ? result.host : result.target, result.target, result.score,
            result.issues.size() + " проблем · " + result.pagesCrawled + " страниц");
    }

    public synchronized List<Models.Project> projects() {
        List<Models.Project> items = new ArrayList<Models.Project>();
        Cursor cursor = database.read().query("projects", new String[]{"json"}, null, null, null, null,
            "pinned DESC, updated_at DESC");
        try {
            while (cursor.moveToNext()) {
                try { items.add(Models.Project.fromJson(new JSONObject(cursor.getString(0)))); }
                catch (Exception ignored) { }
            }
        } finally { cursor.close(); }
        return items;
    }

    public synchronized void upsertProject(String name, String target, int score) {
        upsertProject("SEO", name, target, score, "");
    }

    public synchronized void upsertProject(String type, String name, String target, int score, String summary) {
        String normalizedType = type == null ? "SEO" : type.trim().toUpperCase();
        Models.Project item = findProject(normalizedType, target);
        if (item == null) item = new Models.Project();
        item.type = normalizedType;
        if (!item.userNamed || item.name.length() == 0) item.name = name == null ? "" : name;
        item.target = target == null ? "" : target;
        item.summary = summary == null ? "" : summary;
        item.score = score;
        item.updatedAt = System.currentTimeMillis();
        putProject(item);
        trimProjects(100);
    }

    public synchronized void removeProject(String target) {
        database.write().delete("projects", "LOWER(target)=LOWER(?)", new String[]{target == null ? "" : target});
    }

    public synchronized void removeProject(String type, String target) {
        database.write().delete("projects", "project_key=?", new String[]{AppDatabase.projectKey(type, target)});
    }

    public synchronized void updateProjectMetadata(String type, String target, String name, String notes, String tags, boolean pinned, boolean archived) {
        Models.Project item = findProject(type, target);
        if (item == null) return;
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.length() > 0) { item.name = cleanName; item.userNamed = true; }
        item.notes = notes == null ? "" : notes.trim();
        item.tags = tags == null ? "" : tags.trim();
        item.pinned = pinned;
        item.archived = archived;
        item.updatedAt = System.currentTimeMillis();
        putProject(item);
    }

    public synchronized void setProjectPinned(String type, String target, boolean value) {
        Models.Project item = findProject(type, target); if (item == null) return;
        updateProjectMetadata(item.type, item.target, item.name, item.notes, item.tags, value, item.archived);
    }

    public synchronized void setProjectArchived(String type, String target, boolean value) {
        Models.Project item = findProject(type, target); if (item == null) return;
        updateProjectMetadata(item.type, item.target, item.name, item.notes, item.tags, item.pinned, value);
    }

    private Models.Project findProject(String type, String target) {
        Cursor cursor = database.read().query("projects", new String[]{"json"}, "project_key=?",
            new String[]{AppDatabase.projectKey(type, target)}, null, null, null);
        try {
            if (cursor.moveToFirst()) return Models.Project.fromJson(new JSONObject(cursor.getString(0)));
        } catch (Exception ignored) { }
        finally { cursor.close(); }
        return null;
    }

    private void putProject(Models.Project item) {
        ContentValues values = new ContentValues();
        values.put("project_key", AppDatabase.projectKey(item.type, item.target));
        values.put("type", item.type); values.put("target", item.target); values.put("updated_at", item.updatedAt);
        values.put("pinned", item.pinned ? 1 : 0); values.put("archived", item.archived ? 1 : 0);
        values.put("json", item.toJson().toString());
        database.write().insertWithOnConflict("projects", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized List<Models.ScanSession> scans() {
        List<Models.ScanSession> items = new ArrayList<Models.ScanSession>();
        Cursor cursor = database.read().query("scan_sessions", new String[]{"json"}, null, null, null, null, "timestamp DESC");
        try {
            while (cursor.moveToNext()) {
                try { items.add(Models.ScanSession.fromJson(new JSONObject(cursor.getString(0)))); }
                catch (Exception ignored) { }
            }
        } finally { cursor.close(); }
        return items;
    }

    public synchronized List<Models.PortResult> scanResultsPage(long sessionRowId, int limit, int offset) {
        List<Models.PortResult> items = new ArrayList<Models.PortResult>();
        Cursor cursor = database.read().query("scan_results", new String[]{"json"}, "session_id=?",
            new String[]{String.valueOf(sessionRowId)}, null, null, "host, port", offset + "," + limit);
        try { while (cursor.moveToNext()) try { items.add(Models.PortResult.fromJson(new JSONObject(cursor.getString(0)))); } catch (Exception ignored) { } }
        finally { cursor.close(); }
        return items;
    }

    public synchronized void saveScan(Models.ScanSession session) {
        SQLiteDatabase db = database.write();
        db.beginTransaction();
        try {
            database.insertScanJson(db, session.toJson());
            List<DeviceRegistryStore.Observation> observations = new ArrayList<DeviceRegistryStore.Observation>();
            NetworkScope scope = session.scope != null ? session.scope : NetworkScope.fromJson(null, "scan:" + session.timestamp + ":" + NetworkScope.digest(session.targetExpression));
            for (Models.PortResult r : session.openResults) if (r.open) observations.add(DeviceRegistryStore.scan(r, scope, session.timestamp+Math.max(0,session.durationMs)));
            DeviceRegistryStore.recordBatch(db, observations);
            DeviceHistoryStore.scanFinished(db, session, scope, observations);
            trimTable(db, "scan_sessions", 30, "timestamp DESC");
            db.setTransactionSuccessful();
        } catch (Exception ignored) { }
        finally { db.endTransaction(); }
        upsertProject("IP", session.targetExpression, session.targetExpression, -1,
            session.openResults.size() + " открытых сервисов · порты " + session.portExpression);
    }

    public synchronized List<Models.IptvSnapshot> iptvHistory() {
        List<Models.IptvSnapshot> items = new ArrayList<Models.IptvSnapshot>();
        Cursor cursor = database.read().query("iptv_history", new String[]{"json"}, null, null, null, null, "timestamp DESC");
        try { while (cursor.moveToNext()) try { items.add(Models.IptvSnapshot.fromJson(new JSONObject(cursor.getString(0)))); } catch (Exception ignored) { } }
        finally { cursor.close(); }
        return items;
    }

    public synchronized void saveIptvSnapshot(int channelCount, int playableCount, String source) {
        List<Models.IptvSnapshot> items = iptvHistory();
        Models.IptvSnapshot latest = items.isEmpty() ? null : items.get(0);
        long now = System.currentTimeMillis();
        if (latest != null && latest.channelCount == channelCount && latest.playableCount == playableCount && now - latest.timestamp < 30000L) return;
        Models.IptvSnapshot snapshot = new Models.IptvSnapshot();
        snapshot.timestamp = now; snapshot.channelCount = channelCount; snapshot.playableCount = playableCount;
        snapshot.source = source == null ? "" : source;
        ContentValues values = new ContentValues(); values.put("timestamp", now); values.put("json", snapshot.toJson().toString());
        SQLiteDatabase db = database.write(); db.insert("iptv_history", null, values); trimTable(db, "iptv_history", 50, "timestamp DESC");
        upsertProject("IPTV", "IPTV-каталог", "iptv://catalog", -1, channelCount + " каналов · доступно " + playableCount);
    }

    public synchronized void clearIptvHistory() { database.write().delete("iptv_history", null, null); }
    public synchronized void clearScans() { SQLiteDatabase db = database.write(); db.delete("scan_results", null, null); db.delete("scan_sessions", null, null); }
    public synchronized void clearHistory() { database.write().delete("audits", null, null); }


    public synchronized void saveActiveResult(Models.PortResult result) {
        if (result == null) return;
        ContentValues values = new ContentValues();
        values.put("host", result.ip.length() > 0 ? result.ip : result.target);
        values.put("port", result.port);
        values.put("json", result.toJson().toString());
        database.write().insertWithOnConflict("active_scan_results", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public synchronized List<Models.PortResult> activeResults() {
        List<Models.PortResult> items = new ArrayList<Models.PortResult>();
        Cursor cursor = database.read().query("active_scan_results", new String[]{"json"}, null, null, null, null, "host, port");
        try { while (cursor.moveToNext()) try { items.add(Models.PortResult.fromJson(new JSONObject(cursor.getString(0)))); } catch (Exception ignored) { } }
        finally { cursor.close(); }
        return items;
    }

    public synchronized void clearActiveResults() { database.write().delete("active_scan_results", null, null); }

    public synchronized void saveActiveScan(String json) { checkpointPreferences.edit().putString(ACTIVE_SCAN, json == null ? "" : json).commit(); }
    public synchronized String activeScan() { return checkpointPreferences.getString(ACTIVE_SCAN, ""); }
    public synchronized void clearActiveScan() { checkpointPreferences.edit().remove(ACTIVE_SCAN).commit(); }

    private void trimProjects(int max) {
        SQLiteDatabase db = database.write();
        db.execSQL("DELETE FROM projects WHERE project_key NOT IN (SELECT project_key FROM projects ORDER BY pinned DESC, updated_at DESC LIMIT " + max + ")");
    }

    private static void trimTable(SQLiteDatabase db, String table, int max, String order) {
        db.execSQL("DELETE FROM " + table + " WHERE row_id NOT IN (SELECT row_id FROM " + table + " ORDER BY " + order + " LIMIT " + max + ")");
    }
}
