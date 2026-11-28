@file:OptIn(ExperimentalMaterial3Api::class)

package pro.sitescope.app.radio

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal fun StationOnMap.label() = when {
    cell == null -> "Из справочника"
    !fresh && cell.link.serving -> "Последняя обслуживающая"
    marker == StationMarker.Serving && cell.link == RadioLink.Registered -> "Зарегистрированная"
    marker == StationMarker.Serving -> "Основная БС"
    marker == StationMarker.Secondary -> "Дополнительная сота"
    else -> "Соседняя сота"
}

internal fun StationOnMap.identity(): String {
    val c = cell
    val t = position?.tower
    val rat = c?.rat ?: when (t?.rat) { "L" -> "LTE"; "N" -> "5G NR"; "W" -> "UMTS"; "G" -> "GSM"; "T" -> "TD-SCDMA"; "C" -> "CDMA"; else -> "Сота" }
    return listOfNotNull(c?.sim, rat, (c?.cid ?: t?.cid)?.let { "CID $it" }).joinToString(" · ")
}

private fun markerColor(marker: StationMarker) = when (marker) {
    StationMarker.Serving -> Color(0xff28bd72)
    StationMarker.Secondary -> Color(0xff25c8d8)
    StationMarker.Neighbor -> Color(0xffeaa53a)
    StationMarker.Catalogue -> Color(0xff91a0b5)
}

@Composable
internal fun StationSummary(primary: StationOnMap?, onOpen: () -> Unit) {
    Surface(onClick = onOpen, color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp), shape = MaterialTheme.shapes.medium) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.CellTower, null, tint = primary?.let { markerColor(it.marker) } ?: MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(primary?.identity() ?: "Обслуживающая БС ожидается", style = MaterialTheme.typography.titleSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val detail = primary?.let { item ->
                    listOfNotNull(item.label(), item.cell?.dbm?.let { "$it dBm" },
                        when { item.position == null -> "Координат нет"; item.position.online -> "Оценка OpenCelliD"; else -> "На карте" }).joinToString(" · ")
                } ?: "Android пока не передал данные о соединении"
                Text(detail, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.ChevronRight, "Подробнее")
        }
    }
}

@Composable
internal fun StationsSheet(rows: List<StationOnMap>, onClose: () -> Unit, onDetails: (StationOnMap) -> Unit,
    onOnline: () -> Unit, onCatalogue: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val text = query.trim()
    val shown = rows.filter { r -> text.isEmpty() || listOf(r.identity(), r.label(), r.cell?.mcc.orEmpty(),
        r.position?.tower?.name.orEmpty(), r.position?.tower?.mnc.orEmpty()).any { it.contains(text, ignoreCase = true) } }
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.83f).padding(horizontal = 16.dp)) {
            Text("Соты и базовые станции", style = MaterialTheme.typography.titleLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onCatalogue) { Text("Справочник CLF") }
                TextButton(onClick = onOnline) { Text("OpenCelliD") }
            }
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("CID, SIM или название") })
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (shown.isEmpty()) item {
                    Text(if (rows.isEmpty()) "Соты пока не получены. Координаты можно загрузить из справочника или OpenCelliD." else "Совпадений нет")
                }
                items(shown, key = { it.id }) { row ->
                    ListItem(
                        headlineContent = { Text(row.identity()) },
                        supportingContent = { Text(row.label() + (row.cell?.dbm?.let { " · $it dBm" }.orEmpty()) +
                            if (row.position == null) " · координат нет" else "") },
                        leadingContent = { Icon(Icons.Rounded.CellTower, null, tint = markerColor(row.marker)) },
                        trailingContent = { IconButton(onClick = { onDetails(row) }) { Icon(Icons.Rounded.ChevronRight, "Подробнее") } }
                    )
                }
            }
        }
    }
}

@Composable
internal fun StationDetails(row: StationOnMap, dataSim: String?, network: String?, distance: String?, busy: Boolean, notice: String,
    onClose: () -> Unit, onFocus: () -> Unit, onLookup: (() -> Unit)?) {
    val c = row.cell
    val p = row.position
    val t = p?.tower
    val fields = listOfNotNull(
        row.label() + if (row.fresh && c?.sim == dataSim && dataSim != null && c?.link?.serving == true) " · SIM мобильных данных" else "",
        network?.takeIf { it.isNotBlank() },
        (c?.mcc ?: t?.mcc)?.takeIf { it.isNotBlank() }?.let { "PLMN $it-${c?.mnc ?: t?.mnc.orEmpty()}" },
        (c?.area ?: t?.area)?.let { "${if (c?.rat in listOf("LTE", "NR") || t?.rat in listOf("L", "N")) "TAC" else "LAC"} $it" },
        (c?.pci ?: t?.pci)?.let { "PCI / код соты $it" },
        (c?.channel ?: t?.channel)?.let { "Канал $it" },
        c?.dbm?.let { "Уровень $it dBm" },
        c?.details?.takeIf { it.isNotBlank() },
        c?.measured?.takeIf { it > 0 }?.let { "Измерение ${clock(it)}" },
        t?.name?.takeIf { it.isNotBlank() && p?.online != true },
        t?.let { "%.5f, %.5f".format(it.lat, it.lon) },
        t?.accuracy?.takeIf { it > 0 }?.let { "Точность по справочнику ±$it м" },
        p?.samples?.let { "Измерений в базе $it" },
        distance?.let { "До точки на карте $it по прямой" }
    )
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp).padding(bottom = 25.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(row.identity(), style = MaterialTheme.typography.titleLarge)
            fields.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(when {
                p?.online == true -> "Оценочная позиция соты · OpenCelliD"
                p != null -> "Источник координат · локальный справочник"
                else -> "Координаты соты пока неизвестны"
            }, style = MaterialTheme.typography.bodySmall)
            if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
            if (p != null) Button(onClick = onFocus) { Text("Показать на карте") }
            else if (onLookup != null) Button(onClick = onLookup, enabled = !busy) { Text(if (busy) "Поиск…" else "Найти в OpenCelliD") }
        }
    }
}

@Composable
internal fun StationOnlineSheet(key: String, auto: Boolean, busy: Boolean, status: String, hasGps: Boolean,
    onClose: () -> Unit, onSave: (String, Boolean) -> Unit, onArea: () -> Unit) {
    val uri = LocalUriHandler.current
    var token by remember(key) { mutableStateOf(key) }
    var automatic by remember(auto) { mutableStateOf(auto) }
    ModalBottomSheet(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("OpenCelliD", style = MaterialTheme.typography.titleLarge)
            Text("Поиск обслуживающей соты отправляет её идентификаторы. Поиск рядом отправляет область вокруг отметки GPS.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(token, { token = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("API-ключ OpenCelliD") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                visualTransformation = PasswordVisualTransformation())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Искать обслуживающую соту при её смене", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = automatic, onCheckedChange = { automatic = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onSave(token.trim(), automatic) }, enabled = !busy) { Text("Сохранить") }
                OutlinedButton(onClick = onArea, enabled = !busy && key.isNotBlank() && hasGps && token.trim() == key) { Text("Поиск рядом") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uri.openUri("https://wiki.opencellid.org/docs/api/overview") }) { Text("OpenCelliD · CC BY-SA 4.0") }
        }
    }
}
