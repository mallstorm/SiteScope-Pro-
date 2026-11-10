package pro.sitescope.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One normalized hosts mapping stored in a named profile. */
public final class HostsEntry {
    public long id;
    public String profileId = "default";
    public int position;
    public boolean enabled = true;
    public String address = "";
    public final List<String> hostnames = new ArrayList<String>();
    public String comment = "";
    public String source = "manual";

    public String primaryHost() { return hostnames.isEmpty() ? "" : hostnames.get(0); }

    public String canonicalLine() {
        StringBuilder out = new StringBuilder();
        if (!enabled) out.append("# ");
        out.append(address);
        for (String host : hostnames) out.append(' ').append(host);
        if (comment != null && comment.trim().length() > 0) out.append(" # ").append(comment.trim());
        return out.toString();
    }

    public JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", id); o.put("profileId", profileId); o.put("position", position); o.put("enabled", enabled);
        o.put("address", address); o.put("comment", comment); o.put("source", source);
        JSONArray names = new JSONArray(); for (String name : hostnames) names.put(name); o.put("hostnames", names);
        return o;
    }

    public static HostsEntry fromJson(JSONObject o) {
        HostsEntry e = new HostsEntry();
        e.id = o.optLong("id"); e.profileId = o.optString("profileId", "default"); e.position = o.optInt("position");
        e.enabled = o.optBoolean("enabled", true); e.address = o.optString("address"); e.comment = o.optString("comment"); e.source = o.optString("source", "manual");
        JSONArray names = o.optJSONArray("hostnames"); if (names != null) for (int i = 0; i < names.length(); i++) {
            String value = names.optString(i).trim().toLowerCase(Locale.US); if (value.length() > 0) e.hostnames.add(value);
        }
        return e;
    }
}
