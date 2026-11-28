package pro.sitescope.app.satellite

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.compose.ui.unit.sp
import android.hardware.GeomagneticField
import android.location.Geocoder
import android.util.AtomicFile
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.Alignment
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

internal fun InputStream.readBounded(): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16_384)
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        require(out.size() + count <= 8_000_000) { "Лимит файла 8 МБ" }
        out.write(buffer, 0, count)
    }
    return out.toByteArray()
}

internal fun readCatalog(source: InputStream): Pair<ByteArray, List<DishEntry>> {
    val bytes = source.use { it.readBounded() }
    val rows = DishCatalogue.parse(bytes.toString(Charsets.UTF_8).lines())
    require(rows.isNotEmpty()) { "Нужен CSV, satellites.xml или lamedb версии 4" }
    return bytes to rows
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DishScreen(onFullscreen: (Boolean) -> Unit = {}) {
    val ctx=LocalContext.current
    val scope=rememberCoroutineScope()
    val prefs=remember { ctx.getSharedPreferences("dish-install",0) }
    var lat by rememberSaveable { mutableStateOf(prefs.getString("lat","").orEmpty()) }
    var lon by rememberSaveable { mutableStateOf(prefs.getString("lon","").orEmpty()) }
    var orbit by rememberSaveable { mutableStateOf(prefs.getString("orbit","36.0").orEmpty()) }
    var offset by rememberSaveable { mutableStateOf(prefs.getString("offset","0").orEmpty()) }
    var smooth by rememberSaveable { mutableStateOf(prefs.getBoolean("smooth",true)) }
    val bundledSlots = remember { runCatching { ctx.assets.open("satellite-slots.csv").bufferedReader().use { DishCatalogue.parse(it.readLines()) } }.getOrDefault(DishCatalogue.slots) }
    var access by rememberSaveable { mutableStateOf("Все") }
    var category by rememberSaveable { mutableStateOf("Все") }
    var provider by rememberSaveable { mutableStateOf("") }
    var source by rememberSaveable { mutableStateOf(prefs.getString("catalogSource", "https://raw.githubusercontent.com/openatv/enigma2-plugin-settings-defaultsat/master/etc/enigma2/lamedb").orEmpty()) }
    var query by rememberSaveable { mutableStateOf("") }
    var address by rememberSaveable { mutableStateOf("") }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var aired by rememberSaveable { mutableStateOf(false) }
    var radioMode by rememberSaveable { mutableStateOf("Каналы") }
    var activeTuning by rememberSaveable { mutableStateOf(prefs.getString("activeTuning", "").orEmpty()) }
    var fullMap by rememberSaveable { mutableStateOf(false) }
    var layer by remember {
        mutableStateOf(FieldMapLayer.entries.firstOrNull { it.name == prefs.getString("mapLayer", "") } ?: FieldMapLayer.Streets)
    }
    var centerRequest by remember { mutableIntStateOf(0) }
    val fullscreen = tab == 1 && fullMap
    LaunchedEffect(fullscreen) { onFullscreen(fullscreen) }
    BackHandler(enabled = fullscreen) { fullMap = false }
    var visible by rememberSaveable { mutableStateOf(false) }
    var customOnly by rememberSaveable { mutableStateOf(prefs.getBoolean("customOnly", false)) }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var follow by rememberSaveable { mutableStateOf(false) }
    var favorites by remember { mutableStateOf(prefs.getStringSet("favorites", emptySet()).orEmpty().toSet()) }
    var locationOpen by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var catalogMode by rememberSaveable { mutableIntStateOf(if(customOnly)2 else 0) }
    var catalogTools by rememberSaveable { mutableStateOf(false) }
    var selectedTitle by rememberSaveable { mutableStateOf(prefs.getString("selectedTitle", "").orEmpty()) }
    var lowPass by rememberSaveable { mutableFloatStateOf(prefs.getFloat("lowPass",0.3f).coerceIn(0.1f,1f)) }
    var camera by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var catalog by remember { mutableStateOf<List<DishEntry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var locating by remember { mutableStateOf(false) }
    var geocoding by remember { mutableStateOf(false) }
    val cache=remember { AtomicFile(File(ctx.filesDir,"dish-catalog.txt")) }
    val site=point(lat,lon)
    val orbital=orbit.trim().replace(',','.').toDoubleOrNull()?.takeIf { it in -180.0..180.0 }
    val angles=if(site != null && orbital != null) dishAngles(site,orbital) else null
    val declination=remember(site) { site?.let { GeomagneticField(it.lat.toFloat(),it.lon.toFloat(),0f,System.currentTimeMillis()).declination.toDouble() } ?: 0.0 }
    val heading=if(camera || tab==0 || (tab==1 && follow)) attitude(declination+(offset.replace(',','.').toDoubleOrNull()?.takeIf { it in -180.0..180.0 } ?: 0.0),smooth,lowPass) else null
    LaunchedEffect(Unit) {
        catalog=withContext(Dispatchers.IO) { runCatching { readCatalog(cache.openRead()).second }.getOrDefault(emptyList()) }
    }
    LaunchedEffect(lat,lon,orbit,offset,smooth,customOnly,lowPass,selectedTitle,activeTuning,layer) {
        prefs.edit().putString("lat",lat).putString("lon",lon).putString("orbit",orbit).putString("offset",offset).putBoolean("smooth",smooth).putBoolean("customOnly",customOnly).putFloat("lowPass",lowPass).putString("selectedTitle",selectedTitle).putString("activeTuning",activeTuning).putString("mapLayer",layer.name).apply()
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
                catalogMode = 2
                status="Загружено записей: ${catalog.size}"
            } catch(e:Exception) { status="Ошибка каталога: ${e.localizedMessage}" } finally { busy=false }
        }
    }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) import { requireNotNull(ctx.contentResolver.openInputStream(uri)) }
    }
    fun locate() {
        if (locating) return
        locating = true
        scope.launch {
            try {
                status = "Ищу местоположение…"
                val fix = currentFix(ctx)
                if (fix == null) status = "Нет свежих координат. Проверьте геолокацию или введите точку вручную."
                else {
                    lat = fix.latitude.toString()
                    lon = fix.longitude.toString()
                    status = "${clock(fix.time)} · ±${fix.accuracy.toInt()} м"
                }
            } finally { locating = false }
        }
    }
    val location=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { if(it.values.any { ok -> ok }) locate() else status="Можно указать точку вручную" }
    val cameraPerm=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { camera=it; if(!it) status="Для визира нужен доступ к камере" }
    var discovered by remember { mutableStateOf<List<SatellitePage>>(emptyList()) }
    LaunchedEffect(aired) {
        if (!aired) discovered = withContext(Dispatchers.IO) { DishFeeds.satellites(ctx) }
    }
    val slots = remember(discovered, bundledSlots) {
        val knownPositions = discovered.map { it.orbit }.toSet()
        (discovered.map { DishEntry(it.name, it.orbit) } +
            bundledSlots.filterNot { it.orbital in knownPositions }).sortedBy { it.orbital }
    }
    val shown=remember(query,catalog,slots,visible,favoritesOnly,favorites,site,access,category,provider,catalogMode) {
        (when(catalogMode) {
            0 -> slots
            1 -> catalog.filter{it.provider.isNotBlank()}
            else -> catalog
        }).distinct().filter {
            (catalogMode==0 || access == "Все" || it.access == access) &&
            (catalogMode==0 || category == "Все" || it.kind == category) &&
            (catalogMode==0 || provider.isBlank() || it.provider.contains(provider.trim(), true)) &&
            (!favoritesOnly || it.orbital.toString() in favorites) &&
                (!visible || site==null || dishAngles(site,it.orbital).elevation>0) &&
                (listOf(it.name,it.channel,it.provider,it.tuning,orbitalLabel(it.orbital)).any { s -> s.contains(query.trim(),true) } ||
                 slots.any { slot -> kotlin.math.abs(slot.orbital-it.orbital)<0.01 && slot.name.contains(query.trim(),true) })
        }.let { rows -> if(catalogMode==1)rows.distinctBy{it.orbital to it.provider} else rows }
    }
    val chosenName = selectedTitle.ifBlank { slots.firstOrNull { it.orbital == orbital }?.name ?: "Орбитальная позиция" }
    if (aired) {
        DishFeedScreen(site, catalog, onChoose = { row ->
            orbit = row.orbit.toString()
            selectedTitle = row.satellite
            activeTuning = listOf(row.channel, row.tuning, row.network, row.note.takeUnless { it == row.tuning }.orEmpty())
                .filter(String::isNotBlank).joinToString(" · ")
            aired = false
            tab = 0
        }, onClose = { aired = false }, initialQuery = query, initialMode = radioMode)
        return
    }
    Column(Modifier.fillMaxSize()) {
        if (!fullscreen) Row(Modifier.fillMaxWidth().padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {
            listOf("Наведение","Карта","Каталог").forEachIndexed {i,label->
                TextButton({tab=i;camera=false},Modifier.weight(1f)) {
                    Text(label,color=if(tab==i)MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton({settingsOpen=true}) {Icon(Icons.Rounded.Settings,"Настройки наведения")}
        }
        if(status.isNotBlank() && !fullscreen)Text(status,Modifier.padding(horizontal=12.dp),style=MaterialTheme.typography.labelSmall)
        if(camera) {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(chosenName,Modifier.weight(1f),style=MaterialTheme.typography.titleSmall)
                TextButton({camera=false}) {Text("Закрыть визир")}
            }
            if(angles!=null && angles.elevation>0)Sight(angles,heading,true,Modifier.fillMaxWidth().weight(1f))
            else {Text("Задайте точку установки и выберите спутник над горизонтом.");Spacer(Modifier.weight(1f))}
        } else when(tab) {
            0 -> LazyColumn(Modifier.fillMaxSize(),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(start=12.dp,end=12.dp,bottom=16.dp)) {
                item {
                    Surface(onClick={locationOpen=true},color=MaterialTheme.colorScheme.surfaceContainer,shape=MaterialTheme.shapes.medium) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.Rounded.LocationOn,null)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("Точка установки",style=MaterialTheme.typography.labelSmall)
                                Text(site?.let{"%.5f, %.5f".format(it.lat,it.lon)} ?: "Задать GPS, адрес или координаты",style=MaterialTheme.typography.bodyMedium)
                            }
                            Icon(Icons.Rounded.ChevronRight,"Изменить точку")
                        }
                    }
                }
                item {
                    Surface(onClick={tab=2},shape=MaterialTheme.shapes.medium,color=MaterialTheme.colorScheme.secondaryContainer) {
                        Row(Modifier.fillMaxWidth().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                            Icon(Icons.Rounded.SatelliteAlt,null)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(chosenName,style=MaterialTheme.typography.titleSmall)
                                Text(orbital?.let(::orbitalLabel) ?: "Выбрать спутник",style=MaterialTheme.typography.bodySmall)
                            }
                            Icon(Icons.Rounded.Search,"Выбор спутника")
                        }
                    }
                }
                item {
                    if (activeTuning.isNotBlank()) Text(activeTuning, style = MaterialTheme.typography.bodyMedium)
                    if (angles != null) {
                        val az = angles.azimuth?.let { "${compassDirection(it)} · ${degrees(it)}" } ?: "Азимут не определён"
                        val relative = when {
                            angles.elevation > 0 -> "${degrees(angles.elevation)} над горизонтом"
                            else -> "${degrees(kotlin.math.abs(angles.elevation))} ниже горизонта"
                        }
                        val distance = "%,.0f км".format(angles.rangeKm)
                        Text("$az · $relative", style = MaterialTheme.typography.titleSmall)
                        Text("Расстояние $distance · от точки установки, по орбитальной позиции",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton({
                        if (locationPermissions.any(ctx::allowed)) locate() else location.launch(locationPermissions)
                    }, enabled = !locating) { Text("Рассчитать от моего GPS") }
                    TextButton({ radioMode = "Каналы"; aired = true }) {
                        Icon(Icons.Rounded.Search, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Найти канал и параметры вещания")
                    }
                }
                item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    DishAngleTile("Азимут",angles?.azimuth,0,Modifier.weight(1f))
                    DishAngleTile("Подъём",angles?.elevation,1,Modifier.weight(1f))
                    DishAngleTile("LNB",angles?.skew,2,Modifier.weight(1f))
                }}
                if(angles!=null && angles.elevation<=0)item {Text("Спутник ниже горизонта в выбранной точке",color=MaterialTheme.colorScheme.error)}
                item {DishCompass(angles?.azimuth?.takeIf{angles.elevation>0},heading?.heading,site!=null,belowHorizon=angles?.let{it.elevation<=0}==true)}
                if(heading?.accuracy==android.hardware.SensorManager.SENSOR_STATUS_UNRELIABLE)item {Text("Низкая точность компаса. Отойдите от металла и откалибруйте датчик.",color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
                item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton({if(ctx.allowed(Manifest.permission.CAMERA))camera=true else cameraPerm.launch(Manifest.permission.CAMERA)},Modifier.weight(1f),enabled=angles!=null && angles.elevation>0) {Icon(Icons.Rounded.CameraAlt,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("AR-визир")}
                    OutlinedButton({tab=1},Modifier.weight(1f)){Icon(Icons.Rounded.Map,null,Modifier.size(18.dp));Spacer(Modifier.width(6.dp));Text("Карта")}
                }}
                item {
                    Text("Магнитный азимут: ${degrees(angles?.azimuth?.let{(it-declination+360)%360})}",style=MaterialTheme.typography.labelMedium)
                    Text("LNB — геометрический поворот линейной поляризации. Знак зависит от стороны наблюдения и шкалы конвертера.",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            1 -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton({ fullMap = !fullMap }) {
                        Icon(if (fullscreen) Icons.Rounded.FullscreenExit else Icons.Rounded.Fullscreen,
                            if (fullscreen) "Выйти из полноэкранной карты" else "Развернуть карту")
                    }
                    Column(Modifier.weight(1f)) {
                        Text("Азимут ${degrees(angles?.azimuth)} · ${orbital?.let(::orbitalLabel) ?: "—"}",
                            style = MaterialTheme.typography.titleSmall)
                        Text(if (angles != null && angles.elevation <= 0) "Спутник ниже горизонта"
                            else "Удерживайте карту для выбора точки", style = MaterialTheme.typography.labelSmall)
                    }
                    IconButton({ follow = !follow }) {
                        Icon(Icons.Rounded.Explore, if (follow) "Север сверху" else "Вращать по компасу",
                            tint = if (follow) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton({ if (site == null) locationOpen = true else centerRequest++ },
                        modifier = Modifier) { Icon(Icons.Rounded.MyLocation, "К точке установки") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    FieldMapLayer.entries.forEach { option ->
                        FilterChip(layer == option, { layer = option }, { Text(option.title, fontSize = 12.sp) })
                    }
                    if (follow) Text(degrees(heading?.heading), Modifier.align(Alignment.CenterVertically),
                        style = MaterialTheme.typography.labelSmall)
                }
                val origin = site?.let { Pin(it.lat, it.lon, "Точка установки") } ?: Pin(18.0, 0.0, "")
                val azimuth = angles?.azimuth
                val ray = if (azimuth != null && angles.elevation > 0) {
                    val end = org.osmdroid.util.GeoPoint(origin.lat, origin.lon).destinationPoint(1500.0, azimuth)
                    listOf(origin, Pin(end.latitude, end.longitude, "Направление на спутник"))
                } else emptyList()
                if (site == null) TextButton({ locationOpen = true }, Modifier.padding(horizontal = 8.dp)) {
                    Text("GPS или адрес")
                }
                FieldMap(
                    origin, if (site == null) emptyList() else listOf(origin), ray,
                    onPick = { lat = it.lat.toString(); lon = it.lon.toString() },
                    orientation = if (follow) -(heading?.heading ?: 0.0).toInt().toFloat() else 0f,
                    modifier = Modifier.fillMaxWidth().weight(1f), layer = layer, recenter = centerRequest,
                    initialZoom = if (site == null) 2.0 else 15.0,
                )
            }
            2 -> LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(start=12.dp,end=12.dp,bottom=16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                item {
                    FilledTonalButton({ radioMode = "Каналы"; aired = true }, Modifier.fillMaxWidth()) {
                        Text("Каналы, пакеты и изменения эфира")
                    }
                    Chips(listOf("Спутники","Пакеты","Из файла"),listOf("Спутники","Пакеты","Из файла")[catalogMode]){ selection ->
                        if (selection == "Пакеты") { radioMode = "Пакеты"; aired = true }
                        else catalogMode = listOf("Спутники","Пакеты","Из файла").indexOf(selection)
                    }
                    OutlinedTextField(query,{query=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("Спутник, позиция, канал или пакет")},leadingIcon={Icon(Icons.Rounded.Search,null)})
                    Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                        FilterChip(visible,{visible=!visible},{Text("Над горизонтом")})
                        FilterChip(favoritesOnly,{favoritesOnly=!favoritesOnly},{Text("Избранные")})
                        FilterChip(catalogTools,{catalogTools=!catalogTools},{Text("Импорт и фильтры")})
                    }
                    if(catalogTools) {
                        TextButton({picker.launch(arrayOf("*/*"))},enabled=!busy){Icon(Icons.Rounded.UploadFile,null);Spacer(Modifier.width(6.dp));Text("Открыть CSV / XML / lamedb")}
                        OutlinedTextField(source,{source=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("HTTPS-адрес каталога")})
                        TextButton({
                            prefs.edit().putString("catalogSource",source.trim()).apply()
                            import {
                                val url=URL(source.trim());require(url.protocol=="https" && url.host.isNotBlank()){"Нужен HTTPS-адрес"}
                                url.openConnection().apply{connectTimeout=10_000;readTimeout=15_000}.getInputStream()
                            }
                        },enabled=!busy && source.isNotBlank()){Text(if(busy)"Загрузка…" else "Загрузить каталог")}
                        Chips(listOf("Все","FTA","CA"),access){access=it}
                        Chips(listOf("Все","ТВ","Радио"),category){category=it}
                        OutlinedTextField(provider,{provider=it},Modifier.fillMaxWidth(),singleLine=true,label={Text("Провайдер / пакет")})
                        Text("Пакеты и каналы берутся из вашего каталога. Неизвестная кодировка доступна с фильтром «Все».",style=MaterialTheme.typography.labelSmall)
                    }
                    if(visible && site==null)Text("Для фильтра горизонта задайте точку установки.",style=MaterialTheme.typography.labelSmall)
                    Text("${shown.size} записей${if(catalogMode==0)" · орбитальная позиция слева" else ""}",style=MaterialTheme.typography.labelSmall)
                    if(shown.isEmpty()) {
                        Text(if(catalogMode==1 && catalog.isEmpty())"Импортируйте каталог с названиями провайдеров для списка пакетов." else if(catalogMode==2 && catalog.isEmpty())"Откройте файл каталога или загрузите его по HTTPS." else "Нет совпадений. Измените поиск или фильтры.",style=MaterialTheme.typography.bodyMedium)
                        if(catalog.isEmpty() && catalogMode!=0)TextButton({picker.launch(arrayOf("*/*"))},enabled=!busy){Text("Выбрать файл")}
                    }
                }
                items(shown){row->
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                        Surface(onClick={orbit=row.orbital.toString();selectedTitle=if(catalogMode==1)row.provider else row.channel.ifBlank{row.name};activeTuning=row.tuning;tab=0},Modifier.weight(1f),color=MaterialTheme.colorScheme.surface,shape=MaterialTheme.shapes.small) {
                            Row(Modifier.padding(vertical=12.dp,horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                                Text(orbitalLabel(row.orbital),Modifier.width(72.dp),color=MaterialTheme.colorScheme.primary,style=MaterialTheme.typography.labelLarge)
                                Column {
                                    Text(if(catalogMode==1)row.provider else row.channel.ifBlank{row.name},style=MaterialTheme.typography.bodyMedium)
                                    if(catalogMode!=1 && (row.tuning.isNotBlank() || row.provider.isNotBlank()))Text(listOf(row.provider,row.tuning,row.access,row.kind).filter(String::isNotBlank).joinToString(" · "),style=MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        val key=row.orbital.toString()
                        IconButton({favorites=if(key in favorites)favorites-key else favorites+key;prefs.edit().putStringSet("favorites",favorites).apply()}) {Icon(if(key in favorites)Icons.Rounded.Star else Icons.Rounded.StarBorder,if(key in favorites)"Убрать из избранного" else "В избранное")}
                    }
                }
                item {
                    Text("Справочники каналов",style=MaterialTheme.typography.titleSmall,modifier=Modifier.padding(top=12.dp))
                    listOf("KingOfSat" to "https://en.kingofsat.net/","Satbeams" to "https://www.satbeams.com/","LyngSat" to "https://www.lyngsat.com/").forEach{(name,url)->
                        TextButton({runCatching{ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse(url)))}.onFailure{status="Не найден браузер для открытия ссылки"}}){Text(name);Spacer(Modifier.width(6.dp));Icon(Icons.Rounded.OpenInNew,null,Modifier.size(16.dp))}
                    }
                }
            }
        }
    }
    if(locationOpen)ModalBottomSheet(onDismissRequest={locationOpen=false}) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Точка установки",style=MaterialTheme.typography.titleLarge)
            Button({if(locationPermissions.any(ctx::allowed))locate() else location.launch(locationPermissions)},Modifier.fillMaxWidth()) {Icon(Icons.Rounded.MyLocation,null);Spacer(Modifier.width(8.dp));Text("Получить координаты GPS")}
            if(status.isNotBlank())Text(status,style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(address,{address=it},Modifier.fillMaxWidth(),label={Text("Город, улица или адрес")},singleLine=true)
            TextButton({scope.launch {
                geocoding=true
                status="Поиск адреса…"
                try {
                    @Suppress("DEPRECATION")
                    val found=withTimeoutOrNull(12000){withContext(Dispatchers.IO){Geocoder(ctx).getFromLocationName(address.trim(),1)?.firstOrNull()}}
                    if(found==null)status="Адрес не найден. Введите координаты или используйте GPS."
                    else {lat=found.latitude.toString();lon=found.longitude.toString();status=found.getAddressLine(0) ?: "Точка найдена"}
                } catch(e:CancellationException){throw e}
                catch(e:Exception){status="Служба поиска адресов недоступна. Можно ввести координаты вручную."}
                finally {geocoding=false}
            }},enabled=address.isNotBlank() && !geocoding){Text(if(geocoding)"Поиск…" else "Найти адрес")}
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(lat,{lat=it},Modifier.weight(1f),label={Text("Широта")},singleLine=true,isError=lat.isNotBlank() && (lat.replace(',','.').toDoubleOrNull()?.let{it !in -90.0..90.0} ?: true))
                OutlinedTextField(lon,{lon=it},Modifier.weight(1f),label={Text("Долгота")},singleLine=true,isError=lon.isNotBlank() && (lon.replace(',','.').toDoubleOrNull()?.let{it !in -180.0..180.0} ?: true))
            }
            FilledTonalButton({locationOpen=false},Modifier.fillMaxWidth(),enabled=site!=null){Text("Готово")}
        }
    }
    if(settingsOpen)ModalBottomSheet(onDismissRequest={settingsOpen=false}) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Text("Настройки наведения",style=MaterialTheme.typography.titleLarge)
            OutlinedTextField(orbit,{orbit=it;selectedTitle="";activeTuning=""},Modifier.fillMaxWidth(),label={Text("Орбита: E (+), W (−)")},singleLine=true,isError=orbital==null)
            OutlinedTextField(offset,{offset=it},Modifier.fillMaxWidth(),label={Text("Поправка компаса, °")},singleLine=true,isError=offset.replace(',','.').toDoubleOrNull()?.let{it !in -180.0..180.0} ?: true)
            Row(verticalAlignment=Alignment.CenterVertically) {Text("Сглаживание компаса",Modifier.weight(1f));Switch(smooth,{smooth=it})}
            if(smooth) {
                Text("Фильтр: %.1f · 0.1 — сильное, 1.0 — без сглаживания".format(lowPass),style=MaterialTheme.typography.bodySmall)
                Slider(lowPass,{lowPass=it},valueRange=0.1f..1f,steps=8)
            }
            Text("Магнитное склонение рассчитывается по точке установки. Ручная поправка добавляется к показанию датчика.",style=MaterialTheme.typography.bodySmall)
            FilledTonalButton({settingsOpen=false},Modifier.fillMaxWidth()){Text("Готово")}
        }
    }
}
