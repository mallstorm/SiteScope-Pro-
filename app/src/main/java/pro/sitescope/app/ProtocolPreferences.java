package pro.sitescope.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class ProtocolPreferences {
    public static final String ASK = "ask";
    public static final String BUILTIN = "builtin";
    public static final String EXTERNAL = "external";
    public static final String PANEL = "panel";
    private static final String PREFS = "protocol_router_preferences";

    private ProtocolPreferences() { }

    public static String handler(Context context, String protocol) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String defaultValue = defaultHandler(protocol);
        return prefs.getString("handler_" + protocol, defaultValue);
    }

    public static void setHandler(Context context, String protocol, String handler) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("handler_" + protocol, handler).apply();
    }

    public static String defaultHandler(String protocol) {
        if ("HTTP".equals(protocol) || "HTTPS".equals(protocol) || "RTSP".equals(protocol)
            || "SSH".equals(protocol) || "SFTP".equals(protocol)
            || "FTP".equals(protocol) || "FTPS".equals(protocol) || "WEBDAV".equals(protocol)
            || "VNC".equals(protocol) || "SNMP".equals(protocol) || "MQTT".equals(protocol)
            || "ONVIF".equals(protocol) || "RDP".equals(protocol) || "TELNET".equals(protocol) || "DLNA".equals(protocol)) return BUILTIN;
        if ("UPNP".equals(protocol) || "MDNS".equals(protocol) || "UNKNOWN".equals(protocol)) return PANEL;
        return ASK;
    }
}
