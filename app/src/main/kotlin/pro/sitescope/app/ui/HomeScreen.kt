package pro.sitescope.app.ui

import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import pro.sitescope.app.vpn.VpnMode
import pro.sitescope.app.vpn.VpnViewModel

/**
 * Главная: поиск, одна большая кнопка VPN и шесть крупных сценариев, которые отвечают на вопрос
 * «что вы хотите сделать». Всё остальное — на вкладке «Инструменты».
 */
@Composable
fun HomeScreen(state: AppState) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val vm: VpnViewModel = viewModel()
    val tasks = rememberActiveTaskCount()
    var input by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.start() else toast(context, "Без разрешения VPN включить нельзя")
    }
    fun connect() {
        if (vm.serverList.isEmpty()) { toast(context, "Сначала добавьте сервер"); state.openRoute("vpn", 1); return }
        vm.mode = VpnMode.SERVER
        val prepare = try { VpnService.prepare(context) } catch (e: Exception) { null }
        if (prepare != null) permission.launch(prepare) else vm.start()
    }
    fun go() { if (!state.open(input)) toast(context, "Введите сайт, IP, подсеть или ссылку"); else input = "" }

    Column(Modifier.fillMaxSize()) {
        AppHeader("SiteScope Pro", "Что хотите сделать?", logo = true,
            actions = { HeaderMenu(listOf("Настройки и резервная копия" to { state.openRoute("settings") }, "Диагностика Wi-Fi" to { state.openRoute("wifi") })) })

        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp).padding(top = 12.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SmartSearchBar(input, { input = it }, "Сайт, IP, сеть или ссылка", onGo = { go() }, scan = true,
                onPaste = { clipboard.getText()?.text?.let { input = it.trim().take(500) } })

            VpnHero(vm, onOpen = { state.openRoute("vpn") }, onToggle = { if (vm.active || vm.busy) vm.stop() else connect() })

            val tiles = listOf(
                Scenario(Icons.Rounded.WifiTethering, "Раздать VPN", "Телефоны и ноутбук по QR-коду") { state.openRoute("vpn", 7) },
                Scenario(Icons.Rounded.VpnKey, "Найти ключи", "Бесплатные vless, trojan, vmess") { state.openRoute("keys") },
                Scenario(Icons.Rounded.Language, "Проверить сайт", "SEO, скорость, безопасность") { state.openRoute("site") },
                Scenario(Icons.Rounded.Lan, "Моя сеть", "Кто подключён и что открыто") { state.openRoute("network") },
                Scenario(Icons.Rounded.LiveTv, "Телевидение", "Каналы и плейлисты") { state.openRoute("tv") },
                Scenario(Icons.Rounded.Public, "Узнать IP", "Страна, провайдер, подсети") { state.openRoute("ip") }
            )
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                tiles.chunked(2).forEach { pair ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        pair.forEach { ScenarioTile(it, Modifier.weight(1f).fillMaxHeight()) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { state.selectTab(Tab.TOOLS) }, modifier = Modifier.weight(1f).height(48.dp)) { Icon(Icons.Rounded.Apps, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Все инструменты") }
                if (tasks > 0) Button(onClick = { state.openRoute("tasks") }, modifier = Modifier.weight(1f).height(48.dp)) { Icon(Icons.Rounded.Layers, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Идёт сейчас: $tasks") }
                else OutlinedButton(onClick = { state.openRoute("data", 1) }, modifier = Modifier.weight(1f).height(48.dp)) { Icon(Icons.Rounded.History, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("История") }
            }
        }
    }
}

private class Scenario(val icon: ImageVector, val title: String, val sub: String, val onClick: () -> Unit)

@Composable
private fun VpnHero(vm: VpnViewModel, onOpen: () -> Unit, onToggle: () -> Unit) {
    val t = tones
    val on = vm.active
    val error = vm.lifecycle == pro.sitescope.app.HostsVpnService.STATE_ERROR
    val title = when {
        vm.busy -> "Подождите…"
        on -> "VPN включён"
        error -> "Не удалось подключиться"
        else -> "VPN выключен"
    }
    val sub = when {
        on && vm.runningProfile.isNotEmpty() -> vm.runningProfile
        on -> "Трафик идёт через приложение"
        error -> "Нажмите, чтобы посмотреть причину"
        vm.serverList.isEmpty() -> "Добавьте сервер, и VPN заработает"
        else -> "Одно нажатие — и вы под защитой"
    }
    Row(
        Modifier.fillMaxWidth().height(84.dp).shadow(2.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
            .background(if (on) t.goodSoft else if (error) t.badSoft else MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(onClick = onOpen).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconBadge(if (on) Icons.Rounded.Shield else Icons.Rounded.ShieldMoon, if (on) t.good else if (error) t.bad else MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.surfaceContainerLow, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onToggle, enabled = !vm.busy, modifier = Modifier.height(46.dp),
            colors = if (on) ButtonDefaults.buttonColors(containerColor = t.bad) else ButtonDefaults.buttonColors()) { Text(if (on) "Выключить" else "Включить") }
    }
}

@Composable
private fun ScenarioTile(s: Scenario, modifier: Modifier) {
    Column(
        modifier.shadow(1.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .clickable(onClick = s.onClick).padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        ToolIcon(s.icon, size = 46.dp)
        Spacer(Modifier.height(8.dp))
        Text(s.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 1)
        Text(s.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
