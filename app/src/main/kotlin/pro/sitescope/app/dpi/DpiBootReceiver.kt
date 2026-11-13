package pro.sitescope.app.dpi

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Запускает обход DPI после перезагрузки, если включён автозапуск. VPN-режимы — только если разрешение VPN уже выдано. */
class DpiBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val cfg = DpiStore.load(context)
        if (!cfg.autostart || !DpiEngine.available(context)) return
        if (cfg.isVpn && DpiRuntime.vpnConsent(context) != null) return
        try { DpiRuntime.startService(context, cfg) } catch (_: Exception) { }
    }
}
