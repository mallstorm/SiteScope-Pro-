package pro.sitescope.app;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Small redacted lifecycle log for diagnosing the combined VPN without exposing profile secrets. */
public final class VpnDiagnostics {
    private static final Object LOCK = new Object();
    private static final long MAX = 384L * 1024L;
    private VpnDiagnostics() { }

    public static void log(Context context, String event, String details) {
        synchronized (LOCK) {
            try {
                File dir = new File(context.getFilesDir(), "diagnostics"); if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, "hosts-xray.log");
                if (file.length() > MAX) { File old = new File(dir, "hosts-xray.previous.log"); if (old.exists()) old.delete(); file.renameTo(old); }
                String time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
                String line = time + " | " + clean(event) + " | " + clean(details) + "\n";
                FileOutputStream out = new FileOutputStream(file, true); try { out.write(line.getBytes(StandardCharsets.UTF_8)); } finally { out.close(); }
            } catch (Exception ignored) { }
        }
    }

    public static String read(Context context) {
        try {
            File file = new File(new File(context.getFilesDir(), "diagnostics"), "hosts-xray.log");
            if (!file.isFile()) return "Диагностический журнал пока пуст.";
            byte[] data = new byte[(int)Math.min(file.length(), MAX)]; java.io.FileInputStream in = new java.io.FileInputStream(file);
            try { int offset = 0, n; while (offset < data.length && (n = in.read(data, offset, data.length - offset)) > 0) offset += n; return clean(new String(data, 0, offset, StandardCharsets.UTF_8)); }
            finally { in.close(); }
        } catch (Exception e) { return "Не удалось прочитать журнал: " + e.getMessage(); }
    }

    private static String clean(String value) {
        if (value == null) return "";
        String out = value.replaceAll("(?i)([\"']?(password|passwd|uuid|privateKey|publicKey|shortId|pbk|sid|token|id)[\"']?\\s*[:=]\\s*[\"']?)[^\"',;\\s}]+", "$1<redacted>");
        out = out.replaceAll("(?i)((?:vless|trojan|ss)://)[^@\\s]+@", "$1<redacted>@");
        out = out.replaceAll("(?i)(vmess://)[A-Za-z0-9_\\-+/=]+", "$1<redacted>");
        out = out.replaceAll("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", "<uuid-redacted>");
        return out.length() > 12000 ? out.substring(out.length() - 12000) : out;
    }
}
