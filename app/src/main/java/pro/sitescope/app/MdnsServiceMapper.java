package pro.sitescope.app;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Maps DNS-SD service types to normalized Protocol Router handlers. */
public final class MdnsServiceMapper {
    private MdnsServiceMapper() { }

    public static List<String> protocols(String raw) {
        String text = raw == null ? "" : raw.toLowerCase(Locale.US);
        Set<String> values = new LinkedHashSet<String>();
        if (contains(text, "_https._tcp", "_https-alt._tcp")) values.add("HTTPS");
        if (contains(text, "_http._tcp", "_http-alt._tcp", "_ipp._tcp", "_ipps._tcp", "_printer._tcp")) values.add(text.contains("_ipps._tcp") ? "HTTPS" : "HTTP");
        if (contains(text, "_ssh._tcp")) values.add("SSH");
        if (contains(text, "_sftp-ssh._tcp")) values.add("SFTP");
        if (contains(text, "_smb._tcp", "_cifs._tcp")) values.add("SMB");
        if (contains(text, "_rtsp._tcp", "_airplay._tcp", "_raop._tcp")) values.add("RTSP");
        if (contains(text, "_ftp._tcp")) values.add("FTP");
        if (contains(text, "_rfb._tcp", "_vnc._tcp")) values.add("VNC");
        if (contains(text, "_mqtt._tcp", "_mqtts._tcp")) values.add("MQTT");
        if (contains(text, "_workstation._tcp")) values.add("UNKNOWN");
        return new ArrayList<String>(values);
    }

    private static boolean contains(String text, String... tokens) {
        for (String token : tokens) if (text.contains(token)) return true;
        return false;
    }
}
