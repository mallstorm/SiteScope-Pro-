@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package pro.sitescope.app.ip

import android.content.Context
import android.net.Uri
import android.os.StatFs
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.sitescope.app.IpDataStore
import pro.sitescope.app.RipeStatClient
import pro.sitescope.app.ui.*
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream

private fun human(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f ГБ", bytes / 1073741824.0)
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.0f МБ", bytes / 1048576.0)
    else -> "${bytes / 1024} КБ"
}

private fun grouped(value: Long): String = value.toString().reversed().chunked(3).joinToString(" ").reversed()

/**
 * Загрузка и установка базы DB-IP. Живёт на уровне приложения, поэтому не прерывается,
 * если выйти с экрана. Порядок действий тот же, что в прежней версии: файл → разбор →
 * атомарная замена таблицы → полная проверка.
 */
object IpDbWork {
    var running by mutableStateOf(false); private set
    var message by mutableStateOf(""); private set
    var progress by mutableFloatStateOf(-1f); private set
    var status by mutableStateOf<IpDataStore.Status?>(null); private set
    private val cancel = AtomicBoolean(false)
    private const val UA = "SiteScopePro/1.0 IP-Intelligence"
    private const val BASE = "https://download.db-ip.com/free/"

    fun refresh(context: Context) {
        val app = context.applicationContext
        Thread { val s = try { IpDataStore(app).status() } catch (e: Exception) { null }; status = s }.start()
    }

    fun stop() { cancel.set(true); message = "Останавливаем…" }

    private fun listener() = object : IpDataStore.Progress {
        override fun onProgress(rows: Long, stage: String?) { progress = -1f; message = (stage ?: "Обработка") + ": " + grouped(rows) + " строк" }
        override fun isCancelled(): Boolean = cancel.get()
    }

    private fun start(context: Context, first: String, body: (Context, IpDataStore) -> String) {
        if (running) return
        val app = context.applicationContext
        running = true; cancel.set(false); message = first; progress = -1f
        Thread {
            val db = IpDataStore(app)
            val result = try { body(app, db) } catch (e: Exception) { "Не получилось: " + (e.message ?: e.javaClass.simpleName) }
            status = try { db.status() } catch (e: Exception) { status }
            message = result; progress = -1f; running = false
        }.start()
    }

    fun download(context: Context, city: Boolean) = start(context, "Ищем свежую версию…") { app, db ->
        val dir = File(app.filesDir, "ip-intelligence")
        if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("нет доступа к памяти приложения")
        val file = fetchLatest(city, dir)
        try {
            message = "Устанавливаем ${file.name}…"; progress = -1f
            val input = maybeGzip(FileInputStream(file))
            val count = if (city) db.importCity(input, file.name, listener()) else db.importAsn(input, file.name, listener())
            message = "Проверяем целостность…"
            db.validate(true)
            "Готово: установлено " + grouped(count) + " диапазонов"
        } finally { if (file.exists()) file.delete() }
    }

    fun importFile(context: Context, city: Boolean, uri: Uri, name: String) = start(context, "Читаем файл…") { app, db ->
        val input = maybeGzip(app.contentResolver.openInputStream(uri))
        val count = if (city) db.importCity(input, name, listener()) else db.importAsn(input, name, listener())
        message = "Проверяем целостность…"
        db.validate(true)
        "Готово: установлено " + grouped(count) + " диапазонов"
    }

    fun validate(context: Context) = start(context, "Проверяем целостность базы…") { _, db ->
        val s = db.validate(true); if (s.overallValid) "База в порядке" else "Найдены проблемы — переустановите повреждённую часть"
    }

    fun clear(context: Context, city: Boolean) = start(context, "Удаляем…") { _, db -> if (city) db.clearCity() else db.clearAsn(); "Удалено" }

    /** Проверяет, вышла ли версия новее установленной. Возвращает её номер (ГГГГ-ММ) или пустую строку. */
    fun newerVersion(city: Boolean, installed: String): String {
        val name = latestName(city) ?: return ""
        val remote = IpDataStore.sourceVersion(name) ?: ""
        return if (remote.isNotEmpty() && remote > installed) remote else ""
    }

    private fun months(): List<String> {
        val out = ArrayList<String>()
        for (offset in 0 until 4) {
            val c = Calendar.getInstance(); c.add(Calendar.MONTH, -offset)
            out.add(String.format(Locale.US, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1))
        }
        return out
    }

    private fun fileName(city: Boolean, month: String) = "dbip-" + (if (city) "city" else "asn") + "-lite-" + month + ".csv.gz"

    private fun latestName(city: Boolean): String? {
        for (month in months()) {
            val name = fileName(city, month)
            var c: HttpURLConnection? = null
            try {
                c = URL(BASE + name).openConnection() as HttpURLConnection
                c.connectTimeout = 12000; c.readTimeout = 12000
                c.setRequestProperty("User-Agent", UA); c.setRequestProperty("Range", "bytes=0-0")
                if (c.responseCode in 200..399) return name
            } catch (e: Exception) { } finally { c?.disconnect() }
        }
        return null
    }

    private fun fetchLatest(city: Boolean, dir: File): File {
        var last: Exception? = null
        for (month in months()) {
            val name = fileName(city, month)
            val part = File(dir, "$name.part")
            var c: HttpURLConnection? = null
            try {
                c = URL(BASE + name).openConnection() as HttpURLConnection
                c.connectTimeout = 15000; c.readTimeout = 30000; c.setRequestProperty("User-Agent", UA)
                val code = c.responseCode
                if (code == 404) continue
                if (code !in 200..299) throw IllegalStateException("сервер DB-IP ответил $code")
                val total = c.contentLengthLong
                var loaded = 0L
                BufferedInputStream(c.inputStream, 128 * 1024).use { input ->
                    FileOutputStream(part).use { out ->
                        val buffer = ByteArray(128 * 1024)
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            if (cancel.get()) throw InterruptedException("загрузка остановлена")
                            out.write(buffer, 0, n); loaded += n
                            progress = if (total > 0) (loaded.toFloat() / total).coerceIn(0f, 1f) else -1f
                            message = "Загружено " + human(loaded) + (if (total > 0) " из " + human(total) else "")
                        }
                    }
                }
                if (total > 0 && loaded != total) throw IllegalStateException("файл загружен не полностью")
                val target = File(dir, name)
                if (target.exists()) target.delete()
                if (!part.renameTo(target)) throw IllegalStateException("не удалось сохранить файл")
                return target
            } catch (e: InterruptedException) { if (part.exists()) part.delete(); throw e
            } catch (e: Exception) { last = e; if (part.exists()) part.delete() } finally { c?.disconnect() }
        }
        throw last ?: IllegalStateException("свежая база DB-IP не найдена")
    }

    private fun maybeGzip(source: InputStream?): InputStream {
        if (source == null) throw IllegalStateException("файл недоступен")
        val input = BufferedInputStream(source, 128 * 1024)
        input.mark(4); val a = input.read(); val b = input.read(); input.reset()
        return if (a == 0x1f && b == 0x8b) GZIPInputStream(input, 128 * 1024) else input
    }
}

@Composable
fun IpDbScreen(startTab: Int = 0) {
    var tab by remember { mutableIntStateOf(startTab) }
    val context = LocalContext.current
    LaunchedEffect(Unit) { IpDbWork.refresh(context) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("База", "По месту", "По провайдеру").forEachIndexed { i, title -> FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(title) }) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
            when (tab) { 0 -> DatabaseTab(); 1 -> PlaceTab(); else -> ProviderTab() }
        }
    }
}

// ------------------------------------------------------------------ База

@Composable
private fun DatabaseTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status = IpDbWork.status
    var confirmClear by remember { mutableStateOf<Boolean?>(null) }
    var noSpace by remember { mutableStateOf<String?>(null) }
    var pickCity by remember { mutableStateOf(true) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) IpDbWork.importFile(context, pickCity, uri, uri.lastPathSegment?.substringAfterLast('/') ?: "файл")
    }
    fun download(city: Boolean) {
        if (city) {
            val free = try { StatFs(context.filesDir.absolutePath).availableBytes } catch (e: Exception) { Long.MAX_VALUE }
            if (free < 3L * 1024 * 1024 * 1024) { noSpace = human(free); return }
        }
        IpDbWork.download(context, city)
    }
    fun checkUpdate(city: Boolean, installed: String) = scope.launch {
        val newer = withContext(Dispatchers.IO) { IpDbWork.newerVersion(city, installed) }
        if (newer.isEmpty()) toast(context, "Установлена последняя версия") else download(city)
    }

    Spacer(Modifier.height(8.dp))
    Text("База работает без интернета: по ней приложение мгновенно определяет страну, город и провайдера адреса. Данные — бесплатная DB-IP Lite, обновляется раз в месяц.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (IpDbWork.running || IpDbWork.message.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        SoftCard(color = if (IpDbWork.running) tones.ipSoft else MaterialTheme.colorScheme.surfaceContainerLowest) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(IpDbWork.message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (IpDbWork.running) TextButton(onClick = { IpDbWork.stop() }) { Text("Стоп") }
            }
            if (IpDbWork.running) {
                val p = IpDbWork.progress
                if (p >= 0f) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                else LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                Text("Можно свернуть приложение — установка продолжится.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    DatasetCard("Страны и города", "Где находится адрес. Большая: скачивание около 150 МБ, на телефоне займёт до 3 ГБ.", Icons.Rounded.Place, status?.city,
        onDownload = { download(true) }, onUpdate = { checkUpdate(true, status?.city?.version ?: "") }, onFile = { pickCity = true; picker.launch(arrayOf("*/*")) }, onClear = { confirmClear = true })
    Spacer(Modifier.height(10.dp))
    DatasetCard("Провайдеры", "Кому принадлежит адрес. Небольшая, ставится за пару минут.", Icons.Rounded.Business, status?.asn,
        onDownload = { download(false) }, onUpdate = { checkUpdate(false, status?.asn?.version ?: "") }, onFile = { pickCity = false; picker.launch(arrayOf("*/*")) }, onClear = { confirmClear = false })
    Spacer(Modifier.height(10.dp))
    OutlinedButton(onClick = { IpDbWork.validate(context) }, enabled = !IpDbWork.running && (status?.city?.installed == true || status?.asn?.installed == true), modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Rounded.FactCheck, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Проверить целостность")
    }

    confirmClear?.let { city ->
        ConfirmDialog("Удалить базу?", if (city) "База стран и городов будет удалена с телефона. Её можно скачать снова." else "База провайдеров будет удалена с телефона. Её можно скачать снова.", "Удалить", { confirmClear = null }) {
            confirmClear = null; IpDbWork.clear(context, city)
        }
    }
    noSpace?.let { ConfirmDialog("Мало свободного места", "Для базы городов нужно около 3 ГБ свободной памяти, а сейчас доступно $it. Освободите место или установите только базу провайдеров.", "Понятно", null) { noSpace = null } }
}

@Composable
private fun DatasetCard(title: String, text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, item: IpDataStore.DatasetState?,
                        onDownload: () -> Unit, onUpdate: () -> Unit, onFile: () -> Unit, onClear: () -> Unit) {
    val t = tones
    val busy = IpDbWork.running
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, t.ip, t.ipSoft, 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(10.dp))
        when {
            item == null -> Text("Читаем состояние…", style = MaterialTheme.typography.bodySmall)
            !item.installed -> Pill("Не установлена", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh)
            !item.valid -> Pill("Повреждена", t.bad, t.badSoft, icon = Icons.Rounded.ErrorOutline)
            item.current || item.version.isEmpty() -> Pill("Установлена", t.good, t.goodSoft, icon = Icons.Rounded.Check)
            else -> Pill("Есть версия новее", t.warn, t.warnSoft, icon = Icons.Rounded.Update)
        }
        if (item != null && item.installed) {
            Spacer(Modifier.height(6.dp))
            InfoRow("Диапазонов", grouped(item.rows), copyable = false)
            InfoRow("Версия", item.version, copyable = false)
            if (item.importedAt > 0) InfoRow("Установлена", SimpleDateFormat("d MMM yyyy", Locale("ru")).format(Date(item.importedAt)), copyable = false)
            if (!item.valid) InfoRow("Проблема", item.issue, copyable = false)
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (item != null && item.installed && item.valid) Button(onClick = onUpdate, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Обновить") }
            else Button(onClick = onDownload, enabled = !busy && item != null, modifier = Modifier.weight(1f)) { Text(if (item?.installed == true) "Переустановить" else "Скачать") }
            OutlinedButton(onClick = onFile, enabled = !busy) { Text("Из файла") }
            if (item?.installed == true) IconButton(onClick = onClear, enabled = !busy) { Icon(Icons.Rounded.DeleteOutline, "Удалить") }
        }
    }
}

// ------------------------------------------------------------------ По месту

@Composable
private fun PlaceTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { IpDataStore(context.applicationContext) }
    var countries by remember { mutableStateOf<List<String>>(emptyList()) }
    var country by remember { mutableStateOf("") }
    var region by remember { mutableStateOf("") }
    var city by remember { mutableStateOf("") }
    var regions by remember { mutableStateOf<List<String>>(emptyList()) }
    var cities by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var ranges by remember { mutableStateOf<List<IpDataStore.GeoRange>?>(null) }
    var providers by remember { mutableStateOf<List<IpDataStore.ProviderSummary>>(emptyList()) }
    var online by remember { mutableStateOf<RipeStatClient.CountryResources?>(null) }
    var pick by remember { mutableStateOf(0) }
    val code = country.substringBefore(" — ").trim()
    LaunchedEffect(Unit) { countries = withContext(Dispatchers.IO) { try { db.countries(400) } catch (e: Exception) { emptyList() } } }
    LaunchedEffect(code) { region = ""; city = ""; regions = if (code.isEmpty()) emptyList() else withContext(Dispatchers.IO) { try { db.regions(code, 500) } catch (e: Exception) { emptyList() } } }
    LaunchedEffect(code, region) { city = ""; cities = if (code.isEmpty()) emptyList() else withContext(Dispatchers.IO) { try { db.cities(code, region, 800) } catch (e: Exception) { emptyList() } } }

    fun load(more: Boolean) = scope.launch {
        busy = true
        val offset = if (more) ranges?.size ?: 0 else 0
        val page = withContext(Dispatchers.IO) { try { db.searchGeo(code, region, city, 100, offset) } catch (e: Exception) { emptyList() } }
        ranges = if (more) (ranges ?: emptyList()) + page else page
        if (!more) {
            providers = withContext(Dispatchers.IO) { try { db.providersForGeo(code, region, city, 5000) } catch (e: Exception) { emptyList() } }
            online = null
        }
        busy = false
    }

    Spacer(Modifier.height(8.dp))
    if (IpDbWork.status?.city?.installed != true) {
        EmptyState(Icons.Rounded.Place, "Нужна база городов", "Чтобы искать адреса по стране и городу, установите базу «Страны и города» на вкладке «База».")
        return
    }
    SoftCard {
        PickRow("Страна", country.ifEmpty { "Выбрать" }) { pick = 1 }
        if (regions.isNotEmpty()) PickRow("Регион", region.ifEmpty { "Любой" }) { pick = 2 }
        if (cities.isNotEmpty()) PickRow("Город", city.ifEmpty { "Любой" }) { pick = 3 }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { load(false) }, enabled = code.isNotEmpty() && !busy, modifier = Modifier.weight(1f)) { Text("Показать адреса") }
            OutlinedButton(onClick = { scope.launch { busy = true; online = withContext(Dispatchers.IO) { try { RipeStatClient.countryResources(code) } catch (e: Exception) { null } }; busy = false } },
                enabled = code.isNotEmpty() && !busy) { Text("Вся страна") }
        }
    }
    if (busy) { Spacer(Modifier.height(10.dp)); LoadingCard("Ищем…") }
    online?.let { r ->
        SectionTitle("Все сети страны ${r.countryCode}")
        if (r.error.isNotEmpty()) ErrorCard("Не удалось получить список: проверьте интернет")
        else SoftCard {
            Text("Сетей IPv4: ${r.ipv4.size} · IPv6: ${r.ipv6.size} · провайдеров: ${r.asns.size}", style = MaterialTheme.typography.bodyMedium)
            Text("По данным RIPEstat", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { copyText(context, r.ipv4.joinToString("\n"), "Список IPv4 скопирован") }) { Text("Копировать IPv4") }
                OutlinedButton(onClick = { copyText(context, r.ipv6.joinToString("\n"), "Список IPv6 скопирован") }) { Text("IPv6") }
            }
        }
    }
    if (providers.isNotEmpty()) {
        SectionTitle("Провайдеры здесь")
        SoftCard(padding = 8.dp) {
            providers.take(40).forEach { p ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { copyText(context, "AS" + p.asn) }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.organization.ifEmpty { "AS" + p.asn }, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("AS" + p.asn, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    ranges?.let { list ->
        SectionTitle("Диапазоны адресов", action = if (list.isNotEmpty()) "Копировать" else null, onAction = { copyText(context, list.joinToString("\n") { it.cidrs().replace(", ", "\n") }, "Диапазоны скопированы") })
        if (list.isEmpty()) Text("Ничего не найдено.", style = MaterialTheme.typography.bodyMedium)
        else SoftCard(padding = 12.dp) {
            list.forEach { r ->
                Column(Modifier.fillMaxWidth().clickable { copyText(context, r.cidrs()) }.padding(vertical = 5.dp)) {
                    Text(r.cidrs(), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOf(r.city, r.region, r.organization).filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (list.size % 100 == 0) TextButton(onClick = { load(true) }, enabled = !busy) { Text("Показать ещё") }
        }
    }
    if (pick != 0) PickSheet(when (pick) { 1 -> "Страна"; 2 -> "Регион"; else -> "Город" }, when (pick) { 1 -> countries; 2 -> regions; else -> cities }, pick != 1, { pick = 0 }) { value ->
        when (pick) { 1 -> country = value; 2 -> region = value; else -> city = value }
        ranges = null; providers = emptyList(); pick = 0
    }
}

@Composable
private fun PickRow(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
        Text(value, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Icon(Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun PickSheet(title: String, values: List<String>, allowAny: Boolean, onClose: () -> Unit, onPick: (String) -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = values.filter { query.isBlank() || it.contains(query.trim(), true) }.take(200)
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            BigField(query, { query = it }, "Поиск", keyboard = KeyboardType.Text)
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (allowAny) Text("Любой", Modifier.fillMaxWidth().clickable { onPick("") }.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.primary)
                shown.forEach { v -> Text(v, Modifier.fillMaxWidth().clickable { onPick(v) }.padding(vertical = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

// ------------------------------------------------------------------ По провайдеру

@Composable
private fun ProviderTab() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val db = remember { IpDataStore(context.applicationContext) }
    var query by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf<List<IpDataStore.AsnRange>?>(null) }
    var online by remember { mutableStateOf<List<String>>(emptyList()) }

    fun search() = scope.launch {
        val q = query.trim(); if (q.isEmpty()) return@launch
        busy = true
        local = withContext(Dispatchers.IO) { try { db.searchAsn(q, 1000, 0) } catch (e: Exception) { emptyList() } }
        val number = q.removePrefix("AS").removePrefix("as").trim()
        online = if (number.isNotEmpty() && number.all { it.isDigit() }) withContext(Dispatchers.IO) { try { RipeStatClient.announcedPrefixes("AS$number") } catch (e: Exception) { emptyList() } } else emptyList()
        busy = false
    }

    Spacer(Modifier.height(8.dp))
    BigField(query, { query = it }, "Название провайдера или номер: Ростелеком, AS12389", onGo = { search() }, keyboard = KeyboardType.Text)
    Button(onClick = { search() }, enabled = query.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Найти сети") }
    if (busy) { Spacer(Modifier.height(10.dp)); LoadingCard("Ищем сети провайдера…") }
    if (online.isNotEmpty()) {
        SectionTitle("Сети в интернете сейчас: ${online.size}", action = "Копировать", onAction = { copyText(context, online.joinToString("\n"), "Список скопирован") })
        Text("Что провайдер объявляет прямо сейчас, по данным RIPEstat", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        MonoBlock(online.take(200).joinToString("\n") + (if (online.size > 200) "\n… ещё ${online.size - 200}" else ""), Modifier.padding(top = 6.dp))
    }
    local?.let { list ->
        val byOrg = list.groupBy { "AS" + it.asn + " · " + it.organization }
        SectionTitle("В базе на телефоне: ${list.size}", action = if (list.isNotEmpty()) "Копировать" else null, onAction = { copyText(context, list.joinToString("\n") { it.cidrs().replace(", ", "\n") }, "Список скопирован") })
        if (list.isEmpty() && online.isEmpty()) Text(if (IpDbWork.status?.asn?.installed == true) "Ничего не найдено. Попробуйте другое написание или номер AS." else "База провайдеров не установлена — поставьте её на вкладке «База» или введите номер AS.",
            style = MaterialTheme.typography.bodyMedium)
        byOrg.entries.take(30).forEach { (org, items) ->
            Spacer(Modifier.height(8.dp))
            SoftCard(padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(org, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { copyText(context, items.joinToString("\n") { it.cidrs().replace(", ", "\n") }, "Сети скопированы") }) { Icon(Icons.Rounded.ContentCopy, "Копировать") }
                }
                Text(items.take(12).joinToString("\n") { it.cidrs() } + (if (items.size > 12) "\n… ещё ${items.size - 12}" else ""), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
