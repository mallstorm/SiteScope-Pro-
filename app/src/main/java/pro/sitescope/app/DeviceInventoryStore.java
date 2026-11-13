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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Versioned LAN inventory snapshots and user aliases. */
public final class DeviceInventoryStore {
    public static final String PREFS = "device_inventory";
    private static final String SNAPSHOTS = "snapshots_json";
    private static final String ALIASES = "aliases_json";

    public static final class Snapshot {
        public String id = "";
        public long timestamp;
        public String label = "";
        public final List<Models.DiscoveredDevice> devices = new ArrayList<Models.DiscoveredDevice>();

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                object.put("id", id);
                object.put("timestamp", timestamp);
                object.put("label", label);
                JSONArray rows = new JSONArray();
                for (Models.DiscoveredDevice device : devices) rows.put(deviceToJson(device));
                object.put("devices", rows);
            } catch (Exception ignored) { }
            return object;
        }

        static Snapshot fromJson(JSONObject object) {
            Snapshot snapshot = new Snapshot();
            snapshot.id = object.optString("id");
            snapshot.timestamp = object.optLong("timestamp");
            snapshot.label = object.optString("label");
            JSONArray rows = object.optJSONArray("devices");
            if (rows != null) {
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row != null) snapshot.devices.add(deviceFromJson(row));
                }
            }
            return snapshot;
        }
    }

    public static final class Aggregate {
        public String ip = "";
        public String alias = "";
        public String primaryName = "";
        public String manufacturer = "";
        public String model = "";
        public String hostname = "";
        public final List<Models.DiscoveredDevice> services = new ArrayList<Models.DiscoveredDevice>();
        public final Set<String> protocols = new LinkedHashSet<String>();

        public String title() {
            if (alias.length() > 0) return alias;
            if (primaryName.length() > 0) return primaryName;
            if (hostname.length() > 0) return hostname;
            return ip;
        }
    }

    private DeviceInventoryStore() { }

    public static synchronized Snapshot saveSnapshot(Context context, List<Models.DiscoveredDevice> devices, String label) {
        Snapshot snapshot = new Snapshot();
        snapshot.timestamp = System.currentTimeMillis();
        snapshot.id = String.valueOf(snapshot.timestamp);
        snapshot.label = label == null || label.trim().length() == 0 ? "LAN Discovery" : label.trim();
        snapshot.devices.addAll(deduplicate(devices));
        ContentValues values = new ContentValues();
        values.put("id", snapshot.id); values.put("timestamp", snapshot.timestamp); values.put("json", snapshot.toJson().toString());
        AppDatabase db = AppDatabase.get(context);
        db.write().insertWithOnConflict("inventory_snapshots", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        db.write().execSQL("DELETE FROM inventory_snapshots WHERE id NOT IN (SELECT id FROM inventory_snapshots ORDER BY timestamp DESC LIMIT 20)");
        return snapshot;
    }

    public static synchronized List<Snapshot> list(Context context) {
        List<Snapshot> snapshots = new ArrayList<Snapshot>();
        Cursor cursor = AppDatabase.get(context).read().query("inventory_snapshots", new String[]{"json"}, null, null, null, null,
            "timestamp DESC", "20");
        try {
            while (cursor.moveToNext()) {
                try { snapshots.add(Snapshot.fromJson(new JSONObject(cursor.getString(0)))); }
                catch (Exception ignored) { }
            }
        } finally { cursor.close(); }
        return snapshots;
    }

    public static synchronized void clear(Context context) {
        AppDatabase.get(context).write().delete("inventory_snapshots", null, null);
    }

    public static synchronized String alias(Context context, String ip) {
        Cursor cursor = AppDatabase.get(context).read().query("inventory_aliases", new String[]{"alias"}, "ip=?", new String[]{ip}, null, null, null);
        try { return cursor.moveToFirst() ? cursor.getString(0) : ""; }
        finally { cursor.close(); }
    }

    public static synchronized void setAlias(Context context, String ip, String alias) {
        String clean = alias == null ? "" : alias.trim();
        AppDatabase db = AppDatabase.get(context);
        if (clean.length() == 0) db.write().delete("inventory_aliases", "ip=?", new String[]{ip});
        else {
            ContentValues values = new ContentValues(); values.put("ip", ip); values.put("alias", clean);
            db.write().insertWithOnConflict("inventory_aliases", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    public static List<Aggregate> aggregate(Context context, Snapshot snapshot) {
        LinkedHashMap<String, Aggregate> map = new LinkedHashMap<String, Aggregate>();
        if (snapshot == null) return new ArrayList<Aggregate>();
        for (Models.DiscoveredDevice device : snapshot.devices) {
            String key = device.ip == null || device.ip.length() == 0 ? device.key() : device.ip;
            Aggregate item = map.get(key);
            if (item == null) {
                item = new Aggregate();
                item.ip = device.ip;
                item.alias = alias(context, device.ip);
                map.put(key, item);
            }
            item.services.add(device);
            if (device.protocol.length() > 0) item.protocols.add(device.protocol);
            if (item.primaryName.length() == 0 && device.name.length() > 0 && !device.name.startsWith("uuid:")) item.primaryName = device.name;
            if (item.hostname.length() == 0 && device.hostname.length() > 0) item.hostname = device.hostname;
            if (item.manufacturer.length() == 0 && device.manufacturer.length() > 0) item.manufacturer = device.manufacturer;
            if (item.model.length() == 0 && device.model.length() > 0) item.model = device.model;
        }
        return new ArrayList<Aggregate>(map.values());
    }

    public static Set<String> ipSet(Snapshot snapshot) {
        Set<String> set = new LinkedHashSet<String>();
        if (snapshot != null) for (Models.DiscoveredDevice device : snapshot.devices) if (device.ip.length() > 0) set.add(device.ip);
        return set;
    }

    private static List<Models.DiscoveredDevice> deduplicate(List<Models.DiscoveredDevice> devices) {
        LinkedHashMap<String, Models.DiscoveredDevice> map = new LinkedHashMap<String, Models.DiscoveredDevice>();
        if (devices != null) for (Models.DiscoveredDevice device : devices) map.put(device.key(), device);
        return new ArrayList<Models.DiscoveredDevice>(map.values());
    }

    private static JSONObject deviceToJson(Models.DiscoveredDevice d) {
        JSONObject o = new JSONObject();
        try {
            o.put("ip", d.ip); o.put("port", d.port); o.put("protocol", d.protocol); o.put("deviceType", d.deviceType);
            o.put("name", d.name); o.put("hostname", d.hostname); o.put("manufacturer", d.manufacturer); o.put("model", d.model);
            o.put("identity", d.identity.toJson());
            o.put("location", d.location); o.put("services", d.services); o.put("evidence", d.evidence); o.put("confidence", d.confidence); o.put("latencyMs", d.latencyMs);
        } catch (Exception ignored) { }
        return o;
    }

    private static Models.DiscoveredDevice deviceFromJson(JSONObject o) {
        Models.DiscoveredDevice d = new Models.DiscoveredDevice();
        d.ip = o.optString("ip"); d.port = o.optInt("port"); d.protocol = o.optString("protocol"); d.deviceType = o.optString("deviceType", "Сетевое устройство");
        d.name = o.optString("name"); d.hostname = o.optString("hostname"); d.manufacturer = o.optString("manufacturer"); d.model = o.optString("model");
        d.identity = DeviceIdentity.fromJson(o.optJSONObject("identity"));
        if (!o.has("identity") && "ONVIF WS-Discovery".equals(d.protocol)) {
            d.identity = DeviceFingerprintEngine.fromOnvifScopes(d.model);
            d.model = ""; d.identity.observedAt = 0;
        } else if (!o.has("identity") && "SSDP/UPnP".equals(d.protocol)) {
            d.identity = DeviceFingerprintEngine.fromDiscoveryServer(d.manufacturer);
            d.manufacturer = d.identity.manufacturer; d.identity.observedAt = 0;
        }
        d.location = o.optString("location"); d.services = o.optString("services"); d.evidence = o.optString("evidence"); d.confidence = o.optString("confidence", "medium"); d.latencyMs = o.optLong("latencyMs");
        return d;
    }
}
