package pro.sitescope.app.radio

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.*
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.*

private fun lookup(key: String, fix: Pin?, ids: List<String>, radio: String? = null): List<JSONObject> {
    val url=Uri.parse("https://opencellid.org/cell/"+(if(fix==null)"get" else "getInArea")).buildUpon()
        .appendQueryParameter("key",key).appendQueryParameter("format","json")
    if(fix!=null){
        val halfLat=0.008
        val halfLon=(halfLat/cos(Math.toRadians(fix.lat)).coerceAtLeast(0.01)).coerceAtMost(0.9)
        val bbox=listOf((fix.lat-halfLat).coerceAtLeast(-90.0),(fix.lon-halfLon).coerceAtLeast(-180.0),(fix.lat+halfLat).coerceAtMost(90.0),(fix.lon+halfLon).coerceAtMost(180.0))
        url.appendQueryParameter("BBOX",bbox.joinToString(",")).appendQueryParameter("limit","50")
    }else{
        require(ids.size==4 && ids.all { it.all(Char::isDigit) && it.isNotEmpty() }){"Введите MCC MNC LAC/TAC CID через пробел"}
        listOf("mcc","mnc","lac","cellid").forEachIndexed{i,k->url.appendQueryParameter(k,ids[i])}
        if (radio != null) url.appendQueryParameter("radio", radio)
    }
    val conn=URL(url.build().toString()).openConnection() as HttpURLConnection
    try{
        conn.instanceFollowRedirects=false;conn.connectTimeout=7000;conn.readTimeout=9000
        val code=conn.responseCode
        require(code==200) {when (code) {
            401, 403 -> "OpenCelliD отклонил API-ключ"
            429 -> "Достигнут лимит запросов OpenCelliD"
            else -> "OpenCelliD · HTTP $code"
        }}
        val reply=conn.inputStream.bufferedReader().use{reader->
            val out=StringBuilder();val buf=CharArray(4096)
            while(true){val n=reader.read(buf);if(n<0)break;require(out.length+n<=512000){"Слишком большой ответ"};out.append(buf,0,n)}
            JSONObject(out.toString())
        }
        if (reply.optInt("code") == 1) return emptyList()
        if(reply.has("error") || reply.has("code"))error("Сервис отклонил запрос. Проверьте ключ, параметры и лимит.")
        val list=reply.optJSONArray("cells")
        return if (list != null) (0 until minOf(list.length(), 50)).mapNotNull(list::optJSONObject) else listOf(reply)
    }finally{conn.disconnect()}
}

private val openCellRadios = mapOf("G" to "GSM", "W" to "UMTS", "L" to "LTE", "N" to "NR", "C" to "CDMA")

internal fun openCellPosition(row: JSONObject, expected: StationKey? = null): StationPosition? {
    val rat = openCellRadios.entries.firstOrNull { it.value.equals(row.optString("radio"), true) }?.key ?: return null
    val mcc = row.optString("mcc")
    val rawMnc = row.optString("mnc")
    if (!rawMnc.matches(Regex("[0-9]{1,3}"))) return null
    val mnc = if (expected != null && rawMnc.toInt() == expected.mnc.toInt()) expected.mnc else rawMnc.padStart(2, '0')
    val id = StationKey.of(rat, mcc, mnc, row.optString("lac").toLongOrNull(), row.optString("cellid").toLongOrNull()) ?: return null
    if (expected != null && id != expected) return null
    val lat = row.optDouble("lat")
    val lon = row.optDouble("lon")
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    val tower = Tower(id.rat, id.mcc, id.mnc, id.area, id.cid, null, null, lat, lon, null, "OpenCelliD")
    return StationPosition(tower, online = true, samples = row.optInt("samples").takeIf { it > 0 })
}

internal fun lookupStation(key: String, id: StationKey): StationPosition? {
    val radio = openCellRadios[id.rat] ?: return null
    return lookup(key, null, listOf(id.mcc, id.mnc, id.area.toString(), id.cid.toString()), radio)
        .firstNotNullOfOrNull { openCellPosition(it, id) }
}

internal fun lookupStationArea(key: String, fix: Pin): List<StationPosition> = lookup(key, fix, emptyList())
    .mapNotNull { openCellPosition(it) }

@Composable
fun OnlineCells(){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var key by remember{mutableStateOf("")}
    var ids by remember{mutableStateOf("")}
    var pins by remember{mutableStateOf<List<Pin>>(emptyList())}
    var note by remember{mutableStateOf("")}
    var busy by remember{mutableStateOf(false)}
    fun fetch(near:Boolean){scope.launch{
        busy=true
        try{
            val loc=if(near) currentFix(ctx) ?: error("Нет свежей геопозиции") else null
            val fix=loc?.let{Pin(it.latitude,it.longitude,"Я")}
            pins=withContext(Dispatchers.IO){lookup(key.trim(),fix,ids.trim().split(Regex("\\s+"))).mapNotNull { row ->
                val pos = openCellPosition(row) ?: return@mapNotNull null
                val t = pos.tower
                Pin(t.lat!!, t.lon!!, "${openCellRadios[t.rat]} · ${t.mcc}/${t.mnc} · CID ${t.cid} · OpenCelliD")
            }}
            note=if(near)"Получено ${pins.size} сот, до 50 за запрос" else if(pins.isEmpty())"Запись не найдена" else pins.first().title
        }catch(e:Exception){pins=emptyList();note=if(e is IllegalArgumentException || e is IllegalStateException)e.message.orEmpty() else "Сервис недоступен"}
        finally{busy=false}
    }}
    Column{
        Text("OpenCellID",style=MaterialTheme.typography.titleMedium)
        OutlinedTextField(key,{key=it},Modifier.fillMaxWidth(),label={Text("Свой API-ключ на эту сессию")},singleLine=true,visualTransformation=PasswordVisualTransformation())
        OutlinedTextField(ids,{ids=it},Modifier.fillMaxWidth(),label={Text("MCC MNC LAC/TAC CID")},singleLine=true)
        TextButton({fetch(false)},enabled=!busy && key.isNotBlank() && ids.isNotBlank()){Text("Поиск по параметрам")}
        TextButton({fetch(true)},enabled=!busy && key.isNotBlank()){Text("Найти по моей геопозиции")}
        if(note.isNotBlank())Text(note)
        if(pins.isNotEmpty()){FieldMap(pins.first(),pins);Text("Оценочные позиции сот · OpenCelliD (opencellid.org), CC BY-SA 4.0")}
    }
}

