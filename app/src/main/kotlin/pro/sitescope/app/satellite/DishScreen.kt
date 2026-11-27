package pro.sitescope.app.satellite

import android.Manifest
import android.hardware.GeomagneticField
import android.location.Geocoder
import android.util.AtomicFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import pro.sitescope.app.radio.*
import java.io.File
import java.io.InputStream
import java.net.URL
import java.util.Locale

fun degrees(value: Double?) = value?.let { "%.1f°".format(Locale.getDefault(),it) } ?: "—"

internal fun readCatalog(source: InputStream): Pair<ByteArray,List<DishEntry>> {
    val bytes = source.use { input ->
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16384)
        while(true) {
            val n=input.read(buf)
            if(n<0) break
            require(out.size()+n <= 8_000_000) { "Лимит файла 8 МБ" }
            out.write(buf,0,n)
        }
        out.toByteArray()
    }
    val rows = DishCatalogue.parse(bytes.toString(Charsets.UTF_8).lines())
    require(rows.isNotEmpty()) { "Нужен CSV, satellites.xml или lamedb версии 4" }
    return bytes to rows
}

@Composable
fun DishScreen() {
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    val prefs=remember { ctx.getSharedPreferences("dish-install",0) }
    var lat by rememberSaveable { mutableStateOf(prefs.getString("lat","").orEmpty()) }
    var lon by rememberSaveable { mutableStateOf(prefs.getString("lon","").orEmpty()) }
    var orbit by rememberSaveable { mutableStateOf(prefs.getString("orbit","36.0").orEmpty()) }
    var offset by rememberSaveable { mutableStateOf(prefs.getString("offset","0").orEmpty()) }
    var smooth by rememberSaveable { mutableStateOf(prefs.getBoolean("smooth",true)) }
    val slots = remember { runCatching { ctx.assets.open("satellite-slots.csv").bufferedReader().use { DishCatalogue.parse(it.readLines()) } }.getOrDefault(DishCatalogue.slots) }
    var access by rememberSaveable { mutableStateOf("Все") }
    var category by rememberSaveable { mutableStateOf("Все") }
    var provider by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf(prefs.getString("catalogSource", "https://raw.githubusercontent.com/openatv/enigma2-plugin-settings-defaultsat/master/etc/enigma2/lamedb").orEmpty()) }
    var query by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var visible by rememberSaveable { mutableStateOf(false) }
    var customOnly by rememberSaveable { mutableStateOf(prefs.getBoolean("customOnly", false)) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(false) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet()).orEmpty().toSet()) }
    var camera by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var catalog by remember { mutableStateOf<List<DishEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val cache=remember { AtomicFile(File(ctx.filesDir,"dish-catalog.txt")) }
    val site=point(lat,lon)
    val orbital=orbit.trim().replace(',','.').toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
    val angles=if(site != null && orbital != null) dishAngles(site,orbital) else null
    val declination=remember(site) { site?.let { GeomagneticField(it.lat.toFloat(),it.lon.toFloat(),0f,System.currentTimeMillis()).declination.toDouble() } ?: 0.0 }
    val heading=if(tab==0 || (tab==1 && follow)) attitude(declination+(offset.replace(',','.').toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: 0.0),smooth) else null
    LaunchedEffect(Unit) {
        catalog=withContext(Dispatchers.IO) { runCatching { readCatalog(cache.openRead()).second }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(lat,lon,orbit,offset,smooth,customOnly) {
        prefs.edit().putString("lat",lat).putString("lon",lon).putString("orbit",orbit).putString("offset",offset).putBoolean("smooth",smooth).putBoolean("customOnly",customOnly).apply()
    }
    fun import(open: ()->InputStream) {
        if(busy) return
        busy=true
        scope.launch {
            try {
                catalog=withContext(Dispatchers.IO) {
                    val (bytes,entries)=readCatalog(open())
                    val out=cache.startWrite()
                    try { out.write(bytes); cache.finishWrite(out) } catch(e:Exception) { cache.failWrite(out); throw e }
                    entries
                }
                customOnly = true
                status="Загружено записей: ${catalog.size}"
            } catch(e:Exception) { status="Ошибка каталога: ${e.localizedMessage}" } finally { busy=false }
        }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) import { requireNotNull(ctx.contentResolver.openInputStream(uri)) }
    }
    fun locate() {
        scope.launch {
            status="Ищу местоположение…"
            val fix=currentFix(ctx)
            if(fix==null) status="Нет свежих координат. Проверьте геолокацию или введите точку вручную."
            else { lat=fix.latitude.toString();lon=fix.longitude.toString();status="${fix.provider} · ±${fix.accuracy.toInt()} м" }
        }
    }
    val location=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if(it.values.any { ok -> ok }) locate() else status="Можно указать точку вручную" }
    val cameraPerm=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camera=it; if(!it) status="Для визира нужен доступ к камере" }
    val shown=remember(query,catalog,visible,favoritesOnly,favorites,site,access,category,provider,customOnly) {
        (if (customOnly) catalog else slots+catalog).distinct().filter {
            (access == "Все" || it.access == access) &&
            (category == "Все" || it.kind == category) &&
            (provider.isBlank() || it.provider.contains(provider.trim(), true)) &&
            (!favoritesOnly || it.orbital.toString() in favorites) &&
                (!visible || site==null || dishAngles(site,it.orbital).elevation>0) &&
                (listOf(it.name,it.channel,it.provider,it.tuning,orbitalLabel(it.orbital)).any { s -> s.contains(query.trim(),true) } ||
                 slots.any { slot -> kotlin.math.abs(slot.orbital-it.orbital)<0.01 && slot.name.contains(query.trim(),true) })
        }
    }
    LazyColumn(contentPadding=PaddingValues(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        item { Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            listOf("Наведение","Карта","Каталог").forEachIndexed { i,t -> FilterChip(tab==i,{tab=i},{Text(t)}) }
        } }
        if(status.isNotBlank()) item { Text(status,style=MaterialTheme.typography.bodySmall) }
        if(tab != 2) {
            item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(lat,{lat=it},Modifier.weight(1f),label={Text("Широта")},singleLine=true)
                OutlinedTextField(lon,{lon=it},Modifier.weight(1f),label={Text("Долгота")},singleLine=true)
            } }
            item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(orbit,{orbit=it},Modifier.weight(1f),label={Text("Орбита, ° E (+) / W (−)")},singleLine=true)
                TextButton({ if(locationPermissions.any(ctx::allowed)) locate() else location.launch(locationPermissions) }) {Text("Моя точка")}
            } }
            if(angles!=null) item {
                Text("Азимут ${degrees(angles.azimuth)} · подъём ${degrees(angles.elevation)}",style=MaterialTheme.typography.titleMedium)
                Text("Магнитный ${degrees(angles.azimuth?.let { (it-declination+360)%360 })} · LNB ${degrees(angles.skew)}")
                Text("LNB: геометрический поворот линейной поляризации. Знак зависит от стороны наблюдения и шкалы конвертера.",style=MaterialTheme.typography.bodySmall)
                if(angles.elevation<=0) Text("Спутник ниже горизонта",color=MaterialTheme.colorScheme.error)
            } else item { Text("Укажите широту, долготу и орбитальную позицию.") }
        }
        when(tab) {
            0 -> {
                if(angles!=null && angles.elevation>0) item {
                    Sight(angles,heading,camera)
                    TextButton({ if(camera) camera=false else if(ctx.allowed(Manifest.permission.CAMERA)) camera=true else cameraPerm.launch(Manifest.permission.CAMERA) }) {Text(if(camera) "Компас" else "Визир с камерой")}
                }
                item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(offset,{offset=it},Modifier.weight(1f),label={Text("Поправка компаса, °")},singleLine=true)
                    FilterChip(smooth,{smooth=!smooth},{Text("Сглаживание")})
                } }
            }
            1 -> {
                item {
                    OutlinedTextField(address,{address=it},Modifier.fillMaxWidth(),label={Text("Город, улица")},singleLine=true)
                    TextButton({scope.launch {
                        status="Поиск адреса…"
                        @Suppress("DEPRECATION") val result=withContext(Dispatchers.IO) {runCatching { Geocoder(ctx).getFromLocationName(address,1)?.firstOrNull() }.getOrNull()}
                        if(result==null) status="Адрес не найден" else {lat=result.latitude.toString(); lon=result.longitude.toString();status=result.getAddressLine(0).orEmpty()}
                    }},enabled=address.isNotBlank()) {Text("Найти адрес")}
                    FilterChip(follow,{follow=!follow},{Text("По компасу")})
                    val origin=Pin(site?.lat ?: 0.0,site?.lon ?: 0.0,"Точка установки")
                    val end=angles?.azimuth?.let { org.osmdroid.util.GeoPoint(origin.lat,origin.lon).destinationPoint(1500.0,it) }
                    val ray=if(end!=null && angles.elevation>0) listOf(origin,Pin(end.latitude,end.longitude,"Направление")) else emptyList()
                    FieldMap(origin,if(site==null) emptyList() else listOf(origin),ray,{lat=it.lat.toString();lon=it.lon.toString()},if(follow) -(heading?.heading?.toFloat() ?: 0f) else 0f)
                    Text("Удерживайте точку на карте, чтобы перенести место установки.")
                }
            }
            2 -> {
                item {
                    OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),label={Text("Спутник, канал, пакет или частота")},singleLine=true)
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        FilterChip(customOnly,{customOnly=!customOnly},{Text("Мой каталог")},enabled=catalog.isNotEmpty())
                        FilterChip(visible,{visible=!visible},{Text("Над горизонтом")},enabled=site!=null)
                        FilterChip(favoritesOnly,{favoritesOnly=!favoritesOnly},{Text("Избранное")})
                    }
                    pro.sitescope.app.radio.Chips(listOf("Все", "FTA", "CA"), access) { access = it }
                    pro.sitescope.app.radio.Chips(listOf("Все", "ТВ", "Радио"), category) { category = it }
                    OutlinedTextField(provider, { provider = it }, Modifier.fillMaxWidth(), label = { Text("Провайдер / пакет") }, singleLine = true)
                    OutlinedTextField(source, { source = it }, Modifier.fillMaxWidth(), label = { Text("HTTPS-источник каталога") }, singleLine = true)
                    TextButton({picker.launch(arrayOf("*/*"))},enabled=!busy) {Text("Импорт CSV / XML / lamedb 4")}
                    TextButton({import {
                        val url = URL(source.trim())
                        require(url.protocol == "https") { "Нужен HTTPS-источник" }
                        prefs.edit().putString("catalogSource", source.trim()).apply()
                        val conn=url.openConnection()
                        conn.connectTimeout=8000;conn.readTimeout=12000;conn.getInputStream()
                    }},enabled=!busy) { Text("Обновить каталог") }
                    Text("Записей: ${shown.size}. Импорт заменяет пользовательский каталог.")
                    Text("FTA/CA — отметка исходного списка. Спутники и записи без этих данных видны в фильтре «Все».", style = MaterialTheme.typography.bodySmall)
                }
                items(shown) { row ->
                    Row {
                        TextButton({orbit=row.orbital.toString();tab=0},Modifier.weight(1f)) {
                            Text(listOf(row.channel.ifBlank {row.name},row.provider,row.tuning,row.access,row.kind,if(row.channel.isNotEmpty()) orbitalLabel(row.orbital) else "").filter(String::isNotBlank).joinToString(" · "))
                        }
                        val key=row.orbital.toString()
                        TextButton({favorites=if(key in favorites) favorites-key else favorites+key; prefs.edit().putStringSet("favorites",favorites).apply()}) {Text(if(key in favorites) "★" else "☆")}
                    }
                }
            }
        }
    }
}

