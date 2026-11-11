package pro.sitescope.app.vpn

import android.content.Context
import pro.sitescope.app.HostsVpnService
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Сохраняет причину аварийного завершения. Если приложение «вылетело» из-за ошибки в Java/Kotlin,
 * записывается стек. Если процесс убила нативная часть (ядро Xray или туннель), при следующем запуске
 * из системного журнала вычитываются строки самого приложения — в них остаётся сообщение о сбое.
 */
object CrashLog {
    private val interesting = Regex("GoLog|libgojni|gojni|hev|tun2socks|socks5|AndroidRuntime|FATAL|Fatal signal|signal \\d+|SIGSEGV|SIGABRT|panic|goroutine|Abort message|backtrace|#\\d\\d pc|libc\\s*:|DEBUG\\s*:|xray|Xray|SiteScope", RegexOption.IGNORE_CASE)

    private fun dir(context: Context) = File(context.filesDir, "diagnostics").apply { if (!exists()) mkdirs() }
    private fun stamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val text = StringWriter(); error.printStackTrace(PrintWriter(text))
                File(dir(app), "crash-java.txt").writeText(stamp() + " · поток " + thread.name + "\n" + text.toString().take(12000))
            } catch (e: Throwable) { }
            previous?.uncaughtException(thread, error)
        }
        // Процесс перезапущен, а служба VPN числится работающей: значит, прошлый запуск оборвался. Забираем следы из системного журнала.
        try {
            val prefs = app.getSharedPreferences(HostsVpnService.PREFS, Context.MODE_PRIVATE)
            val state = prefs.getString(HostsVpnService.KEY_STATE, HostsVpnService.STATE_OFF)
            val managed = state == HostsVpnService.STATE_STARTING || state == HostsVpnService.STATE_ACTIVE || state == HostsVpnService.STATE_STOPPING
            val age = System.currentTimeMillis() - prefs.getLong("serviceHeartbeatAt", 0L)
            if (managed && age > 3000L) Thread({ captureSystemLog(app, "после обрыва VPN в фазе: " + (prefs.getString("message", "") ?: "")) }, "SiteScopeCrashCapture").start()
        } catch (e: Throwable) { }
    }

    /** Читает журнал Android (приложению доступны только его собственные строки) и сохраняет то, что относится к сбою. */
    fun captureSystemLog(context: Context, reason: String): String {
        val text = try {
            val process = ProcessBuilder("logcat", "-d", "-t", "3000", "-v", "time").redirectErrorStream(true).start()
            val lines = process.inputStream.bufferedReader().use { it.readLines() }
            try { process.destroy() } catch (e: Throwable) { }
            val kept = lines.filter { interesting.containsMatchIn(it) }.takeLast(260)
            if (kept.isEmpty()) "В системном журнале нет строк о сбое (всего прочитано: ${lines.size})." else kept.joinToString("\n") { it.take(400) }
        } catch (e: Throwable) { "Системный журнал недоступен: " + (e.message ?: e.javaClass.simpleName) }
        val full = stamp() + " · " + reason + "\n" + text
        try { File(dir(context), "crash-native.txt").writeText(full) } catch (e: Throwable) { }
        return full
    }

    fun read(context: Context): String {
        val parts = ArrayList<String>()
        for ((title, name) in listOf("Ошибка приложения" to "crash-java.txt", "Системный журнал при сбое" to "crash-native.txt")) {
            val file = File(dir(context), name)
            if (file.isFile && file.length() > 0) parts.add("=== $title ===\n" + try { file.readText() } catch (e: Exception) { "" })
        }
        return parts.joinToString("\n\n")
    }

    fun clear(context: Context) { File(dir(context), "crash-java.txt").delete(); File(dir(context), "crash-native.txt").delete() }
}
