package pro.sitescope.app;

import android.content.Context;
import android.content.SharedPreferences;

public final class SettingsStore {
    public static final String PREFS = "sitescope_settings_v1";
    private SettingsStore() { }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static int scannerTimeoutMs(Context context) {
        return clamp(prefs(context).getInt("scanner_timeout_ms", 650), ScannerEngine.MIN_TIMEOUT_MS, ScannerEngine.MAX_TIMEOUT_MS);
    }

    public static int scannerConcurrency(Context context) {
        return clamp(prefs(context).getInt("scanner_concurrency", 32), 1, ScannerEngine.MAX_CONCURRENCY);
    }

    public static boolean scannerDetectServices(Context context) {
        return prefs(context).getBoolean("scanner_detect_services", true);
    }

    public static boolean compactCards(Context context) {
        return prefs(context).getBoolean("compact_cards", true);
    }

    public static boolean confirmLargeTasks(Context context) {
        return prefs(context).getBoolean("confirm_large_tasks", true);
    }

    public static void saveScanner(Context context, int timeoutMs, int concurrency, boolean detect, boolean confirmLarge) {
        prefs(context).edit()
            .putInt("scanner_timeout_ms", clamp(timeoutMs, ScannerEngine.MIN_TIMEOUT_MS, ScannerEngine.MAX_TIMEOUT_MS))
            .putInt("scanner_concurrency", clamp(concurrency, 1, ScannerEngine.MAX_CONCURRENCY))
            .putBoolean("scanner_detect_services", detect)
            .putBoolean("confirm_large_tasks", confirmLarge)
            .apply();
    }

    public static void setCompactCards(Context context, boolean compact) {
        prefs(context).edit().putBoolean("compact_cards", compact).apply();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
