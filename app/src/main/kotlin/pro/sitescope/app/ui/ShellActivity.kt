package pro.sitescope.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import pro.sitescope.app.scan.QrScannerDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.ViewModel
import pro.sitescope.app.VpnStateRecovery
import pro.sitescope.app.core.SmartInput
import pro.sitescope.app.data.DataHub
import pro.sitescope.app.ip.IpScreen
import pro.sitescope.app.site.SiteScreen
import pro.sitescope.app.tv.TvScreen

/** Четыре вкладки нижней панели. Раздел, открытый из вкладки, не меняет подсвеченную вкладку. */
enum class Tab(val title: String, val icon: ImageVector) {
    HOME("Главная", Icons.Rounded.Home), TOOLS("Инструменты", Icons.Rounded.Build),
    TASKS("Задачи", Icons.Rounded.Checklist), DATA("Данные", Icons.Rounded.Folder)
}

/** Общее состояние оболочки: вкладка, открытый раздел и «поручения» экранам от умной строки. */
class AppState : ViewModel() {
    var tab by mutableStateOf(Tab.HOME)
    var route by mutableStateOf<String?>(null)
    var routeTab by mutableIntStateOf(0)
    var routeTargets by mutableStateOf<String?>(null)
    var routeNonce by mutableIntStateOf(0)
    var dataTab by mutableIntStateOf(0)
    var dataNonce by mutableIntStateOf(0)
    var siteRequest by mutableStateOf<String?>(null)
    var ipRequest by mutableStateOf<String?>(null)
    var subnetRequest by mutableStateOf<String?>(null)
    var playlistRequest by mutableStateOf<String?>(null)
    var streamRequest by mutableStateOf<String?>(null)
    /** Ссылка сервера (vless://… и др.) из QR-кода: экран VPN добавит её и сбросит поле. */
    var serverRequest by mutableStateOf<String?>(null)

    fun selectTab(target: Tab) { route = null; tab = target }

    /** Открывает раздел поверх текущей вкладки. «data» и «tasks» — это вкладки, а не разделы. */
    fun openRoute(name: String, subTab: Int = 0, targets: String? = null) {
        when (name) {
            "data" -> { dataTab = subTab; dataNonce++; selectTab(Tab.DATA) }
            "tasks" -> selectTab(Tab.TASKS)
            else -> { routeTab = subTab; routeTargets = targets; routeNonce++; route = name }
        }
    }

    fun closeRoute() { route = null }

    /** Открывает подходящий инструмент для введённого текста. Возвращает false, если текст пуст. */
    fun open(text: String): Boolean {
        val trimmed = text.trim()
        if (SERVER_SCHEMES.any { trimmed.startsWith(it, ignoreCase = true) }) { serverRequest = trimmed; openRoute("vpn", 1); return true }
        val r = SmartInput.detect(text)
        when (r.kind) {
            SmartInput.Kind.EMPTY -> return false
            SmartInput.Kind.URL, SmartInput.Kind.DOMAIN -> { siteRequest = r.value; openRoute("site") }
            SmartInput.Kind.IPV4, SmartInput.Kind.IPV6, SmartInput.Kind.ASN, SmartInput.Kind.TEXT -> { ipRequest = r.value; openRoute("ip") }
            SmartInput.Kind.CIDR -> { subnetRequest = r.value; openRoute("ip") }
            SmartInput.Kind.PLAYLIST -> { playlistRequest = r.value; openRoute("tv") }
            SmartInput.Kind.STREAM -> { streamRequest = r.value; openRoute("tv") }
        }
        return true
    }
}

private val SERVER_SCHEMES = listOf("vless://", "vmess://", "trojan://", "ss://", "hysteria2://", "hy2://")

class ShellActivity : ComponentActivity() {
    private val state: AppState by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Если системный процесс VPN был перезапущен — возвращаем пользователя в Hosts Manager, как в 0.39.
        val recovered = try { VpnStateRecovery.recoverIfStale(this) } catch (e: Throwable) { false }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent { AppTheme { Shell(state) } }
        if (recovered) window.decorView.post { state.openRoute("vpn") }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent == null) return
        // Старые значения «tab» из уведомлений и ярлыков сохраняются: ТВ, сайт, IP, «Ещё».
        when (intent.getStringExtra("tab")) {
            "tv" -> state.openRoute("tv"); "site" -> state.openRoute("site"); "ip" -> state.openRoute("ip")
            "more" -> state.selectTab(Tab.TOOLS); "tasks" -> state.selectTab(Tab.TASKS); "data" -> state.selectTab(Tab.DATA)
        }
        intent.getStringExtra("route")?.let { state.openRoute(it, intent.getIntExtra("routeTab", 0), intent.getStringExtra("targets")) }
        if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT)?.let { shared ->
            val candidate = shared.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("http", true) } ?: shared.trim()
            state.open(candidate.take(2000))
        }
    }
}

@Composable
fun Shell(state: AppState) {
    val count = rememberActiveTaskCount()
    val imeUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    BackHandler(enabled = state.route != null || state.tab != Tab.HOME) {
        if (state.route != null) state.closeRoute() else state.tab = Tab.HOME
    }
    var scanning by remember { mutableStateOf(false) }
    var wifiQr by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    if (scanning) QrScannerDialog(onClose = { scanning = false }, onResult = { text ->
        scanning = false
        if (text.startsWith("WIFI:", ignoreCase = true)) wifiQr = text else if (!state.open(text)) toast(context, "Пустой QR-код")
    })
    wifiQr?.let { raw ->
        fun field(key: String) = Regex("(?:^|;|:)$key:((?:\\\\.|[^;])*)").find(raw.removePrefix("WIFI:"))?.groupValues?.get(1)?.replace(Regex("\\\\(.)"), "$1").orEmpty()
        val ssid = field("S"); val password = field("P")
        AlertDialog(onDismissRequest = { wifiQr = null }, title = { Text("Wi-Fi из QR-кода") },
            text = { Text("Сеть: " + ssid.ifEmpty { "—" } + "\nПароль: " + password.ifEmpty { "нет" }) },
            confirmButton = { TextButton(onClick = { if (password.isNotEmpty()) copyText(context, password, "Пароль скопирован"); wifiQr = null }) { Text(if (password.isNotEmpty()) "Копировать пароль" else "Закрыть") } },
            dismissButton = { TextButton(onClick = { wifiQr = null }) { Text("Закрыть") } })
    }
    CompositionLocalProvider(LocalQrScan provides { scanning = true }) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val route = state.route
            if (route != null) {
                CompositionLocalProvider(LocalBackAction provides { state.closeRoute() }) { RouteHost(state, route) }
            } else {
                Crossfade(targetState = state.tab, label = "tab") { tab ->
                    when (tab) {
                        Tab.HOME -> HomeScreen(state)
                        Tab.TOOLS -> ToolsScreen(state)
                        Tab.TASKS -> TasksScreen(state)
                        Tab.DATA -> key(state.dataNonce) { DataHub(state, state.dataTab) }
                    }
                }
            }
        }
        if (!imeUp) {
            if (state.route == "radio" || state.route == "satellite") {
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            } else BottomBar4(state.tab, count) { state.selectTab(it) }
        }
    }
    }
}

@Composable
private fun RouteHost(state: AppState, route: String) {
    when (route) {
        "site" -> SiteScreen(state)
        "ip" -> IpScreen(state)
        "tv" -> TvScreen(state)
        else -> key(state.routeNonce) { ToolContent(route, state.routeTab, state.routeTargets, state) { state.closeRoute() } }
    }
}
