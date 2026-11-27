package pro.sitescope.app.wifi

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import pro.sitescope.app.radio.Chips
import pro.sitescope.app.radio.SignalChart
import pro.sitescope.app.radio.signalColor
import pro.sitescope.app.ui.EmptyState
import pro.sitescope.app.ui.tones

/** One scroll surface keeps nearby networks reachable on smaller displays. */
@Composable
fun WifiScreen() {
    val vm:WifiViewModel=viewModel()
    val owner=LocalLifecycleOwner.current
    var band by rememberSaveable{mutableStateOf("Все")}
    var query by rememberSaveable{mutableStateOf("")}
    var search by rememberSaveable{mutableStateOf(false)}
    val perms=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){vm.start();vm.scan()}
    DisposableEffect(owner) {
        vm.start()
        val observer=LifecycleEventObserver{_,event->
            if(event==Lifecycle.Event.ON_RESUME)vm.start()
            if(event==Lifecycle.Event.ON_PAUSE){vm.stop();vm.stopMeter()}
        }
        owner.lifecycle.addObserver(observer)
        onDispose{owner.lifecycle.removeObserver(observer);vm.stop();vm.stopMeter()}
    }
    val mine=vm.myNetwork()
    val networks=vm.networks.filter{(band=="Все" || it.band==band) && (query.isBlank() || it.ssid.contains(query,true) || it.bssid.contains(query,true))}
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=12.dp),contentPadding=PaddingValues(bottom=20.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        if(!vm.hasPermission)item {
            EmptyState(Icons.Rounded.Wifi,"Нужен доступ к сетям рядом","Android требует разрешение на точную геопозицию для списка Wi-Fi сетей.") {
                Button({perms.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION))}){Text("Разрешить")}
            }
        } else {
            item {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text("Wi-Fi · ${vm.networks.size} сетей",Modifier.weight(1f),style=MaterialTheme.typography.titleMedium)
                    IconButton({search=!search}){Icon(Icons.Rounded.Search,"Поиск по SSID / BSSID")}
                    if(vm.scanning)CircularProgressIndicator(Modifier.size(24.dp),strokeWidth=2.dp)
                    else IconButton({vm.scan()}){Icon(Icons.Rounded.Refresh,"Сканировать Wi-Fi")}
                }
                if(search)OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("SSID или BSSID")},singleLine=true)
                if(vm.lastScanNote.isNotBlank())Text(vm.lastScanNote,style=MaterialTheme.typography.labelSmall)
            }
            item {
                ElevatedCard(Modifier.fillMaxWidth()) {Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Wifi,null,tint=MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(mine?.ssid ?: vm.connectedSsid.takeUnless{it.isBlank() || it=="<unknown ssid>"} ?: "Нет подключённой сети",style=MaterialTheme.typography.titleSmall)
                            Text("Измеритель по комнатам",style=MaterialTheme.typography.labelSmall)
                        }
                        Switch(vm.meterOn,{if(it)vm.startMeter() else vm.stopMeter()})
                    }
                    mine?.let {n->
                        Text("${n.band} · канал ${n.channel} · ${n.frequency} МГц · ${n.width} МГц",style=MaterialTheme.typography.bodySmall)
                        Text("${n.bssid} · ${n.security}",style=MaterialTheme.typography.labelSmall)
                        if(n.open || n.security.contains("WEP"))Text("${if(n.open)"Открытая сеть без пароля" else "Устаревшее шифрование WEP"}",color=tones.warn,style=MaterialTheme.typography.bodySmall)
                        if(vm.crowding(n)>=3 && n.band=="2.4 ГГц")Text("На этом и соседних каналах: ${vm.crowding(n)} других сетей",color=tones.warn,style=MaterialTheme.typography.bodySmall)
                    }
                    if(vm.meterOn) {
                        Text(vm.liveRssi?.let{"$it dBm · ${WifiViewModel.quality(it)}"} ?: "Ожидание измерения…",style=MaterialTheme.typography.titleMedium)
                        if(vm.meterHistory.isNotEmpty())SignalChart(mapOf("Wi-Fi · RSSI" to vm.meterHistory),Modifier.height(140.dp))
                    } else mine?.let{SignalBadge(it.level)}
                }}
            }
            item {Chips(listOf("Все","2.4 ГГц","5 ГГц","6 ГГц"),band){band=it}}
            items(networks,key={it.bssid+it.frequency}){n->
                Surface(Modifier.fillMaxWidth(),color=if(n.isMine)MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.small) {
                    Column(Modifier.padding(10.dp),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(n.band,style=MaterialTheme.typography.labelSmall)
                            Text(n.ssid,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                            Icon(if(n.open)Icons.Rounded.LockOpen else Icons.Rounded.Lock,if(n.open)"Открытая" else "Защищённая",Modifier.size(14.dp))
                            SignalBadge(n.level)
                        }
                        Text("${n.bssid}${if(n.isMine)" · подключено" else ""}",style=MaterialTheme.typography.labelSmall)
                        Text("${n.frequency} МГц · CH ${n.channel} · ${n.width} МГц · ${n.security}",style=MaterialTheme.typography.bodySmall)
                        if(n.measured>0)Text("Замер ${pro.sitescope.app.radio.clock(n.measured)}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if(networks.isEmpty() && !vm.scanning)item {Text("Сети не найдены. Измените фильтр или нажмите сканирование.")}
            item {Text("Android ограничивает частоту сканирования. Показано время каждого результата; расстояние по RSSI не вычисляется.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}
        }
    }
}

@Composable
private fun SignalBadge(level:Int) {
    val color=signalColor(level,true)
    Column(Modifier.width(58.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Text("$level",style=MaterialTheme.typography.labelLarge,color=color)
        LinearProgressIndicator(progress={((level+100)/70f).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(4.dp),color=color)
    }
}
