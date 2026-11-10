package pro.sitescope.app;

/** Honest runtime maturity labels. Presence in the APK is not treated as an integration PASS. */
public final class FeatureReadiness {
    private FeatureReadiness() { }
    public static String protocolStatus(String protocol) {
        String p = protocol == null ? "" : protocol.toUpperCase();
        if ("HTTP".equals(p) || "HTTPS".equals(p) || "RTSP".equals(p) || "UPNP".equals(p) || "MDNS".equals(p)) return "STABLE";
        if ("SSH".equals(p) || "SFTP".equals(p) || "FTP".equals(p) || "FTPS".equals(p) || "WEBDAV".equals(p) || "VNC".equals(p) || "SNMP".equals(p) || "MQTT".equals(p) || "ONVIF".equals(p) || "DLNA".equals(p)) return "BETA";
        return "EXTERNAL";
    }
    public static String label(String protocol) {
        String status = protocolStatus(protocol);
        if ("STABLE".equals(status)) return "Стабильно";
        if ("BETA".equals(status)) return "Бета";
        return "Внешний клиент";
    }
}
