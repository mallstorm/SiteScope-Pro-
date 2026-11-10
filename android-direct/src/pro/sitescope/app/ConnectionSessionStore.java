package pro.sitescope.app;

import android.content.Context;
import android.content.ContentValues;
import android.database.Cursor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Non-secret journal of connection launches and health checks. */
public final class ConnectionSessionStore {
    public static final String PREFS = "connection_sessions";
    private static final String KEY = "events_json";

    public static final class Event {
        public long timestamp;
        public String type = "OPEN";
        public String protocol = "UNKNOWN";
        public String endpoint = "";
        public String profileId = "";
        public String status = "";
        public long latencyMs;
        public String message = "";

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("timestamp", timestamp);
                object.put("type", type);
                object.put("protocol", protocol);
                object.put("endpoint", endpoint);
                object.put("profileId", profileId);
                object.put("status", status);
                object.put("latencyMs", latencyMs);
                object.put("message", message);
            } catch (Exception ignored) { }
            return object;
        }

        static Event fromJson(JSONObject object) {
            Event event = new Event();
            event.timestamp = object.optLong("timestamp");
            event.type = object.optString("type", "OPEN");
            event.protocol = object.optString("protocol", "UNKNOWN");
            event.endpoint = object.optString("endpoint");
            event.profileId = object.optString("profileId");
            event.status = object.optString("status");
            event.latencyMs = object.optLong("latencyMs");
            event.message = object.optString("message");
            return event;
        }
    }

    private ConnectionSessionStore() { }

    public static synchronized void recordOpen(Context context, ServiceEndpoint endpoint, String status, String message) {
        Event event = base(endpoint);
        event.type = "OPEN";
        event.status = status == null ? "LAUNCHED" : status;
        event.message = message == null ? "" : message;
        append(context, event);
    }

    public static synchronized void recordHealth(Context context, ConnectionProfileStore.Profile profile, EndpointProbe.Result probe) {
        Event event = base(profile.endpoint());
        event.profileId = profile.id;
        event.type = "HEALTH";
        event.status = probe.status;
        event.latencyMs = probe.latencyMs;
        event.message = probe.message;
        append(context, event);
    }

    public static synchronized List<Event> list(Context context) {
        List<Event> result = new ArrayList<Event>();
        Cursor cursor = AppDatabase.get(context).read().query("connection_events", new String[]{"json"}, null, null, null, null,
            "timestamp DESC", "500");
        try {
            while (cursor.moveToNext()) {
                try { result.add(Event.fromJson(new JSONObject(cursor.getString(0)))); }
                catch (Exception ignored) { }
            }
        } finally { cursor.close(); }
        return result;
    }

    public static synchronized void clear(Context context) {
        AppDatabase.get(context).write().delete("connection_events", null, null);
    }

    private static Event base(ServiceEndpoint endpoint) {
        Event event = new Event();
        event.timestamp = System.currentTimeMillis();
        event.protocol = endpoint.protocol == null ? "UNKNOWN" : endpoint.protocol;
        event.endpoint = endpoint.uri != null && endpoint.uri.length() > 0 ? endpoint.uri : endpoint.endpointText();
        event.profileId = endpoint.protocol + "|" + endpoint.host + "|" + endpoint.port + "|" + endpoint.uri;
        return event;
    }

    private static void append(Context context, Event event) {
        ContentValues values = new ContentValues();
        values.put("timestamp", event.timestamp);
        values.put("json", event.toJson().toString());
        AppDatabase db = AppDatabase.get(context);
        db.write().insert("connection_events", null, values);
        db.write().execSQL("DELETE FROM connection_events WHERE row_id NOT IN (SELECT row_id FROM connection_events ORDER BY timestamp DESC LIMIT 500)");
    }
}
