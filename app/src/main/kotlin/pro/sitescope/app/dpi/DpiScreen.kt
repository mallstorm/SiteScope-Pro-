@file:OptIn(ExperimentalLayoutApi::class)

package pro.sitescope.app.dpi

import android.app.Activity
import android.content.Intent
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import pro.sitescope.app.HostsVpnService
import pro.sitescope.app.ui.*

private val tabs = listOf("Старт", "Подбор", "Стратегия", "Приложения", "Журнал")

@Composable
fun DpiScreen(vm: DpiViewModel = viewModel()) {
    val context = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    val state by DpiRuntime.state.collectAsState()
    var conflict by remember { mutableStateOf(false) }

    fun launch() {
        val cfg = vm.cfg
        if (!DpiEngine.available(context)) { vm.note = "byedpi недоступен: устройство не arm64 или файл не найден"; return }
        DpiRuntime.startService(context, cfg)
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) launch() else vm.note = "Разрешение VPN не выдано"
    }
    fun begin() {
        if (vm.cfg.isVpn) {
            if (DpiRuntime.siteScopeVpnBusy(context)) { conflict = true; return }
            val intent = DpiRuntime.vpnConsent(context)
            if (intent != null) consent.launch(intent) else launch()
        } else launch()
    }
    LaunchedEffect(vm.requestStart) { if (vm.requestStart) { vm.requestStart = false; if (!DpiRuntime.running) begin() } }
    LaunchedEffect(state.phase) { while (true) { DpiRuntime.refreshLog(); delay(2000) } }

    if (conflict) AlertDialog(
        onDismissRequest = { conflict = false },
        title = { Text("VPN уже занят") },
        text = { Text("Сейчас работает VPN SiteScope (Hosts/Xray). Android допускает только один VPN. Можно остановить его и включить DPI-VPN, либо включить обход внутри VPN SiteScope — тогда Xray будет выходить в сеть через byedpi.") },
        confirmButton = {
            TextButton({
                conflict = false
                context.startService(Intent(context, HostsVpnService::class.java).setAction(HostsVpnService.ACTION_STOP))
                val intent = DpiRuntime.vpnConsent(context)
                if (intent != null) consent.launch(intent) else launch()
            }) { Text("Остановить и включить") }
        },
        dismissButton = {
            Row {
                TextButton({ conflict = false; vm.update(vm.cfg.copy(chainXray = true)); vm.note = "Включено «Обход внутри VPN SiteScope». Перезапустите VPN SiteScope." }) { Text("Внутри VPN SiteScope") }
                TextButton({ conflict = false }) { Text("Отмена") }
            }
        },
    )

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tabs.forEachIndexed { i, t -> MiniChip(t, tab == i) { tab = i } }
        }
        when (tab) {
            0 -> RunTab(vm, state, onToggle = { if (state.phase == DpiPhase.STOPPED || state.phase == DpiPhase.ERROR) begin() else DpiRuntime.stopService(context) })
            1 -> SearchTab(vm)
            2 -> StrategyTab(vm)
            3 -> AppsTab(vm)
            else -> LogTab(state)
        }
    }
}

@Composable
private fun RunTab(vm: DpiViewModel, state: DpiState, onToggle: () -> Unit) {
    val context = LocalContext.current
    val cfg = vm.cfg
    val progress by vm.search.progress.collectAsState()
    var help by remember { mutableStateOf(false) }
    val running = state.phase == DpiPhase.RUNNING
    val shownPort = if (running) state.port else cfg.port
    val activeLine = state.line.ifBlank { cfg.effectiveLine() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ───── статус ─────
        SoftCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Обход блокировок (DPI)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when (state.phase) {
                            DpiPhase.RUNNING -> state.message.ifBlank { "Работает" } + " · порт ${state.port}"
                            DpiPhase.STARTING, DpiPhase.ERROR -> state.message
                            else -> if (!DpiEngine.available(context)) "Движок недоступен на этом устройстве" else "Выключен"
                        }, style = MaterialTheme.typography.bodySmall, color = if (state.phase == DpiPhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                val (label, c, s) = when (state.phase) {
                    DpiPhase.RUNNING -> Triple("Работает", tones.good, tones.goodSoft)
                    DpiPhase.STARTING -> Triple("Запуск", tones.warn, tones.warnSoft)
                    DpiPhase.ERROR -> Triple("Ошибка", tones.bad, tones.badSoft)
                    else -> Triple("Выключен", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh)
                }
                Pill(label, c, s)
            }
            Text("Стратегия: " + DpiPresets.titleOf(activeLine), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            if (running && state.lastCheck.isNotBlank()) Text("Проверка наблюдателя: ${state.lastCheck}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (running && state.switches > 0) Text("Стратегия менялась автоматически: ${state.switches}", style = MaterialTheme.typography.bodySmall, color = tones.warn)
            Button(onToggle, Modifier.fillMaxWidth().padding(top = 8.dp).height(44.dp), enabled = DpiEngine.available(context) && state.phase != DpiPhase.STARTING) {
                Text(if (running) "Остановить" else "Запустить")
            }
            if (vm.note.isNotBlank()) Text(vm.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
            if (DpiRuntimeFail.last.isNotBlank() && !running) Text("Сбой VPN: " + DpiRuntimeFail.last, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))
        }

        // ───── автонастройка и проверка ─────
        SoftCard {
            Text("Автонастройка", style = MaterialTheme.typography.titleMedium)
            Text("Не знаете, что выбрать? Отметьте сайты и нажмите «Подобрать и включить»: приложение проверит, что у вас реально заблокировано, найдёт рабочую стратегию и включит обход.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DpiSites.groups.forEach { g -> MiniChip(g.title, g.id in vm.groups) { if (!vm.searching) vm.groups = if (g.id in vm.groups) vm.groups - g.id else vm.groups + g.id } }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (vm.searching) Button({ vm.stopSearch() }, Modifier.weight(1f).height(40.dp)) { Text("Остановить подбор") }
                else Button({ vm.startSearch(true) }, Modifier.weight(1f).height(40.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Подобрать и включить") }
                OutlinedButton({ vm.runHealth() }, Modifier.weight(1f).height(40.dp), enabled = running && !vm.checking, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(if (vm.checking) "Проверяю…" else "Проверить сейчас") }
            }
            if (progress.running || progress.finished) {
                Text(progress.phase.ifBlank { if (progress.finished) "Готово" else "" }, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                if (progress.running && progress.total > 0) LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total }, Modifier.fillMaxWidth().padding(vertical = 4.dp))
                if (progress.baselineBlocked >= 0) Text("Без обхода недоступно: ${progress.baselineBlocked} из ${progress.baselineTotal}", style = MaterialTheme.typography.bodySmall)
                if (progress.message.isNotBlank()) Text(progress.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (vm.health.isNotEmpty()) {
                val opened = vm.health.count { it.ok }
                Text("Через обход открылось: $opened из ${vm.health.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                vm.health.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (r.ok) "✓" else "✕", color = if (r.ok) tones.good else tones.bad, modifier = Modifier.width(18.dp))
                        Text(r.host, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(if (r.ok) "${r.ms} мс" else r.reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
            }
        }

        // ───── профили ─────
        SoftCard {
            Text("Готовые профили", style = MaterialTheme.typography.titleMedium)
            val current = DpiPresets.byLine(cfg.line)
            FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                DpiPresets.all.forEach { p -> MiniChip(p.title, !cfg.useSimple && current?.id == p.id) { vm.applyPreset(p) } }
            }
            Text(if (cfg.useSimple) "Сейчас используется конструктор (вкладка «Стратегия»)." else current?.hint ?: "Сейчас задана своя строка ключей (вкладка «Стратегия»). Профиль заменит её.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }

        // ───── способ подключения ─────
        SoftCard {
            Text("Как подключить", style = MaterialTheme.typography.titleMedium)
            val modes = listOf(
                Triple("proxy", "Прокси без VPN", "SOCKS5 127.0.0.1:${cfg.port}. Для встроенного браузера и приложений с настройкой прокси (Telegram). VPN-слот свободен."),
                Triple("vpn_only", "VPN: только выбранные", "Через обход идут только отмеченные приложения (вкладка «Приложения»)."),
                Triple("vpn_except", "VPN: все, кроме выбранных", "Всё в обходе, кроме отмеченных (банки, игры)."),
                Triple("vpn_all", "VPN: все приложения", "Весь трафик телефона через обход."),
            )
            modes.forEach { (id, title, text) ->
                Row(Modifier.fillMaxWidth().clickable { vm.update(cfg.copy(mode = id)) }.padding(vertical = 2.dp), verticalAlignment = Alignment.Top) {
                    RadioButton(cfg.mode == id, { vm.update(cfg.copy(mode = id)) }, Modifier.size(36.dp))
                    Column(Modifier.padding(top = 8.dp)) {
                        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (running && cfg.mode != state.mode) Text("Режим изменён — остановите и запустите обход заново.", style = MaterialTheme.typography.bodySmall, color = tones.warn)
        }

        if (!cfg.isVpn) SoftCard {
            Text("Использовать прокси", style = MaterialTheme.typography.titleMedium)
            Text("Адрес: 127.0.0.1, порт $shownPort. Приложение пойдёт через обход, только если умеет работать с SOCKS5.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({
                    try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("tg://socks?server=127.0.0.1&port=$shownPort")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    catch (e: Exception) { toast(context, "Telegram не установлен") }
                }, Modifier.height(36.dp)) { Text("Добавить в Telegram") }
                OutlinedButton({ copyText(context, "127.0.0.1:$shownPort") }, Modifier.height(36.dp)) { Text("Копировать адрес") }
            }
            SwitchLine("Встроенный браузер SiteScope — через обход", "Обычные сайты в браузере приложения идут через byedpi", cfg.browserViaDpi) { vm.update(cfg.copy(browserViaDpi = it)) }
        }

        SoftCard {
            Text("Дополнительно", style = MaterialTheme.typography.titleMedium)
            SwitchLine("Следить и менять стратегию сам", "Раз в ~75 с открываю заблокированные сайты через обход; если перестали — перехожу на следующую лучшую стратегию из подбора", cfg.watchdog) { vm.update(cfg.copy(watchdog = it)) }
            SwitchLine("Запускать при включении телефона", "VPN-режимы — только если разрешение VPN уже выдано", cfg.autostart) { vm.update(cfg.copy(autostart = it)) }
            SwitchLine("Обход внутри VPN SiteScope (Xray)", "Xray выходит в сеть через byedpi: сервер VPN подключается в обход блокировки. Экспериментально", cfg.chainXray) { vm.update(cfg.copy(chainXray = it)) }
            Text("Движок: byedpi (hufrea, MIT), отдельный процесс. Строка: ${activeLine.take(90)}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }

        // ───── справка ─────
        SoftCard(onClick = { help = !help }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Как это работает и как пользоваться", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(if (help) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
            }
            if (help) Text(DPI_HELP, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private const val DPI_HELP = "Что это. Многие провайдеры блокируют сайты не по адресу, а по «отпечатку» первого пакета защищённого соединения: в нём открытым текстом написано имя сайта (SNI). Это называется DPI. " +
    "byedpi — маленькая программа внутри приложения: она слегка ломает этот первый пакет (режет на куски, меняет порядок, добавляет фальшивый пакет), чтобы фильтр его не узнал, а сайт всё равно понял.\n\n" +
    "Это не VPN: трафик не шифруется дополнительно и ваш IP не меняется. Обход помогает, если блокировка идёт по имени сайта. Если сайт закрыт по IP целиком — нужен VPN-сервер (раздел VPN).\n\n" +
    "Как пользоваться:\n1. Выключите VPN. Отметьте сайты и нажмите «Подобрать и включить» — приложение само найдёт стратегию и запустит обход.\n" +
    "2. Выберите способ подключения. «Прокси» — для встроенного браузера SiteScope (включите переключатель) и приложений с настройкой SOCKS5, например Telegram. «VPN» — чтобы направить через обход выбранные или все приложения.\n" +
    "3. Нажмите «Проверить сейчас»: увидите, какие сайты открываются через обход.\n" +
    "4. Если сайты вдруг перестали открываться, наблюдатель сам переключит стратегию; можно и вручную выбрать профиль или повторить подбор.\n\n" +
    "Важно. Рабочая стратегия зависит от провайдера и сети: при смене Wi-Fi/мобильной сети подбор лучше повторить. " +
    "UDP/QUIC (часть YouTube, голос Discord) byedpi не обходит — в браузере отключите QUIC. Если строка стратегии неверна, обход запустится с базовой."

@Composable
private fun SwitchLine(title: String, text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun SearchTab(vm: DpiViewModel) {
    val context = LocalContext.current
    val progress by vm.search.progress.collectAsState()
    val results by vm.search.results.collectAsState()
    val shown = if (results.isNotEmpty()) results else vm.saved
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SoftCard {
                Text("Подбор стратегии", style = MaterialTheme.typography.titleMedium)
                Text("Сначала проверяются сайты без обхода — так видно, что реально заблокировано. Затем все стратегии прогоняются параллельно, лучшие проверяются полностью. VPN на время подбора должен быть выключен.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DpiSites.groups.forEach { g -> FilterChip(g.id in vm.groups, { vm.groups = if (g.id in vm.groups) vm.groups - g.id else vm.groups + g.id }, label = { Text(g.title) }, enabled = !vm.searching) }
                }
                OutlinedTextField(vm.custom, { vm.custom = it }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Свои сайты (через пробел)") }, enabled = !vm.searching, minLines = 1, maxLines = 3)
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(!vm.fullList, { vm.fullList = false }, label = { Text("Быстро (${DpiStrategies.quick(48).size})") }, enabled = !vm.searching)
                    Spacer(Modifier.width(8.dp))
                    FilterChip(vm.fullList, { vm.fullList = true }, label = { Text("Полный (${DpiStrategies.all.size})") }, enabled = !vm.searching)
                }
                Text("Параллельно: ${vm.parallel.toInt()}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                AppSlider(vm.parallel, { vm.parallel = it }, valueRange = 1f..8f, steps = 6, enabled = !vm.searching)
                Text("Время ожидания: ${vm.timeout.toInt()} с", style = MaterialTheme.typography.labelMedium)
                AppSlider(vm.timeout, { vm.timeout = it }, valueRange = 3f..10f, steps = 6, enabled = !vm.searching)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vm.searching) Button({ vm.stopSearch() }, Modifier.weight(1f)) { Text("Остановить") }
                    else {
                        Button({ vm.startSearch(false) }, Modifier.weight(1f)) { Text("Подобрать") }
                        OutlinedButton({ vm.startSearch(true) }, Modifier.weight(1f)) { Text("Подобрать и включить") }
                    }
                }
            }
        }
        if (progress.running || progress.finished) item {
            SoftCard {
                Text(progress.phase.ifBlank { if (progress.finished) "Готово" else "" }, style = MaterialTheme.typography.titleSmall)
                if (progress.running && progress.total > 0) LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total }, Modifier.fillMaxWidth().padding(vertical = 6.dp))
                if (progress.baselineBlocked >= 0) Text("Без обхода недоступно: ${progress.baselineBlocked} из ${progress.baselineTotal} сайтов", style = MaterialTheme.typography.bodyMedium)
                if (progress.message.isNotBlank()) Text(progress.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (progress.running && progress.total > 0) Text("${progress.done} / ${progress.total}", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (vm.note.isNotBlank()) item { Text(vm.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        if (shown.isNotEmpty()) item { SectionTitle("Результаты") }
        items(shown.take(30), key = { it.line + it.full }) { s ->
            SoftCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${s.ok} / ${s.total}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), color = if (s.rate >= 0.8 && s.controlOk) tones.good else if (s.rate > 0) tones.warn else tones.bad)
                    if (s.avgMs > 0) Text("${s.avgMs} мс", style = MaterialTheme.typography.bodySmall)
                    if (!s.full) Pill("быстро", MaterialTheme.colorScheme.onSurfaceVariant, MaterialTheme.colorScheme.surfaceContainerHigh, Modifier.padding(start = 8.dp))
                    if (!s.controlOk) Pill("ломает сайты", tones.bad, tones.badSoft, Modifier.padding(start = 8.dp))
                }
                Text(s.line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 6.dp), maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (s.failed.isNotEmpty()) Text("Не открылись: " + s.failed.take(4).joinToString(", ") + if (s.failed.size > 4) "…" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Button({ vm.applyLine(s.line) }) { Text("Применить") }
                    OutlinedButton({ copyText(context, s.line) }) { Text("Копировать") }
                }
            }
        }
    }
}

@Composable
private fun StrategyTab(vm: DpiViewModel) {
    val cfg = vm.cfg
    var raw by remember(cfg.line) { mutableStateOf(cfg.line) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SoftCard {
            Text("Стратегия", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!cfg.useSimple, { vm.update(cfg.copy(useSimple = false)) }, label = { Text("Командная строка") })
                FilterChip(cfg.useSimple, { vm.update(cfg.copy(useSimple = true)) }, label = { Text("Конструктор") })
            }
            if (!cfg.useSimple) {
                OutlinedTextField(raw, { raw = it; if (DpiArgs.looksValid(it) == null) vm.update(vm.cfg.copy(line = it)) }, Modifier.fillMaxWidth().padding(top = 8.dp),
                    label = { Text("Ключи byedpi") }, minLines = 2, maxLines = 6, textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    supportingText = { Text(DpiArgs.looksValid(raw) ?: "{sni} — поддельный SNI, например -n {sni}. Ключи -i/-p/-D/-w задаёт приложение.") }, isError = DpiArgs.looksValid(raw) != null)
                if (vm.cfg.history.isNotEmpty()) {
                    Text("Недавние", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    vm.cfg.history.take(5).forEach { h -> Text(h, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { vm.applyLine(h) }.padding(vertical = 4.dp)) }
                }
            } else SimpleBuilder(vm)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ vm.validateLine() }, Modifier.weight(1f).height(38.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Проверить строку") }
                Button({ vm.restartNow() }, Modifier.weight(1f).height(38.dp), contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Применить сейчас") }
            }
            if (vm.note.isNotBlank()) Text(vm.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
            Text("Итог: ${cfg.effectiveLine()}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
        SoftCard {
            Text("Параметры", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(cfg.port.toString(), { v -> v.toIntOrNull()?.let { if (it in 1024..65535) vm.update(cfg.copy(port = it)) } }, Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("Порт SOCKS5") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(cfg.sni, { vm.update(cfg.copy(sni = it.trim())) }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Поддельный SNI ({sni})") }, singleLine = true)
            OutlinedTextField(cfg.dns, { vm.update(cfg.copy(dns = it.trim())) }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("DNS в VPN") }, singleLine = true)
            SwitchLine("IPv6 в VPN", "Выключено: IPv6-трафик обходит VPN", cfg.ipv6) { vm.update(cfg.copy(ipv6 = it)) }
            SwitchLine("Применять только к доменам из списка", "Остальной трафик идёт без изменений", cfg.hostsOnly) { vm.update(cfg.copy(hostsOnly = it)) }
            if (cfg.hostsOnly) OutlinedTextField(cfg.hosts, { vm.update(cfg.copy(hosts = it)) }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Домены через пробел") }, minLines = 2, maxLines = 6)
        }
    }
}

@Composable
private fun SimpleBuilder(vm: DpiViewModel) {
    val s = vm.cfg.simple
    fun set(n: DpiSimple) = vm.update(vm.cfg.copy(simple = n))
    Column {
        Text("Метод", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DpiSimple.Method.values().forEach { m -> FilterChip(s.method == m, { set(s.copy(method = m)) }, label = { Text(m.title) }) }
        }
        if (s.method != DpiSimple.Method.NONE) {
            Text("Позиция разреза: ${s.position}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            AppSlider(s.position.toFloat(), { set(s.copy(position = it.toInt())) }, valueRange = 1f..30f)
            SwitchLine("Отсчёт от SNI/Host", "Позиция считается от имени сайта в запросе", s.atHost) { set(s.copy(atHost = it)) }
        }
        if (s.method == DpiSimple.Method.FAKE) {
            Text("TTL поддельного пакета: ${s.fakeTtl}", style = MaterialTheme.typography.labelMedium)
            AppSlider(s.fakeTtl.toFloat(), { set(s.copy(fakeTtl = it.toInt())) }, valueRange = 1f..20f)
            OutlinedTextField(s.fakeSni, { set(s.copy(fakeSni = it)) }, Modifier.fillMaxWidth(), label = { Text("Поддельный SNI") }, singleLine = true)
        }
        SwitchLine("Разделить TLS-запись", "Делит ClientHello на две записи", s.tlsRecord) { set(s.copy(tlsRecord = it)) }
        if (s.tlsRecord) {
            Text("Позиция TLS-разреза: ${s.tlsRecordPosition}", style = MaterialTheme.typography.labelMedium)
            AppSlider(s.tlsRecordPosition.toFloat(), { set(s.copy(tlsRecordPosition = it.toInt())) }, valueRange = 1f..30f)
        }
        SwitchLine("HTTPS (TLS)", "Применять к защищённому трафику", s.desyncHttps) { set(s.copy(desyncHttps = it)) }
        SwitchLine("HTTP", "Применять к обычному HTTP", s.desyncHttp) { set(s.copy(desyncHttp = it)) }
        SwitchLine("Host: смешанный регистр", "hOsT вместо Host", s.httpMixedCaseHost) { set(s.copy(httpMixedCaseHost = it)) }
        SwitchLine("Домен: смешанный регистр", "ExAmPlE.cOm", s.httpMixedCaseDomain) { set(s.copy(httpMixedCaseDomain = it)) }
        SwitchLine("Убрать пробел после Host:", "Host:example.com", s.httpRemoveSpace) { set(s.copy(httpRemoveSpace = it)) }
        SwitchLine("TCP Fast Open", "Первый пакет вместе с SYN", s.tfo) { set(s.copy(tfo = it)) }
        SwitchLine("Игнорировать SACK", "Заставляет ядро переотправить данные", s.dropSack) { set(s.copy(dropSack = it)) }
    }
}

@Composable
private fun AppsTab(vm: DpiViewModel) {
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { vm.loadApps() }
    val cfg = vm.cfg
    val ctx = LocalContext.current
    val list = vm.apps.filter { (showSystem || !it.system || it.pkg in cfg.apps) && (query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true)) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        item {
            SoftCard {
                Text("Приложения", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (cfg.mode) {
                        "vpn_only" -> "Режим «VPN: только выбранные» — через обход пойдут только отмеченные приложения."
                        "vpn_except" -> "Режим «VPN: все, кроме выбранных» — отмеченные приложения идут напрямую."
                        "vpn_all" -> "Режим «VPN: все приложения» — список не используется."
                        else -> "В режиме прокси список не используется: без VPN система не может направить приложение в прокси. Выберите режим VPN на вкладке «Запуск»."
                    }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                )
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Поиск") }, singleLine = true)
                SwitchLine("Показывать системные", "", showSystem) { showSystem = it }
                Text("Отмечено: ${cfg.apps.size}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
        }
        if (vm.appsLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        items(list, key = { it.pkg }) { a ->
            val icon = remember(a.pkg) { try { ctx.packageManager.getApplicationIcon(a.pkg).toBitmap(72, 72).asImageBitmap() } catch (e: Exception) { null } }
            Row(Modifier.fillMaxWidth().clickable { vm.toggleApp(a.pkg) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) Image(icon, null, Modifier.size(36.dp)) else Spacer(Modifier.size(36.dp))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(a.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(a.pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Checkbox(a.pkg in cfg.apps, { vm.toggleApp(a.pkg) })
            }
        }
    }
}

@Composable
private fun LogTab(state: DpiState) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SoftCard {
            Text("Журнал byedpi", style = MaterialTheme.typography.titleMedium)
            val text = state.log.joinToString("\n").ifBlank { "Пока пусто. Движок пишет сюда только ошибки." }
            MonoBlock(text, Modifier.padding(top = 8.dp))
            OutlinedButton({ copyText(context, text) }, Modifier.padding(top = 8.dp)) { Text("Копировать") }
            Text("Файл движка: " + (DpiEngine.binary(context)?.name ?: "не найден") + " · перезапусков: ${state.restarts}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
