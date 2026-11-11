@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package pro.sitescope.app.vpn

import android.app.Activity
import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.sitescope.app.HostsActivity
import pro.sitescope.app.HostsEntry
import pro.sitescope.app.HostsParser
import pro.sitescope.app.HostsStore
import pro.sitescope.app.HostsVpnService
import pro.sitescope.app.VpnDiagnostics
import pro.sitescope.app.VpnNetworkCoordinator
import pro.sitescope.app.VpnStateRecovery
import pro.sitescope.app.XrayProfilePreflight
import pro.sitescope.app.XrayProfileStore
import pro.sitescope.app.ui.*
import java.io.File

/** Режим, который выбирает человек: только свои адреса, через сервер или без сервера (для записи трафика). */
enum class VpnMode(val title: String, val text: String) {
    HOSTS("Свои адреса", "Подменяет адреса выбранных сайтов. Остальной трафик идёт как обычно."),
    SERVER("Через сервер", "Весь трафик телефона идёт через ваш сервер. Свои адреса и DNS действуют внутри туннеля."),
    LOCAL("Без сервера", "Трафик идёт как обычно, но проходит через приложение: можно записать его и применить свои адреса.")
}

private fun bytesText(v: Long): String = when {
    v >= 1L shl 30 -> String.format(java.util.Locale.US, "%.1f ГБ", v / 1073741824.0)
    v >= 1L shl 20 -> String.format(java.util.Locale.US, "%.1f МБ", v / 1048576.0)
    v >= 1024 -> "${v / 1024} КБ"
    else -> "$v Б"
}

class VpnViewModel(app: Application) : AndroidViewModel(app) {
    private val context: Context get() = getApplication()
    private val state get() = context.getSharedPreferences(HostsVpnService.PREFS, Context.MODE_PRIVATE)
    private val hosts by lazy { HostsStore(context) }
    private val servers by lazy { XrayProfileStore(context) }

    // состояние службы
    var lifecycle by mutableStateOf(HostsVpnService.STATE_OFF); private set
    var runningMode by mutableStateOf(""); private set
    var message by mutableStateOf(""); private set
    var runningProfile by mutableStateOf(""); private set
    var runningProfileId by mutableStateOf(""); private set
    var capturing by mutableStateOf(false); private set
    var appliedNote by mutableStateOf("")

    // выбор и данные
    var mode by mutableStateOf(VpnMode.HOSTS)
    var serverList by mutableStateOf<List<XrayProfileStore.Info>>(emptyList()); private set
    var selectedServer by mutableStateOf(""); private set
    var hostProfiles by mutableStateOf<List<HostsStore.Profile>>(emptyList()); private set
    var entries by mutableStateOf<List<HostsEntry>>(emptyList()); private set
    var dnsServers by mutableStateOf("")
    var basicUpstream by mutableStateOf("")
    var appThroughTunnel by mutableStateOf(true)

    // запись
    var filter by mutableStateOf("")
    var seen by mutableStateOf(0L); private set
    var written by mutableStateOf(0L); private set
    var writtenBytes by mutableStateOf(0L); private set
    var flows by mutableStateOf<List<Flow>>(emptyList()); private set
    var files by mutableStateOf<List<File>>(emptyList()); private set
    var finishing by mutableStateOf(false); private set
    var mitm by mutableStateOf(false); private set
    var blockQuic by mutableStateOf(true); private set
    var targets by mutableStateOf<Set<String>>(emptySet()); private set
    var caInstalled by mutableStateOf(false); private set
    var apps by mutableStateOf<List<Pair<String, String>>>(emptyList()); private set

    val active: Boolean get() = lifecycle == HostsVpnService.STATE_ACTIVE
    val busy: Boolean get() = lifecycle == HostsVpnService.STATE_STARTING || lifecycle == HostsVpnService.STATE_STOPPING
    val serverActive: Boolean get() = active && runningMode == HostsVpnService.MODE_XRAY && runningProfileId.isNotEmpty()
    private val captureDir get() = File(context.filesDir, "captures")

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try { VpnStateRecovery.recoverIfStale(context) } catch (e: Throwable) { }
            dnsServers = VpnSettings.dnsServers(context).joinToString(", "); basicUpstream = VpnSettings.basicUpstream(context)
            appThroughTunnel = VpnSettings.appThroughTunnel(context)
            mitm = VpnSettings.mitm(context); blockQuic = VpnSettings.blockQuic(context); targets = VpnSettings.targetApps(context)
            if (mitm) caInstalled = Mitm.caInstalled(context)
            val desired = state.getString("desiredMode", HostsVpnService.MODE_BASIC)
            mode = if (desired == HostsVpnService.MODE_XRAY) (if (VpnSettings.directMode(context)) VpnMode.LOCAL else VpnMode.SERVER) else VpnMode.HOSTS
            reloadData()
        }
        viewModelScope.launch {
            while (isActive) { poll(); delay(1000) }
        }
    }

    private suspend fun poll() = withContext(Dispatchers.IO) {
        try { VpnStateRecovery.recoverIfStale(context) } catch (e: Throwable) { }
        val p = state
        lifecycle = p.getString(HostsVpnService.KEY_STATE, HostsVpnService.STATE_OFF) ?: HostsVpnService.STATE_OFF
        runningMode = p.getString("mode", "") ?: ""; message = p.getString("message", "") ?: ""
        runningProfile = p.getString("profile", "") ?: ""; runningProfileId = p.getString("profileId", "") ?: ""
        val session = CaptureHub.session
        capturing = session != null && lifecycle == HostsVpnService.STATE_ACTIVE
        if (session != null) { seen = session.seen; written = session.written; writtenBytes = session.writtenBytes; flows = session.topFlows(25); session.flush() }
        files = CaptureHub.files(captureDir); finishing = CaptureHub.converting
    }

    private fun reloadData() {
        try {
            serverList = servers.profiles(); selectedServer = servers.info().id
            hostProfiles = hosts.profiles()
            val current = hosts.activeProfile()
            entries = if (current == null) emptyList() else hosts.entries(current.id)
        } catch (e: Exception) { }
    }

    private fun io(block: () -> Unit) = viewModelScope.launch(Dispatchers.IO) { try { block() } catch (e: Exception) { appliedNote = e.message ?: "Не получилось" }; reloadData() }

    // ---------- включение и выключение ----------
    fun start() {
        val service = if (mode == VpnMode.HOSTS) HostsVpnService.MODE_BASIC else HostsVpnService.MODE_XRAY
        VpnSettings.setDirectMode(context, mode == VpnMode.LOCAL)
        try { XrayProfilePreflight.cancel() } catch (e: Throwable) { }
        state.edit().putString(HostsVpnService.KEY_STATE, HostsVpnService.STATE_STARTING).putBoolean("active", false).putString("mode", service)
            .putString("message", "Запуск…").putString("profileId", if (mode == VpnMode.SERVER) selectedServer else "")
            .putLong("changedAt", System.currentTimeMillis()).putLong("heartbeatAt", 0L).putLong("serviceHeartbeatAt", System.currentTimeMillis()).apply()
        lifecycle = HostsVpnService.STATE_STARTING
        context.startForegroundService(Intent(context, HostsVpnService::class.java).setAction(HostsVpnService.ACTION_START).putExtra(HostsVpnService.EXTRA_MODE, service))
    }

    fun stop() {
        state.edit().putString(HostsVpnService.KEY_STATE, HostsVpnService.STATE_STOPPING).putBoolean("active", false).putString("message", "Остановка…")
            .putLong("changedAt", System.currentTimeMillis()).apply()
        lifecycle = HostsVpnService.STATE_STOPPING
        context.startService(Intent(context, HostsVpnService::class.java).setAction(HostsVpnService.ACTION_STOP))
    }

    /** Применяет изменённые адреса и DNS к работающему VPN, не разрывая его. */
    fun applyLive() {
        if (!active) { appliedNote = "Сохранено. Начнёт действовать при включении."; return }
        context.startService(Intent(context, HostsVpnService::class.java).setAction(HostsVpnService.ACTION_RELOAD))
        appliedNote = "Применено на лету, VPN не прерывался."
    }

    // ---------- серверы ----------
    fun addServer(raw: String, done: (String?) -> Unit) = viewModelScope.launch {
        val error = withContext(Dispatchers.IO) { try { servers.add(raw.trim()); null } catch (e: Exception) { e.message ?: "Ссылка не распознана" } }
        withContext(Dispatchers.IO) { reloadData() }; done(error)
    }
    fun selectServer(id: String) = io { servers.select(id); if (serverActive && runningProfileId != id) { reloadData(); viewModelScope.launch { mode = VpnMode.SERVER; start() } } }
    fun renameServer(id: String, name: String) = io { servers.rename(id, name.trim()) }
    fun deleteServer(id: String) = io { servers.delete(id) }

    // ---------- свои адреса ----------
    fun saveEntry(existing: HostsEntry?, address: String, names: String, comment: String): String? {
        val ip = HostsParser.normalizeAddress(address) ?: return "Адрес должен быть IP: например 203.0.113.10"
        val list = names.split(' ', ',', '\n', '\t').map { it.trim() }.filter { it.isNotEmpty() }.map { HostsParser.normalizeHostname(it) ?: return "Не похоже на имя сайта: $it" }
        if (list.isEmpty()) return "Укажите хотя бы одно имя сайта"
        io {
            val profile = hosts.activeProfile() ?: return@io
            val e = existing ?: HostsEntry()
            e.profileId = profile.id; e.address = ip; e.hostnames.clear(); e.hostnames.addAll(list); e.comment = comment.trim()
            hosts.saveEntry(e); applyLive()
        }
        return null
    }
    fun toggleEntry(e: HostsEntry, enabled: Boolean) = io { e.enabled = enabled; hosts.saveEntry(e); applyLive() }
    fun deleteEntry(e: HostsEntry) = io { hosts.deleteEntry(e.id, e.profileId); applyLive() }
    fun importText(raw: String, replace: Boolean, done: (String) -> Unit) = viewModelScope.launch {
        val text = withContext(Dispatchers.IO) {
            try {
                val profile = hosts.activeProfile() ?: return@withContext "Нет набора адресов"
                val parsed = HostsParser.parse(raw, profile.id)
                if (parsed.entries.isEmpty()) return@withContext "В тексте не найдено строк вида «IP имя»"
                val merged = if (replace) parsed.entries else hosts.entries(profile.id) + parsed.entries
                hosts.replaceEntries(profile.id, merged); reloadData(); applyLive()
                "Добавлено строк: ${parsed.entries.size}" + (if (parsed.issues.isNotEmpty()) " · пропущено с ошибками: ${parsed.issues.count { it.error }}" else "")
            } catch (e: Exception) { e.message ?: "Не получилось" }
        }
        done(text)
    }
    fun exportText(): String = entries.joinToString("\n") { it.canonicalLine() }
    fun activateProfile(id: String) = io { hosts.activate(id); reloadData(); applyLive() }
    fun createProfile(name: String) = io { val p = hosts.createProfile(name); hosts.activate(p.id) }
    fun deleteProfile(id: String) = io { hosts.deleteProfile(id); hosts.activeProfile() ?: hosts.profiles().firstOrNull()?.let { hosts.activate(it.id) }; applyLive() }

    // ---------- DNS ----------
    fun saveDns() {
        VpnSettings.setDnsServers(context, dnsServers); VpnSettings.setBasicUpstream(context, basicUpstream); VpnSettings.setAppThroughTunnel(context, appThroughTunnel)
        applyLive()
    }

    // ---------- расшифровка и выбор приложений ----------
    fun switchMitm(on: Boolean) = viewModelScope.launch(Dispatchers.IO) {
        if (on) try { Mitm.ensureCa(context) } catch (e: Exception) { appliedNote = "Не удалось создать сертификат"; return@launch }
        VpnSettings.setMitm(context, on); mitm = on
        caInstalled = on && Mitm.caInstalled(context)
        if (active && runningMode == HostsVpnService.MODE_XRAY) applyLive()
    }
    fun switchQuic(on: Boolean) { VpnSettings.setBlockQuic(context, on); blockQuic = on; if (mitm && active && runningMode == HostsVpnService.MODE_XRAY) applyLive() }
    fun refreshCa() = viewModelScope.launch(Dispatchers.IO) { caInstalled = mitm && Mitm.caInstalled(context) }
    fun exportCa(target: Uri, done: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) { try { val file = Mitm.ensureCa(context); context.contentResolver.openOutputStream(target)?.use { it.write(file.readBytes()) }; true } catch (e: Exception) { false } }
        done(ok)
    }
    fun loadApps() = viewModelScope.launch(Dispatchers.IO) {
        if (apps.isNotEmpty()) return@launch
        val pm = context.packageManager
        apps = try {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to (it.loadLabel(pm)?.toString() ?: it.activityInfo.packageName) }
                .filter { it.first != context.packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        } catch (e: Exception) { emptyList() }
    }
    /** Список приложений действует на уровне VPN, поэтому при включённом VPN он переподключается. */
    fun chooseTargets(value: Set<String>) {
        VpnSettings.setTargetApps(context, value); targets = value
        if (active && runningMode == HostsVpnService.MODE_XRAY) { appliedNote = "VPN переподключается для выбранных приложений."; start() }
    }
    fun appLabel(pkg: String): String = apps.firstOrNull { it.first == pkg }?.second ?: pkg

    // ---------- запись ----------
    fun startCapture() { context.startService(Intent(context, HostsVpnService::class.java).setAction(HostsVpnService.ACTION_CAPTURE_START).putExtra(HostsVpnService.EXTRA_FILTER, filter.trim())); seen = 0; written = 0; writtenBytes = 0; flows = emptyList() }
    fun stopCapture() { context.startService(Intent(context, HostsVpnService::class.java).setAction(HostsVpnService.ACTION_CAPTURE_STOP)) }
    fun deleteFile(file: File) { try { file.delete() } catch (e: Exception) { }; files = CaptureHub.files(captureDir) }
    fun copyFile(file: File, target: Uri, done: (Boolean) -> Unit) = viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) { try { context.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } }; true } catch (e: Exception) { false } }
        done(ok)
    }
}

@Composable
fun VpnScreen(vm: VpnViewModel = viewModel()) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.start() else toast(context, "Без разрешения VPN включить нельзя")
    }
    fun connect() {
        if (vm.mode == VpnMode.SERVER && vm.selectedServer.isEmpty()) { toast(context, "Сначала добавьте сервер"); tab = 1; return }
        val prepare = try { VpnService.prepare(context) } catch (e: Exception) { null }
        if (prepare != null) permission.launch(prepare) else vm.start()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 28.dp)) {
        StatusCard(vm, onConnect = { connect() }, onReason = { tab = 4 })
        if (vm.appliedNote.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { vm.appliedNote = "" }.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Bolt, null, tint = tones.good, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp))
                Text(vm.appliedNote, style = MaterialTheme.typography.bodySmall, color = tones.good)
            }
        }
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Адреса", "Серверы", "DNS", "Запись", "Журнал").forEachIndexed { i, title -> FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(title) }) }
        }
        when (tab) {
            0 -> HostsTab(vm)
            1 -> ServersTab(vm)
            2 -> DnsTab(vm)
            3 -> CaptureTab(vm)
            else -> LogTab(vm)
        }
    }
}

@Composable
private fun StatusCard(vm: VpnViewModel, onConnect: () -> Unit, onReason: () -> Unit) {
    val context = LocalContext.current
    val t = tones
    val error = vm.lifecycle == HostsVpnService.STATE_ERROR
    val title = when {
        vm.lifecycle == HostsVpnService.STATE_STARTING -> "Подключаемся…"
        vm.lifecycle == HostsVpnService.STATE_STOPPING -> "Отключаемся…"
        vm.active && vm.runningMode == HostsVpnService.MODE_BASIC -> "Свои адреса действуют"
        vm.serverActive -> "Подключено через сервер"
        vm.active -> "Трафик идёт через приложение"
        error -> "Не удалось подключиться"
        else -> "VPN выключен"
    }
    SoftCard(color = when { vm.active -> t.goodSoft; error -> t.badSoft; else -> MaterialTheme.colorScheme.surfaceContainerLowest }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (vm.active) Icons.Rounded.Shield else Icons.Rounded.ShieldMoon, if (vm.active) t.good else if (error) t.bad else MaterialTheme.colorScheme.outline,
                MaterialTheme.colorScheme.surfaceContainerLowest, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                if (vm.serverActive && vm.runningProfile.isNotEmpty()) Text(vm.runningProfile, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (vm.message.isNotEmpty()) Text(vm.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!vm.active && !vm.busy) {
            FlowRow(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VpnMode.entries.forEach { m -> FilterChip(selected = vm.mode == m, onClick = { vm.mode = m }, label = { Text(m.title) }) }
            }
            Text(vm.mode.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        if (vm.active || vm.busy) Button(onClick = { vm.stop() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = t.bad)) { Text(if (vm.busy) "Отменить" else "Выключить") }
        else Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.PowerSettingsNew, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Включить") }
        if (error) TextButton(onClick = onReason) { Text("Показать причину сбоя") }
        if (error && vm.message.contains("VPN", true)) TextButton(onClick = {
            try { context.startActivity(VpnNetworkCoordinator.settingsIntent()) } catch (e: Exception) { toast(context, "Откройте Настройки → VPN") }
        }) { Text("Открыть настройки VPN в системе") }
    }
}

// ------------------------------------------------------------------ Адреса

@Composable
private fun HostsTab(vm: VpnViewModel) {
    val context = LocalContext.current
    var editing by remember { mutableStateOf<HostsEntry?>(null) }
    var adding by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var newProfile by remember { mutableStateOf(false) }
    val active = vm.hostProfiles.firstOrNull { it.active }

    SectionTitle("Свои адреса сайтов", action = "Вставить списком", onAction = { importing = true })
    Text("Здесь вы сами решаете, на какой адрес ведёт имя сайта — как в файле hosts. Изменения применяются сразу, даже при включённом VPN.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (vm.hostProfiles.size > 1 || active?.id != HostsStore.DEFAULT_PROFILE_ID) FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        vm.hostProfiles.forEach { p -> FilterChip(selected = p.active, onClick = { if (!p.active) vm.activateProfile(p.id) }, label = { Text(p.name) }) }
    }
    Spacer(Modifier.height(8.dp))
    if (vm.entries.isEmpty()) SoftCard { Text("Пока нет ни одного правила.", style = MaterialTheme.typography.bodyMedium) }
    else SoftCard(padding = 8.dp) {
        vm.entries.forEach { e ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { editing = e }.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.hostnames.joinToString(", "), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text("→ " + e.address + (if (e.comment.isNotEmpty()) "  · " + e.comment else ""), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Switch(checked = e.enabled, onCheckedChange = { vm.toggleEntry(e, it) })
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { adding = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Добавить правило") }
        if (vm.entries.isNotEmpty()) OutlinedButton(onClick = { copyText(context, vm.exportText(), "Список скопирован") }) { Text("Копировать") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { newProfile = true }) { Text("Новый набор") }
        if (active != null && active.id != HostsStore.DEFAULT_PROFILE_ID) TextButton(onClick = { vm.deleteProfile(active.id) }, colors = ButtonDefaults.textButtonColors(contentColor = tones.bad)) { Text("Удалить набор") }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = { open(context, HostsActivity::class.java) }) { Text("Системный hosts (root)") }
    }

    if (adding || editing != null) EntrySheet(vm, editing) { adding = false; editing = null }
    if (newProfile) TextDialog("Новый набор адресов", "", "Например: тестовый сервер", onClose = { newProfile = false }) { vm.createProfile(it); newProfile = false }
    if (importing) {
        var raw by remember { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { importing = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
                Text("Вставить списком", style = MaterialTheme.typography.titleLarge)
                Text("По одной строке на правило: сначала IP, потом имена сайтов.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                BigField(raw, { raw = it }, "203.0.113.10 example.com www.example.com", singleLine = false, minLines = 5, mono = true, keyboard = KeyboardType.Text)
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.importText(raw, false) { toast(context, it) }; importing = false }, enabled = raw.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Добавить") }
                    OutlinedButton(onClick = { vm.importText(raw, true) { toast(context, it) }; importing = false }, enabled = raw.isNotBlank()) { Text("Заменить всё") }
                }
            }
        }
    }
}

@Composable
private fun EntrySheet(vm: VpnViewModel, existing: HostsEntry?, onClose: () -> Unit) {
    val context = LocalContext.current
    var address by remember { mutableStateOf(existing?.address ?: "") }
    var names by remember { mutableStateOf(existing?.hostnames?.joinToString(" ") ?: "") }
    var comment by remember { mutableStateOf(existing?.comment ?: "") }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
            Text(if (existing == null) "Новое правило" else "Правило", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(10.dp))
            BigField(names, { names = it }, "Сайт: example.com www.example.com", leading = Icons.Rounded.Language)
            Spacer(Modifier.height(8.dp))
            BigField(address, { address = it }, "Куда вести: IP-адрес", leading = Icons.Rounded.Dns, keyboard = KeyboardType.Ascii, mono = true)
            Spacer(Modifier.height(8.dp))
            BigField(comment, { comment = it }, "Заметка (необязательно)", leading = Icons.Rounded.Notes, keyboard = KeyboardType.Text)
            Button(onClick = { val error = vm.saveEntry(existing, address, names, comment); if (error == null) onClose() else toast(context, error) }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { Text("Сохранить и применить") }
            if (existing != null) TextButton(onClick = { vm.deleteEntry(existing); onClose() }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.textButtonColors(contentColor = tones.bad)) { Text("Удалить правило") }
        }
    }
}

// ------------------------------------------------------------------ Серверы

@Composable
private fun ServersTab(vm: VpnViewModel) {
    val context = LocalContext.current
    var adding by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf<XrayProfileStore.Info?>(null) }
    var rename by remember { mutableStateOf<XrayProfileStore.Info?>(null) }
    val t = tones
    SectionTitle("Серверы")
    Text("Ваши серверы Xray (VLESS, VMess, Trojan, Shadowsocks). Ключи хранятся в защищённом хранилище телефона.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    if (vm.serverList.isEmpty()) SoftCard { Text("Серверов пока нет. Добавьте ссылку вида vless://… или готовую конфигурацию.", style = MaterialTheme.typography.bodyMedium) }
    vm.serverList.forEach { s ->
        val selected = s.id == vm.selectedServer
        val running = vm.serverActive && vm.runningProfileId == s.id
        SoftCard(padding = 14.dp, onClick = { if (!selected) vm.selectServer(s.id) }, modifier = Modifier.padding(bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = { if (!selected) vm.selectServer(s.id) })
                Column(Modifier.weight(1f)) {
                    Text(s.name.ifEmpty { s.endpoint }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(s.type.uppercase() + " · " + s.endpoint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (running) Pill("Работает", t.good, t.goodSoft)
                IconButton(onClick = { menu = s }) { Icon(Icons.Rounded.MoreVert, "Ещё") }
            }
        }
    }
    Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(6.dp)); Text("Добавить сервер") }
    if (vm.serverActive) Text("Если выбрать другой сервер при включённом VPN, он переподключится сам.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 6.dp))

    if (adding) {
        var raw by remember { mutableStateOf("") }
        ModalBottomSheet(onDismissRequest = { adding = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState())) {
                Text("Новый сервер", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                BigField(raw, { raw = it }, "vless://…, vmess://…, trojan://…, ss://… или JSON", singleLine = false, minLines = 4, mono = true, keyboard = KeyboardType.Text)
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        val clip = (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
                        raw = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString() ?: raw
                    }) { Text("Из буфера") }
                    Button(onClick = { vm.addServer(raw) { error -> if (error == null) { adding = false; toast(context, "Сервер добавлен") } else toast(context, error) } }, enabled = raw.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Сохранить") }
                }
            }
        }
    }
    menu?.let { s ->
        AlertDialog(onDismissRequest = { menu = null }, title = { Text(s.name.ifEmpty { s.endpoint }) }, text = { Text(s.type.uppercase() + " · " + s.endpoint) },
            confirmButton = { TextButton(onClick = { rename = s; menu = null }) { Text("Переименовать") } },
            dismissButton = { TextButton(onClick = { vm.deleteServer(s.id); menu = null }, colors = ButtonDefaults.textButtonColors(contentColor = t.bad)) { Text("Удалить") } })
    }
    rename?.let { s -> TextDialog("Название сервера", s.name, "Например: Амстердам", onClose = { rename = null }) { vm.renameServer(s.id, it); rename = null } }
}

// ------------------------------------------------------------------ DNS

@Composable
private fun DnsTab(vm: VpnViewModel) {
    val scope = rememberCoroutineScope()
    val presets = listOf("Cloudflare" to "1.1.1.1", "Google" to "8.8.8.8", "Quad9" to "9.9.9.9", "AdGuard" to "94.140.14.14", "Яндекс" to "77.88.8.8", "Cloudflare DoH" to "https://1.1.1.1/dns-query")
    SectionTitle("DNS-серверы")
    SoftCard {
        Text("Внутри туннеля", style = MaterialTheme.typography.titleSmall)
        Text("Кто отвечает на вопрос «какой адрес у сайта», когда VPN включён через сервер или без сервера. Пусто — Cloudflare и Google.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        BigField(vm.dnsServers, { vm.dnsServers = it }, "1.1.1.1, 8.8.8.8", leading = Icons.Rounded.Dns, keyboard = KeyboardType.Ascii, mono = true)
        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            presets.forEach { (title, value) -> SuggestionChip(onClick = { vm.dnsServers = value }, label = { Text(title) }) }
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Text("В режиме «Свои адреса»", style = MaterialTheme.typography.titleSmall)
        Text("Куда пересылать остальные DNS-запросы. Пусто — сервер, который выдала ваша сеть.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        BigField(vm.basicUpstream, { vm.basicUpstream = it }, "Например 1.1.1.1", leading = Icons.Rounded.Dns, keyboard = KeyboardType.Ascii, mono = true)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Проверки SiteScope идут через сервер", style = MaterialTheme.typography.bodyMedium)
                Text("Анализ сайта, DNS, «Мой адрес» и ТВ работают так, будто вы находитесь там, где сервер", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = vm.appThroughTunnel, onCheckedChange = { vm.appThroughTunnel = it })
        }
        Button(onClick = { vm.saveDns() }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp)) { Text(if (vm.active) "Применить без разрыва VPN" else "Сохранить") }
    }

    SectionTitle("DNS-запрос")
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("A") }
    var resolver by remember { mutableIntStateOf(0) }
    var through by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<DnsTool.Answer?>(null) }
    val viaTunnel = through && vm.serverActive
    fun ask() = scope.launch {
        busy = true
        answer = withContext(Dispatchers.IO) { DnsTool.query(name, type, DnsTool.resolvers[resolver].second, viaTunnel) }
        busy = false
    }
    SoftCard {
        BigField(name, { name = it }, "Имя сайта: example.com", onGo = { if (name.isNotBlank() && !busy) ask() }, leading = Icons.Rounded.Language)
        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("A", "AAAA", "CNAME", "MX", "TXT", "NS", "CAA").forEach { v -> FilterChip(selected = type == v, onClick = { type = v }, label = { Text(v) }) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DnsTool.resolvers.forEachIndexed { i, r -> FilterChip(selected = resolver == i, onClick = { resolver = i }, label = { Text(r.first) }) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (vm.serverActive) "Спросить через сервер VPN" else "Через сервер — когда VPN включён", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = viaTunnel, onCheckedChange = { through = it }, enabled = vm.serverActive)
        }
        Button(onClick = { ask() }, enabled = name.isNotBlank() && !busy, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) { Text(if (busy) "Спрашиваем…" else "Спросить") }
        answer?.let { a ->
            Spacer(Modifier.height(10.dp))
            if (a.error.isNotEmpty()) ErrorCard("Нет ответа (${a.via}): ${a.error}")
            else {
                Text("${a.status} · ${a.via} · ${a.millis} мс", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (a.records.isEmpty()) Text("Записей этого типа нет.", style = MaterialTheme.typography.bodyMedium)
                a.records.forEach { r -> InfoRow(r.type, r.value, mono = true) }
            }
        }
    }
}

// ------------------------------------------------------------------ Запись

@Composable
private fun CaptureTab(vm: VpnViewModel) {
    val context = LocalContext.current
    var saving by remember { mutableStateOf<File?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val file = saving
        if (uri != null && file != null) vm.copyFile(file, uri) { ok -> toast(context, if (ok) "Файл сохранён" else "Не удалось сохранить") }
        saving = null
    }
    val t = tones
    var picking by remember { mutableStateOf(false) }
    val saveCa = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-x509-ca-cert")) { uri: Uri? ->
        if (uri != null) vm.exportCa(uri) { ok -> toast(context, if (ok) "Сертификат сохранён. Теперь установите его в настройках." else "Не удалось сохранить") }
    }
    LaunchedEffect(Unit) { vm.loadApps(); vm.refreshCa() }
    val full = vm.mode != VpnMode.HOSTS || vm.runningMode == HostsVpnService.MODE_XRAY

    SectionTitle("Что записывать")
    SoftCard {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { picking = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Apps, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Приложения", style = MaterialTheme.typography.titleSmall)
                Text(if (vm.targets.isEmpty()) "Все приложения телефона" else vm.targets.take(3).joinToString(", ") { vm.appLabel(it) } + (if (vm.targets.size > 3) " и ещё ${vm.targets.size - 3}" else ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
        }
        if (vm.targets.isNotEmpty()) Text("VPN и запись действуют только для выбранных приложений. Остальные идут в сеть как обычно.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.LockOpen, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Расшифровка TLS", style = MaterialTheme.typography.titleSmall)
                Text("Ключи сеансов сохраняются в файл дампа: Wireshark покажет содержимое HTTPS", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = vm.mitm, onCheckedChange = { vm.switchMitm(it) })
        }
        if (vm.mitm) {
            Spacer(Modifier.height(8.dp))
            if (vm.caInstalled) Pill("Сертификат SiteScope установлен", t.good, t.goodSoft, icon = Icons.Rounded.Check)
            else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(t.warnSoft).padding(12.dp)) {
                Text("Нужно один раз установить сертификат", style = MaterialTheme.typography.titleSmall)
                Text("1. Сохраните файл сертификата.\n2. Откройте настройки безопасности → «Шифрование и учётные данные» → «Установить сертификат» → «Сертификат ЦС».\n3. Выберите сохранённый файл.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { saveCa.launch("sitescope-ca.crt") }) { Text("Сохранить файл") }
                    OutlinedButton(onClick = { try { context.startActivity(Intent(android.provider.Settings.ACTION_SECURITY_SETTINGS)) } catch (e: Exception) { toast(context, "Откройте Настройки → Безопасность") } }) { Text("Настройки") }
                }
                TextButton(onClick = { vm.refreshCa() }, contentPadding = PaddingValues(0.dp)) { Text("Я установил — проверить") }
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Запретить QUIC и закрытый DNS", style = MaterialTheme.typography.bodyMedium)
                    Text("Иначе часть трафика уйдёт по протоколам, которые расшифровать нельзя", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = vm.blockQuic, onCheckedChange = { vm.switchQuic(it) })
            }
            Text("Расшифровка работает для браузеров и приложений, которые доверяют пользовательским сертификатам. Многие приложения (банки, мессенджеры) им не доверяют и при включённой расшифровке не соединятся — выбирайте конкретные приложения выше.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(top = 6.dp))
        }
        if (!full) Text("Выбор приложений и расшифровка работают в режимах «Через сервер» и «Без сервера».", style = MaterialTheme.typography.labelSmall, color = t.warn, modifier = Modifier.padding(top = 6.dp))
    }
    if (picking) AppPicker(vm.apps, vm.targets, onClose = { picking = false }) { chosen -> picking = false; if (chosen != vm.targets) vm.chooseTargets(chosen) }

    SectionTitle("Запись трафика")
    SoftCard(color = if (vm.capturing) t.badSoft else MaterialTheme.colorScheme.surfaceContainerLowest) {
        Text("Сохраняет сетевые пакеты в файл .pcapng — его открывает Wireshark. Записывается трафик приложений, который идёт через VPN: откройте нужный сайт или приложение, пока идёт запись.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        if (!vm.capturing) {
            BigField(vm.filter, { vm.filter = it }, "Только этот сайт: example.com (необязательно)", leading = Icons.Rounded.FilterAlt)
            Text(when {
                !vm.active -> "Сначала включите VPN. Для полной записи выберите режим «Через сервер» или «Без сервера»."
                vm.runningMode == HostsVpnService.MODE_BASIC -> "В режиме «Свои адреса» через приложение проходят только DNS-запросы — запишутся только они."
                else -> "Запишется весь трафик или только выбранный сайт: его DNS-запросы и обмен с его адресами."
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Button(onClick = { vm.startCapture() }, enabled = vm.active, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Icon(Icons.Rounded.FiberManualRecord, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Начать запись")
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Dot(t.bad, 12.dp); Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Идёт запись", style = MaterialTheme.typography.titleMedium)
                    Text("В файле: ${vm.written} пакетов, ${bytesText(vm.writtenBytes)} · всего прошло: ${vm.seen}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Button(onClick = { vm.stopCapture() }, colors = ButtonDefaults.buttonColors(containerColor = t.bad)) { Text("Стоп") }
            }
        }
    }
    if (vm.capturing && vm.flows.isNotEmpty()) {
        SectionTitle("С кем идёт обмен")
        SoftCard(padding = 12.dp) {
            vm.flows.forEach { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.host.ifEmpty { f.remote }, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(f.protocol + " " + f.remote + ":" + f.port, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text("↑" + bytesText(f.sent) + "  ↓" + bytesText(f.received), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (vm.finishing) { Spacer(Modifier.height(10.dp)); LoadingCard("Собираем файл дампа", "Вкладываем ключи TLS и пакеты") }
    if (vm.files.isNotEmpty()) {
        SectionTitle("Записанные файлы")
        vm.files.take(30).forEach { file ->
            SoftCard(padding = 12.dp, modifier = Modifier.padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Description, t.info, t.infoSoft, 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(bytesText(file.length()) + (if (file.name.contains("-tls.")) " · с ключами TLS" else ""), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                    }
                    IconButton(onClick = { saving = file; save.launch(file.name) }) { Icon(Icons.Rounded.SaveAlt, "Сохранить") }
                    IconButton(onClick = { vm.deleteFile(file) }) { Icon(Icons.Rounded.DeleteOutline, "Удалить") }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ Журнал

@Composable
private fun LogTab(vm: VpnViewModel) {
    val context = LocalContext.current
    var log by remember { mutableStateOf("") }
    var crash by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    LaunchedEffect(vm.lifecycle, vm.message) {
        log = withContext(Dispatchers.IO) { VpnDiagnostics.read(context).lines().takeLast(120).joinToString("\n") }
        crash = withContext(Dispatchers.IO) { CrashLog.read(context) }
    }
    SectionTitle("Причина последнего сбоя", action = if (crash.isNotEmpty()) "Очистить" else null, onAction = { CrashLog.clear(context); crash = "" })
    if (crash.isEmpty()) Text("Сбоев не записано.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    else MonoBlock(crash.takeLast(9000))
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { shareText(context, "SiteScope Pro · журнал VPN\n\n" + crash + "\n\n=== Журнал подключения ===\n" + log, "Журнал VPN SiteScope") }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Отправить журнал") }
        OutlinedButton(onClick = { scope.launch { crash = withContext(Dispatchers.IO) { CrashLog.captureSystemLog(context, "снято вручную"); CrashLog.read(context) } } }) { Text("Снять сейчас") }
    }
    SectionTitle("Журнал", action = "Копировать", onAction = { copyText(context, log, "Журнал скопирован") })
    Text("Что происходило при подключении. Пароли и ключи в журнал не попадают.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    MonoBlock(log.ifEmpty { "Пока пусто." })
}


@Composable
private fun AppPicker(apps: List<Pair<String, String>>, selected: Set<String>, onClose: () -> Unit, onDone: (Set<String>) -> Unit) {
    var chosen by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    val shown = apps.filter { query.isBlank() || it.second.contains(query.trim(), true) || it.first.contains(query.trim(), true) }
        .sortedByDescending { it.first in selected }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
            Text("Для каких приложений", style = MaterialTheme.typography.titleLarge)
            Text(if (chosen.isEmpty()) "Сейчас: все приложения" else "Выбрано: ${chosen.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            BigField(query, { query = it }, "Поиск приложения", keyboard = KeyboardType.Text)
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                items(shown.size) { index ->
                    val (pkg, label) = shown[index]
                    Row(Modifier.fillMaxWidth().clickable { chosen = if (pkg in chosen) chosen - pkg else chosen + pkg }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = pkg in chosen, onCheckedChange = { chosen = if (it) chosen + pkg else chosen - pkg })
                        Column(Modifier.weight(1f)) {
                            Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(pkg, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { chosen = emptySet() }, modifier = Modifier.weight(1f)) { Text("Все приложения") }
                Button(onClick = { onDone(chosen) }, modifier = Modifier.weight(1f)) { Text("Готово") }
            }
        }
    }
}
