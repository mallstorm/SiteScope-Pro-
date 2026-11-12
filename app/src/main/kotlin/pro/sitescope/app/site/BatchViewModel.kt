package pro.sitescope.app.site

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BatchResult(val target: String, val host: String, var up: Boolean = false, var code: Int = 0,
                   var ms: Long = 0, var certDays: Int = -1, var error: String = "", var done: Boolean = false)

/** Пакетная проверка списка сайтов: доступность, код ответа и срок сертификата для каждого. */
class BatchViewModel(app: Application) : AndroidViewModel(app) {
    var input by mutableStateOf("")
    var running by mutableStateOf(false)
    var done by mutableStateOf(0)
    var total by mutableStateOf(0)
    val results = mutableStateListOf<BatchResult>()

    @Volatile private var cancelled = AtomicBoolean(false)

    fun run() {
        val targets = input.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.distinct()
        if (targets.isEmpty() || running) return
        running = true; done = 0; total = targets.size; cancelled.set(false)
        results.clear()
        val list = targets.map { t -> BatchResult(t, try { java.net.URL(SiteExtras.normalize(t)).host } catch (e: Exception) { t }) }
        results.addAll(list)
        val doneCounter = AtomicInteger(0)
        viewModelScope.launch(Dispatchers.IO) {
            val pool = Executors.newFixedThreadPool(8)
            try {
                for ((index, item) in list.withIndex()) {
                    if (cancelled.get()) break
                    pool.execute {
                        if (!cancelled.get()) {
                            val started = System.currentTimeMillis()
                            try {
                                val data = SiteExtras.collect(item.target, checkLinks = false)
                                val code = data.chain.lastOrNull()?.code ?: 0
                                item.up = data.reachable && code in 200..399
                                item.code = code
                                item.ms = System.currentTimeMillis() - started
                                item.certDays = data.tls?.takeIf { it.valid }?.daysLeft ?: -1
                                if (!data.reachable) item.error = data.error
                            } catch (e: Throwable) { item.error = e.message ?: e.javaClass.simpleName }
                            item.done = true
                            results[index] = item
                            done = doneCounter.incrementAndGet()
                        }
                    }
                }
            } finally {
                pool.shutdown()
                try { pool.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES) } catch (e: InterruptedException) { }
            }
            running = false
        }
    }

    fun cancel() { cancelled.set(true); running = false }

    fun exportText(): String = results.joinToString("\n") { r ->
        val status = if (!r.done) "не проверено" else if (r.up) "работает (${r.code}, ${r.ms} мс)" else "не работает" + (r.error.takeIf { it.isNotEmpty() }?.let { " — $it" } ?: "")
        val cert = if (r.certDays in 0..30) " · сертификат истекает через ${r.certDays} дн." else ""
        "${r.host} — $status$cert"
    }
}
