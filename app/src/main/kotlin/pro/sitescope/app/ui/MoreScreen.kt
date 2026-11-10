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

private class Entry(val icon: ImageVector, val title: String, val text: String, val target: Class<*>)

@Composable
fun MoreScreen() {
    val context = LocalContext.current
    val t = tones
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        ScreenHeader("Ещё", "Сеть, защита и ваши данные")
        Group("Защита и доступ", t.info, t.infoSoft, listOf(
            Entry(Icons.Rounded.Shield, "VPN и Hosts", "Туннель Xray, свои правила для адресов сайтов", HostsActivity::class.java)
        ), context)
        Group("Моя сеть", t.net, t.netSoft, listOf(
            Entry(Icons.Rounded.NetworkCheck, "Сканер сети", "Какие сервисы открыты на ваших узлах", ScannerActivity::class.java),
            Entry(Icons.Rounded.Devices, "Устройства рядом", "Роутеры, камеры, принтеры и ТВ в вашей сети", DiscoveryActivity::class.java),
            Entry(Icons.Rounded.Inventory2, "Учёт устройств", "Снимки сети: что появилось и что пропало", InventoryActivity::class.java),
            Entry(Icons.Rounded.Terminal, "Подключения", "SSH, файлы, VNC, камеры и другие клиенты", ConnectionsActivity::class.java)
        ), context)
        Group("Инструменты для специалиста", t.ip, t.ipSoft, listOf(
            Entry(Icons.Rounded.Storage, "База адресов и каталог", "DB-IP, страны, провайдеры, диапазоны", IpIntelligenceActivity::class.java),
            Entry(Icons.Rounded.SettingsInputAntenna, "IPTV в локальной сети", "Поиск udpxy, анонсы SAP, multicast", IptvActivity::class.java),
            Entry(Icons.Rounded.QueryStats, "Метрики поиска", "PageSpeed, Search Console, Яндекс Вебмастер", SearchMetricsActivity::class.java)
        ), context)
        Group("Мои данные", t.site, t.siteSoft, listOf(
            Entry(Icons.Rounded.Folder, "Проекты", "Сайты, сети и каталоги, к которым вы возвращаетесь", ProjectsActivity::class.java),
            Entry(Icons.Rounded.History, "История", "Все прошлые проверки", HistoryActivity::class.java),
            Entry(Icons.Rounded.Settings, "Настройки и резервная копия", "Параметры сканера, экспорт и восстановление", SettingsActivity::class.java)
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
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { open(context, e.target) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
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
