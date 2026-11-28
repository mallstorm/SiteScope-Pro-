package pro.sitescope.app.ui

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import pro.sitescope.app.net.findLocalLan
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Запускает отдельную Activity; ошибка запуска показывается сообщением, а не падением. */
fun open(context: Context, target: Class<*>, configure: Intent.() -> Unit = {}) {
    try { context.startActivity(Intent(context, target).apply(configure)) } catch (e: Exception) { toast(context, "Не удалось открыть раздел") }
}

/** «Сегодня, 14:27», «Вчера, 22:18» или «19 нояб., 09:05». */
fun whenText(time: Long): String = whenText(time, System.currentTimeMillis(), ZoneId.systemDefault())

internal fun whenText(time: Long, now: Long, zone: ZoneId): String {
    if (time <= 0L) return ""
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val then = Instant.ofEpochMilli(time).atZone(zone)
    val locale = Locale.forLanguageTag("ru")
    val clock = then.format(DateTimeFormatter.ofPattern("HH:mm", locale))
    return when (then.toLocalDate()) {
        today -> "Сегодня, $clock"
        today.minusDays(1) -> "Вчера, $clock"
        else -> then.format(DateTimeFormatter.ofPattern(if (today.year == then.year) "d MMM, HH:mm" else "d MMM yyyy, HH:mm", locale))
    }
}

/** IPv4-подсеть того же локального подключения, которое показывает «Моя сеть». */
fun localSubnet(context: Context): String =
    context.getSystemService(ConnectivityManager::class.java)?.let { manager ->
        runCatching { findLocalLan(manager)?.subnet }.getOrNull()
    }.orEmpty()
