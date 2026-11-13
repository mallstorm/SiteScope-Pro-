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
        migrateScanTuning(context);
        return clamp(prefs(context).getInt("scanner_concurrency", 32), ScannerEngine.MIN_CONCURRENCY, ScannerEngine.MAX_CONCURRENCY);
    }

    public static int scannerDelayMs(Context context) {
        migrateScanTuning(context);
        return clamp(prefs(context).getInt("scanner_delay_ms", 0), 0, ScannerEngine.MAX_DELAY_MS);
    }

    private static synchronized void migrateScanTuning(Context context) {
        SharedPreferences settings = prefs(context);
        if (settings.getBoolean("scan_tuning_migrated", false)) return;
        SharedPreferences iptv = context.getSharedPreferences("iptv_discovery", Context.MODE_PRIVATE);
        int workers = iptv.contains("threads") ? iptv.getInt("threads", 8) : settings.getInt("scanner_concurrency", 32);
        settings.edit()
            .putInt("scanner_concurrency", clamp(workers, ScannerEngine.MIN_CONCURRENCY, ScannerEngine.MAX_CONCURRENCY))
            .putInt("scanner_delay_ms", clamp(iptv.getInt("delay_ms", 0), 0, ScannerEngine.MAX_DELAY_MS))
            .putBoolean("scan_tuning_migrated", true).apply();
    }

    public static void saveScanTuning(Context context, int concurrency, int delayMs) {
        migrateScanTuning(context);
        prefs(context).edit()
            .putInt("scanner_concurrency", clamp(concurrency, ScannerEngine.MIN_CONCURRENCY, ScannerEngine.MAX_CONCURRENCY))
            .putInt("scanner_delay_ms", clamp(delayMs, 0, ScannerEngine.MAX_DELAY_MS)).apply();
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
        migrateScanTuning(context);
        prefs(context).edit()
            .putInt("scanner_timeout_ms", clamp(timeoutMs, ScannerEngine.MIN_TIMEOUT_MS, ScannerEngine.MAX_TIMEOUT_MS))
            .putInt("scanner_concurrency", clamp(concurrency, ScannerEngine.MIN_CONCURRENCY, ScannerEngine.MAX_CONCURRENCY))
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
