package pro.sitescope.app.wifi

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import pro.sitescope.app.ui.EmptyState
import pro.sitescope.app.ui.IconBadge
import pro.sitescope.app.ui.Pill
import pro.sitescope.app.ui.SoftCard
import pro.sitescope.app.ui.tones

@Composable
fun WifiScreen() {
    val vm: WifiViewModel = viewModel()
    val lifecycleOwner = LocalLifecycleOwner.current

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        vm.start(); vm.scan()
    }

    DisposableEffect(lifecycleOwner) {
        vm.start()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) vm.start()
            if (event == Lifecycle.Event.ON_PAUSE) { vm.stop(); vm.stopMeter() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); vm.stop(); vm.stopMeter() }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        if (!vm.hasPermission) {
            Spacer(Modifier.height(12.dp))
            EmptyState(Icons.Rounded.Wifi, "Нужен доступ к сетям рядом",
                "Чтобы показать Wi-Fi сети рядом и проверить вашу сеть, Android требует разрешение на определение местоположения. Сами координаты приложению не нужны.") {
                Button(onClick = { permLauncher.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)) }) {
                    Text("Разрешить")
                }
            }
            return@Column
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Сети рядом", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (vm.scanning) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else TextButton(onClick = { vm.scan() }) { Text("Обновить") }
        }
        if (vm.lastScanNote.isNotEmpty()) Text(vm.lastScanNote, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val mine = vm.myNetwork()
        if (mine != null) {
            Spacer(Modifier.height(8.dp))
            MyNetworkCard(mine, vm.crowding(mine))
        }

        Spacer(Modifier.height(12.dp))
        RoomMeterCard(vm)

        Spacer(Modifier.height(16.dp))
        Text("Все сети (${vm.networks.size})", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))

        if (vm.networks.isEmpty() && !vm.scanning) {
            EmptyState(Icons.Rounded.WifiOff, "Сети не найдены", "Нажмите «Обновить» или включите Wi-Fi.")
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(vm.networks) { n -> NetworkRow(n) }
            }
        }
    }
}

@Composable
private fun MyNetworkCard(n: WifiNetwork, crowding: Int) {
    val warn = n.open || n.security.contains("WEP")
    SoftCard(color = if (warn) tones.warnSoft else tones.netSoft) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(if (warn) Icons.Rounded.WarningAmber else Icons.Rounded.Wifi, if (warn) tones.warn else tones.net, MaterialTheme.colorScheme.surface)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(n.ssid, style = MaterialTheme.typography.titleSmall)
                Text("${n.band} · канал ${n.channel} · ${n.security}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${n.level} дБм", style = MaterialTheme.typography.labelMedium)
        }
        if (warn) {
            Spacer(Modifier.height(8.dp))
            Text(
                if (n.open) "Ваша сеть открыта — без пароля любой может подключиться. Включите WPA2/WPA3 в настройках роутера."
                else "Ваша сеть использует устаревшее шифрование WEP, которое легко взламывается. Перейдите на WPA2/WPA3.",
                style = MaterialTheme.typography.bodySmall)
        } else if (crowding >= 3 && n.band == "2.4 ГГц") {
            Spacer(Modifier.height(8.dp))
            Text("На вашем канале и рядом с ним работает ещё $crowding сетей — это может снижать скорость. Попробуйте сменить канал в настройках роутера.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RoomMeterCard(vm: WifiViewModel) {
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.NetworkCheck, tones.info, tones.infoSoft)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Сигнал по комнатам", style = MaterialTheme.typography.titleSmall)
                Text("Ходите по дому и смотрите, где сигнал слабеет", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = vm.meterOn, onCheckedChange = { if (it) vm.startMeter() else vm.stopMeter() })
        }
        if (vm.meterOn) {
            Spacer(Modifier.height(10.dp))
            val rssi = vm.liveRssi
            if (rssi == null) {
                Text("Измеряем…", style = MaterialTheme.typography.bodyMedium)
            } else {
                val quality = WifiViewModel.quality(rssi)
                val color = when { rssi >= -67 -> tones.good; rssi >= -75 -> tones.warn; else -> tones.bad }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$rssi дБм", style = MaterialTheme.typography.headlineSmall, color = color)
                    Spacer(Modifier.width(10.dp))
                    Pill(quality, color, color.copy(alpha = 0.14f))
                }
            }
        }
    }
}

@Composable
private fun NetworkRow(n: WifiNetwork) {
    val warn = n.open || n.security.contains("WEP")
    SoftCard(padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SignalIcon(n.level)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n.ssid, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    if (n.isMine) { Spacer(Modifier.width(6.dp)); Pill("ваша", tones.net, tones.netSoft) }
                }
                Text("${n.band} · канал ${n.channel} · ${n.security}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (warn) Icon(Icons.Rounded.LockOpen, null, tint = tones.warn, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("${n.level}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun SignalIcon(level: Int) {
    val color = when { level >= -67 -> tones.good; level >= -75 -> tones.warn; else -> tones.bad }
    Icon(Icons.Rounded.Wifi, null, tint = color, modifier = Modifier.size(26.dp))
}
