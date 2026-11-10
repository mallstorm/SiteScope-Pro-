package pro.sitescope.app;

import android.content.Context;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;

import go.Seq;
import libv2ray.Libv2ray;

/** Copies Xray data assets to a private directory and initializes the gomobile runtime once. */
public final class XrayAssets {
    private static final String[] FILES = {"geoip.dat", "geosite.dat", "geoip-only-cn-private.dat"};
    private static boolean initialized;
    private static final String BUNDLED_VERSION = "Lib v38, Xray-core v26.6.27";

    private XrayAssets() { }

    public static synchronized File prepare(Context context) throws Exception {
        File root = new File(context.getFilesDir(), "xray-assets");
        if (!root.exists() && !root.mkdirs()) throw new IllegalStateException("Не удалось создать каталог Xray assets");
        for (String name : FILES) copyAsset(context, name, new File(root, name));
        if (!initialized) {
            Seq.setContext(context.getApplicationContext());
            Libv2ray.initCoreEnv(root.getAbsolutePath(), deviceKey(context));
            initialized = true;
        }
        return root;
    }

    /**
     * Returns immutable build metadata without entering the gomobile runtime.
     * checkVersionX() must not be called while a core or legacy delay test is
     * active because some libv2ray builds serialize those native calls.
     */
    public static String version(Context context) { return BUNDLED_VERSION; }

    private static void copyAsset(Context context, String name, File target) throws Exception {
        InputStream probe = context.getAssets().open(name);
        int bundledSize; try { bundledSize = probe.available(); } finally { probe.close(); }
        if (target.isFile() && target.length() > 1024 && (bundledSize <= 0 || target.length() == bundledSize)) return;
        File temp = new File(target.getParentFile(), target.getName() + ".tmp");
        InputStream in = context.getAssets().open(name); FileOutputStream out = new FileOutputStream(temp);
        try { byte[] buffer = new byte[32768]; int read; while ((read = in.read(buffer)) >= 0) out.write(buffer, 0, read); out.flush(); out.getFD().sync(); }
        finally { try { in.close(); } finally { out.close(); } }
        if (target.exists() && !target.delete()) throw new IllegalStateException("Не удалось обновить " + name);
        if (!temp.renameTo(target)) throw new IllegalStateException("Не удалось активировать " + name);
    }

    private static String deviceKey(Context context) throws Exception {
        String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (id == null || id.length() == 0) id = context.getPackageName();
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] hash = digest.digest((context.getPackageName() + ":" + id).getBytes("UTF-8"));
        StringBuilder out = new StringBuilder(); for (int i = 0; i < 16; i++) out.append(String.format("%02x", hash[i] & 0xff)); return out.toString();
    }

    private static String safeMessage(Throwable e) { String value = e.getMessage(); return value == null || value.length() == 0 ? e.getClass().getSimpleName() : value; }
}
