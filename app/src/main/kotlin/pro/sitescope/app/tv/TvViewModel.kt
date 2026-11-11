package pro.sitescope.app.tv

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import pro.sitescope.app.site.SiteMonitor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Очередь для плеера: список каналов, по которому можно листать, и стартовая позиция. */
object PlayerQueue {
    @Volatile var channels: List<Channel> = emptyList()
    @Volatile var index: Int = 0
}

class TvViewModel(app: Application) : AndroidViewModel(app) {
    private val context: Context get() = getApplication<Application>()

    var channels by mutableStateOf(listOf<Channel>())
    var sources by mutableStateOf(listOf<TvSource>())
    var tick by mutableIntStateOf(0)             // растёт при изменении каналов «на месте»
    var loaded by mutableStateOf(false)

    var query by mutableStateOf("")
    var group by mutableStateOf(ALL)
    var statusFilter by mutableStateOf<StreamStatus?>(null)

    var busy by mutableStateOf("")
    var message by mutableStateOf("")

    var checking by mutableStateOf(false)
    var checkDone by mutableIntStateOf(0)
    var checkTotal by mutableIntStateOf(0)
    var lastCheckAt by mutableStateOf(0L)

    var epgBusy by mutableStateOf(false)
    var epgInfo by mutableStateOf("")
    var udpxy by mutableStateOf("")
    var threads by mutableIntStateOf(8)
    var autoCheck by mutableStateOf(false)
    var epgUrls by mutableStateOf("")

    private var batch: StreamChecker.Batch? = null
    @Volatile private var lastTickAt = 0L

    init {
        viewModelScope.launch(Dispatchers.IO) {
            TvStore.load(context); Epg.loadCache(context)
            udpxy = TvStore.udpxyBase; threads = TvStore.threads; autoCheck = TvStore.autoCheck
            lastCheckAt = TvStore.lastCheckAt; epgUrls = TvStore.epgUrls.joinToString("\n")
            publish(); updateEpgInfo(); loaded = true
            if (TvStore.epgUrls.isNotEmpty() && System.currentTimeMillis() - Epg.fetchedAt > 12 * 3600_000L && TvStore.channels.isNotEmpty()) refreshEpg(silent = true)
        }
    }

    private fun publish() { channels = TvStore.snapshot(); sources = ArrayList(TvStore.sources); tick++ }

    /** Перечитывает хранилище после изменений, сделанных с других экранов (шлюз, каналы из локальной сети). */
    fun syncFromStore() { if (loaded) { udpxy = TvStore.udpxyBase; publish() } }
    private fun persist() { TvStore.save(context) }

    fun groups(): List<String> = channels.map { it.group }.filter { it.isNotBlank() }.distinct()
    fun count(status: StreamStatus): Int = channels.count { it.status == status }

    fun visible(): List<Channel> {
        val q = query.trim().lowercase()
        return channels.filter { c ->
            (when (group) { ALL -> true; FAVORITES -> c.favorite; RECENT -> c.lastWatched > 0; else -> c.group == group }) &&
                (statusFilter == null || c.status == statusFilter) && (q.isEmpty() || c.name.lowercase().contains(q))
        }.let { list -> if (group == RECENT) list.sortedByDescending { it.lastWatched }.take(30) else list }
    }

    // ---------- Источники ----------

    fun addUrl(url: String, title: String = "") {
        val link = url.trim()
        if (link.isEmpty() || busy.isNotEmpty()) return
        busy = "Загружаем плейлист…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = TvStore.download(if (link.startsWith("http", true)) link else "http://$link")
                if (!Playlist.looksLikeM3u(text)) throw IllegalStateException("По ссылке не плейлист. Нужен файл формата M3U.")
                val name = title.ifBlank { try { java.net.URL(link).host } catch (e: Exception) { "Плейлист" } }
                install(TvSource("url-" + Playlist.channelId(link, ""), name, "url", link), text)
            } catch (e: Throwable) { message = e.message ?: "Не удалось загрузить плейлист" }
            busy = ""
        }
    }

    fun addXtream(server: String, user: String, password: String) {
        val url = try { Playlist.xtreamUrl(server, user, password) } catch (e: Exception) { message = e.message ?: "Проверьте данные"; return }
        addUrl(url, "Xtream · " + server.trim().removePrefix("http://").removePrefix("https://").substringBefore('/'))
    }

    fun addFile(uri: Uri) {
        if (busy.isNotEmpty()) return
        busy = "Читаем файл…"
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(32768)
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        if (out.size() > 40_000_000 - n) throw IllegalArgumentException("Плейлист превышает 40 МБ")
                        out.write(buffer, 0, n)
                    }
                    out.toByteArray()
                } ?: throw IllegalStateException("Не удалось открыть файл")
                val text = String(bytes, Charsets.UTF_8)
                if (!Playlist.looksLikeM3u(text)) throw IllegalStateException("Это не плейлист M3U")
                val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "Файл"
                install(TvSource("file-" + System.currentTimeMillis(), name, "file", ""), text)
            } catch (e: Throwable) { message = e.message ?: "Не удалось прочитать файл" }
            busy = ""
        }
    }

    fun addText(text: String) {
        if (!Playlist.looksLikeM3u(text)) { message = "Текст не похож на плейлист M3U"; return }
        viewModelScope.launch(Dispatchers.IO) { try { install(TvSource("text-" + System.currentTimeMillis(), "Вставленный плейлист", "file", ""), text) } catch (e: Throwable) { message = e.message ?: "Не удалось добавить" } }
    }

    private fun install(source: TvSource, text: String) {
        val parsed = Playlist.parse(text, source.id)
        if (parsed.channels.isEmpty()) throw IllegalStateException("В плейлисте нет каналов")
        val count = TvStore.putSource(source, parsed)
        persist(); publish()
        epgUrls = TvStore.epgUrls.joinToString("\n")
        message = "Добавлено каналов: $count"
        if (parsed.epgUrls.isNotEmpty()) refreshEpg(silent = true)
    }

    fun refreshSource(source: TvSource) { if (source.type == "url") addUrl(source.url, source.name) }

    fun removeSource(source: TvSource) {
        viewModelScope.launch(Dispatchers.IO) { TvStore.removeSource(source.id); persist(); publish() }
    }

    /** Разовый просмотр потока по ссылке — без добавления в список. */
    fun adHoc(url: String): Channel = Channel().apply { this.url = url.trim(); name = "Поток по ссылке"; id = Playlist.channelId(this.url, name) }

    // ---------- Проверка ----------

    fun startCheck(list: List<Channel>) {
        if (checking || list.isEmpty()) return
        checking = true; checkDone = 0; checkTotal = list.size
        batch = StreamChecker.checkAll(list, threads, TvStore.timeoutMs, udpxy, { TvStore.localCheck(it) }, object : StreamChecker.BatchListener {
            override fun onChannel(channel: Channel, done: Int, total: Int) {
                checkDone = done
                val now = System.currentTimeMillis()
                if (now - lastTickAt > 350) { lastTickAt = now; tick++ }
            }
            override fun onFinished(cancelled: Boolean) {
                TvStore.lastCheckAt = System.currentTimeMillis(); lastCheckAt = TvStore.lastCheckAt
                persist(); TvStore.saveHistory(context)
                checking = false; tick++
            }
        })
    }

    fun stopCheck() { batch?.cancel() }

    fun checkOne(channel: Channel) {
        viewModelScope.launch(Dispatchers.IO) {
            val target = Playlist.playbackUrl(udpxy, channel.url)
            val result = if (target.startsWith("udp://", true) || target.startsWith("rtp://", true)) TvStore.localCheck(channel)
                else StreamChecker.check(target, channel.userAgent, channel.referrer, TvStore.timeoutMs)
            StreamChecker.apply(channel, result); persist(); tick++
        }
    }

    fun removeDead() {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = TvStore.removeChannels { it.status == StreamStatus.DEAD && !it.favorite }
            persist(); publish(); statusFilter = null
            message = if (removed > 0) "Убрано каналов: $removed" else "Нерабочих каналов нет"
        }
    }

    fun dedupe() {
        viewModelScope.launch(Dispatchers.IO) {
            val removed = TvStore.dedupe(); persist(); publish()
            message = if (removed > 0) "Убрано повторов: $removed" else "Повторов нет"
        }
    }

    fun remove(channel: Channel) {
        viewModelScope.launch(Dispatchers.IO) { TvStore.removeChannels { it.id == channel.id }; persist(); publish() }
    }

    fun toggleFavorite(channel: Channel) { channel.favorite = !channel.favorite; tick++; viewModelScope.launch(Dispatchers.IO) { persist() } }

    fun exportM3u(onlyWorking: Boolean): String {
        val list = if (onlyWorking) channels.filter { it.status == StreamStatus.OK || it.status == StreamStatus.SLOW } else channels
        return Playlist.export(list, TvStore.epgUrls)
    }

    fun write(uri: Uri, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }; message = "Плейлист сохранён" }
            catch (e: Exception) { message = "Не удалось сохранить файл" }
        }
    }

    // ---------- Телепрограмма и настройки ----------

    private fun updateEpgInfo() {
        epgInfo = if (Epg.fetchedAt == 0L) "Не загружена" else "Каналов с программой: ${Epg.channelCount} · обновлено " +
            java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale("ru")).format(java.util.Date(Epg.fetchedAt))
    }

    fun refreshEpg(silent: Boolean = false) {
        if (epgBusy) return
        val urls = TvStore.epgUrls.toList()
        if (urls.isEmpty()) { if (!silent) message = "Сначала укажите ссылку на телепрограмму"; return }
        epgBusy = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val count = Epg.refresh(context, urls, TvStore.snapshot())
                if (!silent) message = if (count > 0) "Программа загружена для $count каналов" else "В программе не нашлось ваших каналов"
            } catch (e: Throwable) { if (!silent) message = e.message ?: "Не удалось загрузить программу" }
            updateEpgInfo(); epgBusy = false; tick++
        }
    }

    fun saveSettings() {
        TvStore.udpxyBase = udpxy.trim(); TvStore.threads = threads; TvStore.autoCheck = autoCheck
        TvStore.epgUrls.clear(); TvStore.epgUrls.addAll(epgUrls.lines().map { it.trim() }.filter { it.startsWith("http") })
        viewModelScope.launch(Dispatchers.IO) { persist() }
        val manager = WorkManager.getInstance(context)
        if (autoCheck) manager.enqueueUniquePeriodicWork("tv-check", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<TvCheckWorker>(24, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()).build())
        else manager.cancelUniqueWork("tv-check")
    }

    fun markWatched(channel: Channel) { channel.lastWatched = System.currentTimeMillis(); viewModelScope.launch(Dispatchers.IO) { persist() } }

    override fun onCleared() { batch?.cancel(); super.onCleared() }

    companion object { const val ALL = "\u0000all"; const val FAVORITES = "\u0000fav"; const val RECENT = "\u0000recent"
        var externalChange by androidx.compose.runtime.mutableIntStateOf(0) }
}

/** Ежедневная фоновая проверка каналов (только по Wi-Fi). */
class TvCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        TvStore.load(context)
        val list = TvStore.snapshot()
        if (list.isEmpty()) return Result.success()
        val wasWorking = list.count { it.status == StreamStatus.OK || it.status == StreamStatus.SLOW }
        val latch = CountDownLatch(1)
        val batch = StreamChecker.checkAll(list, TvStore.threads, TvStore.timeoutMs, TvStore.udpxyBase, { TvStore.localCheck(it) }, object : StreamChecker.BatchListener {
            override fun onChannel(channel: Channel, done: Int, total: Int) {}
            override fun onFinished(cancelled: Boolean) { latch.countDown() }
        })
        if (!latch.await(9, TimeUnit.MINUTES)) { batch.cancel(); latch.await(20, TimeUnit.SECONDS) }
        TvStore.lastCheckAt = System.currentTimeMillis()
        TvStore.save(context); TvStore.saveHistory(context)
        val working = list.count { it.status == StreamStatus.OK || it.status == StreamStatus.SLOW }
        val favoritesDown = list.filter { it.favorite && it.status == StreamStatus.DEAD }
        if (favoritesDown.isNotEmpty() || working < wasWorking)
            SiteMonitor.notify(context, 7401, "Проверка каналов: работают $working из ${list.size}",
                if (favoritesDown.isNotEmpty()) "Не отвечают избранные: " + favoritesDown.take(5).joinToString(", ") { it.name } else "Перестали работать каналов: ${wasWorking - working}", "tv")
        return Result.success()
    }
}
