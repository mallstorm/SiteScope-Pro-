package pro.sitescope.app;

import android.content.Context;
import android.os.ParcelFileDescriptor;
import com.v2ray.ang.service.TProxyService;
import org.json.JSONObject;
import pro.sitescope.app.keys.SingBoxConfig;

import java.io.File;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;

/**
 * Single-owner Android TUN -> existing HEV SOCKS bridge -> isolated sing-box CLI.
 * This is a separate MODE_SINGBOX: it never starts libv2ray, preserving the
 * existing MODE_XRAY bootstrap and the single-Android-VPN-slot invariant.
 */
public final class SingBoxRuntime {
    private final Context context;
    private Process process;
    private TProxyService bridge;
    private File configFile;
    private int port;

    public SingBoxRuntime(Context context) { this.context = context.getApplicationContext(); }

    public synchronized void start(ParcelFileDescriptor tun, String link) throws Exception {
        stop();
        File exe = new File(context.getApplicationInfo().nativeLibraryDir, "libsingbox_exec.so");
        if (!exe.isFile() || !exe.canExecute()) throw new IllegalStateException("sing-box ARM64 не найден");
        try (ServerSocket candidate = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = candidate.getLocalPort();
        }
        String config = SingBoxConfig.INSTANCE.build(link, port);
        configFile = File.createTempFile("sitescope-singbox-", ".json", context.getCacheDir());
        java.io.FileOutputStream stream = new java.io.FileOutputStream(configFile);
        try { stream.write(config.getBytes(java.nio.charset.StandardCharsets.UTF_8)); stream.flush(); stream.getFD().sync(); }
        finally { stream.close(); }
        try {
            File output = new File(context.getCacheDir(), "sitescope-singbox-native.log");
            process = new ProcessBuilder(exe.getAbsolutePath(), "run", "-c", configFile.getAbsolutePath())
                .redirectErrorStream(true).redirectOutput(output).start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            boolean ready = false;
            while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
                if (!process.isAlive()) throw new IllegalStateException("sing-box завершился во время запуска");
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 350);
                    ready = true;
                    break;
                } catch (Exception notYetReady) {
                    Thread.sleep(150L);
                }
            }
            if (!ready) throw new java.net.SocketTimeoutException("Локальный SOCKS sing-box не запустился");
            bridge = new TProxyService(context, tun, port);
            bridge.startTun2Socks();
            if (!bridge.isStarted()) throw new IllegalStateException("HEV не запустил TUN→SOCKS");
        } catch (Throwable failure) {
            stop();
            if (failure instanceof Exception) throw (Exception)failure;
            throw new IllegalStateException("Ошибка sing-box runtime", failure);
        }
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive() && bridge != null && bridge.isStarted();
    }

    public synchronized void stop() {
        if (bridge != null) { bridge.stopTun2Socks(); bridge = null; }
        if (process != null) { process.destroyForcibly(); process = null; }
        if (configFile != null) { configFile.delete(); configFile = null; }
    }

    public int socksPort() { return port; }
}
