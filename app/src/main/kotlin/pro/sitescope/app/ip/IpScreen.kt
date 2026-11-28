@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package pro.sitescope.app.ip

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import pro.sitescope.app.IpIntelligenceActivity
import pro.sitescope.app.ScannerActivity
import pro.sitescope.app.ui.*

private val pages = listOf("Найти", "Подсети", "Мой адрес", "База IP")

@Composable
fun IpScreen(state: AppState, vm: IpViewModel = viewModel()) {
    var routeOpen by rememberSaveable { mutableStateOf(false) }
    val openRoute = {
        if (vm.traceOrigin == null && !vm.traceRunning) vm.runTrace()
        routeOpen = true
    }
    LaunchedEffect(state.ipRequest) {
        state.ipRequest?.let { request ->
            state.ipRequest = null
            if (request == "@me") { vm.page = 2; vm.loadMine() } else { vm.page = 0; vm.search(request) }
        }
    }
    LaunchedEffect(state.subnetRequest) {
        state.subnetRequest?.let { request -> state.subnetRequest = null; vm.page = 1; if (request.isNotBlank()) vm.subnetInput = request }
    }
    Column(Modifier.fillMaxSize()) {
        AppHeader("IP и подсети", "Информация об IP, ASN, гео, CIDR: диапазоны, деление, агрегация") {
            HeaderChips(pages, vm.page, { index ->
                if (index == 3) state.openRoute("ipdb") else { vm.page = index; if (index == 2) vm.loadMine() }
            }, listOf(Icons.Rounded.Search, Icons.Rounded.Calculate, Icons.Rounded.MyLocation, Icons.Rounded.Storage))
        }
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp).padding(top = 8.dp)) {
            if (vm.page == 0) { SmartSearchBar(vm.query, { vm.query = it }, "IP, сайт, AS15169 или название провайдера", onGo = { vm.search() }, scan = true); Spacer(Modifier.height(8.dp)) }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
                when (vm.page) { 0 -> LookupPage(vm, state, openRoute); 1 -> SubnetPage(vm); else -> MinePage(vm) }
            }
        }
    }
    vm.info?.takeIf { routeOpen && vm.page == 0 && it.ip.isNotEmpty() && !it.isAsn }?.let { info ->
        IpRouteScreen(vm, info) { routeOpen = false }
    }
}

// ============ Поиск ============

@Composable
private fun LookupPage(vm: IpViewModel, state: AppState, openRoute: () -> Unit) {
    val context = LocalContext.current
    if (vm.searching) LoadingCard("Ищем сведения", "Локальная база · RIPE · RDAP")
    if (vm.searchError.isNotEmpty()) ErrorCard(vm.searchError)

    if (vm.hits.isNotEmpty()) {
        SectionTitle("Найденные провайдеры")
        SoftCard(padding = 6.dp) {
            for (hit in vm.hits.take(25)) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { vm.search(hit.asn) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Business, tones.ip, tones.ipSoft, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(hit.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOf(hit.asn, hit.country, if (hit.prefixes > 0) "сетей: ${hit.prefixes}" else "").filter { it.isNotEmpty() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
            }
        }
    }

    val info = vm.info
    if (info != null) InfoView(vm, info, state, openRoute)
    else if (!vm.searching && vm.hits.isEmpty()) {
        RecentQueries(vm)
        SectionTitle("Примеры")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (q in listOf("8.8.8.8", "1.1.1.1", "yandex.ru", "AS13335")) MiniChip(q, false) { vm.search(q) }
        }
        Spacer(Modifier.height(8.dp))
        SoftCard(onClick = { state.openRoute("ipdb") }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Storage, tones.ip, tones.ipSoft, 40.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("База адресов и каталог", style = MaterialTheme.typography.titleSmall)
                    Text("Установить офлайн-базу DB-IP, смотреть сети по странам, городам и провайдерам", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun InfoView(vm: IpViewModel, info: IpInfo, state: AppState, openRoute: () -> Unit) {
    val context = LocalContext.current
    val dash = !info.isAsn && info.ip.isNotEmpty()
    var more by remember(info.ip, info.query) { mutableStateOf(false) }
    if (dash) {
        IpDashboard(vm, info, state, openRoute)
        TextButton(onClick = { more = !more }, modifier = Modifier.fillMaxWidth()) { Text(if (more) "Скрыть подробности" else "Все сведения и реестр") }
        if (!more) return
    }
    if (!dash) SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (info.flag.isNotEmpty()) Text(info.flag, fontSize = 34.sp, modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text(if (info.isAsn) info.asn else info.ip, style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.clickable { copyText(context, if (info.isAsn) info.asn else info.ip) })
                val subtitle = when {
                    info.holder.isNotEmpty() -> info.holder
                    info.kind.isNotEmpty() -> info.kind
                    else -> ""
                }
                if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (info.query != info.ip && !info.isAsn) Text("Адрес сайта ${info.query}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (info.kind.isNotEmpty()) Pill(info.kind, tones.ip, tones.ipSoft)
            if (info.asn.isNotEmpty() && !info.isAsn) Pill(info.asn, tones.info, tones.infoSoft)
            if (info.announced) Pill("Виден в интернете", tones.good, tones.goodSoft)
        }
    }
    if (info.error.isNotEmpty()) { Spacer(Modifier.height(6.dp)); ErrorCard("Онлайн-справочники не ответили. Показано то, что известно локально.") }

    SectionTitle("Сведения")
    SoftCard {
        InfoRow("Где находится", info.place)
        InfoRow("Провайдер", info.holder)
        InfoRow("Автономная система", info.asn, mono = true)
        InfoRow("Сеть (префикс)", info.prefix, mono = true)
        InfoRow("Имя сети", info.networkName)
        InfoRow("Реестр", info.rir)
        InfoRow("Обратное имя", info.reverse, mono = true)
        InfoRow("Все адреса сайта", info.allAddresses, mono = true)
        InfoRow("Жалобы на адрес", info.abuse.joinToString("\n"))
        if (info.place.isNotEmpty()) Text("Местоположение определяется приблизительно — обычно с точностью до города или узла провайдера" +
            (if (info.geoSource.isNotEmpty()) " (источник: ${info.geoSource})." else "."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }

    if (!info.isAsn && info.ip.isNotEmpty() && !dash) {
        SectionTitle("Проверки")
        // Чёрные списки
        if (Subnet.isIpv4(info.ip) && info.kind.startsWith("Публичный")) ToolCard(Icons.Rounded.GppMaybe, "Чёрные списки", "Не считают ли адрес источником спама", vm.blacklistRunning, vm.blacklists != null, { vm.runBlacklists() }) {
            val list = vm.blacklists ?: emptyList()
            val listed = list.count { it.listed == true }
            Text(if (listed == 0) "В списках не найден" else "Найден в списках: $listed", style = MaterialTheme.typography.titleSmall, color = if (listed == 0) tones.good else tones.bad)
            for (e in list) Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(when (e.listed) { true -> tones.bad; false -> tones.good; null -> MaterialTheme.colorScheme.outline })
                Spacer(Modifier.width(10.dp))
                Text(e.title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text(when (e.listed) { true -> "в списке"; false -> "чисто"; null -> "нет ответа" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ToolCard(Icons.Rounded.NetworkPing, "Связь с адресом", "Отвечает ли узел и как быстро", vm.pingRunning, vm.ping != null, { vm.runPing() }) {
            val p = vm.ping
            if (p != null) {
                if (p.ok) {
                    Text("Отвечает за %.0f мс".format(p.avgMs), style = MaterialTheme.typography.titleSmall, color = if (p.avgMs < 120) tones.good else tones.warn)
                    Text("Мин. %.0f · макс. %.0f мс".format(p.minMs, p.maxMs) + " · потери ${p.lossPercent}% · способ ${p.method}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else Text(p.error.ifEmpty { "Узел не отвечает" }, style = MaterialTheme.typography.titleSmall, color = tones.bad)
            }
        }
        ToolCard(Icons.Rounded.Route, "Маршрут", "Через какие узлы идёт соединение, с геопривязкой", false, vm.hops.isNotEmpty() || vm.traceRunning, { vm.runTrace() }, actionTitle = if (vm.traceRunning) "Стоп" else "Запустить") {
            TraceStatus(vm)
            for (hop in vm.hops) TraceHopRow(hop, vm.hopLocations[hop.address])
        }
        var confirm by remember { mutableStateOf(false) }
        ToolCard(Icons.Rounded.Lan, "Сервисы на узле", "Сайт, почта, SSH и другие популярные порты", vm.servicesRunning, vm.services != null, { confirm = true }) {
            val s = vm.services ?: emptyList()
            if (s.isEmpty()) Text("Популярные порты закрыты или узел их скрывает", style = MaterialTheme.typography.bodyMedium)
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { for ((port, name) in s) Pill("$name · $port", tones.net, tones.netSoft) }
            TextButton(onClick = { open(context, ToolActivity::class.java) { putExtra("screen", "network"); putExtra("tab", 1); putExtra("targets", info.ip) } }, contentPadding = PaddingValues(0.dp)) { Text("Открыть полный сканер") }
        }
        if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Проверка сервисов") },
            text = { Text("Проверяйте только свои узлы или те, на проверку которых у вас есть разрешение владельца.") },
            confirmButton = { TextButton(onClick = { confirm = false; vm.runServices() }) { Text("Это мой узел") } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } })
    }

    if (info.prefixes.isNotEmpty()) {
        SectionTitle("Сети провайдера: ${info.prefixes.size}", action = "В список доступа", onAction = { vm.aclInput = info.prefixes.filter { !it.contains(':') }.joinToString("\n"); vm.page = 1 })
        SoftCard {
            Text(info.prefixes.take(40).joinToString("   "), fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp)
            if (info.prefixes.size > 40) Text("…и ещё ${info.prefixes.size - 40}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row {
                TextButton(onClick = { copyText(context, info.prefixes.joinToString("\n")) }) { Text("Копировать все") }
                TextButton(onClick = { shareText(context, info.prefixes.joinToString("\n"), info.holder) }) { Text("Поделиться") }
            }
        }
    }
    if (info.whois.isNotEmpty()) {
        SectionTitle("Запись в реестре")
        SoftCard { TechDetails(info.whois.joinToString("\n")) }
    }
}

@Composable
private fun ToolCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, text: String, running: Boolean, hasResult: Boolean,
                     onRun: () -> Unit, actionTitle: String = "Запустить", content: @Composable ColumnScope.() -> Unit) {
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tones.ip, tones.ipSoft, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (running) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
            else FilledTonalButton(onClick = onRun, contentPadding = PaddingValues(horizontal = 14.dp)) { Text(if (hasResult && actionTitle == "Запустить") "Ещё раз" else actionTitle) }
        }
        if (hasResult) { Spacer(Modifier.height(6.dp)); content() }
    }
    Spacer(Modifier.height(6.dp))
}

// ============ Мой адрес ============

@Composable
private fun MinePage(vm: IpViewModel) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { vm.loadMine(force = true) }
    val m = vm.mine
    if (vm.mineLoading && m == null) { LoadingCard("Определяем адреса"); return }
    if (m == null) return
    if (m.error.isNotEmpty()) { ErrorCard(m.error); Spacer(Modifier.height(6.dp)) }
    SoftCard {
        val direct = m.ipv4.ifBlank { m.ipv6 }
        val tunnel = m.vpn4.ifBlank { m.vpn6 }
        Text(if (m.vpn) "Основная сеть / через VPN" else "Публичный адрес основной сети",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (m.vpn) {
            if (m.ipv4.isNotEmpty() || m.vpn4.isNotEmpty() || (direct.isEmpty() && tunnel.isEmpty())) {
                PublicAddressPair("IPv4", m.ipv4, m.vpn4)
            }
            if (m.ipv6.isNotEmpty() || m.vpn6.isNotEmpty()) PublicAddressPair("IPv6", m.ipv6, m.vpn6)
            if (direct.isEmpty() && m.error.isEmpty()) Text("Прямой замер недоступен. VPN или Android могут блокировать доступ к основной сети.",
                style = MaterialTheme.typography.bodySmall)
            if (direct.isNotEmpty() && direct == tunnel) Text("Оба замера вернули один адрес.", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(direct.ifBlank { "—" }, style = MaterialTheme.typography.headlineMedium, fontFamily = FontFamily.Monospace,
                modifier = Modifier.clickable(enabled = direct.isNotBlank()) { copyText(context, direct) })
        }
        if (m.provider.isNotEmpty()) Text(m.provider, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (m.transport.isNotEmpty()) Pill(m.transport, tones.ip, tones.ipSoft)
            if (m.vpn) Pill("VPN для приложения", tones.good, tones.goodSoft, icon = Icons.Rounded.Shield)
        }
        FlowRow(Modifier.padding(top = 8.dp)) {
            if (direct.isNotEmpty()) TextButton(onClick = { vm.page = 0; vm.search(direct) }) { Text("Основной IP") }
            if (tunnel.isNotEmpty() && tunnel != direct) TextButton(onClick = { vm.page = 0; vm.search(tunnel) }) { Text("IP через VPN") }
            TextButton(onClick = { vm.loadMine(force = true) }) { Text("Обновить") }
        }
    }
    SectionTitle("Подключение")
    SoftCard {
        if (!m.vpn) {
            InfoRow("IPv4", m.ipv4, mono = true)
            InfoRow("IPv6", m.ipv6, mono = true)
        }
        InfoRow("Провайдер основной сети", m.provider)
        InfoRow("Автономная система", m.asn, mono = true)
        InfoRow("Адрес устройства", m.localAddresses, mono = true)
        InfoRow("DNS основной сети", m.dns, mono = true)
        InfoRow("DNS VPN", m.vpnDns, mono = true)
        InfoRow("Интерфейс основной сети", m.iface, mono = true)
    }
}

@Composable
private fun PublicAddressPair(family: String, direct: String, tunnel: String) {
    val context = LocalContext.current
    Text(family, style = MaterialTheme.typography.labelSmall)
    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(direct.ifBlank { "Не определён" }, Modifier.weight(1f).clickable(enabled = direct.isNotBlank()) { copyText(context, direct) },
            style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        Text(" / ", Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.titleSmall)
        Text(tunnel.ifBlank { "Не определён" }, Modifier.weight(1f).clickable(enabled = tunnel.isNotBlank()) { copyText(context, tunnel) },
            style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
    }
}

// ============ Подсети ============

private val subnetModes = listOf("Калькулятор", "Деление", "VLSM", "Объединение", "Список доступа")

@Composable
private fun SubnetPage(vm: IpViewModel) {
    var mode by remember { mutableIntStateOf(0) }
    LaunchedEffect(vm.aclInput) { if (vm.aclInput.isNotEmpty()) mode = 4 }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        subnetModes.forEachIndexed { i, title -> MiniChip(title, mode == i) { mode = i } }
    }
    Spacer(Modifier.height(6.dp))
    when (mode) { 0 -> Calculator(vm); 1 -> Splitter(vm); 2 -> Vlsm(vm); 3 -> Summarizer(vm); else -> AclBuilder(vm) }
}

@Composable
private fun Calculator(vm: IpViewModel) {
    val context = LocalContext.current
    BigField(vm.subnetInput, { vm.subnetInput = it }, "192.168.1.10/24 или 10.0.0.1 255.255.255.0", leading = Icons.Rounded.Calculate, keyboard = KeyboardType.Text, mono = true)
    val input = vm.subnetInput.trim()
    if (input.contains(':')) {
        val v6 = remember(input) { runCatching { Subnet.v6(input) } }
        val s6 = v6.getOrNull()
        Spacer(Modifier.height(8.dp))
        if (s6 != null) SoftCard {
            Text("Сеть IPv6 /${s6.prefix}", style = MaterialTheme.typography.titleMedium)
            InfoRow("Тип", s6.kind); InfoRow("Кратко", s6.compressed, mono = true); InfoRow("Полностью", s6.expanded, mono = true)
            InfoRow("Начало сети", s6.network, mono = true); InfoRow("Конец сети", s6.last, mono = true); InfoRow("Адресов", grouped(s6.count.toString()))
        } else ErrorCard(v6.exceptionOrNull()?.message ?: "Неверный адрес IPv6")
        return
    }
    val parsed = remember(input) { runCatching { Subnet.parse(input) } }
    val net = parsed.getOrNull()
    if (net == null) { if (input.isNotEmpty()) { Spacer(Modifier.height(6.dp)); ErrorCard(parsed.exceptionOrNull()?.message ?: "Не удалось разобрать адрес") }; return }

    Spacer(Modifier.height(8.dp))
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Маска /${net.prefix}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(net.mask, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AppSlider(value = net.prefix.toFloat(), onValueChange = { vm.subnetInput = Subnet.toIp(net.address) + "/" + it.toInt() }, valueRange = 0f..32f, steps = 31)
        BitBar(net.prefix)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("сеть", style = MaterialTheme.typography.labelSmall, color = tones.ip, modifier = Modifier.weight(1f))
            Text("узлы", style = MaterialTheme.typography.labelSmall, color = tones.warn)
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatTile("Адресов для устройств", grouped(net.usable.toString()), Modifier.weight(1f))
        StatTile("Всего адресов", grouped(net.total.toString()), Modifier.weight(1f))
    }
    Spacer(Modifier.height(8.dp))
    SoftCard {
        InfoRow("Сеть", net.cidr, mono = true)
        InfoRow("Маска", net.mask, mono = true)
        InfoRow("Обратная маска", net.wildcard, mono = true)
        InfoRow("Первый узел", Subnet.toIp(net.firstHost), mono = true)
        InfoRow("Последний узел", Subnet.toIp(net.lastHost), mono = true)
        InfoRow("Широковещательный", if (net.prefix <= 30) Subnet.toIp(net.broadcast) else "нет", mono = true)
        InfoRow("Тип адреса", Subnet.kind(net.address))
        InfoRow("Класс", Subnet.classOf(net.address))
        TechDetails("Адрес   ${Subnet.binary(net.address)}\nМаска   ${Subnet.binary(net.maskLong)}\nСеть    ${Subnet.binary(net.network)}\nHex     ${Subnet.hex(net.address)} / ${Subnet.hex(net.maskLong)}\nЧисло   ${net.address}")
        Row {
            TextButton(onClick = { copyText(context, "Сеть: ${net.cidr}\nМаска: ${net.mask}\nОбратная маска: ${net.wildcard}\nУзлы: ${Subnet.toIp(net.firstHost)} – ${Subnet.toIp(net.lastHost)}\nBroadcast: ${Subnet.toIp(net.broadcast)}\nУзлов: ${net.usable}") }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Копировать всё") }
        }
    }
    SectionTitle("Входит ли адрес в эту сеть?")
    var probe by remember { mutableStateOf("") }
    BigField(probe, { probe = it }, "Например ${Subnet.toIp(net.firstHost)}", leading = Icons.Rounded.Rule, keyboard = KeyboardType.Text, mono = true)
    if (probe.isNotBlank()) {
        val ip = runCatching { Subnet.ipToLong(probe.trim()) }.getOrNull()
        Spacer(Modifier.height(8.dp))
        when {
            ip == null -> ErrorCard("Это не IPv4-адрес")
            net.contains(ip) -> SoftCard(color = tones.goodSoft) { Text("Да, ${probe.trim()} входит в ${net.cidr}", color = tones.good, fontWeight = FontWeight.SemiBold) }
            else -> SoftCard(color = tones.badSoft) { Text("Нет, ${probe.trim()} не входит в ${net.cidr}", color = tones.bad, fontWeight = FontWeight.SemiBold) }
        }
    }
}

private fun grouped(digits: String): String = digits.reversed().chunked(3).joinToString(" ").reversed()

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    SoftCard(modifier = modifier, padding = 14.dp) {
        Text(value, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 32 бита адреса: сколько отдано под сеть, сколько под узлы. */
@Composable
private fun BitBar(prefix: Int) {
    val netColor = tones.ip; val hostColor = tones.warn
    Canvas(Modifier.fillMaxWidth().height(22.dp)) {
        val gap = 2.dp.toPx(); val octetGap = 6.dp.toPx()
        val cell = (size.width - gap * 28 - octetGap * 3) / 32f
        var x = 0f
        for (i in 0 until 32) {
            drawRoundRect(if (i < prefix) netColor else hostColor.copy(alpha = 0.55f), Offset(x, 0f), Size(cell, size.height), CornerRadius(3.dp.toPx()))
            x += cell + if (i % 8 == 7) octetGap else gap
        }
    }
}

@Composable
private fun Splitter(vm: IpViewModel) {
    val context = LocalContext.current
    BigField(vm.subnetInput, { vm.subnetInput = it }, "Сеть, например 10.0.0.0/16", leading = Icons.Rounded.CallSplit, keyboard = KeyboardType.Text, mono = true)
    val net = remember(vm.subnetInput) { runCatching { Subnet.parse(vm.subnetInput.trim()).normalized() }.getOrNull() }
    if (net == null) { Spacer(Modifier.height(6.dp)); ErrorCard("Введите сеть IPv4 с маской"); return }
    var newPrefix by remember(net) { mutableIntStateOf(minOf(32, net.prefix + 1)) }
    if (net.prefix == 32) { Spacer(Modifier.height(6.dp)); ErrorCard("Один адрес разделить нельзя"); return }
    Spacer(Modifier.height(8.dp))
    val count = Subnet.splitCount(net, newPrefix)
    val each = Subnet.V4(0, newPrefix)
    SoftCard {
        Text("Частей: ${grouped(count.toString())} по /$newPrefix", style = MaterialTheme.typography.titleMedium)
        Text("В каждой ${grouped(each.usable.toString())} адресов для устройств · маска ${each.mask}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (net.prefix < 31) AppSlider(value = newPrefix.toFloat(), onValueChange = { newPrefix = it.toInt() }, valueRange = (net.prefix + 1).toFloat()..32f, steps = maxOf(0, 32 - net.prefix - 2))
        Text("Или подберите по числу частей:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (parts in listOf(2, 4, 8, 16, 32, 64)) if (net.prefix + Integer.numberOfTrailingZeros(parts) <= 32)
                AssistChip(onClick = { newPrefix = Subnet.prefixForParts(net, parts) }, label = { Text("$parts") })
        }
    }
    val list = remember(net, newPrefix) { Subnet.split(net, newPrefix, 256) }
    SectionTitle(if (count > list.size) "Первые ${list.size} из ${grouped(count.toString())}" else "Подсети", action = "Копировать", onAction = { copyText(context, list.joinToString("\n") { it.cidr }) })
    SoftCard {
        for (s in list.take(256)) Row(Modifier.fillMaxWidth().clickable { copyText(context, s.cidr) }.padding(vertical = 5.dp)) {
            Text(s.cidr, fontFamily = FontFamily.Monospace, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(Subnet.toIp(s.firstHost).substringAfterLast('.').let { "…$it" } + " – " + Subnet.toIp(s.lastHost), fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Vlsm(vm: IpViewModel) {
    val context = LocalContext.current
    var needs by remember { mutableStateOf("Офис: 100\nСклад: 50\nСерверы: 12\nСвязь: 2") }
    BigField(vm.subnetInput, { vm.subnetInput = it }, "Общая сеть, например 192.168.0.0/24", leading = Icons.Rounded.AccountTree, keyboard = KeyboardType.Text, mono = true)
    Spacer(Modifier.height(6.dp))
    Text("Сколько устройств в каждой части (по строке на часть)", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    BigField(needs, { needs = it }, "Название: число устройств", singleLine = false, minLines = 4, keyboard = KeyboardType.Text)
    val net = runCatching { Subnet.parse(vm.subnetInput.trim()).normalized() }.getOrNull()
    if (net == null) { Spacer(Modifier.height(6.dp)); ErrorCard("Введите общую сеть IPv4 с маской"); return }
    val parsed = needs.lines().mapNotNull { line ->
        val count = Regex("(\\d+)\\s*$").find(line.trim())?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
        val name = line.substringBeforeLast(count.toString()).trim().trimEnd(':', '-', '—', '=').trim().ifEmpty { "Часть" }
        name to count
    }
    if (parsed.isEmpty()) return
    val result = remember(net, needs) { runCatching { Subnet.vlsm(net, parsed) } }
    val allocations = result.getOrNull()
    if (allocations == null) { Spacer(Modifier.height(6.dp)); ErrorCard(result.exceptionOrNull()?.message ?: "Не удалось распределить"); return }
    val used = allocations.sumOf { it.net?.total ?: 0L }
    SectionTitle("Распределение", action = "Копировать", onAction = {
        copyText(context, allocations.joinToString("\n") { a -> a.name + ": " + (a.net?.let { "${it.cidr} (${Subnet.toIp(it.firstHost)} – ${Subnet.toIp(it.lastHost)}, маска ${it.mask})" } ?: "не поместилась") })
    })
    SoftCard {
        for (a in allocations) {
            val n = a.net
            Row(Modifier.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Dot(if (n != null) tones.good else tones.bad)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${a.name} · ${a.needed} устр.", style = MaterialTheme.typography.titleSmall)
                    if (n != null) Text("${Subnet.toIp(n.firstHost)} – ${Subnet.toIp(n.lastHost)} · мест ${n.usable} · маска ${n.mask}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else Text("Не поместилась в общую сеть", style = MaterialTheme.typography.bodySmall, color = tones.bad)
                }
                if (n != null) Text(n.cidr, fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            }
        }
        LinearProgressIndicator(progress = { (used.toFloat() / net.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(RoundedCornerShape(3.dp)))
        Text("Занято ${used * 100 / net.total}% сети ${net.cidr}, свободно адресов: ${grouped((net.total - used).toString())}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Summarizer(vm: IpViewModel) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("10.0.0.0/24\n10.0.1.0/24\n10.0.2.0/23") }
    Text("Список сетей или диапазонов — соберём в самый короткий список", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    BigField(text, { text = it }, "10.0.0.0/24\n10.0.1.0-10.0.1.255", singleLine = false, minLines = 5, keyboard = KeyboardType.Text, mono = true)
    val parsed = remember(text) { runCatching { Subnet.parseList(text) } }
    val nets = parsed.getOrNull()
    if (nets == null) { Spacer(Modifier.height(6.dp)); ErrorCard(parsed.exceptionOrNull()?.message ?: "Проверьте список"); return }
    if (nets.isEmpty()) return
    val summary = remember(nets) { Subnet.summarize(nets) }
    val supernet = remember(nets) { Subnet.supernet(nets) }
    SectionTitle("Было ${nets.size} → стало ${summary.size}", action = "Копировать", onAction = { copyText(context, summary.joinToString("\n") { it.cidr }) })
    MonoBlock(summary.take(300).joinToString("\n") { it.cidr })
    Spacer(Modifier.height(8.dp))
    SoftCard {
        InfoRow("Общая надсеть", supernet.cidr, mono = true)
        InfoRow("Адресов в списке", grouped(summary.sumOf { it.total }.toString()))
        val extra = supernet.total - summary.sumOf { it.total }
        Text(if (extra == 0L) "Надсеть точно совпадает со списком." else "Надсеть захватывает ${grouped(extra.toString())} лишних адресов.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = { vm.aclInput = summary.joinToString("\n") { it.cidr } }, contentPadding = PaddingValues(horizontal = 4.dp)) { Text("Сделать список доступа") }
    }
}

@Composable
private fun AclBuilder(vm: IpViewModel) {
    val context = LocalContext.current
    var format by remember { mutableStateOf(Subnet.AclFormat.MIKROTIK) }
    var allow by remember { mutableStateOf(true) }
    var name by remember { mutableStateOf("SITESCOPE") }
    Text("Сети (по одной в строке) превратим в готовые правила", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    BigField(vm.aclInput, { vm.aclInput = it }, "192.0.2.0/24\n198.51.100.0/24", singleLine = false, minLines = 4, keyboard = KeyboardType.Text, mono = true)
    Spacer(Modifier.height(8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in Subnet.AclFormat.entries) FilterChip(selected = format == f, onClick = { format = f }, label = { Text(f.title) })
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = allow, onClick = { allow = true }, label = { Text("Разрешить") })
        FilterChip(selected = !allow, onClick = { allow = false }, label = { Text("Запретить") })
        OutlinedTextField(name, { name = it.take(24) }, label = { Text("Имя списка") }, singleLine = true, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp))
    }
    if (vm.aclInput.isBlank()) return
    val parsed = remember(vm.aclInput) { runCatching { Subnet.summarize(Subnet.parseList(vm.aclInput)) } }
    val nets = parsed.getOrNull()
    if (nets == null) { Spacer(Modifier.height(6.dp)); ErrorCard(parsed.exceptionOrNull()?.message ?: "Проверьте список (только IPv4)"); return }
    val output = remember(nets, format, allow, name) { Subnet.acl(format, nets.take(2000), name, allow) }
    SectionTitle("Готово: ${nets.size} правил")
    MonoBlock(if (output.length > 6000) output.take(6000) + "\n…" else output)
    Row {
        TextButton(onClick = { copyText(context, output) }) { Text("Копировать") }
        TextButton(onClick = { shareText(context, output, "Список доступа $name") }) { Text("Поделиться") }
    }
}


private fun kindOf(q: String): Triple<String, String, androidx.compose.ui.graphics.vector.ImageVector> = when {
    Regex("^(?i)as\\s?\\d+$").matches(q) -> Triple("ASN", "Автономная система", Icons.Rounded.AccountTree)
    q.contains('/') -> Triple("CIDR", "Подсеть (CIDR)", Icons.Rounded.Layers)
    Subnet.isIpv4(q) || Subnet.isIpv6(q) -> Triple("IP", "IP-адрес", Icons.Rounded.Language)
    q.contains('.') -> Triple("DNS", "Домен", Icons.Rounded.Language)
    else -> Triple("ASN", "Провайдер", Icons.Rounded.Business)
}

@Composable
private fun RecentQueries(vm: IpViewModel) {
    if (vm.recent.isEmpty()) return
    SectionTitle("Недавние запросы")
    SoftCard(padding = 4.dp) {
        vm.recent.take(5).forEachIndexed { i, q ->
            val (tag, sub, icon) = kindOf(q)
            Row(Modifier.fillMaxWidth().clickable { vm.search(q) }.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(q, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Pill(tag, tones.info, tones.infoSoft)
                val t = vm.recentTimes[q] ?: 0L
                if (t > 0) Text(whenText(t), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            if (i < minOf(vm.recent.size, 5) - 1) HorizontalDivider(Modifier.padding(horizontal = 8.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}

private fun rdapUrl(info: IpInfo): Pair<String, String> {
    val host = when {
        info.rir.contains("ARIN", true) -> "rdap.arin.net/registry"
        info.rir.contains("APNIC", true) -> "rdap.apnic.net"
        info.rir.contains("LACNIC", true) -> "rdap.lacnic.net/rdap"
        info.rir.contains("AFRINIC", true) -> "rdap.afrinic.net/rdap"
        else -> "rdap.db.ripe.net"
    }
    return host.substringBefore('/').let { if (it == "rdap.db.ripe.net") "rdap.ripe.net" else it } to "https://$host/ip/${info.ip}"
}

@Composable
private fun IpDashboard(vm: IpViewModel, info: IpInfo, state: AppState, openRoute: () -> Unit) {
    val context = LocalContext.current
    val publicIp = info.kind.startsWith("Публичный") || info.kind.startsWith("Глобальный")
    val bl = vm.blacklists
    val listed = bl?.count { it.listed == true } ?: 0
    var showBl by remember(info.ip) { mutableStateOf(false) }
    fun open(url: String) = try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { toast(context, "Не удалось открыть") }
    // --- Сводка
    SoftCard(padding = 10.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Language, MaterialTheme.colorScheme.onPrimary, MaterialTheme.colorScheme.primary, 52.dp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("IP: " + info.ip, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { copyText(context, info.ip) })
                Text(if (info.query != info.ip) "Адрес сайта " + info.query else "Сведения об адресе и сети", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (info.kind.isNotEmpty()) Pill(info.kind.removeSuffix(" адрес").let { if (publicIp) "Публичный IP" else it }, if (publicIp) tones.good else tones.info, if (publicIp) tones.goodSoft else tones.infoSoft, icon = if (publicIp) Icons.Rounded.Check else null)
                when {
                    bl == null && vm.blacklistRunning -> Pill("Проверяем списки…", tones.info, tones.infoSoft)
                    bl != null && listed == 0 -> Pill("Не в чёрных списках", tones.good, tones.goodSoft, Modifier.clickable { showBl = !showBl }, icon = Icons.Rounded.VerifiedUser)
                    bl != null -> Pill("В списках: $listed", tones.bad, tones.badSoft, Modifier.clickable { showBl = !showBl }, icon = Icons.Rounded.GppMaybe)
                    publicIp && Subnet.isIpv4(info.ip) -> Pill("Проверить списки", tones.info, tones.infoSoft, Modifier.clickable { vm.runBlacklists() })
                }
            }
        }
        if (showBl && bl != null) for (e in bl) Row(Modifier.padding(top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Dot(when (e.listed) { true -> tones.bad; false -> tones.good; null -> MaterialTheme.colorScheme.outline })
            Spacer(Modifier.width(8.dp)); Text(e.title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            Text(when (e.listed) { true -> "в списке"; false -> "чисто"; null -> "нет ответа" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        HorizontalDivider(Modifier.padding(vertical = 6.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
        val (rdapName, rdapLink) = rdapUrl(info)
        Row {
            IpCell(Icons.Rounded.Place, "Расположение", (info.flag + " " + info.place.ifEmpty { "—" }).trim() + if (info.countryCode.isNotEmpty() && info.place.isNotEmpty()) " (${info.countryCode})" else "", Modifier.weight(1f))
            IpCell(Icons.Rounded.Layers, "Провайдер", info.holder.ifEmpty { "—" }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row {
            IpCell(Icons.Rounded.AccountTree, "ASN", info.asn.ifEmpty { "—" }, Modifier.weight(1f), link = if (info.asn.isNotEmpty()) { { open("https://bgp.he.net/" + info.asn) } } else null)
            IpCell(Icons.Rounded.Layers, "Префикс (CIDR)", info.prefix.ifEmpty { "—" }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row {
            IpCell(Icons.Rounded.Description, "RDAP", if (publicIp) rdapName else "—", Modifier.weight(1f), link = if (publicIp) { { open(rdapLink) } } else null)
            val p = vm.ping
            IpCell(Icons.Rounded.MonitorHeart, "Ping",
                when { p != null && p.ok -> "%.0f мс".format(p.avgMs) + " (мин. %.0f, макс. %.0f)".format(p.minMs, p.maxMs); p != null -> "нет ответа"; vm.pingRunning -> "проверяем…"; else -> "—" }, Modifier.weight(1f),
                link = if (!vm.pingRunning) { { vm.runPing() } } else null)
        }
    }
    // --- Подсети
    val cidr = remember(info.prefix, info.ip) {
        try { if (Subnet.isIpv4(info.ip)) Subnet.parse(info.prefix.ifEmpty { info.ip + "/24" }).normalized() else null } catch (e: Exception) { null }
    }
    if (cidr != null) {
        Spacer(Modifier.height(8.dp))
        SoftCard(padding = 10.dp, onClick = { vm.subnetInput = cidr.network.let { Subnet.toIp(it) } + "/" + cidr.prefix; vm.page = 1 }) {
            DashHead(Icons.Rounded.Layers, "Подсети", "Расчёт и анализ CIDR, диапазонов, агрегации", null)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(8.dp)) {
                Column(Modifier.weight(1.3f)) { Pill("CIDR расчёт", tones.info, tones.infoSoft); Text(Subnet.toIp(cidr.network) + "/" + cidr.prefix, style = MaterialTheme.typography.titleSmall, maxLines = 1) }
                Column(Modifier.weight(0.8f)) { Text("Адресов", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text((1L shl (32 - cidr.prefix)).toString(), style = MaterialTheme.typography.titleSmall) }
                Column(Modifier.weight(1.3f)) { Text("Диапазон", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(Subnet.toIp(cidr.network) + " –\n" + Subnet.toIp(cidr.network + (1L shl (32 - cidr.prefix)) - 1), style = MaterialTheme.typography.labelMedium) }
                Column(Modifier.weight(1.1f)) { Text("Маска", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(Subnet.prefixToMask(cidr.prefix), style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
    // --- Маршрут
    Spacer(Modifier.height(8.dp))
    val real = vm.hops.filter { it.address.isNotEmpty() }
    SoftCard(padding = 10.dp, onClick = openRoute) {
        DashHead(Icons.Rounded.AccountTree, "Маршрут", "Трассировка до хоста (traceroute)", when { vm.traceRunning -> "идёт…"; vm.hops.isNotEmpty() -> "Ответили ${real.size} из ${vm.hops.size}"; else -> "Карта" })
        if (real.isEmpty()) Text(if (vm.traceRunning) "Определяем путь до узла…" else "Откройте карту для просмотра переходов.", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            val picks = if (real.size <= 4) real else listOf(real.first(), real[real.size / 3], real[real.size * 2 / 3], real.last()).distinct()
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                picks.forEachIndexed { i, h ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Box(Modifier.weight(1f).height(2.dp).background(if (i == 0) Color.Transparent else tones.good.copy(alpha = 0.5f)))
                            Box(Modifier.size(26.dp).clip(CircleShape).background(if (h.reached) tones.good else MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) { Text("${h.ttl}", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold) }
                            Box(Modifier.weight(1f).height(2.dp).background(if (i == picks.size - 1) Color.Transparent else tones.good.copy(alpha = 0.5f)))
                        }
                        Text(h.address, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (h.ms > 0) Text("%.1f мс".format(h.ms), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        TraceStatus(vm)
    }
    // --- Сервисы
    Spacer(Modifier.height(8.dp))
    var confirm by remember { mutableStateOf(false) }
    val svc = vm.services
    SoftCard(padding = 10.dp, onClick = if (svc == null && !vm.servicesRunning) { { confirm = true } } else null) {
        DashHead(Icons.Rounded.Dns, "Сервисы", "Открытые порты и доступные сервисы", when { vm.servicesRunning -> "проверяем…"; svc != null -> "${svc.size} " + openWord(svc.size); else -> "Проверить" })
        if (svc != null) {
            if (svc.isEmpty()) Text("Популярные порты закрыты или узел их скрывает", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall)
            else FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((port, name) in svc) Row(Modifier.clip(RoundedCornerShape(10.dp)).background(tones.goodSoft).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Dot(tones.good, 7.dp); Spacer(Modifier.width(5.dp))
                    Column(horizontalAlignment = Alignment.CenterHorizontally) { Text("$port", style = MaterialTheme.typography.titleSmall); Text(name, style = MaterialTheme.typography.labelSmall) }
                }
            }
        } else if (vm.servicesRunning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        else Text("Проверка популярных портов (SSH, HTTP, HTTPS, почта, базы данных).", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Проверка сервисов") },
        text = { Text("Проверяйте только свои узлы или те, на проверку которых у вас есть разрешение владельца.") },
        confirmButton = { TextButton(onClick = { confirm = false; vm.runServices() }) { Text("Это мой узел") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Отмена") } })
    // --- Действия
    Spacer(Modifier.height(8.dp))
    SoftCard(padding = 10.dp) {
        DashHead(Icons.Rounded.Bolt, "Действия", "Быстрые действия с результатом", null)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = openRoute, Modifier.weight(1f).height(40.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Icon(Icons.Rounded.Place, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Карта", maxLines = 1, style = MaterialTheme.typography.labelLarge) }
            OutlinedButton(onClick = { copyText(context, summaryText(info, vm), "Результат скопирован") }, Modifier.weight(1f).height(40.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Icon(Icons.Rounded.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Копировать", maxLines = 1, style = MaterialTheme.typography.labelLarge) }
            OutlinedButton(onClick = { shareText(context, summaryText(info, vm), info.ip) }, Modifier.weight(1f).height(40.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Поделиться", maxLines = 1, style = MaterialTheme.typography.labelLarge) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                try { pro.sitescope.app.DataStore(context.applicationContext).upsertProject("IP", info.ip, info.ip, -1, listOf(info.holder, info.place).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "IP-адрес" }); toast(context, "Добавлено в «Данные → Проекты»") }
                catch (e: Exception) { toast(context, "Не удалось сохранить") }
            }, Modifier.weight(1f).height(40.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Icon(Icons.Rounded.BookmarkAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("В проекты", maxLines = 1, style = MaterialTheme.typography.labelLarge) }
            OutlinedButton(onClick = { open(context, ToolActivity::class.java) { putExtra("screen", "network"); putExtra("tab", 1); putExtra("targets", info.ip) } }, Modifier.weight(1f).height(40.dp), contentPadding = PaddingValues(horizontal = 6.dp)) { Icon(Icons.Rounded.NetworkCheck, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Сканер портов", maxLines = 1, style = MaterialTheme.typography.labelLarge) }
        }
    }
    RecentQueries(vm)
}

private fun openWord(n: Int) = if (n % 100 in 11..14) "открытых" else if (n % 10 == 1) "открыт" else "открытых"

private fun summaryText(info: IpInfo, vm: IpViewModel): String = buildString {
    appendLine("IP: " + info.ip)
    if (info.place.isNotEmpty()) appendLine("Расположение: " + info.place)
    if (info.holder.isNotEmpty()) appendLine("Провайдер: " + info.holder)
    if (info.asn.isNotEmpty()) appendLine("ASN: " + info.asn)
    if (info.prefix.isNotEmpty()) appendLine("Префикс: " + info.prefix)
    vm.ping?.takeIf { it.ok }?.let { appendLine("Ping: %.0f мс".format(it.avgMs)) }
    vm.blacklists?.let { l -> appendLine("Чёрные списки: " + (l.count { it.listed == true }.let { if (it == 0) "не найден" else "найден в $it" })) }
    vm.services?.let { s -> if (s.isNotEmpty()) appendLine("Открытые порты: " + s.joinToString(", ") { "${it.first} ${it.second}" }) }
}

@Composable
private fun DashHead(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, badge: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, MaterialTheme.colorScheme.onPrimary, MaterialTheme.colorScheme.primary, 40.dp)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (badge != null) Pill(badge, tones.info, tones.infoSoft)
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun IpCell(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, modifier: Modifier, link: (() -> Unit)? = null) {
    Row(modifier.padding(end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (link != null) Icon(Icons.Rounded.OpenInNew, "Открыть", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp).clickable(onClick = link))
    }
}
