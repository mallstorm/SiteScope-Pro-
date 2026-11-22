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
    private val pendingQueue = LinkedHashMap<String, KeyItem>()
    private var job: Job? = null

    init {
        blocked = KeyBlocklistStore.load(context)
        val excluded = blocked.mapTo(HashSet()) { it.fingerprint }
        items = KeyStore.load(context).filterNot { KeyBlocklistStore.fingerprint(it.link) in excluded }
        for (key in KeyCandidateStore.load(context)) {
            val fingerprint = KeyBlocklistStore.fingerprint(key.link)
            if (fingerprint !in excluded) pendingQueue.putIfAbsent(fingerprint, key)
        }
        // Prior builds saved prechecked-but-unvalidated profiles in the working
        // list. Migrate them to the durable queue without deleting user data.
        for (key in items) if (!key.alive && key.validation == XrayKeyValidator.UNTESTED) {
            val fingerprint = KeyBlocklistStore.fingerprint(key.link)
            if (fingerprint !in pendingQueue) {
                key.checkedAt = 0L
                pendingQueue[fingerprint] = key
            }
        }
        pendingCount = pendingQueue.size
        uncheckedCount = pendingQueue.values.count { it.checkedAt <= 0L }
        KeyCandidateStore.save(context, pendingQueue.values.toList())
    }

    private fun saveQueue() {
        KeyCandidateStore.save(context, pendingQueue.values.toList())
        pendingCount = pendingQueue.size
        uncheckedCount = pendingQueue.values.count { it.checkedAt <= 0L }
    }

    private val lock = Any()
    private fun say(t: String) { synchronized(lock) { log = (listOf(t) + log).take(40) } }
    private fun touch() { synchronized(lock) { version++ } }
    private val cPages = AtomicInteger(); private val cSites = AtomicInteger(); private val cKeys = AtomicInteger(); private val cChecked = AtomicInteger()
    @Volatile private var lastPub = 0L; @Volatile private var lastTouch = 0L; @Volatile private var lastSave = 0L
    /** Счётчики на экране обновляются не чаще раза в 0,4 с, чтобы не перегружать интерфейс. */
    private fun publish(force: Boolean = false) {
        val n = android.os.SystemClock.elapsedRealtime()
        if (!force && n - lastPub < 400) return
        lastPub = n; pages = cPages.get(); sitesDone = cSites.get(); keysFound = cKeys.get(); checked = cChecked.get()
        if (force || n - lastTouch > 1500) { lastTouch = n; touch() }
        if (!force && n - lastSave > 60_000) { lastSave = n; KeyStore.save(context, items) }
    }

    fun stop() { job?.cancel(); SingBoxValidator.cancel(); stage = "Остановка…"; touch() }

    // Clearing the visible database never erases the persistent deny-list.
    fun clearAll() {
        items = emptyList()
        pendingQueue.clear()
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
        items = items.filterNot { KeyBlocklistStore.fingerprint(it.link) in rejected }
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
                        items = (items + recovered).distinctBy { KeyBlocklistStore.fingerprint(it.link) }
                        KeyStore.save(context, items)
                    }
                } finally {
                    running = false; publish(true)
                }
            }
        }
    }

    /** Retry checked-but-slow and inconclusive candidates without re-downloading lists. */
    fun retryQueued() {
        if (running || pendingQueue.isEmpty()) return
        for (item in pendingQueue.values) {
            item.checkedAt = 0
            item.ms = -1
            item.validation = XrayKeyValidator.UNTESTED
            item.validationMs = -1
            item.validationAt = 0
            item.validationNote = ""
        }
        saveQueue()
        running = true
        job = viewModelScope.launch(Dispatchers.Default) {
            try { processPending() }
            catch (cancel: kotlinx.coroutines.CancellationException) {
                stage = "Остановлено"
                throw cancel
            } catch (error: Exception) {
                stage = "Ошибка"
                finishedNote = "Ошибка: " + (error.message ?: "неизвестно")
            } finally {
                KeyStore.save(context, items)
                running = false
                publish(true)
            }
        }
    }

    /**
     * Every unique candidate reaches a latency preflight, then eligible TCP
     * profiles get a routed HTTP/HTTPS test. UDP transports skip TCP-only probes.
     * Slow/unreachable entries are retained for explicit retries, not blacklisted.
     * A completed batch is checkpointed before processing the next one.
     */
    private suspend fun processPending() {
        val all = pendingQueue.values.filter { it.checkedAt <= 0L }
        if (all.isEmpty()) {
            stage = "Готово"
            finishedNote = "Непроверенных нет · медленные можно повторить отдельно"
            return
        }
        val total = all.size
        var processed = 0
        var accepted = 0
        var slow = 0
        val batchSize = KeySettings.keysLimit(context).coerceIn(100, 1500)
        val timeout = KeySettings.timeoutMs(context).coerceAtLeast(1500)
        say("Очередь: $total уникальных соединений · проверяем без пропусков")
        for (batch in all.chunked(batchSize)) {
            coroutineContext.ensureActive()
            checkItems(batch)
            coroutineContext.ensureActive()
            val fast = batch.filter {
                (it.udp && it.type in SingBoxConfig.types && it.ms == -3L) ||
                    (!it.udp && KeyQualityPolicy.fastEnough(it.ms))
            }
            val proxies = fast.filter { it.proxy }
            if (proxies.isNotEmpty()) {
                stage = "Проверка HTTP/HTTPS через прокси"
                cChecked.set(0); checked = 0; toCheck = proxies.size
                val gate = Semaphore(KeySettings.threads(context).coerceAtMost(16))
                coroutineScope {
                    proxies.map { key -> async {
                        gate.acquire()
                        try {
                            ensureActive()
                            KeyChecker.check(key, timeout, true)
                        } catch (cancel: kotlinx.coroutines.CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                            key.ms = -2
                        } finally {
                            cChecked.incrementAndGet()
                            gate.release()
                            publish()
                        }
                    } }.awaitAll()
                }
            }
            // The deep-limit setting sizes Xray/sing-box checkpoints, but it
            // does NOT truncate the set of profiles that get a real HTTPS test.
            val vpnKeys = fast.filter { !it.proxy }
            for (group in vpnKeys.chunked(KeySettings.deepLimit(context).coerceAtLeast(1))) {
                coroutineContext.ensureActive()
                deepValidate(group, group.size)
            }
            val good = fast.filter { it.alive }
            if (good.isNotEmpty()) {
                val merged = items.associateByTo(LinkedHashMap()) { KeyBlocklistStore.fingerprint(it.link) }
                for (item in good) {
                    merged[KeyBlocklistStore.fingerprint(item.link)] = item
                    pendingQueue.remove(KeyBlocklistStore.fingerprint(item.link))
                }
                items = merged.values.toList()
                accepted += good.size
                KeyChecker.geo(context, good)
            }
            // A failed network test can be temporary. Only permanently malformed
            // or unsupported configurations go on the automatic deny-list.
            val invalid = batch.filter { item ->
                item.validation == XrayKeyValidator.FAILED &&
                    (item.validationNote.contains("Некорректн", ignoreCase = true) ||
                        item.validationNote.contains("не поддержива", ignoreCase = true))
            }
            if (invalid.isNotEmpty()) quarantine(invalid) { it.validationNote }
            processed += batch.size
            slow += batch.count { !it.alive && it.ms >= KeyQualityPolicy.MAX_WORKING_MS }
            saveQueue()
            KeyStore.save(context, items)
            say("Проверено $processed/$total · рабочих $accepted · медленных $slow · осталось ${uncheckedCount}")
            publish(true)
        }
        stage = "Готово"
        finishedNote = "Проверено $processed · рабочих $accepted · медленных $slow · в резерве $pendingCount"
    }

    fun start(query: String, kindIds: Set<String>, sources: List<String>) {
        if (running) return
        val c = context
        KeySettings.put(c, "query", query)
        KeySettings.put(c, "sources", sources.joinToString("\n"))
        KeySettings.put(c, "kinds", kindIds.joinToString(","))
        val chosenKinds = kindIds.mapNotNull { KeySources.byId(it) }
        val types = chosenKinds.flatMap { it.types }.toSet().ifEmpty { KeySources.XRAY_TYPES }
        val followSubs = "sub" in kindIds
        val allowV6 = KeySettings.ipv6(c)
        running = true
        finishedNote = ""; log = emptyList(); KeyGuard.tripped = false
        duplicateCount = 0
        cPages.set(0); cSites.set(0); cKeys.set(0); cChecked.set(0)
        sitesFound = 0; sitesDone = 0; pages = 0; keysFound = 0; checked = 0; toCheck = 0
        job = viewModelScope.launch(Dispatchers.Default) {
            try {
                val found = ConcurrentHashMap<String, KeyItem>()
                val excluded = blocked.mapTo(HashSet()) { it.fingerprint }
                // A single fingerprint set for the entire run: source A, source B,
                // known working records, pending records, and permanent blacklist.
                val alreadyKnown = HashSet<String>()
                alreadyKnown.addAll(excluded)
                alreadyKnown.addAll(pendingQueue.keys)
                alreadyKnown.addAll(items.map { KeyBlocklistStore.fingerprint(it.link) })
                val seen = ConcurrentHashMap.newKeySet<String>()
                seen.addAll(alreadyKnown)
                val duplicates = AtomicInteger()
                val oldMatches = AtomicInteger()
                val subSeen = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
                stage = "Поиск сайтов"
                val sites = LinkedHashMap<String, String>()
                fun norm(s: String) = if (s.startsWith("http")) s else if (s.startsWith("t.me/") || s.startsWith("@")) "https://t.me/s/" + s.removePrefix("@").removePrefix("t.me/").removePrefix("s/") else "https://$s"
                for (source in sources) {
                    val url = norm(source)
                    sites.putIfAbsent(url, KeySources.label(url))
                }
                for (kind in chosenKinds) for (url in kind.sources) sites.putIfAbsent(url, KeySources.label(url))
                val presetCount = sites.size
                if (KeySettings.useSearch(c)) {
                    val max = KeySettings.maxSites(c)
                    val hits = withContext(Dispatchers.IO) { KeyCrawler.search(query.trim(), max, types) }
                    say("Поисковики: найдено ${hits.size} ссылок")
                    val hosts = HashSet(sites.values)
                    var added = 0
                    for (hit in hits) {
                        val host = KeyCrawler.hostOf(hit)
                        if (host.isNotEmpty() && hosts.add(host)) { sites[hit] = host; added++ }
                        if (added >= max) break
                    }
                }
                sitesFound = sites.size
                if (sites.isEmpty()) {
                    if (pendingQueue.values.any { it.checkedAt <= 0L }) {
                        say("Источники не найдены, проверяем сохранённую очередь")
                        processPending()
                    } else {
                        finishedNote = "Источники не найдены"
                        stage = "Готово"
                    }
                    return@launch
                }
                if (presetCount > 0) say("Готовых источников: $presetCount")
                stage = "Сканирование сайтов"
                val pagesCap = KeySettings.pagesPerSite(c)
                val depth = KeySettings.depth(c)
                val gate = Semaphore(KeySettings.parallelSites(c))
                coroutineScope {
                    sites.entries.map { (url, label) ->
                        async {
                            gate.acquire()
                            try {
                                ensureActive()
                                if (KeyGuard.tripped) return@async
                                var own = 0
                                try {
                                    KeyCrawler.crawl(url, pagesCap, depth, types,
                                        KeySources.proxyTypesFor(url, types), followSubs,
                                        subSeen, { cPages.incrementAndGet(); publish() }) { keys ->
                                        for (link in keys) {
                                            if (found.size >= 30000) break
                                            val fingerprint = KeyBlocklistStore.fingerprint(link)
                                            if (!seen.add(fingerprint)) {
                                                if (fingerprint in alreadyKnown) oldMatches.incrementAndGet()
                                                else duplicates.incrementAndGet()
                                                continue
                                            }
                                            val parsed = KeyCrawler.parse(link, label, allowV6)
                                            if (parsed == null) {
                                                seen.remove(fingerprint)
                                                continue
                                            }
                                            found[fingerprint] = parsed
                                            own++
                                            cKeys.set(found.size)
                                        }
                                        publish()
                                    }
                                } catch (cancel: kotlinx.coroutines.CancellationException) {
                                    throw cancel
                                } catch (_: Exception) {
                                    say("$label: ошибка")
                                } catch (_: OutOfMemoryError) {
                                    KeyGuard.tripped = true
                                }
                                if (own > 0) say("$label: +$own")
                            } finally {
                                cSites.incrementAndGet()
                                publish()
                                gate.release()
                            }
                        }
                    }.awaitAll()
                }
                publish(true)
                if (KeyGuard.tripped) say("Мало памяти: сбор остановлен досрочно")
                val fresh = found.values.toList()
                for (key in fresh) {
                    pendingQueue.putIfAbsent(KeyBlocklistStore.fingerprint(key.link), key)
                }
                // Persist ALL new unique records before the first network probe.
                // A stopped/crashed scan can resume without discarding discovery.
                saveQueue()
                duplicateCount = duplicates.get()
                say("Уникальных новых: ${fresh.size} · в базе: ${oldMatches.get()} · дубликатов отброшено: ${duplicates.get()}")
                found.clear()
                processPending()
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                stage = "Остановлено"
                throw cancel
            } catch (error: OutOfMemoryError) {
                finishedNote = "Не хватило памяти: попробуйте меньше потоков"
                stage = "Ошибка"
            } catch (error: Exception) {
                finishedNote = "Ошибка: " + (error.message ?: error.javaClass.simpleName)
                stage = "Ошибка"
            } finally {
                KeyStore.save(context, items)
                running = false
                publish(true)
            }
        }
    }

    private suspend fun checkItems(list: List<KeyItem>) {
        if (list.isEmpty()) return
        val c = context
        stage = "Проверка отклика"; cChecked.set(0); checked = 0; toCheck = list.size
        val gate = Semaphore(KeySettings.threads(c))
        val timeout = KeySettings.timeoutMs(c).coerceAtMost(KeyQualityPolicy.CONNECT_TIMEOUT_MS)
        coroutineScope {
            list.map { k -> async {
                gate.acquire()
                try {
                    ensureActive()
                    val ok = kotlinx.coroutines.withTimeoutOrNull(timeout * 5L + 5000L) {
                        KeyChecker.quickCheck(k, timeout)
                    }
                    if (ok == null) { k.ms = -2; k.checkedAt = System.currentTimeMillis() }
                } finally {
                    cChecked.incrementAndGet()
                    gate.release()
                    publish()
                }
            } }.awaitAll()
        }
        publish(true)
    }

    /** Real proof: HTTPS response through a disposable local Xray SOCKS core.
     * The native core is used serially, not once per coroutine.
     */
    private suspend fun deepValidate(list: List<KeyItem>, limit: Int) {
        val eligible = list.filter { !it.proxy &&
            (if (it.type in SingBoxConfig.types) it.ms >= 0 || it.ms == -3L else !it.udp && it.ms >= 0) &&
            (it.validation != XrayKeyValidator.VERIFIED || !it.alive) }
        val candidates = eligible.take(limit)
        if (candidates.isEmpty()) return
        stage = "Проверка VPN через ядра"
        cChecked.set(0); checked = 0; toCheck = candidates.size
        say("Глубокая проверка: ${candidates.size} из ${eligible.size} кандидатов")
        for (key in candidates) {
            coroutineContext.ensureActive()
            val result = withContext(Dispatchers.IO) {
                if (key.type in SingBoxConfig.types) SingBoxValidator.check(context, key.link, 4500)
                else XrayKeyValidator.check(context, key.link, 4500)
            }
            key.validation = result.status
            key.validationMs = result.delayMs
            key.validationAt = System.currentTimeMillis()
            key.validationNote = result.reason
            cChecked.incrementAndGet(); publish(true)
            if (cChecked.get() % 10 == 0) KeyStore.save(context, items)
        }
        if (eligible.size > candidates.size)
            say("Ещё ${eligible.size - candidates.size} кандидатов не валидированы — выберите их и нажмите перепроверку")
    }

    /** Повторная проверка выбранных (или всех) ключей. */
    fun recheck(ids: Set<String>) {
        if (running) return
        running = true; finishedNote = ""
        val list = items.filter { ids.isEmpty() || it.id in ids }
        job = viewModelScope.launch(Dispatchers.Default) {
            try {
                list.forEach { it.validation = 0; it.validationMs = -1L; it.validationAt = 0; it.validationNote = ""; it.country = "" }
                checkItems(list)
                val fast = list.filter {
                    (it.udp && it.type in SingBoxConfig.types && it.ms == -3L) ||
                        (!it.udp && KeyQualityPolicy.fastEnough(it.ms))
                }
                for (proxy in fast.filter { it.proxy }) KeyChecker.check(proxy, KeySettings.timeoutMs(context).coerceAtLeast(1500), true)
                deepValidate(fast, fast.size)
                val recovered = fast.filter { it.alive }
                val rejected = list.filter { !it.alive }
                for (key in rejected) pendingQueue[KeyBlocklistStore.fingerprint(key.link)] = key
                items = items.filterNot { it in rejected }
                saveQueue()
                finishedNote = "Проверено ${list.size} · рабочих ${recovered.size} · в резерве ${rejected.size}"
                stage = "Готово"
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { finishedNote = "Ошибка: " + (e.message ?: "") }
            finally { KeyStore.save(context, items); running = false; publish(true) }
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

// ------------------------------------------------------------------ Поиск

@Composable
private fun SearchTab(vm: KeyHubViewModel, onShow: () -> Unit) {
    val context = LocalContext.current
    val t = tones
    var query by remember { mutableStateOf(KeySettings.lastQuery(context)) }
    var sources by remember { mutableStateOf(KeySettings.lastSources(context)) }
    var picked by remember { mutableStateOf(KeySettings.kinds(context).ifEmpty { setOf("xray") }) }
    var v6 by remember { mutableStateOf(KeySettings.ipv6(context)) }
    val profilePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importLocalProfile(uri) { message -> toast(context, message) }
    }
    fun go() { if (!vm.running && picked.isNotEmpty()) vm.start(query, picked, sources.lines().map { it.trim() }.filter { it.isNotEmpty() }) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Что искать", style = MaterialTheme.typography.titleSmall)
            KeySources.kinds.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { k ->
                        val on = k.id in picked
                        SoftCard(modifier = Modifier.weight(1f), padding = 10.dp, color = if (on) t.infoSoft else MaterialTheme.colorScheme.surfaceContainerLowest,
                            onClick = { picked = if (on) picked - k.id else picked + k.id }) {
                            Row(verticalAlignment = Alignment.Top) {
                                Checkbox(checked = on, onCheckedChange = null, modifier = Modifier.size(24.dp))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(k.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                                    Text(k.sub + " · " + k.sources.size + " ист.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                                }
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Включать IPv6", style = MaterialTheme.typography.bodyLarge); Text("Серверы с адресами IPv6 нужны, только если у вас есть IPv6", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(v6, { v6 = it; KeySettings.put(context, "ipv6", it) })
            }
            BigField(query, { query = it }, "Свой запрос для поиска сайтов (необязательно)", keyboard = KeyboardType.Text, leading = Icons.Rounded.Search, onGo = { go() })
            BigField(sources, { sources = it }, "Свои источники: сайты, @канал Telegram, ссылки на списки (по строке)", singleLine = false, minLines = 2, keyboard = KeyboardType.Uri, leading = Icons.Rounded.Link, mono = true)
            OutlinedButton(onClick = { profilePicker.launch(arrayOf("*/*")) }, enabled = !vm.running) {
                Icon(Icons.Rounded.FileOpen, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Импорт WireGuard .conf / OpenVPN .ovpn")
            }
            if (vm.running) {
                SoftCard(color = t.infoSoft, padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(vm.stage, style = MaterialTheme.typography.titleSmall)
                            Text(when (vm.stage) {
                                "Проверка отклика", "Проверка VPN через ядра" -> "${vm.checked} из ${vm.toCheck}"
                                else -> "источников ${vm.sitesDone}/${vm.sitesFound} · страниц ${vm.pages} · найдено ${vm.keysFound}"
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = { vm.stop() }) { Text("Стоп") }
                    }
                    if ((vm.stage == "Проверка отклика" || vm.stage == "Проверка VPN через ядра") && vm.toCheck > 0) LinearProgressIndicator(progress = { vm.checked / vm.toCheck.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
            if (vm.finishedNote.isNotEmpty() && !vm.running) {
                SoftCard(color = t.goodSoft, padding = 12.dp, onClick = onShow) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, null, tint = t.good); Spacer(Modifier.width(8.dp))
                        Text(vm.finishedNote, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Icon(Icons.Rounded.ChevronRight, null)
                    }
                }
            }
            if (vm.log.isNotEmpty()) SoftCard(padding = 10.dp) { vm.log.take(8).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) } }
            Text("Читаются только открытые страницы и публичные списки. Прокси проверяются реальным запросом через них (ip-api.com, httpbin.org), ключи Xray — реальным HTTPS-запросом через встроенное ядро после TCP-предпроверки. Чужие серверы видят ваш трафик: не используйте их для входов и платежей. Hysteria2, TUIC, WireGuard, OpenVPN и AnyTLS проверяются через sing-box. Перед проверкой отключите другие VPN.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
        if (!vm.running) Surface(tonalElevation = 3.dp) {
            Button(onClick = { go() }, enabled = picked.isNotEmpty(), modifier = Modifier.fillMaxWidth().padding(12.dp).height(50.dp)) {
                Icon(Icons.Rounded.TravelExplore, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Найти и проверить (${picked.size})")
            }
        }
    }
}

// ------------------------------------------------------------------ Ключи

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

    val all = remember(vm.version, vm.items) { vm.items }
    val alive = all.filter { it.alive }
    val countries = remember(vm.version) { alive.groupBy { it.country }.map { it.key to it.value.size }.sortedByDescending { it.second } }
    val types = remember(vm.version) { all.map { it.type }.distinct().sortedBy { TYPE_LABEL.keys.indexOf(it) } }
    val shown = remember(vm.version, country, protocol, onlyAlive, maxMs, sort, elite, httpsOnly, v6Only) {
        all.filter { (!onlyAlive || it.alive) && (country == null || it.country == country) && (protocol == null || it.type == protocol) && (maxMs == 0 || (it.alive && it.verifiedPing in 0..maxMs.toLong())) &&
            (!elite || it.anon == 3) && (!httpsOnly || it.https == 1) && (!v6Only || it.v6) }
            .let { l -> when (sort) { 0 -> l.sortedWith(compareBy<KeyItem> { if (it.verifiedPing < 0) Long.MAX_VALUE else it.verifiedPing }); 1 -> l.sortedWith(compareBy<KeyItem>({ it.country.ifEmpty { "ZZ" } }, { if (it.verifiedPing < 0) Long.MAX_VALUE else it.verifiedPing })); else -> l.sortedByDescending { it.foundAt } } }
    }
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
            listOf(0 to "любой", 150 to "≤150", 300 to "≤300", 600 to "≤600").forEach { (v, l) -> MiniChip(l, maxMs == v) { maxMs = v } }
            listOf("по отклику", "по странам", "новые").forEachIndexed { i, l -> MiniChip(l, sort == i) { sort = i } }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            types.forEach { p -> MiniChip(TYPE_LABEL[p] ?: p, protocol == p) { protocol = if (protocol == p) null else p } }
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
                            k.alive -> Pill("${k.verifiedPing} мс ✓", if (k.verifiedPing <= 400) t.good else t.warn, if (k.verifiedPing <= 400) t.goodSoft else t.warnSoft)
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
    var threads by remember { mutableIntStateOf(KeySettings.threads(c)) }
    var timeout by remember { mutableIntStateOf(KeySettings.timeoutMs(c)) }
    var plimit by remember { mutableIntStateOf(KeySettings.proxyLimit(c)) }
    var klimit by remember { mutableIntStateOf(KeySettings.keysLimit(c)) }
    var deep by remember { mutableIntStateOf(KeySettings.deepLimit(c)) }
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
        // (потоки проверки, сайтов одновременно, ключей и прокси за запуск, таймаут)
        val presets = listOf("Щадящая" to listOf(12, 2, 600, 200, 4500), "Обычная" to listOf(24, 3, 1500, 400, 3500), "Максимум" to listOf(40, 4, 3000, 1000, 3000))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (name, v) ->
                MiniChip(name, threads == v[0] && psites == v[1] && klimit == v[2] && plimit == v[3] && timeout == v[4]) {
                    threads = v[0]; psites = v[1]; klimit = v[2]; plimit = v[3]; timeout = v[4]
                    KeySettings.put(c, "threads", v[0]); KeySettings.put(c, "psites", v[1]); KeySettings.put(c, "klimit", v[2]); KeySettings.put(c, "plimit", v[3]); KeySettings.put(c, "timeout", v[4])
                }
            }
        }
        Text("Если приложение тормозит или зависает — выберите «Щадящая». «Максимум» подходит для мощных телефонов с 6 ГБ памяти и больше. Сбор идёт в фоне и сам останавливается при нехватке памяти.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        SectionTitle("Обход сайтов")
        slider("Сайтов одновременно", psites, 1..4, "psites", { psites = it })
        slider("Страниц на сайт", pages, 1..50, "pages", { pages = it }, "Не больше 50 страниц на каждый сайт")
        slider("Глубина ссылок", depth, 0..3, "depth", { depth = it }, "0 — только указанная страница, 1 — плюс страницы, на которые она ссылается")
        slider("Сайтов за запуск", sites, 1..40, "sites", { sites = it })
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Искать сайты через поисковики", Modifier.weight(1f)); Switch(search, { search = it; KeySettings.put(c, "search", it) }) }
        SectionTitle("Проверка")
        slider("Параллельных проверок", threads, 4..48, "threads", { threads = it })
        slider("Ключей за один запуск", klimit, 200..4000, "klimit", { klimit = it }, "В больших сборниках десятки тысяч ключей: проверяется случайная часть этого размера")
        slider("Таймаут, мс", timeout, 1500..8000, "timeout", { timeout = it })
        slider("Прокси за один запуск", plimit, 100..2000, "plimit", { plimit = it }, "В публичных списках тысячи адресов: проверяется случайная часть этого размера. Мёртвые прокси не сохраняются")
        slider("VPN-ключей с полной проверкой", deep, 10..1000, "deepLimit", { deep = it }, "После предварительной проверки запускается Xray или sing-box для каждого кандидата. Остальные остаются НЕ ПРОВЕРЕНЫ. Выборочная перепроверка работает без ограничения.")
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
