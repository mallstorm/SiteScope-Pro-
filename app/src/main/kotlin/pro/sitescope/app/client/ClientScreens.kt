@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package pro.sitescope.app.client

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.view.View
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.net.toUri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.sitescope.app.ConnectionProfileStore
import pro.sitescope.app.PlayerActivity
import pro.sitescope.app.ProtocolRouter
import pro.sitescope.app.SecureStore
import pro.sitescope.app.ServiceEndpoint
import pro.sitescope.app.proto.*
import pro.sitescope.app.ui.*
import java.io.File
import java.util.concurrent.ArrayBlockingQueue

// ------------------------------------------------------------------ общее

/** Сохранённые логины: только по желанию человека, в защищённом хранилище телефона. */
object Vault {
    class Creds(val user: String, val password: String, val saved: Boolean)
    private fun id(ep: ServiceEndpoint) = try { ConnectionProfileStore.idFor(ep) } catch (e: Exception) { ep.protocol + ep.host + ep.port }
    fun load(context: Context, ep: ServiceEndpoint): Creds = try {
        val store = SecureStore(context.applicationContext); val u = store.get("cred_u_" + id(ep)) ?: ""; val p = store.get("cred_p_" + id(ep)) ?: ""
        Creds(u, p, u.isNotEmpty() || p.isNotEmpty())
    } catch (e: Exception) { Creds("", "", false) }
    fun save(context: Context, ep: ServiceEndpoint, user: String, password: String, keep: Boolean) {
        try {
            val store = SecureStore(context.applicationContext)
            if (keep) { store.put("cred_u_" + id(ep), user); store.put("cred_p_" + id(ep), password) } else { store.clear("cred_u_" + id(ep)); store.clear("cred_p_" + id(ep)) }
        } catch (e: Exception) { }
    }
}

fun sizeText(v: Long): String = when { v >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f ГБ", v / 1073741824.0); v >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f МБ", v / 1048576.0); v >= 1024 -> "${v / 1024} КБ"; else -> "$v Б" }

@Composable
fun SecretField(value: String, onValue: (String) -> Unit, label: String, secret: Boolean = false, keyboard: KeyboardType = KeyboardType.Ascii) {
    OutlinedTextField(value = value, onValueChange = onValue, label = { Text(label, maxLines = 1) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), shape = RoundedCornerShape(14.dp),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard, autoCorrectEnabled = false))
}

/** Карточка входа: логин, пароль, «запомнить». */
@Composable
fun LoginCard(ep: ServiceEndpoint, button: String, busy: Boolean, error: String, needUser: Boolean = true, userLabel: String = "Логин", passwordLabel: String = "Пароль",
              extra: @Composable ColumnScope.() -> Unit = {}, onConnect: (String, String) -> Unit) {
    val context = LocalContext.current
    val saved = remember { Vault.load(context, ep) }
    var user by remember { mutableStateOf(saved.user) }
    var password by remember { mutableStateOf(saved.password) }
    var keep by remember { mutableStateOf(saved.saved) }
    SoftCard {
        Text(ep.displayName.ifEmpty { ep.host }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(ep.host + (if (ep.port > 0) ":" + ep.port else ""), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (needUser) SecretField(user, { user = it }, userLabel)
        SecretField(password, { password = it }, passwordLabel, secret = true)
        extra()
        Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = keep, onCheckedChange = { keep = it }); Text("Запомнить на этом телефоне", style = MaterialTheme.typography.bodyMedium) }
        if (error.isNotEmpty()) Text(error, color = tones.bad, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
        Button(onClick = { Vault.save(context, ep, user.trim(), password, keep); onConnect(user.trim(), password) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
            if (busy) { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary); Spacer(Modifier.width(8.dp)) }
            Text(if (busy) "Подключаемся…" else button)
        }
    }
}

private fun displayName(context: Context, uri: Uri): String {
    try { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) return c.getString(0) ?: "" } } catch (e: Exception) { }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "file.bin"
}

// ------------------------------------------------------------------ файловый менеджер (SFTP, FTP, FTPS, WebDAV)

@Composable
fun FileBrowser(fs: RemoteFs, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var path by remember(fs) { mutableStateOf("") }
    var items by remember(fs) { mutableStateOf<List<RemoteItem>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<RemoteItem?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<RemoteItem?>(null) }
    var confirmDelete by remember { mutableStateOf<RemoteItem?>(null) }
    var pendingDownload by remember { mutableStateOf<RemoteItem?>(null) }

    fun run(label: String, reload: Boolean = true, action: () -> Unit) = scope.launch {
        busy = true; note = label
        val error = withContext(Dispatchers.IO) { try { action(); null } catch (e: Exception) { e.message ?: "Не получилось" } }
        if (error != null) { note = error; busy = false; return@launch }
        if (reload) { val list = withContext(Dispatchers.IO) { try { fs.list(path) } catch (e: Exception) { null } }; if (list != null) items = list.sortedWith(compareByDescending<RemoteItem> { it.directory }.thenBy { it.name.lowercase() }) }
        note = ""; busy = false
    }
    fun open(target: String) = scope.launch {
        busy = true; note = ""
        val result = withContext(Dispatchers.IO) { try { fs.list(target) } catch (e: Exception) { note = e.message ?: "Папка не открылась"; null } }
        if (result != null) { path = target; items = result.sortedWith(compareByDescending<RemoteItem> { it.directory }.thenBy { it.name.lowercase() }) }
        busy = false
    }
    LaunchedEffect(fs) { val home = withContext(Dispatchers.IO) { try { fs.home() } catch (e: Exception) { "/" } }; open(home) }

    val upload = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) { val name = displayName(context, uri); run("Загружаем $name…") { context.contentResolver.openInputStream(uri)!!.use { fs.upload(joinPath(path, name), it) } } }
    }
    val download = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val item = pendingDownload; pendingDownload = null
        if (uri != null && item != null) run("Скачиваем ${item.name}…", reload = false) { context.contentResolver.openOutputStream(uri)!!.use { fs.download(item.path, it) } }
    }

    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { open(parentPath(path)) }, enabled = !busy && path.trimEnd('/').isNotEmpty() && path != "/") { Icon(Icons.Rounded.ArrowUpward, "Вверх") }
            Text(path.ifEmpty { "/" }.let { if (it.length > 34) "…" + it.takeLast(33) else it }, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f), maxLines = 1)
            IconButton(onClick = { newFolder = true }, enabled = !busy) { Icon(Icons.Rounded.CreateNewFolder, "Новая папка") }
            IconButton(onClick = { upload.launch(arrayOf("*/*")) }, enabled = !busy) { Icon(Icons.Rounded.UploadFile, "Загрузить файл") }
            IconButton(onClick = { open(path) }, enabled = !busy) { Icon(Icons.Rounded.Refresh, "Обновить") }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall, color = if (busy) MaterialTheme.colorScheme.onSurfaceVariant else tones.bad, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            if (!busy && items.isEmpty() && note.isEmpty()) item { Text("Папка пуста", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(items) { item ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = !busy) { if (item.directory) open(item.path) else selected = item }.padding(horizontal = 8.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (item.directory) Icons.Rounded.Folder else Icons.Rounded.InsertDriveFile, null, tint = if (item.directory) tones.warn else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!item.directory) Text(sizeText(item.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    IconButton(onClick = { selected = item }) { Icon(Icons.Rounded.MoreVert, "Действия") }
                }
            }
        }
    }

    selected?.let { item ->
        ModalBottomSheet(onDismissRequest = { selected = null }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(item.path, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                if (!item.directory) Button(onClick = { pendingDownload = item; selected = null; download.launch(item.name) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(6.dp)); Text("Скачать на телефон") }
                OutlinedButton(onClick = { rename = item; selected = null }, modifier = Modifier.fillMaxWidth()) { Text("Переименовать") }
                OutlinedButton(onClick = { copyText(context, item.path); selected = null }, modifier = Modifier.fillMaxWidth()) { Text("Копировать путь") }
                TextButton(onClick = { confirmDelete = item; selected = null }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = tones.bad)) { Text("Удалить") }
            }
        }
    }
    if (newFolder) TextDialog("Новая папка", "", "Название", onClose = { newFolder = false }) { name -> newFolder = false; if (name.isNotBlank()) run("Создаём папку…") { fs.mkdir(joinPath(path, name.trim())) } }
    rename?.let { item -> TextDialog("Переименовать", item.name, "Новое название", onClose = { rename = null }) { name -> rename = null; if (name.isNotBlank() && name != item.name) run("Переименовываем…") { fs.rename(item.path, joinPath(parentPath(item.path), name.trim())) } } }
    confirmDelete?.let { item -> ConfirmDialog("Удалить «${item.name}»?", "Удаление на сервере нельзя отменить.", "Удалить", { confirmDelete = null }) { confirmDelete = null; run("Удаляем…") { fs.delete(item) } } }
}

@Composable
fun FileScreen(ep: ServiceEndpoint) {
    val scope = rememberCoroutineScope()
    var fs by remember { mutableStateOf<RemoteFs?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { val f = fs; if (f != null) Thread { f.close() }.start() } }
    val current = fs
    if (current != null) { FileBrowser(current); return }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        LoginCard(ep, "Открыть файлы", busy, error, userLabel = if (ep.protocol == "WEBDAV") "Логин" else "Логин (пусто — гостевой вход)") { user, password ->
            busy = true; error = ""
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        if (ep.protocol == "WEBDAV") {
                            val tls = ep.port == 443 || ep.port == 8443 || ep.uri.startsWith("https")
                            val root = ep.uri.toUri().path?.ifBlank { "/" } ?: "/"
                            WebDavFs(ep.host, ep.port, tls, user, password, root).also { it.list(root) }
                        }
                        else FtpFs(ep.host, ep.port, ep.protocol == "FTPS", user, password).also { it.connect() }
                    } catch (e: Exception) { error = e.message ?: "Не удалось подключиться"; null }
                }
                fs = result; busy = false
            }
        }
        Text(if (ep.protocol == "FTP" || (ep.protocol == "WEBDAV" && ep.port != 443 && ep.port != 8443 && !ep.uri.startsWith("https"))) "Соединение без TLS: пароль и файлы передаются открыто. Для защищённого доступа используйте SFTP, FTPS или HTTPS." else "Соединение зашифровано; сертификат проверяется.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
    }
}

// ------------------------------------------------------------------ SSH-менеджер

private val defaultSnippets = listOf("uptime", "df -h", "free -m", "ip a", "sudo systemctl status", "docker ps", "tail -n 50 /var/log/syslog")

@Composable
fun SshScreen(ep: ServiceEndpoint, startWithFiles: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var client by remember { mutableStateOf<SshClient?>(null) }
    var fs by remember { mutableStateOf<RemoteFs?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var tab by remember { mutableIntStateOf(if (startWithFiles) 1 else 0) }
    var output by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var key by remember { mutableStateOf<ByteArray?>(null) }
    var keyName by remember { mutableStateOf("") }
    var hostPrompt by remember { mutableStateOf<String?>(null) }
    val answers = remember { ArrayBlockingQueue<Boolean>(1) }
    val prefs = remember { context.getSharedPreferences("ssh_manager", Context.MODE_PRIVATE) }
    var snippets by remember { mutableStateOf((prefs.getString("snippets", null)?.split('\n')?.filter { it.isNotBlank() }) ?: defaultSnippets) }
    var addSnippet by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val pickKey = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) try { key = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }; keyName = displayName(context, uri) } catch (e: Exception) { toast(context, "Не удалось прочитать ключ") }
    }
    DisposableEffect(Unit) { onDispose { answers.offer(false); val c = client; if (c != null) Thread { c.close() }.start() } }
    LaunchedEffect(output.length) { scroll.scrollTo(scroll.maxValue) }

    val current = client
    if (current == null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            LoginCard(ep, "Подключиться", busy, error, passwordLabel = if (key != null) "Пароль от ключа (если есть)" else "Пароль", extra = {
                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { pickKey.launch(arrayOf("*/*")) }) { Icon(Icons.Rounded.Key, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(if (key == null) "Войти по ключу" else keyName, maxLines = 1) }
                    if (key != null) IconButton(onClick = { key = null; keyName = "" }) { Icon(Icons.Rounded.Close, "Убрать ключ") }
                }
            }) { user, password ->
                if (user.isEmpty()) { error = "Укажите логин"; return@LoginCard }
                busy = true; error = ""
                scope.launch {
                    val c = SshClient(File(context.filesDir, "ssh_known_hosts"), ep.host, ep.port, user, password, key) { message -> hostPrompt = message; try { answers.take() } catch (e: Exception) { false } }
                    val failure = withContext(Dispatchers.IO) { try { c.connect(); null } catch (e: Exception) { e.message ?: "Не удалось подключиться" } }
                    busy = false
                    if (failure != null) { error = failure; return@launch }
                    client = c
                    Thread { try { c.runShell { chunk -> output = (output + chunk).takeLast(60000) } } catch (e: Exception) { output += "\n[терминал закрыт: " + (e.message ?: "") + "]\n" } }.start()
                }
            }
            Text("Ключ сервера проверяется при каждом входе: при первом подключении приложение спросит, доверять ли ему, а при подмене — предупредит.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        }
    } else Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Терминал") })
            FilterChip(selected = tab == 1, onClick = { tab = 1; if (fs == null) scope.launch { fs = withContext(Dispatchers.IO) { try { current.sftp() } catch (e: Exception) { null } }; if (fs == null) toast(context, "Сервер не разрешает доступ к файлам") } }, label = { Text("Файлы") })
        }
        if (tab == 1) { val f = fs; if (f != null) FileBrowser(f, Modifier.weight(1f)) else Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
        else {
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF14110E)).verticalScroll(scroll).padding(12.dp)) {
                Text(output.ifEmpty { "Ждём приглашение сервера…" }, color = Color(0xFFE9E0D6), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Ctrl+C" to "\u0003", "Tab" to "\t", "↑" to "\u001B[A", "Esc" to "\u001B", "Ctrl+D" to "\u0004").forEach { (title, code) -> AssistChip(onClick = { current.send(code) }, label = { Text(title) }) }
            }
            LazyRowSnippets(snippets, onPick = { command = it }, onAdd = { addSnippet = true })
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BigField(command, { command = it }, "Команда", Modifier.weight(1f), onGo = { current.send(command + "\n"); command = "" }, leading = Icons.Rounded.Terminal, keyboard = KeyboardType.Ascii, mono = true)
                IconButton(onClick = { current.send(command + "\n"); command = "" }) { Icon(Icons.Rounded.Send, "Отправить", tint = MaterialTheme.colorScheme.primary) }
            }
        }
    }

    hostPrompt?.let { message ->
        AlertDialog(onDismissRequest = { }, title = { Text("Доверять этому серверу?") },
            text = { Text(if (message.contains("WARNING", true) || message.contains("changed", true)) "Ключ сервера изменился. Если вы не переустанавливали сервер, это может быть подмена.\n\n$message" else "Вы подключаетесь впервые. Сверьте отпечаток ключа, если он вам известен.\n\n$message", style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { hostPrompt = null; answers.offer(true) }) { Text("Доверять") } },
            dismissButton = { TextButton(onClick = { hostPrompt = null; answers.offer(false) }) { Text("Отмена") } })
    }
    if (addSnippet) TextDialog("Новая быстрая команда", command, "Например: sudo reboot", onClose = { addSnippet = false }) { value ->
        addSnippet = false
        if (value.isNotBlank()) { snippets = (snippets + value.trim()).distinct(); prefs.edit().putString("snippets", snippets.joinToString("\n")).apply() }
    }
}

@Composable
private fun LazyRowSnippets(snippets: List<String>, onPick: (String) -> Unit, onAdd: () -> Unit) {
    androidx.compose.foundation.lazy.LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(snippets) { s -> SuggestionChip(onClick = { onPick(s) }, label = { Text(s, maxLines = 1, fontFamily = FontFamily.Monospace) }) }
        item { SuggestionChip(onClick = onAdd, label = { Text("+ своя") }) }
    }
}

// ------------------------------------------------------------------ удалённый экран VNC

class VncView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dst = Rect()
    var client: VncClient? = null

    @Synchronized fun init(w: Int, h: Int) { if (w > 0 && h > 0) bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); postInvalidate() }
    @Synchronized fun update(x: Int, y: Int, w: Int, h: Int, pixels: IntArray) { val b = bitmap ?: return; try { b.setPixels(pixels, 0, w, x, y, w, h) } catch (e: Exception) { }; postInvalidate() }

    @Synchronized override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas); val b = bitmap ?: return
        val scale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = Math.round(b.width * scale); val h = Math.round(b.height * scale); val l = (width - w) / 2; val t = (height - h) / 2
        dst.set(l, t, l + w, t + h); canvas.drawBitmap(b, null, dst, paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val b = bitmap ?: return true; val c = client ?: return true
        val scale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        val l = (width - Math.round(b.width * scale)) / 2; val t = (height - Math.round(b.height * scale)) / 2
        val x = Math.round((e.x - l) / scale).coerceIn(0, b.width - 1); val y = Math.round((e.y - t) / scale).coerceIn(0, b.height - 1)
        val mask = if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) 1 else 0
        Thread { c.pointer(mask, x, y) }.start(); return true
    }
}

@Composable
fun VncScreen(ep: ServiceEndpoint) {
    val context = LocalContext.current
    val view = remember { VncView(context) }
    var client by remember { mutableStateOf<VncClient?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var info by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { client?.close() } }

    val current = client
    if (current == null) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            LoginCard(ep, "Показать экран", busy, error, needUser = false, passwordLabel = "Пароль VNC (если задан)") { _, password ->
                busy = true; error = ""
                var created: VncClient? = null
                created = VncClient(ep.host, ep.port, password, object : VncClient.Listener {
                    override fun onStatus(text: String) { info = text }
                    override fun onScreen(width: Int, height: Int, name: String) { view.init(width, height); if (name.isNotEmpty()) info = "$name · $width×$height"; busy = false; view.client = created; client = created }
                    override fun onPixels(x: Int, y: Int, w: Int, h: Int, pixels: IntArray) = view.update(x, y, w, h, pixels)
                    override fun onClosed(error2: String?) { busy = false; view.client = null; client = null; error = error2 ?: "" }
                })
                Thread(created).start()
            }
            Text("Поддерживается обычный VNC без сжатия. Пароль VNC не шифрует картинку — в чужих сетях используйте VPN или SSH.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        }
    } else Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(info, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = { current.close() }) { Text("Отключиться") }
        }
        AndroidView(factory = { view }, modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black))
        FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            fun key(block: () -> Unit) { Thread { block() }.start() }
            AssistChip(onClick = { key { current.combo(VncClient.KEY_CTRL, VncClient.KEY_ALT, VncClient.KEY_DELETE) } }, label = { Text("Ctrl+Alt+Del") })
            AssistChip(onClick = { key { current.press(VncClient.KEY_ESC) } }, label = { Text("Esc") })
            AssistChip(onClick = { key { current.press(VncClient.KEY_TAB) } }, label = { Text("Tab") })
            AssistChip(onClick = { key { current.press(VncClient.KEY_ENTER) } }, label = { Text("Enter") })
            AssistChip(onClick = { key { current.press(VncClient.KEY_BACKSPACE) } }, label = { Text("⌫") })
            AssistChip(onClick = { key { current.press(VncClient.KEY_SUPER) } }, label = { Text("Win") })
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BigField(text, { text = it }, "Текст для ввода", Modifier.weight(1f), onGo = { val t = text; text = ""; Thread { current.type(t) }.start() }, leading = Icons.Rounded.Keyboard, keyboard = KeyboardType.Text)
            IconButton(onClick = { val t = text; text = ""; Thread { current.type(t) }.start() }) { Icon(Icons.Rounded.Send, "Ввести", tint = MaterialTheme.colorScheme.primary) }
        }
    }
}

// ------------------------------------------------------------------ SNMP: сведения об оборудовании

private val snmpBranches = listOf("Об устройстве" to "1.3.6.1.2.1.1", "Сетевые порты" to "1.3.6.1.2.1.2.2.1.2", "Состояние портов" to "1.3.6.1.2.1.2.2.1.8", "Адреса" to "1.3.6.1.2.1.4.20.1.1", "Память и диски" to "1.3.6.1.2.1.25.2.3.1.3")
private val snmpNames = mapOf("1.3.6.1.2.1.1.1.0" to "Описание", "1.3.6.1.2.1.1.3.0" to "Работает без перезагрузки", "1.3.6.1.2.1.1.4.0" to "Контакт", "1.3.6.1.2.1.1.5.0" to "Имя", "1.3.6.1.2.1.1.6.0" to "Расположение", "1.3.6.1.2.1.1.2.0" to "Тип оборудования")

@Composable
fun SnmpScreen(ep: ServiceEndpoint) {
    val scope = rememberCoroutineScope()
    var community by remember { mutableStateOf("public") }
    var oid by remember { mutableStateOf(snmpBranches[0].second) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var rows by remember { mutableStateOf<List<Snmp.VarBind>?>(null) }
    fun run(walk: Boolean) = scope.launch {
        busy = true; error = ""
        rows = withContext(Dispatchers.IO) { try { if (walk) Snmp.walk(ep.host, ep.port, community, oid) else Snmp.get(ep.host, ep.port, community, oid) } catch (e: Exception) {
            error = if (e is java.net.SocketTimeoutException) "Устройство не ответило. Проверьте слово доступа и включён ли SNMP." else (e.message ?: "Ошибка"); null } }
        busy = false
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SoftCard {
            Text(ep.displayName.ifEmpty { ep.host }, style = MaterialTheme.typography.titleMedium)
            Text("Только чтение: приложение ничего не меняет на устройстве.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SecretField(community, { community = it }, "Слово доступа (community)")
            FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                snmpBranches.forEach { (title, value) -> FilterChip(selected = oid == value, onClick = { oid = value }, label = { Text(title) }) }
            }
            SecretField(oid, { oid = it }, "Ветка (OID)", keyboard = KeyboardType.Ascii)
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { run(true) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text(if (busy) "Читаем…" else "Прочитать ветку") }
                OutlinedButton(onClick = { run(false) }, enabled = !busy) { Text("Одно значение") }
            }
        }
        if (error.isNotEmpty()) { Spacer(Modifier.height(10.dp)); ErrorCard(error) }
        rows?.let { list ->
            SectionTitle("Получено значений: ${list.size}")
            if (list.isNotEmpty()) SoftCard(padding = 12.dp) {
                list.forEach { v -> InfoRow(snmpNames[v.oid] ?: v.oid.removePrefix(oid).trimStart('.').ifEmpty { v.oid }, v.value.ifEmpty { "—" }, mono = snmpNames[v.oid] == null) }
            }
        }
    }
}

// ------------------------------------------------------------------ MQTT: умный дом и датчики

private class MqttMessage(val topic: String, val payload: String, val incoming: Boolean, val time: Long = System.currentTimeMillis())

@Composable
fun MqttScreen(ep: ServiceEndpoint) {
    val context = LocalContext.current
    var client by remember { mutableStateOf<MqttClient?>(null) }
    var connected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var tls by remember { mutableStateOf(ep.port == 8883) }
    var topic by remember { mutableStateOf("#") }
    var pubTopic by remember { mutableStateOf("") }
    var payload by remember { mutableStateOf("") }
    var subs by remember { mutableStateOf<List<String>>(emptyList()) }
    var messages by remember { mutableStateOf<List<MqttMessage>>(emptyList()) }
    DisposableEffect(Unit) { onDispose { val c = client; if (c != null) Thread { c.close() }.start() } }
    fun act(block: () -> Unit) { Thread { try { block() } catch (e: Exception) { error = e.message ?: "Не отправлено" } }.start() }

    val current = client
    if (current == null || !connected) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        LoginCard(ep, "Подключиться", busy, error, userLabel = "Логин (если нужен)", extra = {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Защищённое соединение (TLS)", Modifier.weight(1f)); Switch(checked = tls, onCheckedChange = { tls = it }) }
        }) { user, password ->
            busy = true; error = ""
            val c = MqttClient(ep.host, ep.port, tls, "sitescope-" + (System.currentTimeMillis() % 100000), user, password, object : MqttClient.Listener {
                override fun onConnected() { busy = false; connected = true }
                override fun onMessage(topic: String, payload: String) { messages = (listOf(MqttMessage(topic, payload, true)) + messages).take(300) }
                override fun onInfo(text: String) { }
                override fun onClosed(error2: String?) { busy = false; connected = false; client = null; error = error2 ?: "" }
            })
            client = c; c.connect()
        }
    } else Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            SoftCard(padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BigField(topic, { topic = it }, "Тема для подписки: home/#", Modifier.weight(1f), leading = Icons.Rounded.Sensors, keyboard = KeyboardType.Ascii, mono = true)
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { val t = topic.trim(); if (t.isNotEmpty() && t !in subs) { subs = subs + t; act { current.subscribe(t) } } }) { Text("Слушать") }
                }
                if (subs.isNotEmpty()) FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    subs.forEach { s -> InputChip(selected = true, onClick = { subs = subs - s; act { current.unsubscribe(s) } }, label = { Text(s, fontFamily = FontFamily.Monospace) }, trailingIcon = { Icon(Icons.Rounded.Close, null, Modifier.size(16.dp)) }) }
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                BigField(pubTopic, { pubTopic = it }, "Тема для отправки", leading = Icons.Rounded.Topic, keyboard = KeyboardType.Ascii, mono = true)
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BigField(payload, { payload = it }, "Сообщение", Modifier.weight(1f), leading = Icons.Rounded.Message, keyboard = KeyboardType.Text)
                    IconButton(onClick = { val t = pubTopic.trim(); if (t.isEmpty()) toast(context, "Укажите тему") else { val p = payload; act { current.publish(t, p) }; messages = (listOf(MqttMessage(t, p, false)) + messages).take(300) } }) { Icon(Icons.Rounded.Send, "Отправить", tint = MaterialTheme.colorScheme.primary) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Сообщения: ${messages.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                TextButton(onClick = { messages = emptyList() }) { Text("Очистить") }
                TextButton(onClick = { current.close() }) { Text("Отключиться") }
            }
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(messages) { m ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (m.incoming) MaterialTheme.colorScheme.surfaceContainerLowest else MaterialTheme.colorScheme.primaryContainer).clickable { copyText(context, m.payload) }.padding(10.dp)) {
                    Text((if (m.incoming) "← " else "→ ") + m.topic, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(m.payload.ifEmpty { "(пусто)" }, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ камеры ONVIF

// ------------------------------------------------------------------ медиасервер DLNA

@Composable
fun DlnaScreen(ep: ServiceEndpoint) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var control by remember { mutableStateOf("") }
    var stack by remember { mutableStateOf(listOf("0" to "Медиасервер")) }
    var items by remember { mutableStateOf<List<Dlna.Item>>(emptyList()) }
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    fun load(id: String) = scope.launch {
        busy = true; error = ""
        val list = withContext(Dispatchers.IO) { try { if (control.isEmpty()) control = Dlna.controlUrl(ep.location).ifEmpty { throw Exception("Это устройство не раздаёт медиафайлы") }; Dlna.browse(control, id) } catch (e: Exception) { error = e.message ?: "Каталог не открылся"; null } }
        if (list != null) items = list
        busy = false
    }
    LaunchedEffect(Unit) { load("0") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { stack = stack.dropLast(1); load(stack.last().first) }, enabled = stack.size > 1 && !busy) { Icon(Icons.Rounded.ArrowUpward, "Вверх") }
            Text(stack.last().second, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotEmpty()) Box(Modifier.padding(16.dp)) { ErrorCard(error) }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp)) {
            items(items) { item ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(enabled = !busy) {
                    if (item.container) { stack = stack + (item.id to item.title); load(item.id) }
                    else if (item.url.isNotEmpty()) try { context.startActivity(Intent(context, PlayerActivity::class.java).putExtra("url", item.url).putExtra("source_url", item.url).putExtra("name", item.title).putExtra("details", "DLNA")) } catch (e: Exception) { toast(context, "Не удалось открыть") }
                }.padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(when { item.container -> Icons.Rounded.Folder; item.mime.startsWith("audio") -> Icons.Rounded.MusicNote; item.mime.startsWith("image") -> Icons.Rounded.Image; else -> Icons.Rounded.Movie }, null,
                        tint = if (item.container) tones.warn else tones.tv)
                    Spacer(Modifier.width(12.dp))
                    Text(item.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ------------------------------------------------------------------ текстовая консоль (Telnet и прочее)

@Composable
fun ConsoleScreen(ep: ServiceEndpoint) {
    var console by remember { mutableStateOf<RawConsole?>(null) }
    var open by remember { mutableStateOf(false) }
    var output by remember { mutableStateOf("") }
    var line by remember { mutableStateOf("") }
    var tls by remember { mutableStateOf(ep.port == 992 || ep.port == 443 || ep.port == 465 || ep.port == 993 || ep.port == 995) }
    val scroll = rememberScrollState()
    LaunchedEffect(output.length) { scroll.scrollTo(scroll.maxValue) }
    DisposableEffect(Unit) { onDispose { console?.close() } }
    fun connect() {
        output = "Подключаемся к ${ep.host}:${ep.port}…\n"
        val c = RawConsole(ep.host, ep.port, tls, object : RawConsole.Listener {
            override fun onOpen() { open = true; output += "Соединение установлено.\n" }
            override fun onText(text: String) { output = (output + text).takeLast(40000) }
            override fun onClosed(error: String?) { open = false; output += if (error == null) "\n[соединение закрыто]\n" else "\n[ошибка: $error]\n" }
        })
        console = c; c.connect()
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Шифрование TLS", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(checked = tls, onCheckedChange = { tls = it }, enabled = !open)
            Spacer(Modifier.width(8.dp))
            if (open) OutlinedButton(onClick = { console?.close() }) { Text("Закрыть") } else Button(onClick = { connect() }) { Text("Подключиться") }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF14110E)).verticalScroll(scroll).padding(12.dp)) {
            Text(output.ifEmpty { "Простая текстовая консоль: видно, что отвечает сервис, и можно отправлять ему строки. Telnet не шифрует соединение." }, color = Color(0xFFE9E0D6), fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BigField(line, { line = it }, "Строка для отправки", Modifier.weight(1f), onGo = { console?.send(line); line = "" }, leading = Icons.Rounded.Terminal, keyboard = KeyboardType.Ascii, mono = true)
            IconButton(onClick = { console?.send(line); line = "" }, enabled = open) { Icon(Icons.Rounded.Send, "Отправить") }
        }
    }
}
