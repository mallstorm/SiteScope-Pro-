@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package pro.sitescope.app.tv

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.sitescope.app.MulticastProbe
import pro.sitescope.app.ScannerEngine
import pro.sitescope.app.ui.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Найденный поток: распознанное название, логотип (если канал есть в ваших списках) и кадр из потока. */
class FoundChannel(val hit: MaskSearch.Hit, name: String, source: String, logo: String) {
    var name by mutableStateOf(name)
    var nameSource by mutableStateOf(source)
    var logo by mutableStateOf(logo)
    var frame by mutableStateOf<ImageBitmap?>(null)
    /** "" — кадр не запрашивали, "busy" — снимаем кадр и читаем эмблему, "done" — закончили. */
    var ocr by mutableStateOf("")
    val alive get() = hit.status == StreamStatus.OK || hit.status == StreamStatus.SLOW
    val title get() = name.ifEmpty { if (ocr == "busy") "Читаем эмблему…" else "Нет названия" }
    val hostPort get() = hit.url.substringAfter("://").substringBefore('/')
}

class MaskViewModel(app: Application) : AndroidViewModel(app) {
    private val context = app.applicationContext
    private val prefs = context.getSharedPreferences("tv_mask", Context.MODE_PRIVATE)

    var template by mutableStateOf(prefs.getString("template", "http://192.168.1.1:%d{4000-4100}/") ?: "")
    var min by mutableStateOf(prefs.getString("min", "1") ?: "1")
    var max by mutableStateOf(prefs.getString("max", "10000") ?: "10000")
    var wildcards by mutableStateOf(prefs.getString("wildcards", ",udp,live,stream,hls,play") ?: "")
    var names by mutableStateOf(prefs.getString("names", "") ?: "")
    var hosts by mutableStateOf(prefs.getString("hosts", "") ?: "")
    var ports by mutableStateOf(prefs.getString("ports", "") ?: "")
    var concurrency by mutableStateOf(prefs.getInt("concurrency", 16).toFloat())
    var timeoutSec by mutableStateOf(prefs.getInt("timeout", 3).toFloat())
    var maxCandidates by mutableStateOf(prefs.getString("maxCand", "2000") ?: "2000")
    var stopAfter by mutableStateOf(prefs.getString("stopAfter", "0") ?: "0")
    var grabFrames by mutableStateOf(prefs.getBoolean("frames", true))
    var useCatalog by mutableStateOf(prefs.getBoolean("catalog", true))

    val found = mutableStateListOf<FoundChannel>()
    var latest by mutableStateOf<FoundChannel?>(null); private set
    var running by mutableStateOf(false); private set
    var done by mutableStateOf(0L); private set
    var total by mutableStateOf(0L); private set
    var error by mutableStateOf(""); private set
    var finished by mutableStateOf(false); private set
    private val cancel = AtomicBoolean(false)
    private val udpGate = java.util.concurrent.Semaphore(3)   // не больше трёх одновременных подписок на группы
    private val frameExec = Executors.newFixedThreadPool(2) { r -> Thread(r).apply { isDaemon = true } }

    fun save() {
        prefs.edit().putString("template", template).putString("min", min).putString("max", max).putString("wildcards", wildcards)
            .putString("names", names).putString("hosts", hosts).putString("ports", ports)
            .putInt("concurrency", concurrency.toInt()).putInt("timeout", timeoutSec.toInt()).putString("maxCand", maxCandidates)
            .putString("stopAfter", stopAfter).putBoolean("frames", grabFrames).putBoolean("catalog", useCatalog).apply()
    }

    private fun lines(s: String) = s.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }

    /** Параметры подстановки; ошибки адресов и портов не роняют экран, а показываются текстом. */
    fun options(catalogNames: List<String> = emptyList()): Pair<MaskSearch.Options, String> {
        var err = ""
        val hostList = try { if (hosts.isBlank()) emptyList() else ScannerEngine.parseTargets(hosts, 4096) } catch (e: Exception) { err = e.message ?: "Проверьте адреса"; emptyList() }
        val portList = try { if (ports.isBlank()) emptyList() else ScannerEngine.parsePorts(ports, 4096) } catch (e: Exception) { err = e.message ?: "Проверьте порты"; emptyList() }
        val nameList = (lines(names) + (if (useCatalog) catalogNames else emptyList())).distinct()
        return MaskSearch.Options(
            min = min.trim().toLongOrNull() ?: 1L, max = max.trim().toLongOrNull() ?: 10_000L,
            wildcards = wildcards.split(',').map { it.trim() }.let { if (it.all { s -> s.isEmpty() }) listOf("") else it.distinct() },
            names = nameList, hosts = hostList, ports = portList
        ) to err
    }

    fun config() = MaskSearch.ScanConfig(
        concurrency = concurrency.toInt(), timeoutMs = (timeoutSec.toInt() * 1000).coerceIn(1000, 15000),
        maxCandidates = maxCandidates.trim().toLongOrNull() ?: 2000L, stopAfter = stopAfter.trim().toIntOrNull() ?: 0,
        udp = { url, timeout -> udpProbe(url, timeout) }
    )

    /** Подписывается на группу, ждёт первый пакет не дольше таймаута, собирает до 2 МБ потока (хватает на ключевой кадр). */
    private fun udpProbe(url: String, timeoutMs: Int): MaskSearch.UdpResult? {
        udpGate.acquire()
        try {
            val p = MulticastProbe.probe(context, url, timeoutMs, 2 * 1024 * 1024, timeoutMs.toLong().coerceIn(1500, 4000), 3500)
            if (!p.playable) return null
            return MaskSearch.UdpResult(p.serviceName ?: "", p.videoCodec ?: "", p.audioCodec ?: "", p.bitrateKbps, p.latencyMs, p.container ?: "", p.sample)
        } catch (e: Exception) { return null } finally { udpGate.release() }
    }

    fun start() {
        if (running) return
        save(); cancel.set(false); found.clear(); latest = null; error = ""; done = 0; total = 0; finished = false; running = true
        viewModelScope.launch(Dispatchers.IO) {
            val catalog = try { TvStore.load(context); TvStore.snapshot() } catch (e: Exception) { emptyList() }
            val logos = HashMap<String, String>()
            for (c in catalog) if (c.logo.startsWith("http")) logos.putIfAbsent(MaskSearch.normalize(c.name), c.logo)
            val (opts, err) = options(catalog.map { it.name }.distinct().take(300))
            val parsed = MaskSearch.parse(template, opts)
            val cfg = config()
            if (err.isNotEmpty()) { error = err; running = false; return@launch }
            if (parsed.total <= 0) { error = parsed.warnings.firstOrNull() ?: "Нечего перебирать"; running = false; return@launch }
            MaskSearch.scan(parsed, cfg, cancel, object : MaskSearch.Listener {
                override fun progress(done: Long, total: Long) { this@MaskViewModel.done = done; this@MaskViewModel.total = total }
                override fun hit(h: MaskSearch.Hit) {
                    val rec = MaskSearch.recognize(h.serviceName, h.nameHint)
                    val logo = if (rec.name.isNotEmpty()) logos[MaskSearch.normalize(rec.name)].orEmpty() else ""
                    val f = FoundChannel(h, rec.name, rec.source, logo)
                    viewModelScope.launch(Dispatchers.Main) {
                        found.add(f)
                        if (f.alive || latest == null) latest = f
                    }
                    if (!grabFrames) h.sample = null
                    if (grabFrames && f.alive && (h.url.startsWith("http") || h.sample != null)) {
                        viewModelScope.launch(Dispatchers.Main) { f.ocr = "busy" }
                        frameExec.execute {
                            val sample = h.sample
                            val tmp = if (sample != null) java.io.File(context.cacheDir, "mask_" + System.nanoTime() + ".ts").also { it.writeBytes(sample) } else null
                            h.sample = null
                            val cap = try { captureFrames(tmp?.absolutePath ?: h.url, cfg.userAgent, tmp != null) } finally { tmp?.delete() }
                            var ocrName = ""
                            if (cap != null) {
                                val thumb = cap.thumb.asImageBitmap()
                                viewModelScope.launch(Dispatchers.Main) { f.frame = thumb }
                                // Название из данных потока надёжнее; эмблему читаем, если его нет (или оно только из маски).
                                if (f.nameSource != "по данным потока") ocrName = try { LogoOcr.recognize(context, cap.frames) } catch (e: Throwable) { "" }
                                cap.frames.forEach { it.recycle() }
                            }
                            viewModelScope.launch(Dispatchers.Main) {
                                if (ocrName.isNotEmpty()) { f.name = ocrName; f.nameSource = "по эмблеме"; f.logo = logos[MaskSearch.normalize(ocrName)].orEmpty() }
                                f.ocr = "done"
                            }
                        }
                    }
                }
            })
            running = false; finished = true
        }
    }

    fun stop() { cancel.set(true) }

    fun addToChannels(onDone: (Int) -> Unit) {
        val list = found.filter { it.alive }.toList()
        viewModelScope.launch {
            val n = withContext(Dispatchers.IO) {
                TvStore.load(context)
                val prev = TvStore.snapshot().filter { it.sourceId == "mask" }
                val fresh = list.map { f ->
                    Channel().apply {
                        name = f.name.ifEmpty { "Канал " + f.hostPort }; group = "Поиск по маске"; logo = f.logo; url = f.hit.url
                        id = Playlist.channelId(url, name); status = f.hit.status; detail = f.hit.detail
                        startMs = f.hit.startMs; bitrateKbps = f.hit.bitrateKbps; resolution = f.hit.resolution; checkedAt = System.currentTimeMillis()
                    }
                }
                val merged = (prev + fresh).distinctBy { it.id }
                val count = TvStore.putSource(TvSource("mask", "Найдено по маске", "import", ""), PlaylistData(merged, emptyList()))
                TvStore.save(context); count
            }
            TvViewModel.externalChange++
            onDone(n)
        }
    }

    fun m3u(): String = MaskSearch.toM3u(found.filter { it.alive }.map { Triple(it.name.ifEmpty { "Канал " + it.hostPort }, "Поиск по маске", it.hit.url) })

    override fun onCleared() { frameExec.shutdownNow() }

    class Captured(val thumb: Bitmap, val frames: List<Bitmap>)

    /**
     * Снимает до трёх кадров в случайные моменты (первые секунды потока): один показывается в окне (квадрат 192×192),
     * все идут на чтение эмблемы. Блокируется не дольше таймаута.
     */
    private fun captureFrames(source: String, ua: String, local: Boolean): Captured? {
        val ex = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        val task = ex.submit<Captured?> {
            val r = MediaMetadataRetriever()
            try {
                if (local) r.setDataSource(source) else r.setDataSource(source, mapOf("User-Agent" to ua))
                val frames = ArrayList<Bitmap>()
                val rnd = java.util.Random()
                repeat(3) {
                    val at = (rnd.nextInt(if (local) 2500 else 4000) + it * 400L) * 1000L
                    val raw = try { r.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) } catch (e: Throwable) { null } ?: return@repeat
                    val scale = if (raw.width > 1280) 1280f / raw.width else 1f
                    frames.add(if (scale < 1f) Bitmap.createScaledBitmap(raw, 1280, (raw.height * scale).toInt().coerceAtLeast(8), true).also { raw.recycle() } else raw)
                }
                if (frames.isEmpty()) return@submit null
                val shown = frames[rnd.nextInt(frames.size)]
                val side = minOf(shown.width, shown.height)
                val square = Bitmap.createBitmap(shown, (shown.width - side) / 2, (shown.height - side) / 2, side, side)
                Captured(Bitmap.createScaledBitmap(square, 192, 192, true), frames)
            } catch (e: Throwable) { null } finally { try { r.release() } catch (_: Throwable) { } }
        }
        return try { task.get(25, TimeUnit.SECONDS) } catch (e: Exception) { task.cancel(true); null } finally { ex.shutdownNow() }
    }
}

private fun statusColor(s: StreamStatus, t: Tones) = when (s) {
    StreamStatus.OK -> t.good; StreamStatus.SLOW -> t.warn; StreamStatus.AUTH, StreamStatus.BLOCKED -> t.bad; else -> t.info
}

@Composable
fun MaskSearchScreen(vm: MaskViewModel = viewModel()) {
    val context = LocalContext.current
    val t = tones
    var catalogNames by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) { catalogNames = withContext(Dispatchers.IO) { try { TvStore.load(context.applicationContext); TvStore.snapshot().map { it.name }.distinct().take(300) } catch (e: Exception) { emptyList() } } }
    val opts = remember(vm.template, vm.min, vm.max, vm.wildcards, vm.names, vm.hosts, vm.ports, vm.useCatalog, catalogNames) { vm.options(catalogNames) }
    val parsed = remember(opts, vm.template) { MaskSearch.parse(vm.template, opts.first) }
    val cfg = vm.config()
    val toCheck = MaskSearch.limit(parsed, cfg)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
        RecognitionWindow(vm.latest, vm.running)

        SectionTitle("Маска ссылки")
        SoftCard {
            BigField(vm.template, { vm.template = it }, "http://95.67.12.82:%d{9000-9100}/", leading = Icons.Rounded.Link, mono = true, singleLine = false, minLines = 2)
            Spacer(Modifier.height(8.dp))
            Text("Подстановки: %d — число из диапазона; %d{9000-9100} — свой диапазон; %03d — с нулями; (http|https|rtsp) — один из вариантов; (.*) — любое значение из списка ниже; channel_name — название канала; xxx.xxx.xxx.xxx — IP из списка; yyyyy — порт из списка.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val base = TvStore.udpxyBase.ifEmpty { "http://192.168.1.1:4022" }.trimEnd('/')
                AssistChip(onClick = { vm.template = "http://95.67.12.82:%d{9000-9100}" }, label = { Text("Порт в диапазоне") })
                AssistChip(onClick = { vm.template = "$base/udp/239.255.%d{0-255}.%d{1-254}:1234" }, label = { Text("udpxy: группы") })
                AssistChip(onClick = { vm.template = "udp://@239.255.%d{0-255}.%d{1-254}:1234" }, label = { Text("Мультикаст udp") })
                AssistChip(onClick = { vm.template = "(http|https)://xxx.xxx.xxx.xxx:yyyyy/(.*)/channel_name/(.*).m3u8" }, label = { Text("Путь с названием") })
                AssistChip(onClick = { vm.template = "http://192.168.1.1:8080/%d" }, label = { Text("Нумерация %d") })
            }
        }

        SectionTitle("Что подставлять")
        SoftCard {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(vm.min, { vm.min = it.filter { c -> c.isDigit() } }, Modifier.weight(1f), label = { Text("%d от") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(vm.max, { vm.max = it.filter { c -> c.isDigit() } }, Modifier.weight(1f), label = { Text("%d до") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(vm.hosts, { vm.hosts = it }, Modifier.fillMaxWidth(), label = { Text("IP для xxx.xxx.xxx.xxx (192.168.1.0/24, 95.67.12.80-90)") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(vm.ports, { vm.ports = it }, Modifier.fillMaxWidth(), label = { Text("Порты для yyyyy (9060-9100, 8080)") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(vm.wildcards, { vm.wildcards = it }, Modifier.fillMaxWidth(), label = { Text("Варианты для (.*) через запятую; пусто = ничего") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(vm.names, { vm.names = it }, Modifier.fillMaxWidth(), label = { Text("Названия для channel_name — по одному в строке") }, minLines = 2)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Добавить названия из моих каналов (${catalogNames.size})", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(vm.useCatalog, { vm.useCatalog = it })
            }
        }

        SectionTitle("Параметры проверки")
        SoftCard {
            Text("Одновременных проверок: ${vm.concurrency.toInt()}", style = MaterialTheme.typography.bodyMedium)
            Slider(vm.concurrency, { vm.concurrency = it }, valueRange = 1f..48f, steps = 46)
            Text("Ожидание ответа: ${vm.timeoutSec.toInt()} с", style = MaterialTheme.typography.bodyMedium)
            Slider(vm.timeoutSec, { vm.timeoutSec = it }, valueRange = 1f..15f, steps = 13)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(vm.maxCandidates, { vm.maxCandidates = it.filter { c -> c.isDigit() }.take(6) }, Modifier.weight(1f), label = { Text("Проверить не более") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(vm.stopAfter, { vm.stopAfter = it.filter { c -> c.isDigit() }.take(4) }, Modifier.weight(1f), label = { Text("Стоп после найденных (0 — нет)") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Снимать кадр и читать эмблему канала (OCR)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(vm.grabFrames, { vm.grabFrames = it })
            }
            Text("Проверяйте только свою сеть и ресурсы, к которым у вас есть право доступа. Логины и пароли не подбираются. Ссылки udp://@группа:порт проверяются подпиской на мультикаст (только в сети провайдера по Wi-Fi/Ethernet, до трёх групп одновременно, молчащая группа занимает до нескольких секунд); rtp:// пока не проверяются.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(Modifier.height(12.dp))
        val problems = parsed.warnings + listOfNotNull(opts.second.takeIf { it.isNotEmpty() })
        if (problems.isNotEmpty()) problems.forEach { Text("• $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Text(if (parsed.total > 0) "Комбинаций: ${parsed.total}" + if (toCheck < parsed.total) ", будет проверено первых $toCheck" else "" else "Комбинаций нет",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 6.dp))
        if (vm.running) {
            LinearProgressIndicator(progress = { if (vm.total > 0) vm.done.toFloat() / vm.total else 0f }, modifier = Modifier.fillMaxWidth())
            Text("Проверено ${vm.done} из ${vm.total} · найдено ${vm.found.count { it.alive }}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            OutlinedButton(onClick = { vm.stop() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Остановить") }
        } else {
            Button(onClick = { vm.start() }, enabled = parsed.total > 0 && opts.second.isEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Rounded.Search, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Искать потоки")
            }
            if (vm.error.isNotEmpty()) { Spacer(Modifier.height(8.dp)); ErrorCard(vm.error) }
        }

        if (vm.found.isNotEmpty()) {
            SectionTitle("Найдено: ${vm.found.count { it.alive }}")
            val alive = vm.found.count { it.alive }
            if (!vm.running && alive > 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                Button(onClick = { vm.addToChannels { n -> toast(context, "В моих каналах: $n. Откройте вкладку «ТВ».") } }, modifier = Modifier.weight(1f)) { Text("В мои каналы") }
                OutlinedButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("m3u", vm.m3u())); toast(context, "Список M3U скопирован")
                }, modifier = Modifier.weight(1f)) { Text("Копировать M3U") }
            }
            vm.found.forEach { f ->
                SoftCard(Modifier.padding(bottom = 8.dp), padding = 12.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FrameSquare(f, 56.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(f.hit.url, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(listOf(f.hit.status.title, f.hit.resolution, if (f.hit.bitrateKbps > 0) "${f.hit.bitrateKbps} кбит/с" else "", f.nameSource).filter { it.isNotEmpty() }.joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = statusColor(f.hit.status, t))
                        }
                    }
                }
            }
        }
    }
}

/** Окно распознавания вверху экрана: маленький квадрат с кадром или логотипом и название канала. */
@Composable
private fun RecognitionWindow(f: FoundChannel?, running: Boolean) {
    val t = tones
    SoftCard(Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (f != null) FrameSquare(f, 96.dp) else Box(Modifier.size(96.dp).clip(RoundedCornerShape(16.dp)).background(t.tvSoft), contentAlignment = Alignment.Center) {
                if (running) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp) else Icon(Icons.Rounded.Tv, null, tint = t.tv, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                if (f == null) {
                    Text(if (running) "Ищем поток…" else "Здесь появится канал", style = MaterialTheme.typography.titleMedium)
                    Text("Кадр из потока в случайный момент и название, прочитанное с эмблемы: 1+1, Интер, НТВ, РЕН ТВ…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(f.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(if (f.name.isEmpty()) (if (f.ocr == "busy") "Снимаем кадр и читаем эмблему…" else "Эмблема не видна — названия нет") else "Название ${f.nameSource}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(f.hit.url, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.outline)
                    Pill(f.hit.status.title, statusColor(f.hit.status, t), t.infoSoft)
                }
            }
        }
    }
}

@Composable
private fun FrameSquare(f: FoundChannel, size: androidx.compose.ui.unit.Dp) {
    val t = tones
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 5)).background(t.tvSoft), contentAlignment = Alignment.Center) {
        Text(f.title.take(2).uppercase(), color = t.tv, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
        val frame = f.frame
        if (frame != null) Image(frame, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else if (f.logo.startsWith("http")) AsyncImage(f.logo, null, Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(4.dp), contentScale = ContentScale.Fit)
        if (frame != null && f.logo.startsWith("http")) AsyncImage(f.logo, null, Modifier.align(Alignment.TopStart).size(size / 3).padding(2.dp), contentScale = ContentScale.Fit)
    }
}
