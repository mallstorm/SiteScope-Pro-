package pro.sitescope.app.radio

import android.content.*
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import pro.sitescope.app.net.SpeedEngine
import pro.sitescope.app.ui.ToolActivity
import pro.sitescope.app.ui.toast
import java.net.URL

@Composable
fun RadioOptions(p:SharedPreferences){
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    var host by rememberSaveable { mutableStateOf(p.getString("testUrl","https://www.google.com/generate_204").orEmpty()) }
    var latency by rememberSaveable {mutableStateOf("")}
    var busy by remember{mutableStateOf(false)}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)){
        Text("Проверка подключения",style=MaterialTheme.typography.titleMedium)
        OutlinedTextField(host,{host=it},Modifier.fillMaxWidth(),label={Text("URL для проверки HTTP")},singleLine=true)
        Row(Modifier.horizontalScroll(rememberScrollState())){
            TextButton({scope.launch{
                val url=runCatching{URL(host.trim()).takeIf{it.protocol in listOf("http","https") && it.host.isNotBlank()}}.getOrNull()
                if(url==null){latency="Проверьте URL";return@launch}
                busy=true;p.edit().putString("testUrl",url.toString()).apply()
                try {
                    val values=withContext(Dispatchers.IO){val server=SpeedEngine.Server("radio","HTTP","",url.toString(),"")
                        List(3){SpeedEngine.pingOnce(server)}}
                    val s=SpeedEngine.stats(values,values.size)
                    val tested=System.currentTimeMillis()
                    latency=if(s.ping<0)"Узел не ответил" else "Медиана ${s.ping.toInt()} мс · джиттер ${s.jitter.toInt()} мс · потери ${s.loss.toInt()}%"
                    ctx.getSharedPreferences("radio-widget",0).edit().putString("latency",s.ping.takeIf{it>=0}?.toInt()?.toString()).putLong("latency-time",tested).apply();RadioWidget.refresh(ctx)
                }finally{busy=false}
            }},enabled=!busy){Text(if(busy)"Измерение…" else "Измерить задержку")}
            TextButton({ctx.startActivity(Intent(ctx,ToolActivity::class.java).putExtra("screen","network").putExtra("tab",3))}){Text("Тест скорости")}
        }
        if(latency.isNotBlank())Text(latency)
        Text("Запись и оповещения",style=MaterialTheme.typography.titleMedium)
        SettingInt(p,"interval","Пауза между замерами, с",15,5..300)
        SettingToggle(p,"weak","Слабый сигнал обслуживающей соты")
        SettingInt(p,"threshold","Порог, dBm",-110,-150..-40)
        SettingToggle(p,"internet","Потеря доступа в интернет")
        SettingToggle(p,"service","Потеря обслуживания SIM")
        SettingToggle(p,"roaming","Вход в роуминг")
        SettingToggle(p,"data","Отключение мобильных данных")
        SettingToggle(p,"slow","Низкая скорость по завершённому тесту")
        SettingInt(p,"speedMin","Порог загрузки, Мбит/с",5,1..1000)
        SettingToggle(p,"quiet","Тихие часы")
        SettingInt(p,"quietStart","Начало тихих часов",22,0..23)
        SettingInt(p,"quietEnd","Конец тихих часов",8,0..23)
        Text("События связи отслеживаются во время записи. Виджет показывает время последнего измерения.",style=MaterialTheme.typography.bodySmall)
        TextButton({runCatching{ctx.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))}.onFailure{toast(ctx,"Настройки сети недоступны")}}){Text("Системные настройки сети")}
    }
}

@Composable
private fun SettingToggle(p:SharedPreferences,key:String,title:String){
    var enabled by remember{mutableStateOf(p.getBoolean(key,false))}
    Row(Modifier.fillMaxWidth(),verticalAlignment=androidx.compose.ui.Alignment.CenterVertically){
        Text(title,Modifier.weight(1f));Switch(enabled,{enabled=it;p.edit().putBoolean(key,it).apply()})
    }
}
@Composable
private fun SettingInt(p:SharedPreferences,key:String,title:String,default:Int,range:IntRange){
    var text by rememberSaveable{mutableStateOf(p.getInt(key,default).toString())}
    OutlinedTextField(text,{text=it;it.toIntOrNull()?.takeIf{v->v in range}?.let{v->p.edit().putInt(key,v).apply()}},label={Text(title)},singleLine=true,isError=text.toIntOrNull() !in range)
}

