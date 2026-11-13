package pro.sitescope.app.dpi

import android.content.Context
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Один процесс ciadpi (byedpi) — локальный SOCKS5-сервер с обходом DPI. Отдельный процесс даёт изоляцию от сбоев
 * и позволяет подбирать стратегии параллельно на разных портах.
 */
class DpiEngine(private val context: Context) {
    @Volatile private var process: Process? = null
    @Volatile var port: Int = 0
        private set
    private val tail = ConcurrentLinkedDeque<String>()

    val logLines: List<String> get() = tail.toList()
    val isAlive: Boolean get() = process?.isAlive == true

    /** Запускает процесс и ждёт, пока порт начнёт принимать соединения. Возвращает null при успехе или текст ошибки. */
    fun start(args: List<String>, readyTimeoutMs: Int = 3000): String? {
        stop()
        val binary = binary(context) ?: return "Не найден исполняемый файл byedpi (libciadpi_exec.so). Поддерживается только arm64."
        val p = try {
            ProcessBuilder(listOf(binary.absolutePath) + args).directory(context.cacheDir).redirectErrorStream(true).start()
        } catch (e: Exception) {
            return "Не удалось запустить byedpi: ${e.message ?: e.javaClass.simpleName}"
        }
        process = p
        port = args.firstOrNull { it.startsWith("-p") }?.drop(2)?.toIntOrNull() ?: 1080
        tail.clear()
        Thread({
            try { p.inputStream.bufferedReader().forEachLine { tail.addLast(it); while (tail.size > 200) tail.pollFirst() } } catch (_: Exception) { }
        }, "dpi-log").apply { isDaemon = true }.start()
        val deadline = System.currentTimeMillis() + readyTimeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!p.isAlive) return "byedpi завершился (код ${p.exitValue()}): " + tail.joinToString(" | ").take(300)
            try { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 250); return null } } catch (_: Exception) { Thread.sleep(60) }
        }
        stop()
        return "byedpi не открыл порт $port за ${readyTimeoutMs / 1000} с"
    }

    fun stop() {
        val p = process ?: return
        process = null
        try { p.destroy() } catch (_: Exception) { }
        try {
            if (!p.waitFor(800, java.util.concurrent.TimeUnit.MILLISECONDS)) p.destroyForcibly()
        } catch (_: Exception) { try { p.destroyForcibly() } catch (_: Exception) { } }
    }

    /** Ждёт завершения процесса (для надзора). */
    fun waitExit(): Int = try { process?.waitFor() ?: -1 } catch (_: InterruptedException) { -1 }

    companion object {
        fun binary(context: Context): File? {
            val f = File(context.applicationInfo.nativeLibraryDir, "libciadpi_exec.so")
            return if (f.exists() && (f.canExecute() || f.setExecutable(true))) f else null
        }

        fun available(context: Context) = binary(context) != null

        /** Свободный локальный порт. */
        fun freePort(): Int = ServerSocket(0, 1, java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { it.localPort }

        fun portFree(port: Int): Boolean = try { ServerSocket(port, 1, java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).use { true } } catch (_: Exception) { false }
    }
}
