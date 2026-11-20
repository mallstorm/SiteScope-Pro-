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
    var vpnStart by remember(vpnTab) { mutableIntStateOf(vpnTab.coerceIn(0, 5)) }
    // Порядок вкладок как в макете; число — индекс вкладки внутри VpnScreen.
    val chips = listOf("Адреса" to 0, "Серверы" to 1, "Маршруты" to 3, "DNS" to 2, "Запись" to 4, "Журнал" to 5)
    BackHandler(enabled = current != 0) { current = 0 }
    Column(Modifier.fillMaxSize()) {
        AppHeader("Защита и доступ", "VPN, Hosts/Xray, DPI, Tor / I2P", onBack = if (current != 0) ({ current = 0 }) else null) {
            if (state != null) { StatusPills(state); Spacer(Modifier.height(8.dp)) }
            HeaderChips(chips.map { it.first }, if (current == 1) chips.indexOfFirst { it.second == vpnStart } else -1, { i -> vpnStart = chips[i].second; current = 1 },
                listOf(Icons.Rounded.Link, Icons.Rounded.Storage, Icons.Rounded.AccountTree, Icons.Rounded.Dns, Icons.Rounded.RadioButtonChecked, Icons.Rounded.Description))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (current) {
                0 -> ProtectSummary { target, tab -> vpnStart = tab; current = target }
                1 -> key(vpnStart) { VpnScreen(initialTab = vpnStart, importLink = state?.serverRequest, onImportDone = { state?.serverRequest = null }, showTabs = false) }
                2 -> DpiScreen()
                3 -> AnonScreen()
                else -> TlsInspectorScreen()
            }
        }
    }
}
