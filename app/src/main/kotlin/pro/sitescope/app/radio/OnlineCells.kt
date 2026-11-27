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

private fun lookup(key:String, fix:Pin?, ids:List<String>):List<Pin>{
    val url=Uri.parse("https://opencellid.org/cell/"+(if(fix==null)"get" else "getInArea")).buildUpon()
        .appendQueryParameter("key",key).appendQueryParameter("format","json")
    if(fix!=null){
        val halfLat=0.009
        val halfLon=(halfLat/cos(Math.toRadians(fix.lat)).coerceAtLeast(0.01)).coerceAtMost(0.9)
        val bbox=listOf((fix.lat-halfLat).coerceAtLeast(-90.0),(fix.lon-halfLon).coerceAtLeast(-180.0),(fix.lat+halfLat).coerceAtMost(90.0),(fix.lon+halfLon).coerceAtMost(180.0))
        url.appendQueryParameter("BBOX",bbox.joinToString(",")).appendQueryParameter("limit","50")
    }else{
        require(ids.size==4 && ids.all { it.all(Char::isDigit) && it.isNotEmpty() }){"Введите MCC MNC LAC/TAC CID через пробел"}
        listOf("mcc","mnc","lac","cellid").forEachIndexed{i,k->url.appendQueryParameter(k,ids[i])}
    }
    val conn=URL(url.build().toString()).openConnection() as HttpURLConnection
    try{
        conn.instanceFollowRedirects=false;conn.connectTimeout=7000;conn.readTimeout=9000
        val code=conn.responseCode
        require(code==200) {"HTTP $code"}
        val reply=conn.inputStream.bufferedReader().use{reader->
            val out=StringBuilder();val buf=CharArray(4096)
            while(true){val n=reader.read(buf);if(n<0)break;require(out.length+n<=512000){"Слишком большой ответ"};out.append(buf,0,n)}
            JSONObject(out.toString())
        }
        if(reply.has("error") || reply.has("code"))error("Сервис отклонил запрос. Проверьте ключ, параметры и лимит.")
        val list=reply.optJSONArray("cells")
        val records=if(list!=null)(0 until list.length()).mapNotNull(list::optJSONObject) else listOf(reply)
        return records.mapNotNull{o->
            val y=o.optDouble("lat");val x=o.optDouble("lon")
            if(y !in -90.0..90.0 || x !in -180.0..180.0) null else Pin(y,x,"${o.optString("radio")} · ${o.optString("mcc")}/${o.optString("mnc")} · CID ${o.optString("cellid")} · OpenCellID")
        }
    }finally{conn.disconnect()}
}

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
            pins=withContext(Dispatchers.IO){lookup(key.trim(),fix,ids.trim().split(Regex("\\s+")))}
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

