@file:OptIn(ExperimentalMaterial3Api::class)

package pro.sitescope.app.tv

import android.content.Context
import android.net.wifi.WifiManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.sitescope.app.IptvTargets
import kotlinx.coroutines.Job
import pro.sitescope.app.IptvEngine
import pro.sitescope.app.Models
import pro.sitescope.app.ScannerEngine
import pro.sitescope.app.net.NetViewModel
import pro.sitescope.app.ui.*

/** Телевидение от провайдера в домашней сети: поиск шлюза udpxy и каналов, которые сеть объявляет сама (SAP). */
@Composable
fun TvLanScreen(net: NetViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val t = tones
    var targets by remember { mutableStateOf(net.localSubnet()) }
    var ports by remember { mutableStateOf("4022,8080,81,80") }
    val discoveryPrefs = remember { context.getSharedPreferences("iptv_discovery", Context.MODE_PRIVATE) }
    var threadCount by remember { mutableIntStateOf(discoveryPrefs.getInt("threads", 8).coerceIn(2, 250)) }
    var delayMs by remember { mutableIntStateOf(discoveryPrefs.getInt("delay_ms", 0).coerceIn(0, 5000)) }
    var searching by remember { mutableStateOf(false) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    val gateways = remember { mutableStateListOf<Models.UdpxyEndpoint>() }
    var current by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var sapStatus by remember { mutableStateOf("") }
    val announced = remember { mutableStateListOf<Models.IptvChannel>() }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { TvStore.load(context.applicationContext) }; current = TvStore.udpxyBase }

    fun choose(base: String) = scope.launch {
        withContext(Dispatchers.IO) { TvStore.udpxyBase = base; TvStore.save(context.applicationContext) }
        current = base; TvViewModel.externalChange++
        toast(context, if (base.isEmpty()) "Шлюз отключён" else "Шлюз выбран: каналы пойдут через него")
    }

    fun findGateways() = scope.launch {
        searching = true; error = ""; gateways.clear(); status = "Готовим список адресов…"
        val workers = threadCount; val delay = delayMs
        discoveryPrefs.edit().putInt("threads", workers).putInt("delay_ms", delay).apply()
        val job = coroutineContext[Job]
        try { withContext(Dispatchers.IO) {
            try {
                val hosts = IptvTargets.parse(targets); val list = ScannerEngine.parsePorts(ports)
                IptvEngine.discoverUdpxy(hosts, list, 900, workers, delay.toLong(), object : IptvEngine.DiscoveryListener {
                    override fun onProgress(completed: Long, total: Long, currentHost: String?) { status = "Проверено $completed из $total" }
                    override fun isCancelled(): Boolean = job?.isActive == false
                    override fun onFound(endpoint: Models.UdpxyEndpoint?) { if (endpoint != null) scope.launch { gateways.add(endpoint) } }
                })
            } catch (e: Exception) { error = e.message ?: "Проверьте адреса и порты" }
        }
        } finally { searching = false }
        status = if (gateways.isEmpty() && error.isEmpty()) "Шлюз не найден. Возможно, провайдер его не использует — тогда каналы открываются напрямую." else ""
    }.also { searchJob = it }

    fun listen() = scope.launch {
        listening = true; announced.clear(); sapStatus = "Слушаем сеть 8 секунд…"
        val lock = try { (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createMulticastLock("sitescope-sap").apply { setReferenceCounted(false); acquire() } } catch (e: Exception) { null }
        withContext(Dispatchers.IO) {
            try {
                IptvEngine.discoverSap(8, object : IptvEngine.SapListener {
                    override fun onChannel(channel: Models.IptvChannel?) { if (channel != null) scope.launch { announced.add(channel) } }
                    override fun onStatus(value: String?) { }
                })
            } catch (e: Exception) { }
        }
        try { lock?.release() } catch (e: Exception) { }
        listening = false; sapStatus = if (announced.isEmpty()) "Сеть не объявляет каналы. Это нормально: так делают не все провайдеры." else ""
    }

    fun addAnnounced() = scope.launch {
        val count = withContext(Dispatchers.IO) {
            val list = announced.filter { it.sourceUrl.isNotEmpty() }.map { src ->
                Channel().apply { name = src.name.ifEmpty { src.sourceUrl }; group = src.group.ifEmpty { "Локальная сеть" }; logo = src.logoUrl; url = src.sourceUrl; id = Playlist.channelId(url, name) }
            }
            val n = TvStore.putSource(TvSource("sap", "Каналы из локальной сети", "import", ""), PlaylistData(list, emptyList()))
            TvStore.save(context.applicationContext); n
        }
        TvViewModel.externalChange++
        toast(context, "Добавлено каналов: $count. Они появились на вкладке «ТВ».")
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
        Text("Для телевидения, которое провайдер раздаёт внутри своей сети. Работает только по Wi-Fi этой сети.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))

        SectionTitle("Шлюз udpxy")
        SoftCard {
            Text("Шлюз на роутере превращает «сырой» поток провайдера в обычную ссылку, которую может открыть плеер.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (current.isNotEmpty()) {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill("Выбран", t.good, t.goodSoft, icon = Icons.Rounded.Check)
                    Spacer(Modifier.width(8.dp))
                    Text(current, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TextButton(onClick = { choose("") }) { Text("Отключить") }
                }
            }
            Spacer(Modifier.height(10.dp))
            BigField(targets, { targets = it }, "Где искать: 192.168.1.0/24", leading = Icons.Rounded.Lan, mono = true)
            Spacer(Modifier.height(8.dp))
            BigField(ports, { ports = it }, "Порты", leading = Icons.Rounded.Tag, keyboard = KeyboardType.Ascii, mono = true)
            Text("Параллельных потоков: $threadCount", modifier = Modifier.padding(top = 12.dp))
            Slider(value = threadCount.toFloat(), onValueChange = { threadCount = it.roundToInt() }, valueRange = 2f..250f, steps = 247, enabled = !searching)
            Text("Задержка между стартами проверок: $delayMs мс")
            Slider(value = delayMs.toFloat(), onValueChange = { delayMs = (it / 50).roundToInt() * 50 }, valueRange = 0f..5000f, steps = 99, enabled = !searching)
            Button(onClick = { findGateways() }, enabled = !searching && targets.isNotBlank(), modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (searching) "Ищем…" else "Найти шлюз")
            }
            if (searching) TextButton(onClick = { searchJob?.cancel(); status = "Поиск остановлен" }) { Text("Остановить поиск") }
            if (searching) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
        if (error.isNotEmpty()) { Spacer(Modifier.height(8.dp)); ErrorCard(error) }
        gateways.forEach { g ->
            Spacer(Modifier.height(8.dp))
            SoftCard(padding = 14.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Router, t.tv, t.tvSoft, 42.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(g.host + ":" + g.port, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                        Text((if (g.confirmed) "Подтверждён" else "Похож на шлюз") + (if (g.version.isNotEmpty()) " · версия " + g.version else "") + " · ${g.latencyMs} мс",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (current == g.baseUrl) Pill("Выбран", t.good, t.goodSoft) else Button(onClick = { choose(g.baseUrl) }) { Text("Выбрать") }
                }
            }
        }

        SectionTitle("Каналы, которые объявляет сеть")
        SoftCard {
            Text("Некоторые провайдеры сами рассылают список каналов по сети. Приложение только слушает, ничего не отправляя.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = { listen() }, enabled = !listening, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) {
                Icon(Icons.Rounded.Hearing, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (listening) "Слушаем…" else "Послушать сеть")
            }
            if (listening) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            if (sapStatus.isNotEmpty()) Text(sapStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            if (announced.isNotEmpty()) {
                Text("Найдено каналов: ${announced.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
                announced.take(60).forEach { c ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(c.name.ifEmpty { "Без названия" }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.sourceUrl.substringAfter("@"), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline)
                    }
                }
                if (!listening) Button(onClick = { addAnnounced() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Добавить в мои каналы") }
            }
        }
    }
}
