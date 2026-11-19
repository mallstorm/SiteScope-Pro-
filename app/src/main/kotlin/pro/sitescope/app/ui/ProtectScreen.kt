package pro.sitescope.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
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
import pro.sitescope.app.anon.AnonScreen
import pro.sitescope.app.dpi.DpiScreen
import pro.sitescope.app.vpn.TlsInspectorScreen
import pro.sitescope.app.vpn.VpnScreen

/** Хаб «Защита и доступ»: VPN (адреса, серверы, DNS, маршруты), обход блокировок DPI, Onion/I2P и TLS Inspector. */
@Composable
fun ProtectScreen(section: Int, vpnTab: Int, state: AppState? = null) {
    var current by remember(section) { mutableIntStateOf(section.coerceIn(0, 3)) }
    Column(Modifier.fillMaxSize()) {
        AppHeader("Защита и доступ", "VPN, DPI, Tor / I2P, TLS-инспекция и маршруты") {
            if (state != null) { StatusPills(state); Spacer(Modifier.height(8.dp)) }
            HeaderChips(listOf("VPN", "DPI", "Onion · I2P", "TLS Inspector"), current, { current = it },
                listOf(Icons.Rounded.Shield, Icons.Rounded.LockOpen, Icons.Rounded.Language, Icons.Rounded.Security))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (current) {
                0 -> VpnScreen(initialTab = vpnTab)
                1 -> DpiScreen()
                2 -> AnonScreen()
                else -> TlsInspectorScreen()
            }
        }
    }
}
