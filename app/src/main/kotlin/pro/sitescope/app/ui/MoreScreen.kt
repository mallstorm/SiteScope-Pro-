package pro.sitescope.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import pro.sitescope.app.*

private class Entry(val icon: ImageVector, val title: String, val text: String, val target: Class<*>, val screen: String? = null, val tab: Int = 0)

@Composable
fun MoreScreen() {
    val context = LocalContext.current
    val t = tones
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenHeader("Ещё", "Сеть, защита и ваши данные")
        Group("Защита и доступ", t.info, t.infoSoft, listOf(
            Entry(Icons.Rounded.Shield, "VPN и свои адреса", "Сервер, подмена адресов и DNS на лету, запись трафика", ToolActivity::class.java, "vpn", 0)
        ), context)
        Group("Моя сеть", t.net, t.netSoft, listOf(
            Entry(Icons.Rounded.Devices, "Устройства рядом", "Кто в вашей сети, что появилось и что пропало", ToolActivity::class.java, "network", 0),
            Entry(Icons.Rounded.NetworkCheck, "Проверка сети", "Какие сервисы открыты на ваших адресах", ToolActivity::class.java, "network", 1),
            Entry(Icons.Rounded.Terminal, "Подключения", "SSH, файлы, удалённый экран, камеры", ToolActivity::class.java, "network", 2),
            Entry(Icons.Rounded.Wifi, "Диагностика Wi-Fi", "Сети рядом, занятость каналов, своя сеть, сигнал по комнатам", ToolActivity::class.java, "wifi", 0)
        ), context)
        Group("Дополнительно", t.ip, t.ipSoft, listOf(
            Entry(Icons.Rounded.Storage, "База адресов", "Определение адресов без интернета, сети стран и провайдеров", ToolActivity::class.java, "ipdb", 0),
            Entry(Icons.Rounded.SettingsInputAntenna, "ТВ в локальной сети", "Шлюз на роутере и каналы от провайдера", ToolActivity::class.java, "tvlan", 0),
            Entry(Icons.Rounded.QueryStats, "Метрики поиска", "Скорость по Google, показы и клики, ИКС Яндекса", ToolActivity::class.java, "metrics", 0),
            Entry(Icons.Rounded.PlaylistAddCheck, "Проверка списка сайтов", "Доступность, код ответа и сертификат сразу у нескольких доменов", ToolActivity::class.java, "batch", 0)
        ), context)
        Group("Мои данные", t.site, t.siteSoft, listOf(
            Entry(Icons.Rounded.Folder, "Проекты", "Сайты, сети и плейлисты, к которым вы возвращаетесь", ToolActivity::class.java, "data", 0),
            Entry(Icons.Rounded.History, "История", "Все прошлые проверки", ToolActivity::class.java, "data", 1),
            Entry(Icons.Rounded.Settings, "Настройки и резервная копия", "Оформление, проверка сети, перенос данных", ToolActivity::class.java, "settings", 0)
        ), context)
        val version = try { context.packageManager.getPackageInfo(context.packageName, 0).versionName } catch (e: Exception) { "" }
        Text("SiteScope Pro $version", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
private fun Group(title: String, color: Color, soft: Color, entries: List<Entry>, context: android.content.Context) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        SectionTitle(title)
        SoftCard(padding = 6.dp) {
            entries.forEachIndexed { index, e ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { open(context, e.target) { if (e.screen != null) { putExtra("screen", e.screen); putExtra("tab", e.tab) } } }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(e.icon, color, soft, 42.dp)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = MaterialTheme.typography.titleSmall)
                        Text(e.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
                }
                if (index < entries.size - 1) HorizontalDivider(Modifier.padding(start = 66.dp, end = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}
