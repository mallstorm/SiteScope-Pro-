package pro.sitescope.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Foreground coordinator for authorized connect-only inventory scans.
 * It stores a durable checkpoint and resumes with a small overlap after process death,
 * preventing missed probes while deduplicating open IP:port observations.
 */
public final class ScanService extends Service {
    public static final String ACTION_START = "pro.sitescope.app.scan.START";
    public static final String ACTION_PAUSE = "pro.sitescope.app.scan.PAUSE";
    public static final String ACTION_RESUME = "pro.sitescope.app.scan.RESUME";
    public static final String ACTION_STOP = "pro.sitescope.app.scan.STOP";
    public static final String EXTRA_TARGETS = "targets";
    public static final String EXTRA_PORTS = "ports";
    public static final String EXTRA_TIMEOUT = "timeout";
    public static final String EXTRA_CONCURRENCY = "concurrency";
    public static final String EXTRA_DELAY_MS = "delayMs";
    public static final String EXTRA_DETECT = "detect";
    public static final String EXTRA_EXTENDED_PRIVATE = "extendedPrivate";

    private static final int NOTIFICATION_ID = 7005;
    private static final String CHANNEL_ID = "sitescope_scanner";
    private static final CopyOnWriteArrayList<Observer> OBSERVERS = new CopyOnWriteArrayList<Observer>();
    private static volatile Snapshot LAST = new Snapshot();

    public interface Observer { void onScanState(Snapshot state); }

    public static void register(Observer observer) {
        if (observer == null) return;
        OBSERVERS.addIfAbsent(observer);
        observer.onScanState(copyOf(LAST));
    }

    public static void unregister(Observer observer) { if (observer != null) OBSERVERS.remove(observer); }
    public static Snapshot snapshot() { return copyOf(LAST); }

    public static final class Snapshot {
        public NetworkScope scope;
        public boolean active;
        public boolean paused;
        public boolean completed;
        public boolean cancelled;
        public boolean recovered;
        public String targetExpression = "";
        public String portExpression = "";
        public String currentTarget = "";
        public String error = "";
        public int timeoutMs = 650;
        public int concurrency = 32;
        public int delayMs;
        public boolean detectServices = true;
        public boolean extendedPrivateMode;
        public int totalProbes;
        public int completedProbes;
        public long startedAt;
        public long finishedAt;
        public final List<Models.PortResult> results = new ArrayList<Models.PortResult>();

        public JSONObject toJson() {
            JSONObject object = new JSONObject();
            try {
                if (scope != null) object.put("scope", scope.toJson());
                object.put("active", active);
                object.put("paused", paused);
                object.put("completed", completed);
                object.put("cancelled", cancelled);
                object.put("recovered", recovered);
                object.put("targetExpression", targetExpression);
                object.put("portExpression", portExpression);
                object.put("currentTarget", currentTarget);
                object.put("error", error);
                object.put("timeoutMs", timeoutMs);
                object.put("concurrency", concurrency);
                object.put("delayMs", delayMs);
                object.put("detectServices", detectServices);
                object.put("extendedPrivateMode", extendedPrivateMode);
                object.put("totalProbes", totalProbes);
                object.put("completedProbes", completedProbes);
                object.put("startedAt", startedAt);
                object.put("finishedAt", finishedAt);
                JSONArray rows = new JSONArray();
                for (Models.PortResult result : results) rows.put(result.toJson());
                object.put("results", rows);
            } catch (Exception ignored) { }
            return object;
        }

        public JSONObject toCheckpointJson() {
            JSONObject object = toJson();
            object.remove("results");
            return object;
        }

        public static Snapshot fromJson(String json) {
            Snapshot state = new Snapshot();
            if (json == null || json.length() == 0) return state;
            try {
                JSONObject object = new JSONObject(json);
                if (object.has("scope")) state.scope = NetworkScope.fromJson(object.optJSONObject("scope"), "scan");
                state.active = object.optBoolean("active");
                state.paused = object.optBoolean("paused");
                state.completed = object.optBoolean("completed");
                state.cancelled = object.optBoolean("cancelled");
                state.recovered = object.optBoolean("recovered");
                state.targetExpression = object.optString("targetExpression");
                state.portExpression = object.optString("portExpression");
                state.currentTarget = object.optString("currentTarget");
                state.error = object.optString("error");
                state.timeoutMs = object.optInt("timeoutMs", 650);
                state.concurrency = object.optInt("concurrency", 32);
                state.delayMs = Math.max(0, Math.min(ScannerEngine.MAX_DELAY_MS, object.optInt("delayMs", 0)));
                state.detectServices = object.optBoolean("detectServices", true);
                state.extendedPrivateMode = object.optBoolean("extendedPrivateMode", false);
                state.totalProbes = object.optInt("totalProbes");
                state.completedProbes = object.optInt("completedProbes");
                state.startedAt = object.optLong("startedAt");
                state.finishedAt = object.optLong("finishedAt");
                JSONArray rows = object.optJSONArray("results");
                if (rows != null) for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i);
                    if (row != null) state.results.add(Models.PortResult.fromJson(row));
                }
            } catch (Exception ignored) { }
            return state;
        }
    }

    private final Object lock = new Object();
    private long taskGeneration;
    private DataStore store;
    private ScannerEngine.Task task;
    private Snapshot state;
    private long lastPersistAt;

    @Override public void onCreate() {
        super.onCreate();
        store = new DataStore(this);
        createChannel();
        state = Snapshot.fromJson(store.activeScan());
        mergeResults(state.results, store.activeResults());
        LAST = copyOf(state);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "" : intent.getAction();
        if (ACTION_STOP.equals(action)) { stopCurrent(true); return START_NOT_STICKY; }
        if (ACTION_PAUSE.equals(action)) { pauseCurrent(); return START_STICKY; }
        if (ACTION_RESUME.equals(action)) { resumeCurrent(); return START_STICKY; }
        if (ACTION_START.equals(action)) {
            String targets = intent.getStringExtra(EXTRA_TARGETS);
            String ports = intent.getStringExtra(EXTRA_PORTS);
            int timeout = intent.getIntExtra(EXTRA_TIMEOUT, 650);
            int concurrency = intent.getIntExtra(EXTRA_CONCURRENCY, 32);
            int delayMs = intent.getIntExtra(EXTRA_DELAY_MS, SettingsStore.scannerDelayMs(this));
            boolean detect = intent.getBooleanExtra(EXTRA_DETECT, true);
            boolean extendedPrivate = intent.getBooleanExtra(EXTRA_EXTENDED_PRIVATE, false);
            begin(targets, ports, timeout, concurrency, delayMs, detect, extendedPrivate, 0, false, new ArrayList<Models.PortResult>());
            return START_STICKY;
        }
        // START_STICKY recovery path. Restart from a small overlap so no in-flight probe is lost.
        if (state != null && state.active && !state.completed && state.targetExpression.length() > 0) {
            int overlap = Math.max(12, state.concurrency * 3);
            int resumeIndex = Math.max(0, state.completedProbes - overlap);
            begin(state.targetExpression, state.portExpression, state.timeoutMs, state.concurrency, state.delayMs,
                state.detectServices, state.extendedPrivateMode, resumeIndex, true, new ArrayList<Models.PortResult>(state.results));
            return START_STICKY;
        }
        stopSelf();
        return START_NOT_STICKY;
    }

    private void begin(final String targetsRaw, final String portsRaw, int timeout, int concurrency, int delayMs,
                       boolean detect, boolean extendedPrivate, int startIndex, boolean recovered, List<Models.PortResult> prior) {
        synchronized (lock) {
            stopTaskOnly();
            final long runGeneration = taskGeneration;
            if (!recovered) store.clearActiveResults();
            try {
                final NetworkScope scope = NetworkScope.current(this);
                if (recovered && (state.scope == null || !state.scope.id.equals(scope.id))) {
                    state.finishedAt = System.currentTimeMillis(); state.cancelled = true;
                    saveCompletedSession();
                    throw new IllegalStateException("Сеть изменилась. Прежние результаты сохранены; запустите новое сканирование.");
                }
                final int hostLimit = extendedPrivate ? ScannerEngine.EXTENDED_MAX_HOSTS : ScannerEngine.STANDARD_MAX_HOSTS;
                final int portLimit = extendedPrivate ? ScannerEngine.EXTENDED_MAX_PORTS : ScannerEngine.STANDARD_MAX_PORTS;
                final List<String> hosts = ScannerEngine.parseTargets(targetsRaw, hostLimit);
                final List<Integer> ports = ScannerEngine.parsePorts(portsRaw, portLimit);
                final ScannerEngine.Config config = new ScannerEngine.Config();
                config.boundNetwork = scope.network;
                config.timeoutMs = timeout;
                config.concurrency = concurrency;
                config.delayMs = delayMs;
                config.detectServices = detect;
                config.extendedPrivateMode = extendedPrivate;
                config.startIndex = startIndex;
                config.normalize();

                state = new Snapshot();
                state.scope = scope;
                state.active = true;
                state.paused = false;
                state.completed = false;
                state.recovered = recovered;
                state.targetExpression = targetsRaw == null ? "" : targetsRaw;
                state.portExpression = portsRaw == null ? "" : portsRaw;
                state.timeoutMs = config.timeoutMs;
                state.concurrency = config.concurrency;
                state.delayMs = config.delayMs;
                state.detectServices = config.detectServices;
                state.extendedPrivateMode = config.extendedPrivateMode;
                state.totalProbes = hosts.size() * ports.size();
                state.completedProbes = startIndex;
                state.startedAt = recovered && LAST.startedAt > 0 ? LAST.startedAt : System.currentTimeMillis();
                mergeResults(state.results, prior);
                publish(true);
                startForeground(NOTIFICATION_ID, notification("Подготовка задания", state.completedProbes, state.totalProbes, false));

                task = ScannerEngine.start(hosts, ports, config, new ScannerEngine.Listener() {
                    @Override public void onStarted(int hostCount, int portCount, int total) {
                        synchronized (lock) {
                            if (runGeneration != taskGeneration) return;
                            state.totalProbes = total;
                            publish(true);
                        }
                    }

                    @Override public void onProgress(int completed, int total, int openCount, String currentTarget) {
                        synchronized (lock) {
                            if (runGeneration != taskGeneration) return;
                            state.completedProbes = completed;
                            state.totalProbes = total;
                            state.currentTarget = currentTarget;
                            publish(false);
                        }
                    }

                    @Override public void onOpen(Models.PortResult result) {
                        synchronized (lock) {
                            if (runGeneration != taskGeneration) return;
                            result.scope = state.scope;
                            upsertResult(state.results, result);
                            store.saveActiveResult(result);
                            publish(false);
                        }
                    }

                    @Override public void onComplete(List<Models.PortResult> openResults, boolean cancelled, long durationMs, String error) {
                        synchronized (lock) {
                            if (runGeneration != taskGeneration) return;
                            mergeResults(state.results, openResults);
                            state.active = false;
                            state.paused = false;
                            state.completed = true;
                            state.cancelled = cancelled;
                            state.error = error == null ? "" : error;
                            state.finishedAt = System.currentTimeMillis();
                            if (!cancelled && state.error.length() == 0) state.completedProbes = state.totalProbes;
                            saveCompletedSession();
                            store.clearActiveResults();
                            publish(true);
                            stopForeground(false);
                            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                            if (manager != null) manager.notify(NOTIFICATION_ID, notification(
                                state.error.length() > 0 ? "Ошибка сканирования" : (cancelled ? "Сканирование остановлено" : "Сканирование завершено"),
                                state.completedProbes, state.totalProbes, true));
                            store.clearActiveScan();
                            stopSelf();
                        }
                    }
                });
            } catch (Exception error) {
                state = new Snapshot();
                state.completed = true;
                state.error = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                publish(true);
                stopForeground(true);
                store.clearActiveScan();
                stopSelf();
            }
        }
    }

    private void pauseCurrent() {
        synchronized (lock) {
            if (task == null || state == null || !state.active) return;
            task.pause();
            state.paused = true;
            publish(true);
        }
    }

    private void resumeCurrent() {
        synchronized (lock) {
            if (task == null || state == null || !state.active) return;
            task.resume();
            state.paused = false;
            publish(true);
        }
    }

    private void stopCurrent(boolean user) {
        synchronized (lock) {
            if (state != null) state.cancelled = user;
            if (task != null) task.cancel();
            else {
                if (state != null) {
                    state.active = false;
                    state.completed = true;
                    state.finishedAt = System.currentTimeMillis();
                    publish(true);
                }
                store.clearActiveScan();
                store.clearActiveResults();
                stopForeground(true);
                stopSelf();
            }
        }
    }

    private void stopTaskOnly() {
        synchronized (lock) {
            taskGeneration++;
            if (task != null) task.cancel();
            task = null;
        }
    }

    private void publish(boolean forcePersist) {
        LAST = copyOf(state);
        long now = System.currentTimeMillis();
        if (state.active && (forcePersist || now - lastPersistAt > 800L)) {
            store.saveActiveScan(state.toCheckpointJson().toString());
            lastPersistAt = now;
        }
        for (Observer observer : OBSERVERS) {
            try { observer.onScanState(copyOf(state)); } catch (Exception ignored) { }
        }
        if (state.active) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.notify(NOTIFICATION_ID, notification(
                state.paused ? "Сканирование приостановлено" : "Инвентаризация сети",
                state.completedProbes, state.totalProbes, false));
        }
    }

    private void saveCompletedSession() {
        Models.ScanSession session = new Models.ScanSession();
        session.scope = state.scope;
        session.targetExpression = state.targetExpression;
        session.portExpression = state.portExpression;
        session.timestamp = state.startedAt > 0 ? state.startedAt : System.currentTimeMillis();
        session.durationMs = Math.max(0L, state.finishedAt - session.timestamp);
        session.totalProbes = state.totalProbes;
        session.completedProbes = state.completedProbes;
        session.cancelled = state.cancelled;
        session.recovered = state.recovered;
        session.error = state.error;
        session.openResults.addAll(state.results);
        store.saveScan(session);
    }

    private Notification notification(String title, int done, int total, boolean finished) {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 11, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CHANNEL_ID) : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText((total > 0 ? done + " / " + total : "Подготовка") + " · найдено " + (state == null ? 0 : state.results.size()))
            .setContentIntent(contentIntent)
            .setOngoing(!finished)
            .setOnlyAlertOnce(true)
            .setProgress(Math.max(total, 1), Math.min(done, Math.max(total, 1)), total <= 0);
        if (!finished) {
            String action = state != null && state.paused ? ACTION_RESUME : ACTION_PAUSE;
            String label = state != null && state.paused ? "Продолжить" : "Пауза";
            PendingIntent pause = PendingIntent.getService(this, 12, new Intent(this, ScanService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            PendingIntent stop = PendingIntent.getService(this, 13, new Intent(this, ScanService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            builder.addAction(android.R.drawable.ic_media_pause, label, pause);
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", stop);
        }
        return builder.build();
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Инженерный сканер", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Прогресс авторизованной инвентаризации IP и портов");
        manager.createNotificationChannel(channel);
    }

    private static void mergeResults(List<Models.PortResult> destination, List<Models.PortResult> source) {
        if (source == null) return;
        for (Models.PortResult result : source) upsertResult(destination, result);
    }

    private static void upsertResult(List<Models.PortResult> destination, Models.PortResult result) {
        if (result == null) return;
        String key = result.ip + ":" + result.port;
        for (int i = 0; i < destination.size(); i++) {
            Models.PortResult existing = destination.get(i);
            if ((existing.ip + ":" + existing.port).equals(key)) { destination.set(i, result); return; }
        }
        destination.add(result);
    }

    private static Snapshot copyOf(Snapshot source) {
        if (source == null) return new Snapshot();
        return Snapshot.fromJson(source.toJson().toString());
    }

    @Override public void onDestroy() {
        stopTaskOnly();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
