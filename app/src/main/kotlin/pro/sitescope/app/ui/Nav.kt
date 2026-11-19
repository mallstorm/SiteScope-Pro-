package pro.sitescope.app.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Запускает отдельную Activity; ошибка запуска показывается сообщением, а не падением. */
fun open(context: Context, target: Class<*>, configure: Intent.() -> Unit = {}) {
    try { context.startActivity(Intent(context, target).apply(configure)) } catch (e: Exception) { toast(context, "Не удалось открыть раздел") }
}

/** «Сегодня, 14:27», «Вчера, 22:18» или «19 нояб., 09:05». */
fun whenText(time: Long): String {
    if (time <= 0L) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = time }
    val clock = SimpleDateFormat("HH:mm", Locale("ru")).format(Date(time))
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val dayDiff = now.get(Calendar.DAY_OF_YEAR) - then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameYear && dayDiff == 0 -> "Сегодня, $clock"
        sameYear && dayDiff == 1 -> "Вчера, $clock"
        else -> SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(time))
    }
}

/** Подсеть локальной сети (Wi-Fi или Ethernet) в виде 192.168.1.0/24; пусто, если её нет. Работает мимо VPN-туннеля. */
fun localSubnet(context: Context): String {
    return try {
        val manager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        @Suppress("DEPRECATION")
        for (network in manager.allNetworks) {
            val caps = manager.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val link = manager.getLinkProperties(network) ?: continue
            for (address in link.linkAddresses) {
                val ip = address.address
                if (ip is Inet4Address && !ip.isLoopbackAddress) {
                    val prefix = address.prefixLength
                    val bytes = ip.address
                    var value = ((bytes[0].toLong() and 0xFF) shl 24) or ((bytes[1].toLong() and 0xFF) shl 16) or ((bytes[2].toLong() and 0xFF) shl 8) or (bytes[3].toLong() and 0xFF)
                    val mask = if (prefix <= 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
                    value = value and mask
                    return "${(value shr 24) and 0xFF}.${(value shr 16) and 0xFF}.${(value shr 8) and 0xFF}.${value and 0xFF}/$prefix"
                }
            }
        }
        ""
    } catch (e: Exception) { "" }
}
