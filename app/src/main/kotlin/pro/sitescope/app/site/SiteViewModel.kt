package pro.sitescope.app.site

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.sitescope.app.AuditEngine
import pro.sitescope.app.CrawlerEngine
import pro.sitescope.app.DataStore
import pro.sitescope.app.Models

class SiteHistoryItem(val target: String, val host: String, val score: Int, val time: Long, val full: Boolean)

class SiteViewModel(app: Application) : AndroidViewModel(app) {
    var input by mutableStateOf("")
    var fullCrawl by mutableStateOf(false)
    var maxPages by mutableStateOf(25)
    var running by mutableStateOf(false)
    var stage by mutableStateOf("")
    var stageDetail by mutableStateOf("")
    var report by mutableStateOf<Report?>(null)
    var history by mutableStateOf(listOf<SiteHistoryItem>())
    var monitored by mutableStateOf(setOf<String>())
    var watchChanges by mutableStateOf(false)

    @Volatile private var cancelled = false
    private var job: Job? = null

    init { refreshHistory(); monitored = SiteMonitor.targets(app) }

    fun refreshHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = try {
                val seen = HashSet<String>()
                DataStore(getApplication<Application>()).audits().filter { it.host.isNotEmpty() && seen.add(it.host + it.fullCrawl) }.take(30)
                    .map { SiteHistoryItem(it.target, it.host, it.score, it.timestamp, it.fullCrawl) }
            } catch (e: Exception) { emptyList() }
            withContext(Dispatchers.Main) { history = list }
        }
    }

    fun run(target: String = input) {
        val text = target.trim()
        if (text.isEmpty() || running) return
        input = text; running = true; cancelled = false; report = null
        stage = "Открываем сайт"; stageDetail = SiteExtras.normalize(text)
        val full = fullCrawl; val pages = maxPages
        job = viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                val store = DataStore(getApplication<Application>())
                val audit: Models.AuditResult = if (full) {
                    post("Обходим страницы", "")
                    val config = CrawlerEngine.Config().apply { maxPages = pages }
                    CrawlerEngine.crawl(text, config, object : CrawlerEngine.ProgressListener {
                        override fun onProgress(crawled: Int, maxPages: Int, discovered: Int, currentUrl: String?) {
                            post("Обходим страницы: $crawled из $maxPages", currentUrl ?: "")
                        }
                        override fun isCancelled(): Boolean = cancelled
                    })
                } else {
                    post("Проверяем страницу, домен и почту", "HTTP · DNS · сертификат · технологии")
                    AuditEngine.run(text)
                }
                if (cancelled) null else {
                    post("Замеряем скорость и защиту", "Цепочка переходов · заголовки · ссылки")
                    val extras = SiteExtras.collect(text, checkLinks = true, maxLinks = 30) { cancelled }
                    val previous = try { store.audits().firstOrNull { it.host == audit.host && it.timestamp < audit.timestamp }?.score } catch (e: Exception) { null }
                    val built = SiteReportBuilder.build(audit, extras, previous)
                    audit.score = built.score
                    try { store.saveAudit(audit) } catch (e: Exception) { }
                    built
                }
            } catch (e: Throwable) { null }
            withContext(Dispatchers.Main) {
                running = false
                if (!cancelled) { report = result; refreshHistory() }
            }
        }
    }

    private fun post(title: String, detail: String) { stage = title; stageDetail = detail }

    fun cancel() { cancelled = true; running = false; job?.cancel() }
    fun clear() { report = null }

    fun toggleMonitor(target: String) {
        monitored = SiteMonitor.toggle(getApplication<Application>(), target)
        watchChanges = SiteMonitor.watchesChanges(getApplication<Application>(), target)
    }

    fun loadWatchChanges(target: String) { watchChanges = SiteMonitor.watchesChanges(getApplication<Application>(), target) }

    fun toggleWatchChanges(target: String) {
        watchChanges = !watchChanges
        SiteMonitor.setWatchesChanges(getApplication<Application>(), target, watchChanges)
    }
}
