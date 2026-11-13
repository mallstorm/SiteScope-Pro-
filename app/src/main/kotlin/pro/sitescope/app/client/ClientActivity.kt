@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package pro.sitescope.app.client

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.sitescope.app.ConnectionProfileStore
import pro.sitescope.app.EndpointProbe
import pro.sitescope.app.MdnsServiceMapper
import pro.sitescope.app.ProtocolPreferences
import pro.sitescope.app.ProtocolRouter
import pro.sitescope.app.ServiceEndpoint
import pro.sitescope.app.UpnpDescriptionParser
import pro.sitescope.app.proto.Upnp
import pro.sitescope.app.ui.*
import java.net.InetSocketAddress
import java.net.Socket

/** Что приложение умеет делать с каждым видом подключения. */
class Kind(val id: String, val title: String, val manager: String, val icon: ImageVector, val port: Int, val about: String)

val KINDS = listOf(
    Kind("SSH", "SSH", "SSH-менеджер", Icons.Rounded.Terminal, 22, "Командная строка сервера или роутера и его файлы."),
    Kind("SFTP", "SFTP", "Файловый менеджер", Icons.Rounded.Folder, 22, "Файлы на сервере по защищённому каналу SSH."),
    Kind("FTP", "FTP", "Файловый менеджер", Icons.Rounded.Folder, 21, "Файлы на сервере. Пароль и данные передаются открыто."),
    Kind("FTPS", "FTPS", "Файловый менеджер", Icons.Rounded.Folder, 990, "Файлы на сервере с шифрованием."),
    Kind("WEBDAV", "WebDAV", "Файловый менеджер", Icons.Rounded.Cloud, 80, "Облачная или сетевая папка поверх веб-протокола."),
    Kind("RDP", "RDP", "RDP-менеджер", Icons.Rounded.DesktopWindows, 3389, "Удалённый рабочий стол Windows."),
    Kind("VNC", "VNC", "Удалённый экран", Icons.Rounded.ScreenShare, 5900, "Экран другого компьютера: видеть и управлять."),
    Kind("TELNET", "Telnet", "Текстовая консоль", Icons.Rounded.Code, 23, "Старая командная строка оборудования, без шифрования."),
    Kind("SNMP", "SNMP", "Сведения об оборудовании", Icons.Rounded.Analytics, 161, "Состояние роутеров, коммутаторов, принтеров."),
    Kind("MQTT", "MQTT", "Сообщения умного дома", Icons.Rounded.Sensors, 1883, "Обмен сообщениями датчиков и умных устройств."),
    Kind("ONVIF", "ONVIF", "Камера", Icons.Rounded.Videocam, 80, "IP-камера: сведения и видеопотоки."),
    Kind("DLNA", "DLNA", "Медиатека", Icons.Rounded.VideoLibrary, 0, "Фильмы, музыка и фото с медиасервера."),
    Kind("RTSP", "RTSP", "Видеоплеер", Icons.Rounded.PlayCircle, 554, "Видеопоток камеры или вещания."),
    Kind("HTTP", "Веб-страница", "Встроенный браузер", Icons.Rounded.Language, 80, "Веб-панель устройства или сайт."),
    Kind("HTTPS", "Веб-страница", "Встроенный браузер", Icons.Rounded.Lock, 443, "Веб-панель устройства или сайт по защищённому каналу.")
)

fun kindOf(protocol: String): Kind? = KINDS.firstOrNull { it.id == protocol }
private val inApp = setOf("SSH", "SFTP", "FTP", "FTPS", "WEBDAV", "RDP", "VNC", "TELNET", "SNMP", "MQTT", "ONVIF", "DLNA")

private fun copyOf(ep: ServiceEndpoint, protocol: String, port: Int): ServiceEndpoint {
    val e = ServiceEndpoint()
    e.host = ep.host; e.port = port; e.protocol = protocol; e.service = protocol; e.displayName = ep.displayName; e.resourceType = ep.resourceType
    e.scope = ep.scope
    e.location = if (protocol == "DLNA" || protocol == "ONVIF") ep.location else ""
    e.uri = try { ServiceEndpoint.buildUri(protocol, ep.host, port, "") ?: "" } catch (ex: Exception) { "" }
    return e
}

/** Один экран на любое подключение: панель устройства или менеджер нужного протокола. */
class ClientActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val source = ServiceEndpoint.from(intent)
        val startPanel = intent.getBooleanExtra("panel", false) || source.protocol !in inApp
        setContent {
            AppTheme {
                var ep by remember { mutableStateOf(source) }
                var panel by remember { mutableStateOf(startPanel) }
                var cameFromPanel by remember { mutableStateOf(false) }
                BackHandler(enabled = !panel && cameFromPanel) { ep = source; panel = true }
                val kind = kindOf(ep.protocol)
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
                    BackHeader(if (panel) "Устройство" else kind?.manager ?: ep.protocol, { if (!panel && cameFromPanel) { ep = source; panel = true } else finish() })
                    if (panel) PanelScreen(ep) { target ->
                        if (target.protocol in inApp) { ConnectionProfileStore.recordOpened(this@ClientActivity, target); ep = target; cameFromPanel = true; panel = false }
                        else try { ProtocolRouter.openBuiltIn(this@ClientActivity, target) } catch (e: Exception) { toast(this@ClientActivity, "Не удалось открыть") }
                    } else key(ep.protocol + ep.host + ep.port) {
                        when (ep.protocol) {
                            "SSH" -> SshScreen(ep, false)
                            "SFTP" -> SshScreen(ep, true)
                            "FTP", "FTPS", "WEBDAV" -> FileScreen(ep)
                            "VNC" -> VncScreen(ep)
                            "SNMP" -> SnmpScreen(ep)
                            "MQTT" -> MqttScreen(ep)
                            "ONVIF" -> OnvifScreen(ep)
                            "DLNA" -> DlnaScreen(ep)
                            "RDP" -> RdpScreen(ep)
                            else -> ConsoleScreen(ep)
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ панель устройства

private val quickPorts = listOf(21 to "FTP", 22 to "SSH", 23 to "TELNET", 80 to "HTTP", 443 to "HTTPS", 554 to "RTSP", 1883 to "MQTT", 3389 to "RDP", 5900 to "VNC", 8080 to "HTTP", 8443 to "HTTPS")

@Composable
fun PanelScreen(ep: ServiceEndpoint, onOpen: (ServiceEndpoint) -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val t = tones
    val kind = kindOf(ep.protocol)
    var probe by remember { mutableStateOf<EndpointProbe.Result?>(null) }
    var probing by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<Pair<Int, String>>?>(null) }
    var scanning by remember { mutableStateOf(false) }
    var upnp by remember { mutableStateOf<UpnpDescriptionParser.Description?>(null) }
    var upnpError by remember { mutableStateOf("") }
    var handler by remember { mutableStateOf(ProtocolPreferences.handler(context, ep.protocol)) }
    val address = ep.uri.ifEmpty { ep.host + (if (ep.port > 0) ":" + ep.port else "") }
    val hasDescription = ep.location.startsWith("http")
    LaunchedEffect(ep.location) {
        if (hasDescription) upnp = withContext(Dispatchers.IO) { try { Upnp.describe(ep.location) } catch (e: Exception) { upnpError = "Описание устройства не загрузилось"; null } }
    }
    val mdns = remember(ep.services) { try { MdnsServiceMapper.protocols(ep.services).filter { it != "UNKNOWN" && kindOf(it) != null } } catch (e: Exception) { emptyList() } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
        SoftCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(kind?.icon ?: Icons.Rounded.DevicesOther, t.net, t.netSoft, 54.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(upnp?.friendlyName?.ifEmpty { null } ?: ep.displayName.ifEmpty { ep.host }, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(ep.host + (if (ep.port > 0) ":" + ep.port else ""), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill(kind?.title ?: ep.protocol, t.net, t.netSoft)
                if (ep.resourceType.isNotEmpty()) Pill(ep.resourceType, MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh)
                probe?.let { p -> if (p.reachable) Pill(if (p.latencyMs > 0) "${p.latencyMs} мс" else "В сети", t.good, t.goodSoft) else if (p.status != "MANUAL") Pill("Не отвечает", t.bad, t.badSoft) }
            }
            Text(kind?.about ?: ep.description.ifEmpty { "Сервис на этом адресе не распознан. Его можно проверить или открыть в текстовой консоли." }, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            if (kind != null) Button(onClick = { onOpen(ep) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Icon(kind.icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Открыть: " + kind.manager) }
            else if (ep.port > 0) Button(onClick = { onOpen(copyOf(ep, "TELNET", ep.port)) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Открыть текстовую консоль") }
        }

        upnp?.let { d ->
            SectionTitle("Об устройстве")
            SoftCard(padding = 12.dp) {
                InfoRow("Производитель", d.manufacturer); InfoRow("Модель", listOf(d.modelName, d.modelNumber).filter { it.isNotEmpty() }.joinToString(" ")); InfoRow("Описание", d.modelDescription)
                InfoRow("Серийный номер", d.serialNumber); InfoRow("Тип", d.deviceType.substringAfter("device:").substringBefore(':'))
                val web = if (d.presentationUrl.isNotEmpty()) Upnp.resolve(ep.location, d.presentationUrl) else ""
                if (web.isNotEmpty()) OutlinedButton(onClick = { val e = copyOf(ep, if (web.startsWith("https")) "HTTPS" else "HTTP", 0); e.uri = web; onOpen(e) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Icon(Icons.Rounded.Language, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Веб-панель устройства") }
                if (d.serviceInfos.any { it.serviceType.contains("ContentDirectory") }) OutlinedButton(onClick = { onOpen(copyOf(ep, "DLNA", ep.port)) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.VideoLibrary, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Медиатека: фильмы, музыка, фото") }
                TechDetails(d.services.joinToString("\n"), Modifier.padding(top = 6.dp))
            }
        }
        if (upnpError.isNotEmpty() && upnp == null) Text(upnpError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))

        if (mdns.isNotEmpty()) {
            SectionTitle("Устройство само сообщает о сервисах")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                mdns.forEach { p -> val k = kindOf(p)!!; AssistChip(onClick = { onOpen(copyOf(ep, p, if (ep.port > 0 && p == ep.protocol) ep.port else k.port)) }, label = { Text(k.manager) }, leadingIcon = { Icon(k.icon, null, Modifier.size(18.dp)) }) }
            }
        }

        SectionTitle("Действия")
        SoftCard(padding = 6.dp) {
            ActionLine(Icons.Rounded.NetworkPing, "Проверить, отвечает ли", if (probing) "Проверяем…" else probe?.let { p -> if (p.reachable) "Отвечает" + (if (p.latencyMs > 0) " за ${p.latencyMs} мс" else "") else p.message.ifEmpty { "Не отвечает" } } ?: "Быстрая проверка соединения") {
                if (!probing) { probing = true; scope.launch { probe = withContext(Dispatchers.IO) { try { EndpointProbe.probe(ep, 2500) } catch (e: Exception) { null } }; probing = false } }
            }
            ActionLine(Icons.Rounded.Radar, "Что ещё открыто на этом устройстве", if (scanning) "Проверяем популярные порты…" else found?.let { if (it.isEmpty()) "Других популярных сервисов не найдено" else "Найдено: ${it.size}" } ?: "SSH, веб-панель, камера, удалённый стол и другие") {
                if (!scanning) { scanning = true; scope.launch {
                    found = withContext(Dispatchers.IO) { quickPorts.map { (port, proto) -> async { try { Socket().use { s -> s.connect(InetSocketAddress(ep.host, port), 600) }; port to proto } catch (e: Exception) { null } } }.awaitAll().filterNotNull() }
                    scanning = false } }
            }
            found?.takeIf { it.isNotEmpty() }?.let { list ->
                FlowRow(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { (port, proto) -> AssistChip(onClick = { onOpen(copyOf(ep, proto, port)) }, label = { Text((kindOf(proto)?.title ?: proto) + " · $port") }) }
                }
            }
            ActionLine(Icons.Rounded.OpenInNew, "Открыть в другом приложении", "Если у вас есть свой клиент для " + (kind?.title ?: ep.protocol)) { if (activity != null) try { ProtocolRouter.openExternal(activity, ep) } catch (e: Exception) { } }
            ActionLine(Icons.Rounded.BookmarkAdd, "Сохранить в подключения", "Будет под рукой в разделе «Подключения»") { try { ConnectionProfileStore.save(context, ep); toast(context, "Сохранено") } catch (e: Exception) { toast(context, "Не удалось сохранить") } }
            ActionLine(Icons.Rounded.NetworkCheck, "Полная проверка портов", "Все сервисы на этом адресе") { context.startActivity(Intent(context, ToolActivity::class.java).putExtra("screen", "network").putExtra("tab", 1).putExtra("targets", ep.host)) }
            ActionLine(Icons.Rounded.Public, "Сведения об адресе", "Провайдер, страна, сеть") {
                context.startActivity(Intent(context, ShellActivity::class.java).setAction(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, ep.host).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
            ActionLine(Icons.Rounded.ContentCopy, "Копировать адрес", address) { copyText(context, address) }
            ActionLine(Icons.Rounded.Share, "Поделиться", "Отправить адрес коллеге") { shareText(context, (ep.displayName.ifEmpty { ep.host }) + "\n" + address) }
        }

        if (kind != null) {
            SectionTitle("Как открывать «${kind.title}»")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ProtocolPreferences.BUILTIN to "В SiteScope", ProtocolPreferences.EXTERNAL to "В другом приложении", ProtocolPreferences.PANEL to "Сначала эта панель").forEach { (id, title) ->
                    FilterChip(selected = handler == id, onClick = { handler = id; ProtocolPreferences.setHandler(context, ep.protocol, id) }, label = { Text(title) })
                }
            }
        }
        TechDetails(listOf(ep.service, ep.description, ep.evidence, ep.banner, ep.tlsSubject, ep.location, ep.services).filter { it.isNotBlank() }.distinct().joinToString("\n"), Modifier.padding(top = 14.dp))
    }
}

@Composable
private fun ActionLine(icon: ImageVector, title: String, text: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, tones.net, tones.netSoft, 38.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ------------------------------------------------------------------ RDP-менеджер

@Composable
fun RdpScreen(ep: ServiceEndpoint) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("rdp_manager", Context.MODE_PRIVATE) }
    val id = remember { ep.host + ":" + ep.port }
    var user by remember { mutableStateOf(prefs.getString("user_$id", "") ?: "") }
    var domain by remember { mutableStateOf(prefs.getString("domain_$id", "") ?: "") }
    var fullscreen by remember { mutableStateOf(prefs.getBoolean("full_$id", true)) }
    var admin by remember { mutableStateOf(prefs.getBoolean("admin_$id", false)) }
    var clipboard by remember { mutableStateOf(prefs.getBoolean("clip_$id", true)) }
    var check by remember { mutableStateOf("") }
    val port = if (ep.port > 0) ep.port else 3389
    fun persist() = prefs.edit().putString("user_$id", user.trim()).putString("domain_$id", domain.trim()).putBoolean("full_$id", fullscreen).putBoolean("admin_$id", admin).putBoolean("clip_$id", clipboard).apply()
    fun fileText(): String = buildString {
        append("full address:s:${ep.host}:$port\n")
        if (user.isNotBlank()) append("username:s:" + (if (domain.isNotBlank()) domain.trim() + "\\" else "") + user.trim() + "\n")
        append("screen mode id:i:" + (if (fullscreen) 2 else 1) + "\n")
        append("redirectclipboard:i:" + (if (clipboard) 1 else 0) + "\n")
        append("administrative session:i:" + (if (admin) 1 else 0) + "\n")
        append("authentication level:i:2\nprompt for credentials:i:1\n")
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-rdp")) { uri: Uri? ->
        if (uri != null) try { context.contentResolver.openOutputStream(uri)!!.use { it.write(fileText().toByteArray(Charsets.UTF_8)) }; toast(context, "Файл сохранён") } catch (e: Exception) { toast(context, "Не удалось сохранить") }
    }
    fun connect() {
        persist()
        val name = (if (domain.isNotBlank()) domain.trim() + "\\" else "") + user.trim()
        val query = "full%20address=s:" + Uri.encode("${ep.host}:$port") + (if (name.isNotBlank()) "&username=s:" + Uri.encode(name) else "") + "&screen%20mode%20id=i:" + (if (fullscreen) 2 else 1)
        for (link in listOf("rdp://$query", "ms-rd:$query", "rdp://${ep.host}:$port")) {
            try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (e: Exception) { }
        }
        toast(context, "Не найдено приложение для удалённого рабочего стола")
        try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.microsoft.rdc.androidx")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SoftCard {
            Text(ep.displayName.ifEmpty { ep.host }, style = MaterialTheme.typography.titleMedium)
            Text("${ep.host}:$port", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SecretField(user, { user = it }, "Пользователь Windows")
            SecretField(domain, { domain = it }, "Домен (если есть)")
            SwitchLine("На весь экран", fullscreen) { fullscreen = it }
            SwitchLine("Общий буфер обмена", clipboard) { clipboard = it }
            SwitchLine("Сеанс администратора", admin) { admin = it }
            Button(onClick = { connect() }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Icon(Icons.Rounded.DesktopWindows, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Подключиться") }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { persist(); saveFile.launch((ep.displayName.ifEmpty { ep.host }).filter { it.isLetterOrDigit() || it == '.' || it == '-' } + ".rdp") }, modifier = Modifier.weight(1f)) { Text("Файл .rdp") }
                OutlinedButton(onClick = { check = "Проверяем…"; scope.launch {
                    check = withContext(Dispatchers.IO) { val started = System.currentTimeMillis(); try { Socket().use { it.connect(InetSocketAddress(ep.host, port), 3000) }; "Сервер отвечает за ${System.currentTimeMillis() - started} мс" } catch (e: Exception) { "Сервер не отвечает на порту $port" } }
                } }, modifier = Modifier.weight(1f)) { Text("Проверить") }
            }
            if (check.isNotEmpty()) Text(check, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
        Text("Менеджер хранит параметры подключения и передаёт их приложению удалённого рабочего стола (например, Windows App от Microsoft). Сам экран Windows показывает оно: встроенного RDP-экрана в SiteScope пока нет. Пароль вводится в том приложении и здесь не хранится.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun SwitchLine(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) { Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(checked = checked, onCheckedChange = onChange) }
}
