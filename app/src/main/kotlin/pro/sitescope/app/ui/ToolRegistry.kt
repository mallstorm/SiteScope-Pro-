package pro.sitescope.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.ManageSearch
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlaylistAddCheck
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material.icons.rounded.SettingsInputAntenna
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.ui.graphics.vector.ImageVector

enum class ToolCategory(val title: String) { ALL("Все"), ANALYSIS("Анализ"), NET("Сеть"), SECURITY("Безопасность"), TV("ТВ") }

/**
 * Инструмент каталога. [route] — ключ экрана оболочки, [tab] — начальная вкладка экрана,
 * [prepare] подготавливает запрос (например, «Мой адрес» или пустой калькулятор подсетей) перед открытием.
 */
class Tool(
    val id: String, val title: String, val subtitle: String, val icon: ImageVector, val route: String,
    val tab: Int = 0, val isNew: Boolean = false, val prepare: ((AppState) -> Unit)? = null
)

class ToolGroup(val id: String, val title: String, val subtitle: String, val icon: ImageVector, val categories: Set<ToolCategory>, val tools: List<Tool>)

/** Единый источник каталога «Инструменты» и его поиска. */
object ToolRegistry {
    val groups: List<ToolGroup> = listOf(
        ToolGroup("site", "Сайты и SEO", "Аудит, индексация, скорость, видимость", Icons.Rounded.Language, setOf(ToolCategory.ANALYSIS), listOf(
            Tool("audit", "Быстрый аудит", "Оценка, SEO, защита, домен", Icons.Rounded.Search, "site"),
            Tool("crawl", "Полный обход сайта", "Все страницы, битые ссылки", Icons.Rounded.Layers, "site"),
            Tool("metrics", "Метрики поиска", "Скорость, Search Console, ИКС", Icons.Rounded.QueryStats, "metrics"),
            Tool("batch", "Проверка списка сайтов", "Код ответа и сертификат", Icons.Rounded.Link, "batch", isNew = true)
        )),
        ToolGroup("ip", "IP и подсети", "Информация об IP, ASN, подсетях, гео и CIDR", Icons.Rounded.Public, setOf(ToolCategory.ANALYSIS, ToolCategory.NET), listOf(
            Tool("ipsearch", "Поиск IP / ASN", "Провайдер, сеть, RDAP", Icons.Rounded.TravelExplore, "ip"),
            Tool("subnets", "Калькулятор подсетей", "Маски, деление, VLSM", Icons.Rounded.Calculate, "ip") { it.subnetRequest = "" },
            Tool("myip", "Мой адрес", "Публичный IP и провайдер", Icons.Rounded.MyLocation, "ip") { it.ipRequest = "@me" },
            Tool("ipdb", "База адресов DB-IP", "Определение без интернета", Icons.Rounded.Storage, "ipdb", isNew = true)
        )),
        ToolGroup("net", "Моя сеть", "Устройства, порты, подключения и диагностика", Icons.Rounded.Lan, setOf(ToolCategory.NET), listOf(
            Tool("devices", "Устройства", "Кто в вашей сети", Icons.Rounded.Devices, "network", 0),
            Tool("scanner", "Сканер портов", "Открытые сервисы", Icons.Rounded.NetworkCheck, "network", 1),
            Tool("connections", "Подключения", "SSH, файлы, экран, камеры", Icons.Rounded.Terminal, "network", 2),
            Tool("wifi", "Wi-Fi диагностика", "Каналы, сигнал, комнаты", Icons.Rounded.Wifi, "wifi"),
            Tool("speed", "Скорость", "Тест в стиле Speedtest", Icons.Rounded.Speed, "network", 3),
            Tool("external", "Доступ извне", "Доступность ваших сервисов", Icons.Rounded.OpenInNew, "network", 4, isNew = true),
            Tool("routers", "Роутеры", "Состояние и настройки", Icons.Rounded.Router, "network", 5)
        )),
        ToolGroup("tv", "Телевидение", "IPTV, M3U, потоки, каналы и источники", Icons.Rounded.LiveTv, setOf(ToolCategory.TV), listOf(
            Tool("channels", "Каналы", "Плейлисты и просмотр", Icons.Rounded.List, "tv"),
            Tool("sources", "Источники", "M3U и телепрограмма", Icons.Rounded.Cloud, "tv", isNew = true),
            Tool("streamcheck", "Проверка потоков", "Доступность и качество", Icons.Rounded.PlaylistAddCheck, "tv"),
            Tool("tvlan", "ТВ в локальной сети", "udpxy и multicast", Icons.Rounded.SettingsInputAntenna, "tvlan"),
            Tool("tvmask", "Поиск по маске", "Match*, ?HD, %d", Icons.Rounded.ManageSearch, "tvmask")
        )),
        ToolGroup("protect", "Защита и доступ", "VPN, DPI, обход блокировок, анонимные сети", Icons.Rounded.Shield, setOf(ToolCategory.SECURITY), listOf(
            Tool("vpn", "VPN и свои адреса", "Туннель, Hosts, DNS", Icons.Rounded.Shield, "vpn", 0),
            Tool("dpi", "DPI byedpi", "Обход блокировок", Icons.Rounded.LockOpen, "dpi"),
            Tool("anon", "Onion и I2P", "Tor и I2P в браузере", Icons.Rounded.Language, "anon", isNew = true),
            Tool("keys", "Ключи и прокси", "Все протоколы: поиск, проверка, экспорт", Icons.Rounded.VpnKey, "keys", isNew = true),
            Tool("tls", "TLS Inspector", "Рукопожатия, JA3, QUIC", Icons.Rounded.Security, "tls-inspector", isNew = true),
            Tool("journal", "Журнал", "События и действия", Icons.Rounded.Dns, "vpn", 5)
        ))
    )

    val toolIcon: ImageVector get() = Icons.Rounded.Build
    val okIcon: ImageVector get() = Icons.Rounded.CheckCircle
}
