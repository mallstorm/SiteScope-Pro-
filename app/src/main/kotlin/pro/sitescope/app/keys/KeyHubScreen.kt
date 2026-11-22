package pro.sitescope.app.keys

import android.app.Application
import android.content.Context
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
import kotlin.coroutines.coroutineContext
import pro.sitescope.app.ui.*
import pro.sitescope.app.vpn.Countries
import pro.sitescope.app.vpn.VpnShare
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private val TYPE_LABEL = mapOf("vless" to "VLESS", "vmess" to "VMess", "trojan" to "Trojan", "ss" to "Shadowsocks", "socks" to "SOCKS5", "http" to "HTTP",
    "hysteria2" to "Hysteria2", "tuic" to "TUIC", "wireguard" to "WireGuard")
private val ANON_LABEL = mapOf(1 to "прозрачный", 2 to "анонимный", 3 to "элитный")

class KeyHubViewModel(app: Application) : AndroidViewModel(app) {
    private val context: Context get() = getApplication()
    var items by mutableStateOf<List<KeyItem>>(emptyList())
    var version by mutableIntStateOf(0)
    var running by mutableStateOf(false)
    var stage by mutableStateOf("")
    var sitesFound by mutableIntStateOf(0); var sitesDone by mutableIntStateOf(0); var pages by mutableIntStateOf(0)
    var keysFound by mutableIntStateOf(0); var checked by mutableIntStateOf(0); var toCheck by mutableIntStateOf(0)
    var log by mutableStateOf<List<String>>(emptyList())
    var finishedNote by mutableStateOf("")
    private var job: Job? = null

    init { items = KeyStore.load(context) }

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

    fun stop() { job?.cancel(); stage = "Остановлено"; running = false; KeyStore.save(context, items); touch() }

    fun clearAll() { items = emptyList(); KeyStore.clear(context); touch() }
    fun remove(ids: Set<String>) { items = items.filter { it.id !in ids }; KeyStore.save(context, items); touch() }

    fun start(query: String, kindIds: Set<String>, sources: List<String>) {
        if (running) return
        val c = context
        KeySettings.put(c, "query", query); KeySettings.put(c, "sources", sources.joinToString("\n")); KeySettings.put(c, "kinds", kindIds.joinToString(","))
        val chosenKinds = kindIds.mapNotNull { KeySources.byId(it) }
        val types = chosenKinds.flatMap { it.types }.toSet().ifEmpty { KeySources.XRAY_TYPES }
        val followSubs = "sub" in kindIds; val eliteOnly = "elite" in kindIds; val allowV6 = KeySettings.ipv6(c)
        running = true; finishedNote = ""; log = emptyList(); KeyGuard.tripped = false
        cPages.set(0); cSites.set(0); cKeys.set(0); cChecked.set(0)
        sitesFound = 0; sitesDone = 0; pages = 0; keysFound = 0; checked = 0; toCheck = 0
        // Вся работа идёт в фоне: главный поток отвечает только за экран.
        job = viewModelScope.launch(Dispatchers.Default) {
            try {
                val found = ConcurrentHashMap<String, KeyItem>()
                val subSeen = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
                stage = "Поиск сайтов"
                val sites = LinkedHashMap<String, String>()
                fun norm(s: String) = if (s.startsWith("http")) s else if (s.startsWith("t.me/") || s.startsWith("@")) "https://t.me/s/" + s.removePrefix("@").removePrefix("t.me/").removePrefix("s/") else "https://$s"
                for (s in sources) { val u = norm(s); sites.putIfAbsent(u, KeySources.label(u)) }
                for (k in chosenKinds) for (u in k.sources) sites.putIfAbsent(u, KeySources.label(u))
                val presetCount = sites.size
                if (KeySettings.useSearch(c)) {
                    val max = KeySettings.maxSites(c)
                    val hits = withContext(Dispatchers.IO) { KeyCrawler.search(query.trim(), max, types) }
                    say("Поисковики: найдено ${hits.size} ссылок")
                    val hosts = HashSet(sites.values)
                    var added = 0
                    for (h in hits) { val host = KeyCrawler.hostOf(h); if (host.isNotEmpty() && hosts.add(host)) { sites[h] = host; added++ }; if (added >= max) break }
                }
                sitesFound = sites.size
                if (sites.isEmpty()) { finishedNote = "Источники не найдены. Добавьте свои или проверьте интернет"; stage = "Готово"; return@launch }
                if (presetCount > 0) say("Готовых источников: $presetCount")
                // Обход
                stage = "Сканирование сайтов"
                val pagesCap = KeySettings.pagesPerSite(c); val depth = KeySettings.depth(c)
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
                                    KeyCrawler.crawl(url, pagesCap, depth, types, KeySources.proxyTypesFor(url, types), followSubs, subSeen, { cPages.incrementAndGet(); publish() }) { keys ->
                                        for (k in keys) {
                                            if (found.size >= 30000) break
                                            KeyCrawler.parse(k, label, allowV6)?.let { if (found.putIfAbsent(it.id, it) == null) { own++; cKeys.set(found.size) } }
                                        }
                                        publish()
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { say("$label: ошибка") } catch (e: OutOfMemoryError) { KeyGuard.tripped = true }
                                if (own > 0) say("$label: +$own")
                            } finally { cSites.incrementAndGet(); publish(); gate.release() }
                        }
                    }.awaitAll()
                }
                publish(true)
                if (KeyGuard.tripped) say("Мало памяти: сбор остановлен досрочно, найденное проверяется")
                // Слияние с базой: уже проверенные сохраняют результат. Проверяем не больше лимитов.
                val old = items.associateBy { it.id }
                val fresh0 = found.values.filter { it.id !in old }
                val proxyLimit = KeySettings.proxyLimit(c); val keyLimit = KeySettings.keysLimit(c)
                val fresh = fresh0.filter { !it.proxy }.shuffled().take(keyLimit) + fresh0.filter { it.proxy }.shuffled().take(proxyLimit)
                items = old.values.toList() + fresh; touch()
                say("Новых: ${fresh0.size}, уже были: ${found.size - fresh0.size}" + if (fresh.size < fresh0.size) " · к проверке: ${fresh.size}" else "")
                val foundTotal = found.size
                found.clear()
                // Проверка
                checkItems(fresh)
                deepValidate(fresh, KeySettings.deepLimit(c))
                KeyChecker.geo(c, fresh.filter { it.alive })
                val drop = fresh.filter { it.proxy && (it.ms == -2L || (eliteOnly && it.anon != 3)) }.map { it.id }.toSet()
                if (drop.isNotEmpty()) items = items.filter { it.id !in drop }
                val liveNow = fresh.count { it.alive && it.id !in drop }
                finishedNote = "Найдено $foundTotal · TCP/прокси ${fresh.size} · подтверждено $liveNow" + (fresh.count { it.proxy && it.anon == 3 && it.id !in drop }.let { if (it > 0) " · элитных $it" else "" })
                stage = "Готово"
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) { finishedNote = "Не хватило памяти: выберите режим «Щадящая» в настройках"; stage = "Ошибка" }
            catch (e: Exception) { finishedNote = "Ошибка: " + (e.message ?: e.javaClass.simpleName); stage = "Ошибка" }
            finally { KeyStore.save(context, items); running = false; publish(true) }
        }
    }

    private suspend fun checkItems(list: List<KeyItem>) {
        if (list.isEmpty()) return
        val c = context
        stage = "Проверка отклика"; cChecked.set(0); checked = 0; toCheck = list.size
        val gate = Semaphore(KeySettings.threads(c)); val timeout = KeySettings.timeoutMs(c); val tls = KeySettings.tlsCheck(c)
        coroutineScope {
            list.map { k -> async {
                gate.acquire()
                try {
                    ensureActive()
                    val ok = kotlinx.coroutines.withTimeoutOrNull(timeout * 6L + 5000) { KeyChecker.check(k, timeout, tls) }
                    if (ok == null) { k.ms = -2; k.checkedAt = System.currentTimeMillis() }
                } finally { cChecked.incrementAndGet(); gate.release(); publish() }
            } }.awaitAll()
        }
        stage = "Определение стран"
        KeyChecker.geo(c, list.filter { it.alive || it.ms == -3L })
        publish(true)
    }

    /** Real proof: HTTPS response through a disposable local Xray SOCKS core.
     * The native core is used serially, not once per coroutine.
     */
    private suspend fun deepValidate(list: List<KeyItem>, limit: Int) {
        val eligible = list.filter { !it.proxy && !it.udp && it.ms >= 0 &&
            (it.validation != XrayKeyValidator.VERIFIED || !it.alive) }
        val candidates = eligible.take(limit)
        if (candidates.isEmpty()) return
        stage = "Проверка VPN через Xray"
        cChecked.set(0); checked = 0; toCheck = candidates.size
        say("Глубокая проверка: ${candidates.size} из ${eligible.size} кандидатов")
        for (key in candidates) {
            coroutineContext.ensureActive()
            val result = withContext(Dispatchers.IO) { XrayKeyValidator.check(context, key.link, 4500) }
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
                deepValidate(list, list.size)
                finishedNote = "Проверено ${list.size} · подтверждено ${list.count { it.alive }}"
                stage = "Готово"
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { finishedNote = "Ошибка: " + (e.message ?: "") }
            finally { KeyStore.save(context, items); running = false; publish(true) }
        }
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
    Column(Modifier.fillMaxSize()) {
        AppHeader("Ключи и прокси", "VLESS, VMess, SS, Trojan, SOCKS, HTTP, Hysteria2, WireGuard", onBack = onBack) {
            HeaderChips(listOf("Поиск", "Ключи ${vm.items.size}", "Настройки"), tab, { tab = it }, listOf(Icons.Rounded.Search, Icons.Rounded.VpnKey, Icons.Rounded.Tune))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> SearchTab(vm, onShow = { tab = 1 })
                1 -> KeysTab(vm)
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
            if (vm.running) {
                SoftCard(color = t.infoSoft, padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(vm.stage, style = MaterialTheme.typography.titleSmall)
                            Text(when (vm.stage) {
                                "Проверка отклика", "Проверка VPN через Xray" -> "${vm.checked} из ${vm.toCheck}"
                                else -> "источников ${vm.sitesDone}/${vm.sitesFound} · страниц ${vm.pages} · найдено ${vm.keysFound}"
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        OutlinedButton(onClick = { vm.stop() }) { Text("Стоп") }
                    }
                    if ((vm.stage == "Проверка отклика" || vm.stage == "Проверка VPN через Xray") && vm.toCheck > 0) LinearProgressIndicator(progress = { vm.checked / vm.toCheck.toFloat() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
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
            Text("Читаются только открытые страницы и публичные списки. Прокси проверяются реальным запросом через них (ip-api.com, httpbin.org), ключи Xray — реальным HTTPS-запросом через встроенное ядро после TCP-предпроверки. Чужие серверы видят ваш трафик: не используйте их для входов и платежей. Hysteria2, TUIC и WireGuard только собираются в список для экспорта.",
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
private fun KeysTab(vm: KeyHubViewModel) {
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
                            Text((TYPE_LABEL[k.type] ?: k.type) + (if (!k.proxy && k.validation == 1) " · HTTPS через Xray ✓" else if (!k.proxy && k.validation == 2) " · ${k.validationNote}" else if (k.tls == 1) " · TCP/TLS ✓" else "") + (if (k.proxy && k.ms >= 0) (ANON_LABEL[k.anon]?.let { " · $it" } ?: "") + (if (k.https == 1) " · HTTPS ✓" else if (k.https == 2) " · без HTTPS" else "") else "") + (if (k.v6) " · IPv6" else "") + " · " + k.source, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        when {
                            k.udp -> Pill("Не проверен", t.info, t.infoSoft)
                            !k.proxy && k.validation == 2 -> Pill("Не работает", t.bad, t.badSoft)
                            !k.proxy && k.validation == 3 -> Pill("Ошибка теста", t.warn, t.warnSoft)
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
                IconButton(onClick = { vm.remove(selected); selected = emptySet() }, enabled = selected.isNotEmpty()) { Icon(Icons.Rounded.Delete, "Удалить", tint = t.bad) }
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
        slider("VPN-ключей с полной проверкой", deep, 10..1000, "deepLimit", { deep = it }, "После TCP-проверки запускается реальный Xray для каждого кандидата. Остальные остаются НЕ ПРОВЕРЕНЫ. Выборочная перепроверка работает без ограничения.")
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
