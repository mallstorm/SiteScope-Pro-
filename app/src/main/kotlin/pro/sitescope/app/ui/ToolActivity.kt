package pro.sitescope.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import pro.sitescope.app.data.DataScreen
import pro.sitescope.app.ip.IpDbScreen
import pro.sitescope.app.net.NetworkScreen
import pro.sitescope.app.site.MetricsScreen
import pro.sitescope.app.tv.TvLanScreen
import pro.sitescope.app.vpn.VpnScreen

/** Экраны второго уровня из раздела «Ещё»: сеть, мои данные, настройки. */
class ToolActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra("screen") ?: "network"
        val tab = intent.getIntExtra("tab", 0)
        val targets = intent.getStringExtra("targets")
        // Сканер работает в фоне с уведомлением о ходе проверки.
        if (screen == "network" && Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && savedInstanceState == null) {
            try { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7) } catch (e: Exception) { }
        }
        setContent {
            AppTheme {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
                    when (screen) {
                        "data" -> { BackHeader("Мои данные", { finish() }); DataScreen(tab) }
                        "vpn" -> { BackHeader("VPN и свои адреса", { finish() }); VpnScreen() }
                        "settings" -> { BackHeader("Настройки", { finish() }); SettingsScreen() }
                        "ipdb" -> { BackHeader("База адресов", { finish() }); IpDbScreen(tab) }
                        "tvlan" -> { BackHeader("ТВ в локальной сети", { finish() }); TvLanScreen() }
                        "metrics" -> { BackHeader("Метрики поиска", { finish() }); MetricsScreen(targets) }
                        else -> { BackHeader("Моя сеть", { finish() }); NetworkScreen(tab, targets) }
                    }
                }
            }
        }
    }
}
