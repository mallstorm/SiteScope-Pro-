package pro.sitescope.app.keys

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONObject
import pro.sitescope.app.HostsVpnService
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Runs the signed-upstream sing-box Android CLI as its OWN OS process.
 * Unlike a gomobile libbox AAR this cannot collide with libv2ray's go.Seq/JNI.
 * No VpnService is created or modified; a successful check requires HTTPS 204
 * over the candidate protocol and through sing-box's loopback SOCKS inbound.
 */
object SingBoxValidator {
    private val running = java.util.concurrent.ConcurrentHashMap.newKeySet<Process>()
    private val targets = listOf("www.gstatic.com", "cp.cloudflare.com")

    fun cancel() {
        running.forEach { try { it.destroyForcibly() } catch (_: Exception) {} }
    }

    fun check(context: Context, link: String, timeoutMs: Int): XrayKeyValidator.Result {
        var process: Process? = null
        var configFile: File? = null
        var logFile: File? = null
        try {
            // Prevent a second active VPN from making a failed key look alive.
            val prefs = context.getSharedPreferences(HostsVpnService.PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean("active", false))
                return result(XrayKeyValidator.INCONCLUSIVE, "Выключите VPN SiteScope перед проверкой")
            val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true)
                return result(XrayKeyValidator.INCONCLUSIVE, "Отключите сторонний VPN для точного теста")
            val executable = File(context.applicationInfo.nativeLibraryDir, "libsingbox_exec.so")
            if (!executable.isFile || !executable.canExecute())
                return result(XrayKeyValidator.INCONCLUSIVE, "Движок sing-box не установлен в APK")
            val port = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }
            val json = SingBoxConfig.build(link, port)
            configFile = File.createTempFile("singbox-profile-", ".json", context.cacheDir)
            configFile.writeText(json)
            logFile = File.createTempFile("singbox-error-", ".log", context.cacheDir)
            process = ProcessBuilder(executable.absolutePath, "run", "-c", configFile.absolutePath)
                .redirectErrorStream(true).redirectOutput(logFile).start()
            process?.let { running.add(it) }
            val readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var ready = false
            while (System.nanoTime() < readyDeadline) {
                if (Thread.currentThread().isInterrupted)
                    return result(XrayKeyValidator.INCONCLUSIVE, "Тест прерван")
                if (!process.isAlive) {
                    val reason = if (process.exitValue() != 0) "sing-box отклонил профиль или завершился" else "sing-box завершился"
                    return result(XrayKeyValidator.INCONCLUSIVE, reason)
                }
                try {
                    Socket().use { sock -> sock.connect(InetSocketAddress("127.0.0.1", port), 350) }
                    ready = true
                    break
                } catch (_: Exception) { Thread.sleep(120) }
            }
            if (!ready) return result(XrayKeyValidator.INCONCLUSIVE, "Локальный SOCKS sing-box не запустился")
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
            for (host in targets) {
                if (Thread.currentThread().isInterrupted)
                    return result(XrayKeyValidator.INCONCLUSIVE, "Тест прерван")
                try {
                    val delay = https204(proxy, host, timeoutMs.coerceIn(2000, 12000))
                    return XrayKeyValidator.Result(XrayKeyValidator.VERIFIED, delay,
                        "HTTPS 204 через sing-box (" + link.substringBefore("://") + ")")
                } catch (_: Exception) {
                    // A TLS handshake without an HTTP response never verifies a VPN key.
                }
            }
            return if (directNetworkWorks()) result(XrayKeyValidator.FAILED,
                "Два HTTPS-теста через конфигурацию завершились ошибкой")
                else result(XrayKeyValidator.INCONCLUSIVE,
                    "Не удалось проверить доступность контрольных сайтов")
        } catch (e: IllegalArgumentException) {
            return result(XrayKeyValidator.FAILED, e.message ?: "Некорректный формат ключа")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return result(XrayKeyValidator.INCONCLUSIVE, "Тест отменён")
        } catch (e: Exception) {
            return result(XrayKeyValidator.INCONCLUSIVE,
                "Не удалось выполнить тест sing-box: " + e.javaClass.simpleName)
        } finally {
            try { process?.destroyForcibly() } catch (_: Exception) {}
            try { process?.waitFor(1, TimeUnit.SECONDS) } catch (_: Exception) {}
            process?.let { running.remove(it) }
            configFile?.delete()
            logFile?.delete()
        }
    }

    private fun https204(proxy: Proxy, target: String, timeoutMs: Int): Long {
        val start = System.nanoTime()
        Socket(proxy).use { socket ->
            socket.connect(InetSocketAddress.createUnresolved(target, 443), timeoutMs)
            socket.soTimeout = timeoutMs
            (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(socket, target, 443, true).use { wrapped ->
                    val ssl = wrapped as SSLSocket
                    ssl.soTimeout = timeoutMs
                    ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                    ssl.startHandshake()
                    val out = ssl.outputStream
                    out.write(("GET /generate_204 HTTP/1.1\r\nHost: " + target +
                        "\r\nUser-Agent: SiteScope/2\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII))
                    out.flush()
                    val response = ssl.inputStream.bufferedReader(Charsets.US_ASCII).readLine()
                    check(response != null && Regex("HTTP/1\\.[01] 204(?:\\s.*)?").matches(response)) {
                        "Контрольный HTTPS-сервер не вернул 204"
                    }
                    return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                }
        }
    }

    private fun directNetworkWorks(): Boolean {
        for (target in targets) try {
            val conn = java.net.URL("https://" + target + "/generate_204").openConnection() as HttpsURLConnection
            conn.connectTimeout = 2000
            conn.readTimeout = 2000
            try { if (conn.responseCode == 204) return true } finally { conn.disconnect() }
        } catch (_: Exception) {}
        return false
    }

    private fun result(status: Int, reason: String) =
        XrayKeyValidator.Result(status, -1L, reason)
}
