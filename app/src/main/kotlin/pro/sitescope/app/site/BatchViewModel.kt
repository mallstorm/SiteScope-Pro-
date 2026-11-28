package pro.sitescope.app.site

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.sitescope.app.core.TaskCenter

data class BatchResult(
    val target: String,
    val host: String,
    val up: Boolean = false,
    val code: Int = 0,
    val ms: Long = 0,
    val certDays: Int = -1,
    val error: String = "",
    val done: Boolean = false,
)

class BatchViewModel(app: Application) : AndroidViewModel(app) {
    var input by mutableStateOf("")
    var running by mutableStateOf(false)
        private set
    var stopping by mutableStateOf(false)
        private set
    var done by mutableStateOf(0)
        private set
    var total by mutableStateOf(0)
        private set
    val results = mutableStateListOf<BatchResult>()
    private var job: Job? = null

    fun run() {
        val targets = input.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.distinct().toList()
        if (targets.isEmpty() || running) return
        val list = targets.map { target ->
            BatchResult(target, runCatching { java.net.URL(SiteExtras.normalize(target)).host }.getOrDefault(target))
        }
        running = true
        stopping = false
        done = 0
        total = list.size
        results.clear()
        results.addAll(list)
        val task = TaskCenter.start(TaskCenter.Kind.BATCH, "Проверка списка сайтов", "Сайтов: ${list.size}") { cancel() }
        job = viewModelScope.launch {
            var state = TaskCenter.State.CANCELLED
            try {
                var next = 0
                coroutineScope {
                    repeat(minOf(8, total)) {
                        launch {
                            while (isActive && next < list.size) {
                                val index = next++
                                val item = withContext(Dispatchers.IO) {
                                    check(list[index]) { !isActive }
                                }
                                results[index] = item
                                done++
                                TaskCenter.progress(task, done.toFloat() / total, "$done из $total")
                            }
                        }
                    }
                }
                state = TaskCenter.State.DONE
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                state = TaskCenter.State.FAILED
            } finally {
                running = false
                stopping = false
                val detail = when (state) {
                    TaskCenter.State.CANCELLED -> "Остановлено"
                    TaskCenter.State.FAILED -> "Проверка завершилась с ошибкой"
                    else -> "Проверено: $done"
                }
                TaskCenter.finish(task, state, detail)
            }
        }
    }

    private fun check(item: BatchResult, cancelled: () -> Boolean): BatchResult {
        val started = System.nanoTime()
        return try {
            val data = SiteExtras.collect(item.target, checkLinks = false, cancelled = cancelled)
            val code = data.chain.lastOrNull()?.code ?: 0
            item.copy(
                up = data.reachable && code in 200..399, code = code,
                ms = (System.nanoTime() - started) / 1_000_000,
                certDays = data.tls?.takeIf { it.valid }?.daysLeft ?: -1,
                error = data.error.takeUnless { data.reachable }.orEmpty(),
                done = true,
            )
        } catch (e: Exception) {
            item.copy(error = e.message ?: "Ошибка проверки", done = true)
        }
    }

    fun cancel() {
        if (running && !stopping) {
            stopping = true
            job?.cancel()
        }
    }

    fun exportText(): String = results.joinToString("\n") { r ->
        val status = if (!r.done) "не проверено" else if (r.up) "работает (${r.code}, ${r.ms} мс)" else "не работает" + (r.error.takeIf { it.isNotEmpty() }?.let { " — $it" } ?: "")
        val cert = if (r.certDays in 0..30) " · сертификат истекает через ${r.certDays} дн." else ""
        "${r.host} — $status$cert"
    }
}
