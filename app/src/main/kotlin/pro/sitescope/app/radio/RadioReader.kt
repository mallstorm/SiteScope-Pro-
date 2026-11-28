@file:Suppress("DEPRECATION")
package pro.sitescope.app.radio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.telephony.*
import kotlinx.coroutines.*
import kotlin.coroutines.resume

data class RadioCell(val sim: String, val rat: String, val registered: Boolean, val mcc: String, val mnc: String,
    val area: Long?, val cid: Long?, val pci: Int?, val channel: Int?, val dbm: Int?, val details: String, val measured: Long,
    val link: RadioLink = if (registered) RadioLink.Registered else RadioLink.Neighbor)
enum class RadioLink(val serving: Boolean) { Primary(true), Secondary(true), Registered(true), Neighbor(false) }
data class SimSignal(val name: String,val operator: String,val roaming: Boolean,val data: Int?,val service: Int?,val strength: String)
data class WifiReading(val ssid:String,val dbm:Int,val frequency:Int,val link:Int)
data class RadioFrame(val time:Long,val sims:List<SimSignal>,val cells:List<RadioCell>,val wifi:WifiReading?,val internet:Boolean,val note:String,
    val activeDataSim: String? = null)

private fun Int.known() = takeUnless { it == Int.MAX_VALUE || it == Int.MIN_VALUE }
private fun Long.known() = takeUnless { it == Long.MAX_VALUE || it < 0 }
private fun metrics(vararg values:Pair<String,Int?>) = values.mapNotNull { (k,v)->v?.known()?.let { "$k $it" } }.joinToString(" · ")

private fun CellInfo.row(sim:String,time:Long): RadioCell? {
    val ageMs=(SystemClock.elapsedRealtimeNanos()-timeStamp)/1_000_000
    val link = when (cellConnectionStatus) {
        CellInfo.CONNECTION_PRIMARY_SERVING -> RadioLink.Primary
        CellInfo.CONNECTION_SECONDARY_SERVING -> RadioLink.Secondary
        CellInfo.CONNECTION_NONE -> RadioLink.Neighbor
        else -> if (isRegistered) RadioLink.Registered else RadioLink.Neighbor
    }
    fun cell(rat:String,mcc:String?,mnc:String?,area:Int?,cid:Long?,pci:Int?,channel:Int?,s:CellSignalStrength,details:String) =
        RadioCell(sim,rat,isRegistered || link.serving,mcc.orEmpty(),mnc.orEmpty(),area?.known()?.toLong(),cid?.known(),pci?.known(),channel?.known(),s.dbm.takeIf { it in -160..-1 },details,
            if(timeStamp>0 && ageMs>=0) time-ageMs else 0L, link)
    return when {
        this is CellInfoLte -> with(cellIdentity) {
            val s=cellSignalStrength
            cell("LTE",mccString,mncString,tac,ci.known()?.toLong(),pci,earfcn,s,
                metrics("RSRP dBm" to s.rsrp,"RSRQ dB" to s.rsrq,"RSSNR dB" to s.rssnr,"CQI" to s.cqi,"TA" to s.timingAdvance,"BW кГц" to bandwidth) +
                    if (Build.VERSION.SDK_INT >= 30 && bands.isNotEmpty()) " · Band " + bands.joinToString("/") else "")
        }
        Build.VERSION.SDK_INT>=29 && this is CellInfoNr -> with(cellIdentity as CellIdentityNr) {
            val s=cellSignalStrength as CellSignalStrengthNr
            cell("NR",mccString,mncString,tac,nci,pci,nrarfcn,s,metrics("SS-RSRP dBm" to s.ssRsrp,"SS-RSRQ dB" to s.ssRsrq,"SS-SINR dB" to s.ssSinr,"CSI-RSRP dBm" to s.csiRsrp,"CSI-RSRQ dB" to s.csiRsrq,"CSI-SINR dB" to s.csiSinr) +
                if (Build.VERSION.SDK_INT >= 30 && bands.isNotEmpty()) " · Band n" + bands.joinToString("/n") else "")
        }
        this is CellInfoGsm -> with(cellIdentity) { val s=cellSignalStrength;cell("GSM",mccString,mncString,lac,cid.known()?.toLong(),bsic,arfcn,s,metrics("TA" to s.timingAdvance)) }
        this is CellInfoWcdma -> with(cellIdentity) { cell("WCDMA",mccString,mncString,lac,cid.known()?.toLong(),psc,uarfcn,cellSignalStrength,
                if (Build.VERSION.SDK_INT>=30) metrics("Ec/No dB" to cellSignalStrength.ecNo) else "") }
        Build.VERSION.SDK_INT>=29 && this is CellInfoTdscdma -> with(cellIdentity) {cell("TD-SCDMA",mccString,mncString,lac,cid.known()?.toLong(),cpid,uarfcn,cellSignalStrength,metrics("RSCP dBm" to cellSignalStrength.rscp)) }
        this is CellInfoCdma -> with(cellIdentity) {val s=cellSignalStrength;cell("CDMA",null,null,networkId,basestationId.known()?.toLong(),systemId,null,s,metrics("CDMA dBm" to s.cdmaDbm,"EVDO dBm" to s.evdoDbm)) }
        else -> null
    }
}

@SuppressLint("MissingPermission")
private suspend fun subscriptionCells(ctx:Context,tm:TelephonyManager): List<CellInfo>? {
    if (Build.VERSION.SDK_INT<29) return null
    return withTimeoutOrNull(3500) {
        suspendCancellableCoroutine { cont ->
            try {
                tm.requestCellInfoUpdate(ctx.mainExecutor,object:TelephonyManager.CellInfoCallback(){
                    override fun onCellInfo(cellInfo:MutableList<CellInfo>) { if(cont.isActive) cont.resume(cellInfo) }
                    override fun onError(errorCode:Int, detail:Throwable?) { if(cont.isActive) cont.resume(null) }
                })
            } catch(e:RuntimeException){if(cont.isActive) cont.resume(null)}
        }
    }
}

@SuppressLint("MissingPermission")
suspend fun readRadio(ctx:Context): RadioFrame = withContext(Dispatchers.IO) {
    val tm=ctx.getSystemService(TelephonyManager::class.java)
    val cm=ctx.getSystemService(ConnectivityManager::class.java)
    val subscribed=ctx.allowed(Manifest.permission.READ_PHONE_STATE)
    val location=ctx.allowed(Manifest.permission.ACCESS_FINE_LOCATION)
    val subscriptions=if(subscribed) runCatching { ctx.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty() }.getOrDefault(emptyList()) else emptyList()
    val dataSubscription = runCatching {
        if (Build.VERSION.SDK_INT >= 30) SubscriptionManager.getActiveDataSubscriptionId()
        else SubscriptionManager.getDefaultDataSubscriptionId()
    }.getOrNull()
    val activeDataSim = subscriptions.firstOrNull { it.subscriptionId == dataSubscription }?.let { "SIM ${it.simSlotIndex + 1}" }
    val time=System.currentTimeMillis()
    val sims=mutableListOf<SimSignal>()
    val rows=mutableListOf<RadioCell>()
    var missing=false
    if(tm!=null) {
        val reads=subscriptions.map { sub -> async {
            val mgr=tm.createForSubscriptionId(sub.subscriptionId)
            val slot="SIM ${sub.simSlotIndex+1}"
            val strength=runCatching {
                val signal=mgr.signalStrength
                if(signal!=null && Build.VERSION.SDK_INT>=29) signal.cellSignalStrengths.mapNotNull { s ->
                    s.dbm.takeIf { it in -160..-1 }?.let { "${s.javaClass.simpleName.removePrefix("CellSignalStrength")} $it dBm" } }.joinToString(" · ")
                else signal?.let { "Уровень ${it.level}/4" }.orEmpty()
            }.getOrDefault("")
            val summary=SimSignal(slot,sub.carrierName?.toString().orEmpty(),runCatching { mgr.isNetworkRoaming }.getOrDefault(false),
                runCatching { mgr.dataState }.getOrNull(),runCatching { mgr.serviceState?.state }.getOrNull(),strength)
            summary to if(location) subscriptionCells(ctx,mgr) else null
        } }.awaitAll()
        for((summary, cells) in reads) {
            sims+=summary
            if(cells.isNullOrEmpty()) missing=true else rows+=cells.mapNotNull { it.row(summary.name,System.currentTimeMillis()) }
        }
        if(rows.isEmpty() && location) rows+=runCatching { tm.allCellInfo.orEmpty().mapNotNull { it.row("Общий кэш",System.currentTimeMillis()) } }.getOrDefault(emptyList())
    }
    val wifi=runCatching {
        val connected=cm?.allNetworks?.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)==true } == true
        val w=ctx.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
        if(connected && w!=null && w.rssi in -126..-1) WifiReading(w.ssid.trim('"').takeUnless { it=="<unknown ssid>" }.orEmpty(),w.rssi,w.frequency,w.linkSpeed) else null
    }.getOrNull()
    val valid=cm?.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)==true
    val linkDetails = runCatching {
        val network = cm?.allNetworks?.firstOrNull {
            val capabilities = cm.getNetworkCapabilities(it)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        val properties = network?.let { cm?.getLinkProperties(it) }
        buildList {
            properties?.linkAddresses?.takeIf { it.isNotEmpty() }?.let { addresses ->
                add("IP " + addresses.joinToString { "${it.address.hostAddress}/${it.prefixLength}" })
            }
            properties?.dnsServers?.takeIf { it.isNotEmpty() }?.let { servers ->
                add("DNS " + servers.joinToString { it.hostAddress.orEmpty() })
            }
            properties?.routes?.filter { it.isDefaultRoute }?.mapNotNull { route ->
                route.gateway?.takeUnless { it.isAnyLocalAddress }?.hostAddress
            }
                ?.distinct()?.takeIf { it.isNotEmpty() }?.let { add("Шлюз " + it.joinToString()) }
            val dhcp = if (network != null) ctx.applicationContext.getSystemService(WifiManager::class.java)?.dhcpInfo else null
            dhcp?.serverAddress?.takeIf { it != 0 }?.let { address ->
                add("DHCP " + (0..3).joinToString(".") { ((address ushr (it * 8)) and 255).toString() })
            }
        }.joinToString(" · ")
    }.getOrDefault("")
    val note=listOfNotNull(if(!subscribed) "Для отдельных SIM разрешите доступ к телефону." else null,
        if(!location) "Для идентификаторов сот нужна точная геопозиция." else null,
        if(subscribed && subscriptions.isEmpty()) "Активные SIM недоступны." else null,
        if(missing && location) "Android вернул неполные данные. Общий кэш показан без привязки к SIM." else null, linkDetails.takeIf { it.isNotEmpty() }).joinToString("\n")
    RadioFrame(System.currentTimeMillis(),sims,rows,wifi,valid,note,activeDataSim)
}

