package pro.sitescope.app;

import java.io.StringReader;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

/** Small, reviewable ruleset. Does not authenticate, follow URLs, or inspect scripts. */
public final class DeviceFingerprintEngine {
    public static final int RULES_VERSION = 1;
    private DeviceFingerprintEngine() { }

    private static final class Rule {
        final String vendor, family, kind;
        final Pattern marker, model;
        Rule(String vendor, String family, String kind, String marker, String model) {
            this.vendor = vendor; this.family = family; this.kind = kind;
            this.marker = Pattern.compile(marker, Pattern.CASE_INSENSITIVE);
            this.model = model.isEmpty() ? null : Pattern.compile(model, Pattern.CASE_INSENSITIVE);
        }
    }

    private static final Rule[] RULES = {
        new Rule("MikroTik", "", "Сетевое оборудование", "\\b(?:MikroTik|RouterOS)\\b", "\\b((?:CCR|CRS|CSS|RB)[0-9][A-Z0-9+-]{2,35})(?![A-Z0-9])"),
        new Rule("Ubiquiti", "", "Сетевое оборудование", "\\b(?:Ubiquiti|UBNT|UniFi|airOS|EdgeOS)\\b", "\\b((?:UAP|USW|USG|UDM|U6|U7|ER)-[A-Z0-9][A-Z0-9-]{0,30})(?![A-Z0-9])"),
        new Rule("ASUS", "", "Сетевое оборудование", "\\b(?:ASUS(?:WRT)?|ASUSTeK)\\b", "\\b((?:RT|GT|DSL|RP|TUF)-[A-Z0-9][A-Z0-9-]{1,30})(?![A-Z0-9])"),
        new Rule("TP-Link", "", "Сетевое оборудование", "\\bTP[- ]?LINK\\b", "\\b(Archer\\s+[A-Z]{1,4}[0-9]{1,5}[A-Z0-9-]{0,8}|(?:TL|ER|EAP)-[A-Z0-9-]{2,24})(?![A-Z0-9])"),
        new Rule("Hikvision", "", "Сетевое видеооборудование", "\\bHikvision(?:-Webs)?\\b", "\\b(DS-[A-Z0-9][A-Z0-9()-]{3,35})(?![A-Z0-9])"),
        new Rule("Dahua", "", "Сетевое видеооборудование", "\\bDahua(?:HttpDvr)?\\b", "\\b((?:DH-)?(?:IPC|NVR|DHI|XVR)-[A-Z0-9][A-Z0-9-]{2,35})(?![A-Z0-9])"),
        new Rule("Synology", "", "Сетевое оборудование", "\\b(?:Synology|DiskStation|RackStation)\\b", "\\b((?:DS|RS)[0-9]{2,5}[A-Z]{0,4}\\+?)(?![A-Z0-9])"),
        new Rule("QNAP", "", "Сетевое оборудование", "\\bQNAP\\b", "\\b((?:TS|TVS|HS)-[A-Z0-9][A-Z0-9-]{1,30})(?![A-Z0-9])")
    };

    private static void add(DeviceIdentity d, String field, String value, String source, String confidence, String detail) {
        value = DeviceIdentity.text(value); if (value.isEmpty()) return;
        if (d.findings.size() < 24) d.findings.add(new DeviceIdentity.Finding(field, value, source, confidence, DeviceIdentity.text(detail)));
        if ("manufacturer".equals(field)) {
            if (!d.manufacturer.isEmpty() && !d.manufacturer.equalsIgnoreCase(value)) d.conflicting = true;
            else d.manufacturer = value;
        } else if ("model".equals(field)) {
            if (!d.model.isEmpty() && !d.model.equalsIgnoreCase(value)) d.conflicting = true;
            else d.model = value;
        } else if ("family".equals(field)) d.family = value;
        else if ("deviceType".equals(field)) d.deviceType = value;
        if (rank(confidence) > rank(d.confidence)) d.confidence = confidence;
    }

    private static int rank(String value) { return "high".equals(value) ? 3 : "medium".equals(value) ? 2 : "low".equals(value) ? 1 : 0; }

    private static DeviceIdentity finish(DeviceIdentity d) {
        if (!d.findings.isEmpty()) d.observedAt = System.currentTimeMillis();
        if (d.conflicting) { d.manufacturer = ""; d.family = ""; d.model = ""; d.deviceType = ""; d.confidence = "unknown"; }
        return d;
    }

    public static DeviceIdentity fromHttp(String server, String realm, String title, String tlsSubject) {
        DeviceIdentity d = new DeviceIdentity();
        identifyHttp(d, DeviceIdentity.text(server), "HTTP Server", false);
        identifyHttp(d, DeviceIdentity.text(realm), "HTTP realm", true);
        identifyHttp(d, DeviceIdentity.text(title), "HTTP title", true);
        // A brand-specific model may be in an unbranded title/realm of the same HTTP response.
        // A certificate alone must not enable this association.
        if (!d.conflicting && !d.manufacturer.isEmpty()) for (Rule rule : RULES) {
            if (!rule.vendor.equals(d.manufacturer) || rule.model == null) continue;
            addModels(d, rule, DeviceIdentity.text(realm), "HTTP realm");
            addModels(d, rule, DeviceIdentity.text(title), "HTTP title");
        }
        String organization = organization(tlsSubject);
        for (Rule rule : RULES) if (isVendorOrganization(rule.vendor, organization))
            add(d, "manufacturer", rule.vendor, "TLS subject O (не проверен)", "low", organization);
        return finish(d);
    }

    public static DeviceIdentity fromDiscoveryServer(String server) {
        DeviceIdentity d = new DeviceIdentity();
        identifyHttp(d, DeviceIdentity.text(server), "SSDP SERVER", false);
        return finish(d);
    }

    private static void identifyHttp(DeviceIdentity d, String text, String source, boolean allowModel) {
        for (Rule r : RULES) {
            Matcher marker = r.marker.matcher(text);
            if (!marker.find()) continue;
            String matched = marker.group();
            String family = r.family;
            if ("MikroTik".equals(r.vendor)) family = word(text, "RouterOS", "SwOS");
            if ("Ubiquiti".equals(r.vendor)) family = word(text, "UniFi", "airOS", "EdgeOS");
            if ("ASUS".equals(r.vendor)) family = word(text, "ASUSWRT");
            if ("TP-Link".equals(r.vendor)) family = word(text, "Archer", "Omada", "Deco");
            if ("Synology".equals(r.vendor)) family = word(text, "DSM", "DiskStation", "RackStation");
            if ("QNAP".equals(r.vendor)) family = word(text, "QTS", "QuTS");
            if (source.equals("HTTP title") && family.isEmpty() && (r.model == null || !r.model.matcher(text).find())
                && word(text, "Login", "Router", "Camera", "NVR", "DVR", "NAS", "WebFig", "WebClient", "Management").isEmpty()) continue;
            add(d, "manufacturer", r.vendor, source, "medium", matched);
            if (!family.isEmpty()) add(d, "family", family, source, "medium", family);
            String kind = r.kind;
            if ("ASUSWRT".equals(family) || !word(text, "Router").isEmpty()) kind = "Маршрутизатор";
            if (("Synology".equals(r.vendor) || "QNAP".equals(r.vendor)) && !family.isEmpty()) kind = "Сетевое хранилище";
            add(d, "deviceType", kind, source, "medium", matched);
            if (allowModel) addModels(d, r, text, source);
        }
    }

    private static void addModels(DeviceIdentity d, Rule rule, String text, String source) {
        if (rule.model == null) return;
        Matcher m = rule.model.matcher(text);
        while (m.find()) {
            String model = DeviceIdentity.text(m.group(1)); boolean seen = false;
            for (DeviceIdentity.Finding f : d.findings) if (f.field.equals("model") && f.value.equalsIgnoreCase(model) && f.source.equals(source)) seen = true;
            if (!seen) add(d, "model", model, source, "medium", model);
        }
    }

    private static String word(String text, String... words) {
        for (String word : words) if (Pattern.compile("\\b" + Pattern.quote(word) + "\\b", Pattern.CASE_INSENSITIVE).matcher(text).find()) return word;
        return "";
    }

    // Parse only O, never CN/issuer: hostnames and CA names do not identify hardware.
    private static String organization(String dn) {
        if (dn == null || dn.length() > 2048) return "";
        StringBuilder part = new StringBuilder(); boolean escaped = false, quoted = false;
        for (int i = 0; i <= dn.length(); i++) {
            char c = i == dn.length() ? ',' : dn.charAt(i);
            if (escaped) { part.append(c); escaped = false; continue; }
            if (c == '\\') { escaped = true; continue; }
            if (c == '"') { quoted = !quoted; continue; }
            if (c == ',' && !quoted) {
                String field = part.toString().trim();
                if (field.regionMatches(true, 0, "O=", 0, 2)) return DeviceIdentity.text(field.substring(2));
                part.setLength(0);
            } else part.append(c);
        }
        return "";
    }

    private static boolean isVendorOrganization(String vendor, String organization) {
        String o = organization.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
        if (o.isEmpty()) return false;
        if (o.equals(vendor.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", ""))) return true;
        if ("MikroTik".equals(vendor)) return o.equals("mikrotikls sia".replace(" ", ""));
        if ("ASUS".equals(vendor)) return o.equals("asustekcomputerinc");
        if ("TP-Link".equals(vendor)) return o.equals("tplinktechnologiescoltd") || o.equals("tplinkcorporationlimited");
        if ("Ubiquiti".equals(vendor)) return o.equals("ubiquitiinc") || o.equals("ubiquitinetworksinc");
        return false;
    }

    public static DeviceIdentity fromOnvifScopes(String hardware) {
        DeviceIdentity d = new DeviceIdentity();
        add(d, "family", "ONVIF", "ONVIF WS-Discovery", "medium", "ProbeMatch");
        add(d, "deviceType", "ONVIF-устройство", "ONVIF WS-Discovery", "medium", "Тип оборудования уточняется по возможностям сервиса");
        add(d, "hardware", hardware, "ONVIF hardware scope", "low", "Аппаратный идентификатор не обязательно является моделью");
        return finish(d);
    }

    public static DeviceIdentity fromOnvifXml(String xml) {
        DeviceIdentity d = new DeviceIdentity();
        if (xml == null || xml.length() > 65536 || Pattern.compile("<!\\s*(?:DOCTYPE|ENTITY)", Pattern.CASE_INSENSITIVE).matcher(xml).find()) return d;
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance(); factory.setNamespaceAware(true);
            XmlPullParser p = factory.newPullParser(); p.setInput(new StringReader(xml));
            int responseDepth = -1, bodyDepth = -1, responses = 0;
            for (int event = p.getEventType(); event != XmlPullParser.END_DOCUMENT; event = p.next()) {
                boolean soap = "http://www.w3.org/2003/05/soap-envelope".equals(p.getNamespace()) || "http://schemas.xmlsoap.org/soap/envelope/".equals(p.getNamespace());
                if (event == XmlPullParser.START_TAG && p.getDepth() == 1 && (!soap || !"Envelope".equals(p.getName()))) return new DeviceIdentity();
                if (event == XmlPullParser.START_TAG && soap && "Fault".equals(p.getName())) return new DeviceIdentity();
                if (event == XmlPullParser.START_TAG && soap && "Body".equals(p.getName()) && p.getDepth() == 2) bodyDepth = 2;
                else if (event == XmlPullParser.START_TAG && bodyDepth > 0 && p.getDepth() == bodyDepth + 1 && "GetDeviceInformationResponse".equals(p.getName())
                    && "http://www.onvif.org/ver10/device/wsdl".equals(p.getNamespace())) {
                    if (++responses > 1) return new DeviceIdentity();
                    responseDepth = p.getDepth();
                }
                else if (event == XmlPullParser.START_TAG && responseDepth > 0 && p.getDepth() == responseDepth + 1) {
                    String field = "Manufacturer".equals(p.getName()) ? "manufacturer" : "Model".equals(p.getName()) ? "model" : "";
                    if (!field.isEmpty() && "http://www.onvif.org/ver10/device/wsdl".equals(p.getNamespace())) {
                        String value = DeviceIdentity.text(p.nextText());
                        boolean placeholder = value.equalsIgnoreCase("unknown") || value.equalsIgnoreCase("N/A") || value.equals("-")
                            || "model".equals(field) && (value.equalsIgnoreCase("IP Camera") || value.equalsIgnoreCase("Network Camera") || value.equalsIgnoreCase("Device"));
                        if (!placeholder)
                            add(d, field, value, "ONVIF GetDeviceInformation", "high", "Поле " + field + " в ответе устройства");
                    }
                } else if (event == XmlPullParser.END_TAG && p.getDepth() == responseDepth) responseDepth = -1;
                if (event == XmlPullParser.END_TAG && p.getDepth() == bodyDepth) bodyDepth = -1;
            }
            if (d.manufacturer.length() > 0 || d.model.length() > 0) {
                add(d, "family", "ONVIF", "ONVIF GetDeviceInformation", "high", "Структурированный ответ устройства");
                add(d, "deviceType", "ONVIF-устройство", "ONVIF GetDeviceInformation", "medium", "ONVIF device service");
            }
        } catch (Exception invalid) { return new DeviceIdentity(); }
        return finish(d);
    }
}
