package pro.sitescope.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import pro.sitescope.app.HostsActivity
import pro.sitescope.app.VpnStateRecovery
import pro.sitescope.app.core.SmartInput
import pro.sitescope.app.ip.IpScreen
import pro.sitescope.app.site.SiteScreen
import pro.sitescope.app.tv.TvScreen

enum class Tab(val title: String, val icon: ImageVector) {
    HOME("Главная", Icons.Rounded.Home), SITE("Сайт", Icons.Rounded.Language), IP("IP", Icons.Rounded.Public),
    TV("ТВ", Icons.Rounded.LiveTv), MORE("Ещё", Icons.Rounded.MoreHoriz)
}

/** Общее состояние оболочки: активная вкладка и «поручения» экранам от умной строки. */
class AppState : ViewModel() {
    var tab by mutableStateOf(Tab.HOME)
    var siteRequest by mutableStateOf<String?>(null)
    var ipRequest by mutableStateOf<String?>(null)
    var subnetRequest by mutableStateOf<String?>(null)
    var playlistRequest by mutableStateOf<String?>(null)
    var streamRequest by mutableStateOf<String?>(null)

    /** Открывает подходящий инструмент для введённого текста. Возвращает false, если текст пуст. */
    fun open(text: String): Boolean {
        val r = SmartInput.detect(text)
        when (r.kind) {
            SmartInput.Kind.EMPTY -> return false
            SmartInput.Kind.URL, SmartInput.Kind.DOMAIN -> { siteRequest = r.value; tab = Tab.SITE }
            SmartInput.Kind.IPV4, SmartInput.Kind.IPV6, SmartInput.Kind.ASN, SmartInput.Kind.TEXT -> { ipRequest = r.value; tab = Tab.IP }
            SmartInput.Kind.CIDR -> { subnetRequest = r.value; tab = Tab.IP }
            SmartInput.Kind.PLAYLIST -> { playlistRequest = r.value; tab = Tab.TV }
            SmartInput.Kind.STREAM -> { streamRequest = r.value; tab = Tab.TV }
        }
        return true
    }
}

class ShellActivity : ComponentActivity() {
    private val state: AppState by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Если системный процесс VPN был перезапущен — возвращаем пользователя в Hosts Manager, как в 0.39.
        val recovered = try { VpnStateRecovery.recoverIfStale(this) } catch (e: Throwable) { false }
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) handle(intent)
        setContent { AppTheme { Shell(state) } }
        if (recovered) window.decorView.post { startActivity(Intent(this, HostsActivity::class.java)) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent == null) return
        when (intent.getStringExtra("tab")) {
            "tv" -> state.tab = Tab.TV; "site" -> state.tab = Tab.SITE; "ip" -> state.tab = Tab.IP; "more" -> state.tab = Tab.MORE
        }
        if (intent.action == Intent.ACTION_SEND) intent.getStringExtra(Intent.EXTRA_TEXT)?.let { shared ->
            val candidate = shared.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("http", true) } ?: shared.trim()
            state.open(candidate.take(2000))
        }
    }
}

@Composable
fun Shell(state: AppState) {
    BackHandler(enabled = state.tab != Tab.HOME) { state.tab = Tab.HOME }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                for (tab in Tab.entries) NavigationBarItem(
                    selected = state.tab == tab, onClick = { state.tab = tab },
                    icon = { Icon(tab.icon, contentDescription = tab.title) }, label = { Text(tab.title) }
                )
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            Crossfade(targetState = state.tab, label = "tab") { tab ->
                when (tab) {
                    Tab.HOME -> HomeScreen(state)
                    Tab.SITE -> SiteScreen(state)
                    Tab.IP -> IpScreen(state)
                    Tab.TV -> TvScreen(state)
                    Tab.MORE -> MoreScreen()
                }
            }
        }
    }
}
