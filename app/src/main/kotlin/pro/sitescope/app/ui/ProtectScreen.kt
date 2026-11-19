package pro.sitescope.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.runtime.Composable
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
    var vpnStart by remember(vpnTab) { mutableIntStateOf(vpnTab) }
    Column(Modifier.fillMaxSize()) {
        AppHeader("Защита и доступ", "VPN, DPI, Tor / I2P, TLS-инспекция и маршруты") {
            if (state != null) { StatusPills(state); Spacer(Modifier.height(8.dp)) }
            HeaderChips(listOf("Сводка", "VPN", "DPI", "Onion · I2P", "TLS Inspector"), current, { current = it },
                listOf(Icons.Rounded.Dashboard, Icons.Rounded.Shield, Icons.Rounded.LockOpen, Icons.Rounded.Language, Icons.Rounded.Security))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (current) {
                0 -> ProtectSummary { target, tab -> vpnStart = tab; current = target }
                1 -> VpnScreen(initialTab = vpnStart, importLink = state?.serverRequest, onImportDone = { state?.serverRequest = null })
                2 -> DpiScreen()
                3 -> AnonScreen()
                else -> TlsInspectorScreen()
            }
        }
    }
}
