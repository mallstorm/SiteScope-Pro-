package pro.sitescope.app.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/**
 * Единый реестр длительных операций приложения: проверки сайтов, обходы, сканирования, проверка потоков.
 * Экран «Задачи» и пилюля «Задачи: N» в шапке читают его вместе с живыми службами (VPN, DPI, Tor, I2P, сканер).
 * Реестр хранит только состояние текущего запуска приложения; долговременная история лежит в хранилищах разделов.
 */
object TaskCenter {
    enum class Kind(val title: String, val route: String) {
        SITE_AUDIT("Проверка сайта", "site"), CRAWL("Обход сайта", "site"), BATCH("Проверка списка сайтов", "batch"),
        SCAN("Сканирование сети", "network"), TV_CHECK("Проверка потоков", "tv"), TV_REFRESH("Обновление плейлиста", "tv"),
        TV_RECORD("Запись канала", "tv"), METRICS("Метрики поиска", "metrics"), OTHER("Задача", "")
    }

    enum class State { RUNNING, DONE, FAILED, CANCELLED }

    data class Task(
        val id: Long,
        val kind: Kind,
        val title: String,
        val target: String,
        val state: State = State.RUNNING,
        /** От 0 до 1; отрицательное значение — ход неизвестен. */
        val progress: Float = -1f,
        val detail: String = "",
        val startedAt: Long = System.currentTimeMillis(),
        val finishedAt: Long = 0L,
        val cancellable: Boolean = false
    ) { val active: Boolean get() = state == State.RUNNING }

    private const val KEEP_FINISHED = 40
    private val ids = AtomicLong(1)
    private val cancels = java.util.concurrent.ConcurrentHashMap<Long, () -> Unit>()
    private val _tasks = MutableStateFlow<List<Task>>(emptyList())
    val tasks: StateFlow<List<Task>> = _tasks.asStateFlow()

    /** Регистрирует начатую операцию и возвращает её номер. [onCancel] вызывается по кнопке «Остановить». */
    fun start(kind: Kind, title: String, target: String = "", onCancel: (() -> Unit)? = null): Long {
        val id = ids.getAndIncrement()
        if (onCancel != null) cancels[id] = onCancel
        _tasks.update { list -> trim(listOf(Task(id, kind, title, target, cancellable = onCancel != null)) + list) }
        return id
    }

    fun progress(id: Long, progress: Float, detail: String = "") {
        _tasks.update { list -> list.map { if (it.id == id && it.active) it.copy(progress = progress.coerceAtMost(1f), detail = detail.ifEmpty { it.detail }) else it } }
    }

    fun finish(id: Long, state: State = State.DONE, detail: String = "") {
        cancels.remove(id)
        _tasks.update { list -> list.map { if (it.id == id && it.active) it.copy(state = state, finishedAt = System.currentTimeMillis(), progress = if (state == State.DONE) 1f else it.progress, detail = detail.ifEmpty { it.detail }, cancellable = false) else it } }
    }

    /** Останавливает операцию через сохранённый обработчик отмены. */
    fun cancel(id: Long) {
        val action = cancels.remove(id)
        try { action?.invoke() } catch (_: Exception) { }
        finish(id, State.CANCELLED, "Остановлено")
    }

    fun clearFinished() { _tasks.update { list -> list.filter { it.active } } }

    private fun trim(list: List<Task>): List<Task> {
        var finished = 0
        return list.filter { if (it.active) true else { finished++; finished <= KEEP_FINISHED } }
    }
}
