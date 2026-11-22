package pro.sitescope.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pro.sitescope.app.anon.AnonScreen
import pro.sitescope.app.dpi.DpiScreen
import pro.sitescope.app.vpn.TlsInspectorScreen
import pro.sitescope.app.vpn.VpnScreen

/**
 * Хаб «Защита и доступ». [section]: -1 — сводка, 0 — VPN (адреса, серверы, DNS, маршруты), 1 — DPI, 2 — Onion/I2P, 3 — TLS Inspector.
 * Сводка повторяет макет: все четыре направления на одном экране, каждая карточка ведёт в свой раздел.
 */
@Composable
fun ProtectScreen(section: Int, vpnTab: Int, state: AppState? = null) {
    var current by remember(section) { mutableIntStateOf((section + 1).coerceIn(0, 4)) }
    var vpnStart by remember(vpnTab) { mutableIntStateOf(if (vpnTab == 0) 8 else vpnTab.coerceIn(0, 9)) }
    // Порядок вкладок как в макете; число — индекс вкладки внутри VpnScreen.
    val chips = listOf("Главное" to 8, "Серверы" to 1, "Ротация" to 6, "Раздача" to 7, "Ещё" to 9)
    BackHandler(enabled = current != 0) { current = 0 }
    Column(Modifier.fillMaxSize()) {
        AppHeader("Защита и доступ", "VPN, Hosts/Xray, DPI, Tor / I2P", onBack = if (current != 0) ({ current = 0 }) else null) {
            if (state != null) { StatusPills(state); Spacer(Modifier.height(8.dp)) }
            HeaderChips(chips.map { it.first }, if (current == 1) (chips.indexOfFirst { it.second == vpnStart }.takeIf { it >= 0 } ?: 4) else -1, { i -> vpnStart = chips[i].second; current = 1 },
                listOf(Icons.Rounded.Shield, Icons.Rounded.Storage, Icons.Rounded.Autorenew, Icons.Rounded.WifiTethering, Icons.Rounded.MoreHoriz))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (current) {
                0 -> ProtectSummary { target, tab -> vpnStart = if (target == 1 && tab == 0) 8 else tab; current = target }
                1 -> key(vpnStart) { VpnScreen(initialTab = vpnStart, importLink = state?.serverRequest, onImportDone = { state?.serverRequest = null }, showTabs = false, onKeys = { state?.openRoute("keys") }) }
                2 -> DpiScreen()
                3 -> AnonScreen()
                else -> TlsInspectorScreen()
            }
        }
    }
}
