package pro.sitescope.app;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public final class ConnectionProfileStore {
    public static final String PREFS = "connection_profiles";
    private static final String KEY = "profiles_json";

    public static final class Profile {
        public String id = "";
        public String name = "";
        public String protocol = "UNKNOWN";
        public String host = "";
        public int port;
        public String uri = "";
        public String resourceType = "";
        public String notes = "";
        public String tags = "";
        public boolean pinned;
        public long savedAt;
        public long lastOpenedAt;
        public int launchCount;
        public long lastCheckedAt;
        public String lastStatus = "";
        public long lastLatencyMs;
        public String lastMessage = "";

        public ServiceEndpoint endpoint() {
            ServiceEndpoint endpoint = new ServiceEndpoint();
            endpoint.host = host;
            endpoint.port = port;
            endpoint.protocol = protocol;
            endpoint.service = protocol;
            endpoint.displayName = name;
            endpoint.uri = uri;
            endpoint.resourceType = resourceType;
            return endpoint;
        }

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("id", id);
                object.put("name", name);
                object.put("protocol", protocol);
                object.put("host", host);
                object.put("port", port);
                object.put("uri", uri);
                object.put("resourceType", resourceType);
                object.put("notes", notes);
                object.put("tags", tags);
                object.put("pinned", pinned);
                object.put("savedAt", savedAt);
                object.put("lastOpenedAt", lastOpenedAt);
                object.put("launchCount", launchCount);
                object.put("lastCheckedAt", lastCheckedAt);
                object.put("lastStatus", lastStatus);
                object.put("lastLatencyMs", lastLatencyMs);
                object.put("lastMessage", lastMessage);
            } catch (Exception ignored) { }
            return object;
        }

        static Profile fromJson(JSONObject object) {
            Profile profile = new Profile();
            profile.id = object.optString("id");
            profile.name = object.optString("name");
            profile.protocol = object.optString("protocol", "UNKNOWN");
            profile.host = object.optString("host");
            profile.port = object.optInt("port");
            profile.uri = object.optString("uri");
            profile.resourceType = object.optString("resourceType");
            profile.notes = object.optString("notes");
            profile.tags = object.optString("tags");
            profile.pinned = object.optBoolean("pinned");
            profile.savedAt = object.optLong("savedAt");
            profile.lastOpenedAt = object.optLong("lastOpenedAt");
            profile.launchCount = object.optInt("launchCount");
            profile.lastCheckedAt = object.optLong("lastCheckedAt");
            profile.lastStatus = object.optString("lastStatus");
            profile.lastLatencyMs = object.optLong("lastLatencyMs");
            profile.lastMessage = object.optString("lastMessage");
            return profile;
        }
    }

    private ConnectionProfileStore() { }

    public static synchronized void save(Context context, ServiceEndpoint endpoint) {
        List<Profile> profiles = list(context);
        String id = idFor(endpoint);
        Profile target = findIn(profiles, id);
        if (target == null) {
            target = new Profile();
            profiles.add(target);
        }
        target.id = id;
        target.name = endpoint.displayName.length() > 0 ? endpoint.displayName : endpoint.endpointText();
        target.protocol = endpoint.protocol;
        target.host = endpoint.host;
        target.port = endpoint.port;
        target.uri = endpoint.uri;
        target.resourceType = endpoint.resourceType;
        target.savedAt = System.currentTimeMillis();
        write(context, profiles);
    }

    public static synchronized void updateMetadata(Context context, String id, String name, String notes, String tags, boolean pinned) {
        List<Profile> profiles = list(context);
        Profile target = findIn(profiles, id);
        if (target == null) return;
        String cleanName = name == null ? "" : name.trim();
        if (cleanName.length() > 0) target.name = cleanName;
        target.notes = notes == null ? "" : notes.trim();
        target.tags = tags == null ? "" : tags.trim();
        target.pinned = pinned;
        write(context, profiles);
    }

    public static synchronized void recordOpened(Context context, ServiceEndpoint endpoint) {
        List<Profile> profiles = list(context);
        Profile target = findIn(profiles, idFor(endpoint));
        if (target == null) return;
        target.lastOpenedAt = System.currentTimeMillis();
        target.launchCount++;
        write(context, profiles);
    }

    public static synchronized void updateHealth(Context context, String id, EndpointProbe.Result result) {
        List<Profile> profiles = list(context);
        Profile target = findIn(profiles, id);
        if (target == null) return;
        target.lastCheckedAt = System.currentTimeMillis();
        target.lastStatus = result.status;
        target.lastLatencyMs = result.latencyMs;
        target.lastMessage = result.message;
        write(context, profiles);
    }

    public static synchronized Profile find(Context context, String id) {
        return findIn(list(context), id);
    }

    public static synchronized void remove(Context context, String id) {
        List<Profile> profiles = list(context);
        for (int i = profiles.size() - 1; i >= 0; i--) if (id.equals(profiles.get(i).id)) profiles.remove(i);
        write(context, profiles);
    }

    public static synchronized List<Profile> list(Context context) {
        List<Profile> profiles = new ArrayList<Profile>();
        Cursor cursor = AppDatabase.get(context).read().query("connection_profiles", new String[]{"json"}, null, null, null, null,
            "pinned DESC, sort_at DESC");
        try {
            while (cursor.moveToNext()) {
                try { profiles.add(Profile.fromJson(new JSONObject(cursor.getString(0)))); }
                catch (Exception ignored) { }
            }
        } finally { cursor.close(); }
        return profiles;
    }

    public static String idFor(ServiceEndpoint endpoint) {
        return endpoint.protocol + "|" + endpoint.host + "|" + endpoint.port + "|" + endpoint.uri;
    }

    private static Profile findIn(List<Profile> profiles, String id) {
        for (Profile profile : profiles) if (id.equals(profile.id)) return profile;
        return null;
    }

    private static void write(Context context, List<Profile> profiles) {
        SQLiteDatabase db = AppDatabase.get(context).write();
        db.beginTransaction();
        try {
            db.delete("connection_profiles", null, null);
            for (Profile profile : profiles) {
                ContentValues values = new ContentValues();
                values.put("id", profile.id);
                values.put("pinned", profile.pinned ? 1 : 0);
                values.put("sort_at", Math.max(profile.savedAt, profile.lastOpenedAt));
                values.put("json", profile.toJson().toString());
                db.insertWithOnConflict("connection_profiles", null, values, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
}
