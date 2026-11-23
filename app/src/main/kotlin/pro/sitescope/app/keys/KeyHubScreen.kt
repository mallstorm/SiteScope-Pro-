package pro.sitescope.app.keys

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.sitescope.app.XrayProfileStore
import pro.sitescope.app.HostsVpnService
import kotlin.coroutines.coroutineContext
import pro.sitescope.app.ui.*
import pro.sitescope.app.vpn.Countries
import pro.sitescope.app.vpn.VpnShare
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val TYPE_LABEL = mapOf("vless" to "VLESS", "vmess" to "VMess", "trojan" to "Trojan", "ss" to "Shadowsocks", "socks" to "SOCKS5", "http" to "HTTP",
    "hysteria2" to "Hysteria2", "tuic" to "TUIC", "wireguard" to "WireGuard",
    "openvpn" to "OpenVPN", "anytls" to "AnyTLS")
private val ANON_LABEL = mapOf(1 to "прозрачный", 2 to "анонимный", 3 to "элитный")

class KeyHubViewModel(app: Application) : AndroidViewModel(app) {
    private val context: Context get() = getApplication()
    var items by mutableStateOf<List<KeyItem>>(emptyList())
    var blocked by mutableStateOf<List<BlockedKey>>(emptyList())
    var version by mutableIntStateOf(0)
    var running by mutableStateOf(false)
    var stage by mutableStateOf("")
    var sitesFound by mutableIntStateOf(0); var sitesDone by mutableIntStateOf(0); var pages by mutableIntStateOf(0)
    var keysFound by mutableIntStateOf(0); var checked by mutableIntStateOf(0); var toCheck by mutableIntStateOf(0)
    var log by mutableStateOf<List<String>>(emptyList())
    var finishedNote by mutableStateOf("")
    var pendingCount by mutableIntStateOf(0)
    var uncheckedCount by mutableIntStateOf(0)
    var duplicateCount by mutableIntStateOf(0)
    var queueDone by mutableIntStateOf(0)
    var queueTotal by mutableIntStateOf(0)
    var loading by mutableStateOf(true)
    var crawling by mutableStateOf(false)
    var originStats by mutableStateOf<Map<String, StatView>>(emptyMap())
    var typeStats by mutableStateOf<Map<String, StatView>>(emptyMap())
    var aliveNow by mutableIntStateOf(0); var slowNow by mutableIntStateOf(0); var deferredNow by mutableIntStateOf(0)
    private val pendingQueue = ConcurrentHashMap<String, KeyItem>()
    private val known = ConcurrentHashMap.newKeySet<String>()
    private var job: Job? = null

    init {
        // База может быть огромной: читаем её в фоне, чтобы экран открывался мгновенно.
        viewModelScope.launch(Dispatchers.Default) {
            try {
                val list = KeyBlocklistStore.load(context)
                val excluded = list.mapTo(HashSet()) { it.fingerprint }
                val loaded = KeyStore.load(context).filter { it.fp !in excluded }
                for (key in KeyCandidateStore.load(context)) if (key.fp !in excluded) pendingQueue.putIfAbsent(key.fp, key)
                // Старые сборки сохраняли непроверенные профили в рабочем списке: переносим их в очередь.
                for (key in loaded) if (!key.alive && key.validation == XrayKeyValidator.UNTESTED && !pendingQueue.containsKey(key.fp)) {
                    key.checkedAt = 0L
                    pendingQueue[key.fp] = key
                }
                known.addAll(loaded.map { it.fp })
                blocked = list
                items = loaded
                pendingCount = pendingQueue.size
                uncheckedCount = pendingQueue.values.count { it.checkedAt <= 0L }
            } catch (_: Exception) {
            } catch (_: OutOfMemoryError) {
            } finally { loading = false }
        }
    }

    private fun saveQueue() {
        KeyCandidateStore.save(context, pendingQueue.values.toList())
        pendingCount = pendingQueue.size
        uncheckedCount = pendingQueue.values.count { it.checkedAt <= 0L }
    }

    private val lock = Any()
    private fun say(t: String) { synchronized(lock) { log = (listOf(t) + log).take(40) } }
    private fun touch() { synchronized(lock) { version++ } }
    private val cChecked = AtomicInteger()
    private val cPages = AtomicInteger(); private val cSites = AtomicInteger(); private val cSitesTotal = AtomicInteger(); private val cKeys = AtomicInteger()
    @Volatile private var lastPub = 0L
    /** Счётчики сбора на экране обновляются не чаще раза в 0,4 с. */
    private fun publish(force: Boolean = false) {
        val n = android.os.SystemClock.elapsedRealtime()
        if (!force && n - lastPub < 400) return
        lastPub = n; pages = cPages.get(); sitesDone = cSites.get(); sitesFound = cSitesTotal.get(); keysFound = cKeys.get()
    }

    fun stop() { job?.cancel(); SingBoxValidator.cancel(); stage = "Остановка…"; touch() }

    // Clearing the visible database never erases the persistent deny-list.
    fun clearAll() {
        items = emptyList()
        pendingQueue.clear(); known.clear()
        KeyStore.clear(context)
        KeyCandidateStore.clear(context)
        pendingCount = 0; uncheckedCount = 0
        touch()
    }

    private fun quarantine(candidates: List<KeyItem>, reason: (KeyItem) -> String): Int {
        if (candidates.isEmpty()) return 0
        val entries = blocked.associateBy { it.fingerprint }.toMutableMap()
        var added = 0
        for (key in candidates) {
            val fingerprint = KeyBlocklistStore.fingerprint(key.link)
            if (fingerprint !in entries) {
                entries[fingerprint] = BlockedKey(key, reason(key))
                added++
            }
        }
        // Persist tombstones first, to prevent resurrection on partial failure.
        KeyBlocklistStore.save(context, entries.values.toList())
        blocked = entries.values.toList()
        val rejected = entries.keys
        items = items.filterNot { it.fp in rejected }
        known.removeAll(rejected)
        for (fingerprint in rejected) pendingQueue.remove(fingerprint)
        saveQueue()
        KeyStore.save(context, items)
        touch()
        return added
    }

    /** Trash means 'exclude permanently', not just remove the card from UI. */
    fun remove(ids: Set<String>) {
        if (running || ids.isEmpty()) return
        quarantine(items.filter { it.id in ids }) { "Исключён вручную" }
    }

    /** Imports private OpenVPN/WireGuard profiles without making them public. */
    fun importLocalProfile(uri: Uri, done: (String) -> Unit) {
        if (running) return
        viewModelScope.launch(Dispatchers.IO) {
            val result = try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (output.size() <= 262144) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                    }
                    output.toByteArray()
                } ?: throw IllegalArgumentException("Не удалось прочитать файл")
                require(bytes.size <= 262144) { "Файл больше 256 КБ" }
                val profile = SingBoxConfig.importProfile(String(bytes, Charsets.UTF_8))
                val key = KeyItem(profile.link, profile.type.uppercase() + " · " + profile.host,
                    profile.type, profile.host, profile.port, "Локальный файл", System.currentTimeMillis())
                val hash = KeyBlocklistStore.fingerprint(key.link)
                if (blocked.any { it.fingerprint == hash }) "Этот профиль уже находится в чёрном списке"
                else if (items.any { KeyBlocklistStore.fingerprint(it.link) == hash }) "Профиль уже добавлен"
                else {
                    items = items + key
                    KeyStore.save(context, items)
                    touch()
                    "Импортирован ${profile.type.uppercase()} — выполните проверку"
                }
            } catch (error: Exception) {
                "Импорт не выполнен: " + (error.message ?: "неизвестная ошибка")
            }
            withContext(Dispatchers.Main) { done(result) }
        }
    }

    /** Only real routed traffic can restore a blacklisted profile. */
    fun recheckBlocked(ids: Set<String>) {
        if (running) return
        val candidates = blocked.filter { ids.isEmpty() || it.fingerprint in ids }
        if (candidates.isEmpty()) return
        running = true
        finishedNote = ""
        stage = "Проверка чёрного списка"
        cChecked.set(0); checked = 0; toCheck = candidates.size
        job = viewModelScope.launch(Dispatchers.Default) {
            val remaining = blocked.associateBy { it.fingerprint }.toMutableMap()
            val recovered = ArrayList<KeyItem>()
            var attempts = 0
            try {
                val timeout = KeySettings.timeoutMs(context)
                val checkTls = KeySettings.tlsCheck(context)
                for (entry in candidates) {
                    coroutineContext.ensureActive()
                    val key = entry.item
                    key.ms = -1L
                    key.validation = 0; key.validationMs = -1L
                    key.validationAt = 0L; key.validationNote = ""
                    try {
                        KeyChecker.check(key, timeout, checkTls)
                        if (!key.proxy && (key.ms >= 0 || key.ms == -3L) &&
                            (key.type in SingBoxConfig.types || !key.udp)) {
                            val result = withContext(Dispatchers.IO) {
                                if (key.type in SingBoxConfig.types)
                                    SingBoxValidator.check(context, key.link, timeout)
                                else XrayKeyValidator.check(context, key.link, timeout)
                            }
                            key.validation = result.status
                            key.validationMs = result.delayMs
                            key.validationAt = System.currentTimeMillis()
                            key.validationNote = result.reason
                        } else if (key.udp) {
                            key.validation = XrayKeyValidator.INCONCLUSIVE
                            key.validationNote = "Полная UDP-проверка пока недоступна"
                        }
                    } catch (cancel: kotlinx.coroutines.CancellationException) {
                        throw cancel
                    } catch (error: Exception) {
                        key.validation = XrayKeyValidator.INCONCLUSIVE
                        key.validationNote = "Ошибка проверки: " + (error.message ?: "нет ответа")
                    }
                    if (key.alive) {
                        remaining.remove(entry.fingerprint)
                        recovered += key
                    } else {
                        remaining[entry.fingerprint] = entry.copy(lastCheckAt = System.currentTimeMillis())
                    }
                    attempts++
                    cChecked.incrementAndGet(); publish(true)
                    if (attempts % 10 == 0) {
                        KeyBlocklistStore.save(context, remaining.values.toList())
                        blocked = remaining.values.toList()
                    }
                }
                if (recovered.isNotEmpty()) KeyChecker.geo(context, recovered)
                finishedNote = "Проверено ${attempts} · восстановлено ${recovered.size} · осталось ${remaining.size}"
                stage = "Готово"
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                stage = "Остановлено"
                throw cancel
            } catch (error: Exception) {
                finishedNote = "Ошибка проверки: " + (error.message ?: "неизвестно")
                stage = "Ошибка"
            } finally {
                // Also keep progress after cancellation of a long batch.
                try {
                    KeyBlocklistStore.save(context, remaining.values.toList())
                    blocked = remaining.values.toList()
                    if (recovered.isNotEmpty()) {
                        items = (items + recovered).distinctBy { it.fp }; known.addAll(recovered.map { it.fp })
                        KeyStore.save(context, items)
                    }
                } finally {
                    running = false; publish(true)
                }
            }
        }
    }

    // ------------------------------------------------------------------ Конвейер

    private class Src(val url: String, val label: String, val kind: String)
    private val settledQ = java.util.concurrent.ConcurrentLinkedQueue<KeyItem>()
    private val stateLock = Any()
    private var pipe: KeyPipeline? = null
    private var lastDisk = 0L
    private var changed = false

    private fun resetKey(k: KeyItem) {
        k.checkedAt = 0L; k.ms = -1L; k.tls = 0; k.anon = 0; k.https = 0
        k.validation = XrayKeyValidator.UNTESTED; k.validationMs = -1L; k.validationAt = 0L; k.validationNote = ""
    }

    private fun persist() {
        saveQueue()
        KeyStore.save(context, items)
        lastDisk = android.os.SystemClock.elapsedRealtime()
        changed = false
    }

    /** Раз в ~0,7 с: готовые ключи попадают в список, остальные — в резерв, счётчики обновляются. */
    private suspend fun flush(final: Boolean) {
        val done = ArrayList<KeyItem>()
        var e = settledQ.poll()
        while (e != null) { done += e; e = settledQ.poll() }
        val good = done.filter { it.alive }
        val bad = done.filter { !it.alive }
        if (good.isNotEmpty()) {
            val noCountry = good.filter { it.country.isEmpty() }
            if (noCountry.isNotEmpty()) try { KeyChecker.geo(context, noCountry) } catch (_: Exception) { }
        }
        var invalid: List<KeyItem> = emptyList()
        synchronized(stateLock) {
            if (good.isNotEmpty()) {
                val fresh = good.filter { known.add(it.fp) }
                for (k in good) pendingQueue.remove(k.fp)
                if (fresh.isNotEmpty()) items = items + fresh
            }
            if (bad.isNotEmpty()) {
                val drop = HashSet<String>()
                for (k in bad) { pendingQueue[k.fp] = k; if (known.remove(k.fp)) drop += k.fp }
                if (drop.isNotEmpty()) items = items.filterNot { it.fp in drop }
                // В чёрный список — только заведомо некорректные и неподдерживаемые конфигурации.
                invalid = bad.filter { k ->
                    k.validation == XrayKeyValidator.FAILED &&
                        (k.validationNote.contains("Некорректн", ignoreCase = true) || k.validationNote.contains("не поддержива", ignoreCase = true))
                }
            }
            if (done.isNotEmpty()) changed = true
        }
        if (invalid.isNotEmpty()) quarantine(invalid) { it.validationNote }
        pipe?.let { p ->
            originStats = p.byOrigin.mapValues { it.value.view() }
            typeStats = p.byType.mapValues { it.value.view() }
            checked = p.settled.get(); toCheck = p.found.get() - p.deferred.get()
            aliveNow = p.alive.get(); slowNow = p.slow.get(); deferredNow = p.deferred.get()
        }
        pendingCount = pendingQueue.size
        uncheckedCount = pendingQueue.values.count { it.checkedAt <= 0L }
        publish(true)
        if (done.isNotEmpty() || final) touch()
        if (final || (changed && android.os.SystemClock.elapsedRealtime() - lastDisk > 45_000)) persist()
    }

    /** Запускает конвейер: [feed] подаёт адреса (из сбора или из очереди), проверка идёт параллельно. */
    private suspend fun runPipeline(feed: suspend (route: (KeyItem, String) -> Unit) -> Unit): KeyPipeline {
        val c = context
        val timeout = KeySettings.timeoutMs(c)
        val cfg = PipelineConfig(KeySettings.tcpThreads(c), timeout.coerceAtMost(KeyQualityPolicy.CONNECT_TIMEOUT_MS),
            KeySettings.xrayThreads(c), KeySettings.singThreads(c), KeySettings.proxyThreads(c), timeout.coerceAtLeast(2000),
            KeySettings.proxyLimit(c), KeySettings.tlsCheck(c))
        val p = KeyPipeline(c, cfg, ::say) { settledQ.add(it) }
        pipe = p
        originStats = emptyMap(); typeStats = emptyMap(); aliveNow = 0; slowNow = 0; deferredNow = 0; checked = 0; toCheck = 0
        kotlinx.coroutines.coroutineScope {
            val flusher = launch(Dispatchers.IO) { while (true) { kotlinx.coroutines.delay(700); flush(false) } }
            try {
                p.execute(feed)
            } finally {
                flusher.cancel(); flusher.join()
                withContext(kotlinx.coroutines.NonCancellable) { flush(true) }
            }
        }
        return p
    }

    private fun summary(p: KeyPipeline): String =
        "Найдено ${p.found.get()} · проверено ${p.settled.get()} · рабочих ${p.alive.get()} · медленных ${p.slow.get()}" +
            (if (p.deferred.get() > 0) " · прокси в резерве ${p.deferred.get()}" else "")

    private fun launchRun(stageName: String, body: suspend () -> Unit) {
        running = true; finishedNote = ""; stage = stageName; crawling = false
        job = viewModelScope.launch(Dispatchers.Default) {
            try {
                body()
                stage = "Готово"
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                stage = "Остановлено"
                throw cancel
            } catch (error: OutOfMemoryError) {
                finishedNote = "Не хватило памяти: выберите режим «Щадящая» в настройках"; stage = "Ошибка"
            } catch (error: Exception) {
                finishedNote = "Ошибка: " + (error.message ?: error.javaClass.simpleName); stage = "Ошибка"
            } finally {
                try { persist() } catch (_: Exception) { }
                crawling = false; running = false; publish(true); touch()
            }
        }
    }

    /** Повторная проверка всего резерва: непроверенные, медленные и спорные ключи. */
    fun retryQueued() {
        if (running || loading || pendingQueue.isEmpty()) return
        cPages.set(0); cSites.set(0); cSitesTotal.set(0); cKeys.set(0)
        sitesFound = 0; sitesDone = 0; pages = 0; keysFound = 0
        launchRun("Проверка резерва") {
            val list = pendingQueue.values.toList()
            for (k in list) resetKey(k)
            say("Резерв: ${list.size} адресов")
            val p = runPipeline { route -> for (k in list) route(k, "queue") }
            finishedNote = summary(p)
        }
    }

    /** Полная перепроверка выбранных (или всех) ключей. */
    fun recheck(ids: Set<String>) {
        if (running || loading) return
        val list = items.filter { ids.isEmpty() || it.id in ids }
        if (list.isEmpty()) return
        cPages.set(0); cSites.set(0); cSitesTotal.set(0); cKeys.set(0)
        sitesFound = 0; sitesDone = 0; pages = 0; keysFound = 0
        launchRun("Перепроверка") {
            for (k in list) { resetKey(k); k.country = "" }
            val p = runPipeline { route -> for (k in list) route(k, "recheck") }
            finishedNote = summary(p)
        }
    }

    /** Комбайн: источники скачиваются, а найденные адреса сразу проверяются — без пауз между этапами. */
    fun start(query: String, kindIds: Set<String>, sources: List<String>) {
        if (running || loading) return
        val c = context
        KeySettings.put(c, "query", query)
        KeySettings.put(c, "sources", sources.joinToString("\n"))
        KeySettings.put(c, "kinds", kindIds.joinToString(","))
        val chosenKinds = kindIds.mapNotNull { KeySources.byId(it) }
        val types = chosenKinds.flatMap { it.types }.toSet().ifEmpty { KeySources.XRAY_TYPES }
        val followSubs = "sub" in kindIds
        val allowV6 = KeySettings.ipv6(c)
        KeyGuard.tripped = false
        duplicateCount = 0
        cPages.set(0); cSites.set(0); cSitesTotal.set(0); cKeys.set(0)
        sitesFound = 0; sitesDone = 0; pages = 0; keysFound = 0
        log = emptyList()
        launchRun("Сбор и проверка") {
            crawling = true
            val excluded = blocked.mapTo(HashSet()) { it.fingerprint }
            val seen = ConcurrentHashMap.newKeySet<String>()
            seen.addAll(excluded); seen.addAll(pendingQueue.keys); seen.addAll(known)
            val duplicates = AtomicInteger(); val oldMatches = AtomicInteger()
            val subSeen = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
            val maxCollect = KeySettings.maxCollect(c)
            val pagesCap = KeySettings.pagesPerSite(c); val depth = KeySettings.depth(c)
            val p = runPipeline { route ->
                kotlinx.coroutines.coroutineScope {
                    val sites = kotlinx.coroutines.channels.Channel<Src>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                    val queued = ConcurrentHashMap.newKeySet<String>()
                    fun norm(s: String) = if (s.startsWith("http")) s else if (s.startsWith("t.me/") || s.startsWith("@")) "https://t.me/s/" + s.removePrefix("@").removePrefix("t.me/").removePrefix("s/") else "https://$s"
                    fun offer(url: String, label: String, kind: String) {
                        if (queued.add(url)) { cSitesTotal.incrementAndGet(); sites.trySend(Src(url, label, kind)) }
                    }
                    for (s in sources) { val u = norm(s); offer(u, KeySources.label(u), "own") }
                    for (k in chosenKinds) for (u in k.sources) offer(u, KeySources.label(u), k.id)
                    say("Источников: ${queued.size} · проверка идёт параллельно со сбором")
                    // Адреса, которые не успели проверить в прошлый раз, идут на проверку сразу.
                    for (key in pendingQueue.values.toList()) if (key.checkedAt <= 0L && key.type in types) route(key, "queue")
                    // Поиск сайтов идёт одновременно со скачиванием готовых списков.
                    val searchJob = launch(Dispatchers.IO) {
                        if (!KeySettings.useSearch(c)) return@launch
                        try {
                            val max = KeySettings.maxSites(c)
                            val hits = KeyCrawler.search(query.trim(), max, types)
                            say("Поисковики: найдено ${hits.size} ссылок")
                            val hosts = HashSet<String>()
                            var added = 0
                            for (hit in hits) {
                                val host = KeyCrawler.hostOf(hit)
                                if (host.isNotEmpty() && hosts.add(host) && queued.none { KeyCrawler.hostOf(it) == host }) { offer(hit, host, "search"); added++ }
                                if (added >= max) break
                            }
                        } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel } catch (_: Exception) { }
                    }
                    val crawlers = List(KeySettings.parallelSites(c)) {
                        launch {
                            for (src in sites) {
                                try {
                                    ensureActive()
                                    if (KeyGuard.tripped) continue
                                    var own = 0
                                    try {
                                        KeyCrawler.crawl(src.url, pagesCap, depth, types, KeySources.proxyTypesFor(src.url, types), followSubs, subSeen,
                                            { cPages.incrementAndGet(); publish() }) { keys ->
                                            for (link in keys) {
                                                if (cKeys.get() >= maxCollect) break
                                                val rawFp = KeyBlocklistStore.fingerprint(link)
                                                if (rawFp in seen) {
                                                    if (rawFp in known || pendingQueue.containsKey(rawFp)) oldMatches.incrementAndGet() else duplicates.incrementAndGet()
                                                    continue
                                                }
                                                val parsed = KeyCrawler.parse(link, src.label, allowV6) ?: continue
                                                // socks:// и socks5:// — одна и та же конфигурация.
                                                val fp = parsed.fp
                                                if (!seen.add(fp)) { duplicates.incrementAndGet(); seen.add(rawFp); continue }
                                                if (rawFp != fp) seen.add(rawFp)
                                                pendingQueue.putIfAbsent(fp, parsed)
                                                own++; cKeys.incrementAndGet()
                                                route(parsed, src.kind)
                                            }
                                            publish()
                                        }
                                    } catch (cancel: kotlinx.coroutines.CancellationException) { throw cancel
                                    } catch (_: Exception) { say("${src.label}: ошибка")
                                    } catch (_: OutOfMemoryError) { KeyGuard.tripped = true }
                                    if (own > 0) say("${src.label}: +$own")
                                } finally { cSites.incrementAndGet(); publish() }
                            }
                        }
                    }
                    searchJob.join()
                    sites.close()
                    crawlers.forEach { it.join() }
                    crawling = false; stage = "Доделываю очередь проверки"
                    if (KeyGuard.tripped) say("Мало памяти: сбор остановлен досрочно")
                    duplicateCount = duplicates.get()
                    say("Новых: ${cKeys.get()} · уже в базе: ${oldMatches.get()} · дубликатов: ${duplicates.get()}")
                }
            }
            finishedNote = summary(p)
        }
    }

    fun connectSingBox(key: KeyItem) {
        if (running || key.type !in SingBoxConfig.types || !key.alive) return
        val intent = Intent(context, HostsVpnService::class.java)
            .setAction(HostsVpnService.ACTION_START)
            .putExtra(HostsVpnService.EXTRA_MODE, HostsVpnService.MODE_SINGBOX)
            .putExtra(HostsVpnService.EXTRA_SINGBOX_ID, key.id)
        context.getSharedPreferences(HostsVpnService.PREFS, Context.MODE_PRIVATE)
            .edit().putString(HostsVpnService.EXTRA_SINGBOX_ID, key.id).apply()
        context.startForegroundService(intent)
    }

    fun addToServers(list: List<KeyItem>, done: (Int) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val store = XrayProfileStore(context); var n = 0
            for (k in list) try { KeyExport.profileLink(k)?.let { store.add(it); n++ } } catch (e: Exception) { }
            withContext(Dispatchers.Main) { done(n) }
        }
    }
}

private fun countryName(code: String) = if (code.length != 2) "Не определена" else Locale("", code).getDisplayCountry(Locale("ru")).replaceFirstChar { it.uppercase() }.ifEmpty { code }

@Composable
fun KeyHubScreen(onBack: () -> Unit, vm: KeyHubViewModel = viewModel()) {
    var tab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    var pending by remember { mutableStateOf<KeyItem?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val key = pending
        pending = null
        if (result.resultCode == Activity.RESULT_OK && key != null) vm.connectSingBox(key)
        else toast(context, "Разрешение Android VPN не предоставлено")
    }
    fun connect(key: KeyItem) {
        pending = key
        val prepare = try { VpnService.prepare(context) } catch (e: Exception) {
            toast(context, "Не удалось запросить разрешение VPN")
            return
        }
        if (prepare != null) permission.launch(prepare)
        else { pending = null; vm.connectSingBox(key) }
    }
    Column(Modifier.fillMaxSize()) {
        AppHeader("Ключи и прокси", "Xray + sing-box · VLESS, Hysteria2, TUIC, WG, OpenVPN, AnyTLS", onBack = onBack) {
            HeaderChips(listOf("Поиск", "Ключи ${vm.items.size}", "Чёрный список ${vm.blocked.size}", "Настройки"), tab, { tab = it }, listOf(Icons.Rounded.Search, Icons.Rounded.VpnKey, Icons.Rounded.Block, Icons.Rounded.Tune))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> SearchTab(vm, onShow = { tab = 1 })
                1 -> KeysTab(vm, onConnect = ::connect)
                2 -> BlacklistTab(vm)
                else -> SettingsTab(vm)
            }
        }
    }
}

// ------------------------------------------------------------------ Комбайн

private fun kindIcon(id: String): androidx.compose.ui.graphics.vector.ImageVector = when (id) {
    "xray" -> Icons.Rounded.VpnKey; "udp" -> Icons.Rounded.Bolt; "socks" -> Icons.Rounded.Hub; "http" -> Icons.Rounded.Language
    "elite" -> Icons.Rounded.Verified; "anytls" -> Icons.Rounded.Lock; else -> Icons.Rounded.RssFeed
}

private fun vpnActive(c: Context): Boolean = try {
    val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true
} catch (_: Exception) { false }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchTab(vm: KeyHubViewModel, onShow: () -> Unit) {
    val context = LocalContext.current
    val t = tones
    var query by remember { mutableStateOf(KeySettings.lastQuery(context)) }
    var sources by remember { mutableStateOf(KeySettings.lastSources(context)) }
    var picked by remember { mutableStateOf(KeySettings.kinds(context).ifEmpty { setOf("xray") }) }
    var v6 by remember { mutableStateOf(KeySettings.ipv6(context)) }
    var sheet by remember { mutableStateOf(false) }
    val vpn = remember(vm.running, vm.version) { vpnActive(context) }
    val profilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importLocalProfile(uri) { message -> toast(context, message) }
    }
    fun go() { if (!vm.running && !vm.loading && picked.isNotEmpty()) vm.start(query, picked, sources.lines().map { it.trim() }.filter { it.isNotEmpty() }) }
    val foundTotal = vm.originStats.values.sumOf { it.found }
    val waiting = (foundTotal - vm.deferredNow - vm.checked).coerceAtLeast(0)
    Column(Modifier.fillMaxSize()) {
        // --- Шапка комбайна: состояние, запуск, общие счётчики
        SoftCard(modifier = Modifier.padding(horizontal = 12.dp).padding(top = 10.dp), padding = 12.dp,
            color = if (vm.running) t.infoSoft else MaterialTheme.colorScheme.surfaceContainerLowest) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(if (vm.running) Icons.Rounded.Sync else Icons.Rounded.TravelExplore, t.info, t.infoSoft, 44.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (vm.loading) "Загрузка базы…" else if (vm.running) vm.stage.ifEmpty { "Работаю" } else "Комбайн ключей", style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(
                        if (vm.loading) "Читаю сохранённые ключи в фоне"
                        else if (vm.running) (if (vm.crawling) "Источников ${vm.sitesDone}/${vm.sitesFound} · страниц ${vm.pages} · проверка идёт параллельно" else "Сбор завершён, доделываю проверку")
                        else "Сбор и проверка идут одновременно, по типам",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                if (vm.running) Button(onClick = { vm.stop() }, colors = ButtonDefaults.buttonColors(containerColor = t.bad)) { Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Стоп") }
                else Button(onClick = { go() }, enabled = picked.isNotEmpty() && !vm.loading) { Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Запуск") }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Stat("Найдено", foundTotal.toString(), Modifier.weight(1f))
                Stat("Проверено", vm.checked.toString(), Modifier.weight(1f))
                Stat("Рабочих", vm.aliveNow.toString(), Modifier.weight(1f), t.good)
                Stat("Ждут", waiting.toString(), Modifier.weight(1f), t.warn)
            }
            if (vm.running) {
                if (foundTotal > 0) LinearProgressIndicator(progress = { (vm.checked / foundTotal.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
            } else if (vm.finishedNote.isNotEmpty()) {
                Row(Modifier.padding(top = 8.dp).clickable { onShow() }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.CheckCircle, null, tint = t.good, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                    Text(vm.finishedNote, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.ChevronRight, null)
                }
            }
            val last = vm.log.firstOrNull()
            if (last != null && (vm.running || vm.finishedNote.isEmpty())) Text(last, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            if (vpn) Text("Включён VPN — Xray и sing-box ключи проверить нельзя. Отключите его.", style = MaterialTheme.typography.labelSmall, color = t.bad, modifier = Modifier.padding(top = 6.dp))
        }
        // --- Живая лента по протоколам
        if (vm.typeStats.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            vm.typeStats.entries.sortedBy { TYPE_LABEL.keys.indexOf(it.key) }.forEach { (type, st) ->
                Pill((TYPE_LABEL[type] ?: type) + " ${st.alive}/${st.checked}/${st.found}", if (st.alive > 0) t.good else t.info, if (st.alive > 0) t.goodSoft else t.infoSoft)
            }
        } else Spacer(Modifier.height(8.dp))
        // --- Категории: выбор и живые счётчики (прокручивается только сетка)
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Что собирать", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            TextButton(onClick = { picked = if (picked.size == KeySources.kinds.size) emptySet() else KeySources.kinds.map { it.id }.toSet() }, enabled = !vm.running) {
                Text(if (picked.size == KeySources.kinds.size) "Сбросить" else "Выбрать всё")
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            KeySources.kinds.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { k ->
                        val on = k.id in picked
                        val st = vm.originStats[k.id]
                        SoftCard(modifier = Modifier.weight(1f), padding = 10.dp, color = if (on) t.infoSoft else MaterialTheme.colorScheme.surfaceContainerLowest,
                            onClick = { if (!vm.running) picked = if (on) picked - k.id else picked + k.id }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconBadge(kindIcon(k.id), if (on) t.info else MaterialTheme.colorScheme.onSurfaceVariant, if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer, 34.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(k.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, modifier = Modifier.weight(1f))
                                if (on) Icon(Icons.Rounded.CheckCircle, null, tint = t.info, modifier = Modifier.size(18.dp))
                            }
                            Text(if (st == null) k.sub else "найдено ${st.found} · ✓ ${st.alive}", style = MaterialTheme.typography.labelSmall,
                                color = if (st != null && st.alive > 0) t.good else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                            if (st != null && st.found > 0) LinearProgressIndicator(progress = { (st.checked / st.found.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                            else Text("${k.sources.size} источников", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            val extra = listOf("own" to "Свои источники", "search" to "Поисковики", "queue" to "Резерв").mapNotNull { (id, name) -> vm.originStats[id]?.let { name to it } }
            if (extra.isNotEmpty()) Text(extra.joinToString(" · ") { (name, st) -> "$name: ${st.found} (✓ ${st.alive})" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
        }
        // --- Нижняя панель
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { sheet = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Tune, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Источники", maxLines = 1) }
                OutlinedButton(onClick = { vm.retryQueued() }, enabled = !vm.running && !vm.loading && vm.pendingCount > 0, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Refresh, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Резерв ${vm.pendingCount}", maxLines = 1) }
                FilledTonalButton(onClick = onShow, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.VpnKey, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Ключи ${vm.items.size}", maxLines = 1) }
            }
        }
    }
    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Запрос и источники", style = MaterialTheme.typography.titleLarge)
            Text("Оба поля можно оставить пустыми: готовые списки выбранных категорий скачиваются сами.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BigField(query, { query = it }, "Запрос для поиска сайтов (необязательно)", keyboard = KeyboardType.Text, leading = Icons.Rounded.Search)
            BigField(sources, { sources = it }, "Свои источники: сайты, @канал Telegram, ссылки на списки (по строке)", singleLine = false, minLines = 2, keyboard = KeyboardType.Uri, leading = Icons.Rounded.Link, mono = true)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Включать IPv6"); Text("Нужно, только если у вас есть IPv6", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(v6, { v6 = it; KeySettings.put(context, "ipv6", it) })
            }
            OutlinedButton(onClick = { profilePicker.launch(arrayOf("*/*")) }, enabled = !vm.running) {
                Icon(Icons.Rounded.FileOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Импорт WireGuard .conf / OpenVPN .ovpn")
            }
            Text("Прокси проверяются реальным запросом через них, ключи — через ядра Xray и sing-box. Чужие серверы видят ваш трафик: не используйте их для входов и платежей.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}

// ------------------------------------------------------------------ Ключи

private class KeysView(val all: List<KeyItem> = emptyList(), val alive: List<KeyItem> = emptyList(), val countries: List<Pair<String, Int>> = emptyList(),
                       val types: List<Pair<String, Int>> = emptyList(), val shown: List<KeyItem> = emptyList())

@Composable
private fun KeysTab(vm: KeyHubViewModel, onConnect: (KeyItem) -> Unit) {
    val context = LocalContext.current
    val t = tones
    var country by remember { mutableStateOf<String?>(null) }
    var protocol by remember { mutableStateOf<String?>(null) }
    var elite by remember { mutableStateOf(false) }
    var httpsOnly by remember { mutableStateOf(false) }
    var v6Only by remember { mutableStateOf(false) }
    var onlyAlive by remember { mutableStateOf(false) }
    var maxMs by remember { mutableIntStateOf(0) }
    var sort by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var export by remember { mutableStateOf(false) }
    var bestDialog by remember { mutableStateOf(false) }

    // Фильтрация и сортировка тысяч ключей идут в фоне, чтобы список не подвешивал экран.
    val view by produceState(KeysView(), vm.version, vm.items, country, protocol, onlyAlive, maxMs, sort, elite, httpsOnly, v6Only) {
        val src = vm.items
        value = withContext(Dispatchers.Default) {
            val all = src
            val alive = all.filter { it.alive }
            val countries = alive.groupBy { it.country }.map { it.key to it.value.size }.sortedByDescending { it.second }
            val types = all.groupBy { it.type }.map { it.key to it.value.size }.sortedBy { TYPE_LABEL.keys.indexOf(it.first) }
            val shown = all.filter { (!onlyAlive || it.alive) && (country == null || it.country == country) && (protocol == null || it.type == protocol) && (maxMs == 0 || (it.alive && it.verifiedPing in 0..maxMs.toLong())) &&
                (!elite || it.anon == 3) && (!httpsOnly || it.https == 1) && (!v6Only || it.v6) }
                .let { l -> when (sort) { 0 -> l.sortedWith(compareBy<KeyItem> { if (it.verifiedPing < 0) Long.MAX_VALUE else it.verifiedPing }); 1 -> l.sortedWith(compareBy<KeyItem>({ it.country.ifEmpty { "ZZ" } }, { if (it.verifiedPing < 0) Long.MAX_VALUE else it.verifiedPing })); else -> l.sortedByDescending { it.foundAt } } }
            KeysView(all, alive, countries, types, shown)
        }
    }
    val all = view.all; val alive = view.alive; val countries = view.countries; val types = view.types; val shown = view.shown
    val chosen = all.filter { it.id in selected }

    Column(Modifier.fillMaxSize()) {
        // фиксированная верхняя часть
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat("Всего", all.size.toString(), Modifier.weight(1f)); Stat("Рабочих", alive.size.toString(), Modifier.weight(1f), t.good); Stat("Стран", countries.count { it.first.length == 2 }.toString(), Modifier.weight(1f)); Stat("Выбрано", selected.size.toString(), Modifier.weight(1f), t.info)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniChip("Все страны", country == null) { country = null }
            countries.forEach { (code, n) -> MiniChip((if (code.length == 2) Countries.flag(code) else "🌐") + " $n", country == code) { country = if (country == code) null else code } }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MiniChip("Проверены", onlyAlive) { onlyAlive = !onlyAlive }
            listOf(0 to "любой", 200 to "≤200", 600 to "≤600", 999 to "≤999").forEach { (v, l) -> MiniChip(l, maxMs == v) { maxMs = v } }
            listOf("по отклику", "по странам", "новые").forEachIndexed { i, l -> MiniChip(l, sort == i) { sort = i } }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            types.forEach { (p, n) -> MiniChip((TYPE_LABEL[p] ?: p) + " " + n, protocol == p) { protocol = if (protocol == p) null else p } }
            if (all.any { it.proxy }) { MiniChip("Элитные", elite) { elite = !elite }; MiniChip("HTTPS ✓", httpsOnly) { httpsOnly = !httpsOnly } }
            if (all.any { it.v6 }) MiniChip("IPv6", v6Only) { v6Only = !v6Only }
        }
        if (vm.running) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (shown.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(if (all.isEmpty()) "Ключей пока нет. Запустите поиск на первой вкладке." else if (onlyAlive) "Подтверждённых подключений нет. Выключите фильтр и запустите перепроверку." else "Нет ключей под выбранные фильтры", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(shown, key = { it.id }) { k ->
                val on = k.id in selected
                SoftCard(padding = 8.dp, onClick = { selected = if (on) selected - k.id else selected + k.id }, color = if (on) t.infoSoft else MaterialTheme.colorScheme.surfaceContainerLowest) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (k.country.length == 2) Countries.flag(k.country) else "🌐", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(k.host + ":" + k.port, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
                            Text((TYPE_LABEL[k.type] ?: k.type) + (if (!k.proxy && k.validation == 1) " · HTTPS через ${if (k.type in SingBoxConfig.types) "sing-box" else "Xray"} ✓" else if (!k.proxy && (k.validation == 2 || k.validation == 3)) " · ${k.validationNote}" else if (k.tls == 1) " · TCP/TLS ✓" else "") + (if (k.proxy && k.ms >= 0) (ANON_LABEL[k.anon]?.let { " · $it" } ?: "") + (if (k.https == 1) " · HTTPS ✓" else if (k.https == 2) " · без HTTPS" else "") else "") + (if (k.v6) " · IPv6" else "") + " · " + k.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        when {
                            k.alive -> Pill("${k.verifiedPing} мс ✓", if (k.verifiedPing <= 200) t.good else if (k.verifiedPing <= 600) t.warn else t.bad, if (k.verifiedPing <= 200) t.goodSoft else if (k.verifiedPing <= 600) t.warnSoft else t.badSoft)
                            k.verifiedPing >= KeyQualityPolicy.MAX_WORKING_MS -> Pill("Медленный ${k.verifiedPing} мс", t.bad, t.badSoft)
                            !k.proxy && k.validation == 2 -> Pill("Не работает", t.bad, t.badSoft)
                            !k.proxy && k.validation == 3 -> Pill("Ошибка теста", t.warn, t.warnSoft)
                            k.udp -> Pill("Не проверен", t.info, t.infoSoft)
                            k.ms == -2L -> Pill("Нет TCP", t.bad, t.badSoft)
                            !k.proxy && !k.alive -> Pill("Не проверен", MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.surfaceContainerLow)
                            k.ms < 0 -> Pill("нет", t.bad, t.badSoft)
                            else -> Pill("${k.verifiedPing} мс", if (k.verifiedPing <= 150) t.good else if (k.verifiedPing <= 400) t.warn else t.bad, if (k.verifiedPing <= 150) t.goodSoft else if (k.verifiedPing <= 400) t.warnSoft else t.badSoft)
                        }
                    }
                }
            }
        }
        // нижняя панель действий
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { bestDialog = true }, contentPadding = PaddingValues(horizontal = 10.dp), enabled = alive.isNotEmpty()) { Icon(Icons.Rounded.AutoAwesome, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Лучшие") }
                OutlinedButton(onClick = { selected = if (selected.size == shown.size) emptySet() else shown.map { it.id }.toSet() }, contentPadding = PaddingValues(horizontal = 10.dp), enabled = shown.isNotEmpty()) { Text(if (selected.isNotEmpty() && selected.size == shown.size) "Снять" else "Все") }
                IconButton(onClick = { vm.recheck(selected) }, enabled = !vm.running && all.isNotEmpty()) { Icon(Icons.Rounded.Refresh, "Полная перепроверка выделенных или всех") }
                IconButton(onClick = { vm.remove(selected); selected = emptySet() }, enabled = !vm.running && selected.isNotEmpty()) { Icon(Icons.Rounded.Block, "В чёрный список", tint = t.bad) }
                IconButton(
                    onClick = { chosen.singleOrNull()?.let(onConnect) },
                    enabled = !vm.running && chosen.size == 1 &&
                        chosen.first().alive && chosen.first().type in SingBoxConfig.types
                ) {
                    Icon(Icons.Rounded.PowerSettingsNew, "Подключиться через sing-box", tint = t.good)
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = { export = true }, enabled = chosen.any { it.alive }) { Icon(Icons.Rounded.IosShare, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("Экспорт") }
            }
        }
    }

    if (bestDialog) AlertDialog(onDismissRequest = { bestDialog = false }, title = { Text("Выбрать лучшие") },
        text = { Column { Text("Сколько самых быстрых ключей взять из каждой страны (среди показанных):", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1, 3, 5, 10).forEach { n -> MiniChip("$n", false) {
                    selected = shown.filter { it.alive }.groupBy { it.country }.flatMap { (_, v) -> v.sortedBy { it.verifiedPing }.take(n) }.map { it.id }.toSet(); bestDialog = false
                } } } } },
        confirmButton = { TextButton(onClick = { bestDialog = false }) { Text("Закрыть") } })
    if (export) ExportSheet(vm, chosen.filter { it.alive }) { export = false }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier, color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface) {
    SoftCard(modifier = modifier, padding = 8.dp) {
        Text(value, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

// ------------------------------------------------------------------ Экспорт

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ExportSheet(vm: KeyHubViewModel, chosen: List<KeyItem>, onClose: () -> Unit) {
    val context = LocalContext.current
    var pending by remember { mutableStateOf("") }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) try { context.contentResolver.openOutputStream(uri)?.use { it.write(pending.toByteArray()) }; toast(context, "Файл сохранён") } catch (e: Exception) { toast(context, "Не удалось сохранить") }
    }
    var serving by remember { mutableStateOf(KeySubServer.running) }
    val addr = remember(serving) { VpnShare.addresses().firstOrNull()?.ip }
    val url = if (serving && addr != null) "http://$addr:${KeySubServer.port}/sub/${KeySubServer.token}" else ""
    val text = remember(chosen) { KeyExport.text(chosen) }
    val sub = remember(chosen) { KeyExport.subscription(chosen) }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Экспорт · ${chosen.size} ключей", style = MaterialTheme.typography.titleLarge)
            Text("Имена ключей станут «🇩🇪 DE · 85 мс · адрес», чтобы сразу видеть страну и отклик.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ExportRow(Icons.Rounded.ContentCopy, "Скопировать ссылки", "v2rayNG и аналоги: «Импорт из буфера обмена»") { copyText(context, text, "Скопировано ${chosen.size}"); onClose() }
            ExportRow(Icons.Rounded.Description, "Сохранить файл .txt", "Список ссылок, по одной в строке") { pending = text; saver.launch("xray-keys.txt") }
            ExportRow(Icons.Rounded.Dataset, "Сохранить подписку (base64)", "Файл для импорта подпиской") { pending = sub; saver.launch("xray-subscription.txt") }
            if (chosen.any { it.proxy }) ExportRow(Icons.Rounded.FormatListBulleted, "Список ip:port (прокси)", "Для ручного добавления SOCKS и HTTP в любое приложение") { copyText(context, KeyExport.hostPort(chosen), "Скопировано"); onClose() }
            ExportRow(Icons.Rounded.Share, "Поделиться", "Отправить список в другое приложение") { shareText(context, text, "Ключи Xray"); onClose() }
            ExportRow(Icons.Rounded.Download, "Добавить в мои серверы", "Появятся на вкладке «Серверы» и в пуле ротации") {
                vm.addToServers(chosen) { n -> toast(context, "Добавлено серверов: $n" + (if (n < chosen.size) " (Hysteria2, TUIC и WireGuard ядро пока не принимает)" else "")); onClose() }
            }
            ExportRow(Icons.Rounded.Wifi, if (serving) "Подписка по Wi-Fi: выключить" else "Подписка по Wi-Fi: включить", "Ссылка вида http://адрес/sub/… для v2rayNG, пока телефон в одной сети") {
                if (serving) { KeySubServer.stop(); serving = false } else serving = KeySubServer.start(sub)
            }
            if (serving) {
                if (url.isEmpty()) Text("Подключитесь к Wi-Fi или включите точку доступа, чтобы получить адрес", style = MaterialTheme.typography.bodySmall, color = tones.warn)
                else {
                    KeySubServer.body = sub
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(remember(url) { VpnShare.qr(url) }.asImageBitmap(), "QR", Modifier.size(180.dp).clip(RoundedCornerShape(12.dp)))
                        Text(url, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 6.dp))
                        OutlinedButton(onClick = { copyText(context, url, "Ссылка скопирована") }) { Text("Скопировать ссылку подписки") }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, onClick: () -> Unit) {
    SoftCard(padding = 10.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tones.info, MaterialTheme.colorScheme.surfaceContainerLow, 36.dp)
            Spacer(Modifier.width(10.dp))
            Column { Text(title, style = MaterialTheme.typography.titleSmall); Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

// ------------------------------------------------------------------ Чёрный список — сохраняется отдельно от базы ключей

@Composable
private fun BlacklistTab(vm: KeyHubViewModel) {
    val t = tones
    var protocol by remember { mutableStateOf<String?>(null) }
    var origin by remember { mutableStateOf("all") }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    val all = vm.blocked
    val protocols = remember(vm.version, all) { all.map { it.item.type }.distinct().sorted() }
    val shown = remember(vm.version, all, protocol, origin) {
        all.filter { (protocol == null || it.item.type == protocol) &&
            (origin == "all" || (origin == "manual") == (it.reason == "Исключён вручную")) }
            .sortedByDescending { it.blockedAt }
    }
    // Drop selections after successful restoration.
    LaunchedEffect(all) { selected = selected.intersect(all.map { it.fingerprint }.toSet()) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Постоянные исключения: ${all.size}", style = MaterialTheme.typography.titleMedium)
                Text("При повторном поиске эти конфигурации пропускаются. Новый ключ на том же IP не блокируется.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Rounded.Shield, null, tint = t.info)
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MiniChip("Все", origin == "all") { origin = "all" }
            MiniChip("Удалённые вручную", origin == "manual") { origin = "manual" }
            MiniChip("Не прошли тест", origin == "automatic") { origin = "automatic" }
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MiniChip("Все протоколы", protocol == null) { protocol = null }
            protocols.forEach { kind ->
                MiniChip(TYPE_LABEL[kind] ?: kind, protocol == kind) {
                    protocol = if (protocol == kind) null else kind
                }
            }
        }
        if (vm.running) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(vm.stage, style = MaterialTheme.typography.labelMedium)
                if (vm.toCheck > 0) {
                    Text("${vm.checked} из ${vm.toCheck}", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { (vm.checked / vm.toCheck.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedButton(onClick = { vm.stop() }) { Text("Остановить") }
            }
        } else if (vm.finishedNote.isNotBlank() && vm.stage == "Готово") {
            Text(vm.finishedNote, Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = t.good)
        }
        if (shown.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(if (all.isEmpty()) "Чёрный список пуст" else "По выбранным фильтрам записей нет",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(shown, key = { it.fingerprint }) { entry ->
                val k = entry.item
                val on = entry.fingerprint in selected
                SoftCard(
                    padding = 10.dp,
                    color = if (on) t.infoSoft else MaterialTheme.colorScheme.surfaceContainerLowest,
                    onClick = {
                        selected = if (on) selected - entry.fingerprint else selected + entry.fingerprint
                    }
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (k.country.length == 2) Countries.flag(k.country) else "🌐")
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${TYPE_LABEL[k.type] ?: k.type} · ${k.host}:${k.port}",
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(entry.reason, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                            if (entry.lastCheckAt > 0) {
                                Text("Повторная проверка: ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(entry.lastCheckAt))}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (k.validationNote.isNotBlank() && k.validationNote != entry.reason)
                                Text(k.validationNote, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                        Pill("Исключён", t.bad, t.badSoft)
                    }
                }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = {
                        selected = if (selected.size == shown.size &&
                            shown.all { it.fingerprint in selected })
                            emptySet() else shown.map { it.fingerprint }.toSet()
                    }, enabled = shown.isNotEmpty() && !vm.running,
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) { Text(if (selected.isEmpty()) "Выбрать" else "Все / снять") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { vm.recheckBlocked(selected) },
                    enabled = !vm.running && all.isNotEmpty()
                ) {
                    Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (selected.isEmpty()) "Проверить все" else "Проверить (${selected.size})")
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Настройки

@Composable
private fun SettingsTab(vm: KeyHubViewModel) {
    val c = LocalContext.current
    var pages by remember { mutableIntStateOf(KeySettings.pagesPerSite(c)) }
    var depth by remember { mutableIntStateOf(KeySettings.depth(c)) }
    var sites by remember { mutableIntStateOf(KeySettings.maxSites(c)) }
    var tcp by remember { mutableIntStateOf(KeySettings.tcpThreads(c)) }
    var xt by remember { mutableIntStateOf(KeySettings.xrayThreads(c)) }
    var st by remember { mutableIntStateOf(KeySettings.singThreads(c)) }
    var pt by remember { mutableIntStateOf(KeySettings.proxyThreads(c)) }
    var collect by remember { mutableIntStateOf(KeySettings.maxCollect(c)) }
    var timeout by remember { mutableIntStateOf(KeySettings.timeoutMs(c)) }
    var plimit by remember { mutableIntStateOf(KeySettings.proxyLimit(c)) }
    var psites by remember { mutableIntStateOf(KeySettings.parallelSites(c)) }
    var tls by remember { mutableStateOf(KeySettings.tlsCheck(c)) }
    var search by remember { mutableStateOf(KeySettings.useSearch(c)) }
    var confirm by remember { mutableStateOf(false) }
    @Composable fun slider(title: String, value: Int, range: IntRange, key: String, set: (Int) -> Unit, hint: String = "") {
        Text("$title: $value", style = MaterialTheme.typography.labelLarge)
        Slider(value = value.toFloat(), onValueChange = { set(it.toInt()) }, onValueChangeFinished = { KeySettings.put(c, key, value) }, valueRange = range.first.toFloat()..range.last.toFloat())
        if (hint.isNotEmpty()) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionTitle("Нагрузка")
        // (TCP, Xray, sing-box, прокси, сайтов одновременно, прокси за запуск, адресов за запуск)
        val presets = listOf("Щадящая" to listOf(24, 6, 1, 16, 2, 800, 10000), "Обычная" to listOf(64, 12, 3, 32, 4, 3000, 40000), "Максимум" to listOf(120, 16, 5, 56, 6, 8000, 60000))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (name, v) ->
                MiniChip(name, tcp == v[0] && xt == v[1] && st == v[2] && pt == v[3] && psites == v[4] && plimit == v[5] && collect == v[6]) {
                    tcp = v[0]; xt = v[1]; st = v[2]; pt = v[3]; psites = v[4]; plimit = v[5]; collect = v[6]
                    KeySettings.put(c, "tcpThreads", v[0]); KeySettings.put(c, "xthreads", v[1]); KeySettings.put(c, "sthreads", v[2]); KeySettings.put(c, "pthreads", v[3])
                    KeySettings.put(c, "psites", v[4]); KeySettings.put(c, "plimit", v[5]); KeySettings.put(c, "collect", v[6])
                }
            }
        }
        Text("Если приложение тормозит или зависает — выберите «Щадящая». «Максимум» — для телефонов с 6 ГБ памяти и больше. Сбор и проверка идут одновременно и сами останавливаются при нехватке памяти.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        SectionTitle("Сбор")
        slider("Сайтов одновременно", psites, 1..6, "psites", { psites = it })
        slider("Максимум адресов за запуск", collect, 1000..60000, "collect", { collect = it }, "Предел новых уникальных адресов за один запуск")
        slider("Страниц на сайт", pages, 1..50, "pages", { pages = it }, "Не больше 50 страниц на каждый сайт")
        slider("Глубина ссылок", depth, 0..3, "depth", { depth = it }, "0 — только указанная страница, 1 — плюс страницы, на которые она ссылается")
        slider("Сайтов из поисковиков", sites, 1..40, "sites", { sites = it })
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Искать сайты через поисковики", Modifier.weight(1f)); Switch(search, { search = it; KeySettings.put(c, "search", it) }) }
        SectionTitle("Проверка")
        slider("TCP-потоки", tcp, 16..128, "tcpThreads", { tcp = it }, "Быстрая предпроверка порта (один раз на адрес)")
        slider("Xray-проверки", xt, 2..16, "xthreads", { xt = it }, "Реальный HTTPS через ядро Xray")
        slider("sing-box процессы", st, 1..6, "sthreads", { st = it }, "Hysteria2, TUIC, WireGuard, OpenVPN, AnyTLS — отдельные процессы, едят память")
        slider("Потоки прокси", pt, 8..64, "pthreads", { pt = it }, "SOCKS и HTTP: запрос через сам прокси")
        slider("Прокси за запуск", plimit, 100..20000, "plimit", { plimit = it }, "Сверх лимита прокси уходят в резерв")
        slider("Таймаут HTTP/HTTPS, мс", timeout, 800..8000, "timeout", { timeout = it }, "TCP-фильтр ключей не больше 1000 мс; затем реальная проверка через ядро")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("TLS-рукопожатие"); Text("Для ключей с TLS и Reality: отсекает серверы, которые только принимают порт", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Switch(tls, { tls = it; KeySettings.put(c, "tls", it) })
        }
        Text("Если VPN включён, проверка идёт через него и отклик искажается — для точных цифр выключите VPN.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 6.dp))
        SectionTitle("База")
        OutlinedButton(onClick = { confirm = true }, enabled = vm.items.isNotEmpty()) { Icon(Icons.Rounded.DeleteSweep, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Очистить базу ключей (${vm.items.size})") }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Очистить базу?") }, text = { Text("Все найденные ключи будут удалены. Добавленные в «Серверы» останутся.") },
        confirmButton = { TextButton(onClick = { vm.clearAll(); confirm = false }) { Text("Очистить", color = tones.bad) } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } })
}
