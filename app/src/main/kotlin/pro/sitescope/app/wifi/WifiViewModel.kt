package pro.sitescope.app.wifi

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class WifiNetwork(val ssid: String, val bssid: String, val level: Int, val frequency: Int, val channel: Int,
                   val band: String, val security: String, val open: Boolean, val isMine: Boolean)

/** Сканирование и диагностика Wi-Fi: сети рядом, занятость каналов, своя сеть, сигнал по комнатам. */
class WifiViewModel(app: Application) : AndroidViewModel(app) {
    private val context: Context get() = getApplication()
    private val manager get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    var hasPermission by mutableStateOf(false)
    var scanning by mutableStateOf(false)
    var networks by mutableStateOf<List<WifiNetwork>>(emptyList())
    var connectedSsid by mutableStateOf("")
    var liveRssi by mutableStateOf<Int?>(null)
    var meterOn by mutableStateOf(false)
    var lastScanNote by mutableStateOf("")

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) { readResults() }
    }

    fun start() {
        try { context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) } catch (e: Exception) { }
        readResults()
    }

    fun stop() { try { context.unregisterReceiver(receiver) } catch (e: Exception) { } }

    fun scan() {
        if (scanning) return
        scanning = true; lastScanNote = ""
        @Suppress("DEPRECATION")
        val started = try { manager.startScan() } catch (e: Exception) { false }
        if (!started) { lastScanNote = "Система ограничила частые сканирования — показаны последние известные сети."; readResults() }
        viewModelScope.launch { delay(4000); if (scanning) { scanning = false; readResults() } }
    }

    private fun readResults() {
        scanning = false
        connectedSsid = try {
            @Suppress("DEPRECATION")
            manager.connectionInfo?.ssid?.trim('"') ?: ""
        } catch (e: Exception) { "" }
        val connectedBssid = try {
            @Suppress("DEPRECATION")
            manager.connectionInfo?.bssid ?: ""
        } catch (e: Exception) { "" }
        val results: List<ScanResult> = try { manager.scanResults ?: emptyList() } catch (e: SecurityException) { hasPermission = false; emptyList() }
        if (results.isNotEmpty() || connectedSsid.isNotEmpty()) hasPermission = true
        networks = results.map { r ->
            val ssid = try { r.SSID ?: "" } catch (e: Exception) { "" }
            val cap = r.capabilities ?: ""
            val open = !cap.contains("WPA") && !cap.contains("WEP") && !cap.contains("EAP") && !cap.contains("SAE")
            val security = when {
                cap.contains("SAE") -> "WPA3"
                cap.contains("WPA3") -> "WPA3"
                cap.contains("WPA2") -> "WPA2"
                cap.contains("WPA") -> "WPA"
                cap.contains("WEP") -> "WEP (устаревшая)"
                open -> "Открытая"
                else -> "—"
            }
            val freq = r.frequency
            val (channel, band) = channelOf(freq)
            WifiNetwork(ssid.ifBlank { "(скрыта)" }, r.BSSID ?: "", r.level, freq, channel, band, security, open, r.BSSID == connectedBssid)
        }.sortedByDescending { it.level }
    }

    private fun channelOf(freq: Int): Pair<Int, String> = when {
        freq in 2412..2484 -> ((freq - 2412) / 5 + 1) to "2.4 ГГц"
        freq in 5160..5885 -> ((freq - 5000) / 5) to "5 ГГц"
        freq in 5955..7115 -> ((freq - 5950) / 5 + 1) to "6 ГГц"
        else -> 0 to "?"
    }

    /** Чужие сети на тех же или соседних каналах 2.4 ГГц, что мешают вашей. */
    fun crowding(own: WifiNetwork): Int = networks.count { it.band == "2.4 ГГц" && it != own && kotlin.math.abs(it.channel - own.channel) <= 2 }

    fun myNetwork(): WifiNetwork? = networks.firstOrNull { it.isMine } ?: networks.firstOrNull { it.ssid == connectedSsid }

    // ---------- живой сигнал для обхода по комнатам ----------
    fun startMeter() {
        if (meterOn) return
        meterOn = true
        viewModelScope.launch {
            while (isActive && meterOn) {
                liveRssi = try {
                    @Suppress("DEPRECATION")
                    manager.connectionInfo?.rssi
                } catch (e: Exception) { null }
                delay(1200)
            }
        }
    }
    fun stopMeter() { meterOn = false; liveRssi = null }

    companion object {
        fun quality(rssi: Int): String = when {
            rssi >= -55 -> "Отличный"
            rssi >= -67 -> "Хороший"
            rssi >= -75 -> "Слабый"
            else -> "Очень слабый"
        }
    }
}
