package pro.sitescope.app.tv

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Проверка архива передач (catch-up) — открывается ли запись прошедшей передачи у провайдера. */
object ArchiveChecker {
    class Result(val channel: Channel, val hasArchive: Boolean, val programme: String, val ok: Boolean, val detail: String)

    fun supportsArchive(c: Channel): Boolean = try { Playlist.catchupUrl(c, 0, 1) != null } catch (e: Exception) { false }

    /** Берёт последнюю прошедшую передачу из телепрограммы (или условное окно «час назад», если программы нет) и пробует открыть запись. */
    fun check(channel: Channel): Result {
        if (!supportsArchive(channel)) return Result(channel, false, "", false, "В плейлисте нет описания архива для этого канала")
        val now = System.currentTimeMillis()
        val past = Epg.forChannel(channel).filter { it.stop < now }.sortedByDescending { it.start }.firstOrNull()
        val start = past?.start ?: (now - 65 * 60_000L)
        val end = past?.stop ?: (now - 60 * 60_000L)
        val title = past?.title ?: ""
        val url = Playlist.catchupUrl(channel, start, end) ?: return Result(channel, true, title, false, "Не удалось собрать ссылку архива")
        val r = StreamChecker.check(url, channel.userAgent, channel.referrer, 8000)
        val ok = r.status == StreamStatus.OK || r.status == StreamStatus.SLOW
        val detail = if (ok) "Архив открывается" + (if (title.isNotEmpty()) " · $title" else "") else r.detail.ifEmpty { "Архив недоступен" }
        return Result(channel, true, title, ok, detail)
    }

    interface Listener {
        fun onResult(result: Result, done: Int, total: Int)
        fun onFinished(results: List<Result>)
    }

    /** Параллельная проверка архива у всех каналов, у которых он описан в плейлисте. */
    fun checkAll(channels: List<Channel>, threads: Int, listener: Listener) {
        val list = channels.filter { supportsArchive(it) }
        if (list.isEmpty()) { listener.onFinished(emptyList()); return }
        val pool = Executors.newFixedThreadPool(threads.coerceIn(1, 16))
        val done = AtomicInteger(0)
        val results = java.util.Collections.synchronizedList(ArrayList<Result>())
        Thread({
            val futures = list.map { c ->
                pool.submit {
                    val r = try { check(c) } catch (e: Exception) { Result(c, true, "", false, e.message ?: "Ошибка проверки") }
                    results.add(r)
                    listener.onResult(r, done.incrementAndGet(), list.size)
                }
            }
            for (f in futures) try { f.get() } catch (e: Exception) { }
            pool.shutdown()
            listener.onFinished(ArrayList(results))
        }, "SiteScope-ArchiveCheck").start()
    }
}
