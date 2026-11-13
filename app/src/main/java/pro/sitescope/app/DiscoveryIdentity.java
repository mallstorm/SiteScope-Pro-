package pro.sitescope.app;

import java.io.StringReader;
import java.util.Locale;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

public final class DiscoveryIdentity {
    private DiscoveryIdentity() { }
    public static String ssdp(String usn) { return canonical("ssdp", usn == null ? "" : usn.split("::", 2)[0]); }
    private static String canonical(String protocol, String raw) {
        String uuid = raw.trim().toLowerCase(Locale.US).replaceFirst("^(?:urn:)?uuid:", "");
        if (!uuid.matches("[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}") || uuid.replace("-", "").matches("0+|f+")) return "";
        return protocol + ":uuid:" + uuid;
    }
    private static boolean addressing(String ns) { return "http://schemas.xmlsoap.org/ws/2004/08/addressing".equals(ns) || "http://www.w3.org/2005/08/addressing".equals(ns); }
    private static boolean discovery(String ns) { return "http://schemas.xmlsoap.org/ws/2005/04/discovery".equals(ns) || "http://docs.oasis-open.org/ws-dd/ns/discovery/2009/01".equals(ns); }
    private static boolean soap(String ns) { return "http://www.w3.org/2003/05/soap-envelope".equals(ns) || "http://schemas.xmlsoap.org/soap/envelope/".equals(ns); }
    public static String onvif(String xml) {
        if (xml == null || xml.length() > 65536 || xml.toUpperCase(Locale.US).contains("<!DOCTYPE") || xml.toUpperCase(Locale.US).contains("<!ENTITY")) return "";
        try {
            XmlPullParserFactory f = XmlPullParserFactory.newInstance(); f.setNamespaceAware(true);
            XmlPullParser p = f.newPullParser(); p.setInput(new StringReader(xml));
            int match = -1, endpoint = -1, body = -1, matches = -1; String found = "";
            for (int e = p.getEventType(); e != XmlPullParser.END_DOCUMENT; e = p.next()) {
                if (e == XmlPullParser.START_TAG) {
                    if (p.getDepth() == 1 && (!"Envelope".equals(p.getName()) || !soap(p.getNamespace()))) return "";
                    if (p.getDepth() == 2 && "Body".equals(p.getName()) && soap(p.getNamespace())) body = p.getDepth();
                    else if (body > 0 && p.getDepth() == body + 1 && "ProbeMatches".equals(p.getName()) && discovery(p.getNamespace())) matches = p.getDepth();
                    else if (matches > 0 && p.getDepth() == matches + 1 && p.getName().equals("ProbeMatch") && discovery(p.getNamespace())) match = p.getDepth();
                    else if (match > 0 && p.getDepth() == match + 1 && addressing(p.getNamespace()) && p.getName().equals("EndpointReference")) endpoint = p.getDepth();
                    else if (endpoint > 0 && p.getDepth() == endpoint + 1 && addressing(p.getNamespace()) && p.getName().equals("Address")) {
                        String id = canonical("onvif", p.nextText());
                        if (id.isEmpty() || !found.isEmpty()) return "";
                        found = id;
                    }
                } else if (e == XmlPullParser.END_TAG) {
                    if (p.getDepth() == endpoint) endpoint = -1;
                    if (p.getDepth() == match) match = -1;
                    if (p.getDepth() == matches) matches = -1;
                    if (p.getDepth() == body) body = -1;
                }
            }
            return found;
        } catch (Exception invalid) { return ""; }
    }
}
