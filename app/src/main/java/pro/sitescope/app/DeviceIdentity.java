package pro.sitescope.app;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Hardware claims, separate from protocol detection and TLS authentication. */
public final class DeviceIdentity {
    public String manufacturer = "";
    public String family = "";
    public String model = "";
    public String deviceType = "";
    public String confidence = "unknown";
    public boolean conflicting;
    public long observedAt;
    public int rulesVersion = DeviceFingerprintEngine.RULES_VERSION;
    public final List<Finding> findings = new ArrayList<Finding>();

    public static final class Finding {
        public String field = "", value = "", source = "", confidence = "", detail = "";
        Finding(String field, String value, String source, String confidence, String detail) {
            this.field = field; this.value = value; this.source = source;
            this.confidence = confidence; this.detail = detail;
        }
    }

    public boolean isKnown() { return !conflicting && (manufacturer.length() > 0 || family.length() > 0 || model.length() > 0); }

    public String summary() {
        if (conflicting) return "Признаки оборудования противоречат друг другу";
        String label = manufacturer;
        if (family.length() > 0 && !family.equalsIgnoreCase(manufacturer)) label += (label.isEmpty() ? "" : " · ") + family;
        if (model.length() > 0) label += (label.isEmpty() ? "" : " · ") + model;
        return label.isEmpty() ? "Производитель и модель не определены" : label + (model.isEmpty() ? " · модель не определена" : "");
    }

    public String confidenceLabel() {
        if (conflicting) return "Конфликт признаков";
        if ("high".equals(confidence)) return "Высокая";
        if ("medium".equals(confidence)) return "Средняя";
        if ("low".equals(confidence)) return "Низкая";
        return "Недостаточно данных";
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("rulesVersion", rulesVersion);
            o.put("manufacturer", manufacturer); o.put("family", family); o.put("model", model);
            o.put("deviceType", deviceType); o.put("confidence", confidence);
            o.put("conflicting", conflicting); o.put("observedAt", observedAt);
            JSONArray rows = new JSONArray();
            for (Finding f : findings) {
                JSONObject row = new JSONObject();
                row.put("field", f.field); row.put("value", f.value); row.put("source", f.source);
                row.put("confidence", f.confidence); row.put("detail", f.detail); rows.put(row);
            }
            o.put("findings", rows);
        } catch (Exception ignored) { }
        return o;
    }

    public static DeviceIdentity fromJson(JSONObject o) {
        DeviceIdentity d = new DeviceIdentity();
        if (o == null) return d;
        d.manufacturer = text(o.optString("manufacturer")); d.family = text(o.optString("family"));
        d.model = text(o.optString("model")); d.deviceType = text(o.optString("deviceType"));
        d.confidence = o.optString("confidence", "unknown"); d.conflicting = o.optBoolean("conflicting");
        d.observedAt = o.optLong("observedAt");
        d.rulesVersion = o.optInt("rulesVersion", 1);
        JSONArray rows = o.optJSONArray("findings");
        if (rows != null) for (int i = 0; i < Math.min(rows.length(), 24); i++) {
            JSONObject row = rows.optJSONObject(i); if (row == null) continue;
            d.findings.add(new Finding(text(row.optString("field")), text(row.optString("value")),
                text(row.optString("source")), text(row.optString("confidence")), text(row.optString("detail"))));
        }
        if (d.conflicting) { d.manufacturer = ""; d.family = ""; d.model = ""; d.deviceType = ""; d.confidence = "unknown"; }
        return d;
    }

    static String text(String s) {
        if (s == null) return "";
        String clean = s.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").replaceAll("\\s+", " ").trim();
        return clean.substring(0, Math.min(clean.length(), 240));
    }
}
