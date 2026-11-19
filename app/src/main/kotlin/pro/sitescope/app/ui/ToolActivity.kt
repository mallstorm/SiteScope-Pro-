package pro.sitescope.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import pro.sitescope.app.data.DataScreen
import pro.sitescope.app.ip.IpDbScreen
import pro.sitescope.app.net.NetworkScreen
import pro.sitescope.app.site.BatchScreen
import pro.sitescope.app.site.MetricsScreen
import pro.sitescope.app.tv.MaskSearchScreen
import pro.sitescope.app.tv.TvLanScreen
import pro.sitescope.app.vpn.HttpsContentScreen
import pro.sitescope.app.wifi.WifiScreen

/** Экраны второго уровня для внешних запусков (уведомления, ярлыки); внутри приложения их показывает оболочка через [ToolContent]. */
class ToolActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val screen = intent.getStringExtra("screen") ?: "network"
        val tab = intent.getIntExtra("tab", 0)
        val targets = intent.getStringExtra("targets")
        setContent {
            AppTheme {
                Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).navigationBarsPadding().imePadding()) {
                    ToolContent(screen, tab, targets) { finish() }
                }
            }
        }
    }
}

/** Содержимое экрана инструмента с шапкой «назад»; общее для ToolActivity и оболочки. */
@Composable
fun ToolContent(screen: String, tab: Int, targets: String?, onBack: () -> Unit) {
    val context = LocalContext.current
    // Сканер работает в фоне с уведомлением о ходе проверки.
    if (screen == "network" && Build.VERSION.SDK_INT >= 33) {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(Unit) {
            if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                try { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) } catch (e: Exception) { }
            }
        }
    }
    CompositionLocalProvider(LocalBackAction provides onBack) { Column(Modifier.fillMaxSize()) {
        when (screen) {
            "data" -> { BackHeader("Мои данные", onBack); DataScreen(tab) }
            "vpn" -> ProtectScreen(0, tab)
            "tls-inspector" -> ProtectScreen(3, 0)
            "https-content" -> { BackHeader("HTTPS · содержимое", onBack); HttpsContentScreen() }
            "settings" -> { BackHeader("Настройки", onBack); SettingsScreen() }
            "ipdb" -> { BackHeader("База адресов", onBack); IpDbScreen(tab) }
            "tvmask" -> { BackHeader("Поиск ТВ по маске ссылки", onBack); MaskSearchScreen() }
            "dpi" -> ProtectScreen(1, 0)
            "anon" -> ProtectScreen(2, 0)
            "tvlan" -> { BackHeader("ТВ в локальной сети", onBack); TvLanScreen() }
            "metrics" -> { BackHeader("Метрики поиска", onBack); MetricsScreen(targets) }
            "batch" -> { BackHeader("Проверка списка сайтов", onBack); BatchScreen() }
            "wifi" -> { BackHeader("Диагностика Wi-Fi", onBack); WifiScreen() }
            else -> NetworkScreen(tab, targets)
        }
    } }
}
