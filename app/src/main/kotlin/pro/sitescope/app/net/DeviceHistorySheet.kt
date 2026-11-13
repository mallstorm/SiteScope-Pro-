@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
package pro.sitescope.app.net

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.sitescope.app.DeviceHistoryStore
import pro.sitescope.app.ui.ErrorCard
import pro.sitescope.app.ui.SoftCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun DeviceHistorySheet(cardId: String, close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var events by remember(cardId) { mutableStateOf<List<DeviceHistoryStore.Event>>(emptyList()) }
    var limit by remember(cardId) { mutableIntStateOf(100) }
    var days by remember { mutableIntStateOf(90) }
    var applied by remember { mutableIntStateOf(90) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var clear by remember { mutableStateOf(false) }
    fun reload(action: (() -> Unit)? = null) {
        scope.launch {
            loading = true
            try {
                val result = withContext(Dispatchers.IO) { action?.invoke(); DeviceHistoryStore.list(context, cardId, limit) to DeviceHistoryStore.retention(context) }
                events = result.first; applied = result.second; days = applied; error = ""
            } catch (e: Exception) { error = "Не удалось загрузить историю" }
            finally { loading = false }
        }
    }
    LaunchedEffect(cardId, limit) { reload() }
    ModalBottomSheet(onDismissRequest = close) {
        LazyColumn(Modifier.fillMaxWidth().weight(1f, false), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("История изменений", style = MaterialTheme.typography.titleLarge)
                Text("Сравниваются наблюдения одного источника в одной сети. Отсутствие ответа не означает, что устройство выключено.", style = MaterialTheme.typography.bodySmall)
                Text("Хранение: $applied дней, не более 20 000 событий во всём каталоге.", style = MaterialTheme.typography.bodySmall)
                if (cardId.isEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(7, 30, 90, 365).forEach { value -> FilterChip(selected = days == value, onClick = { days = value }, enabled = !loading, label = { Text("$value дн.") }) }
                    }
                    if (days != applied) {
                        Text("Применение удалит события старше выбранного срока.", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = { val selected = days; reload { DeviceHistoryStore.setRetention(context, selected) } }, enabled = !loading) { Text("Применить срок") }
                    }
                    TextButton(onClick = { clear = true }, enabled = !loading) { Text("Очистить историю") }
                }
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (error.isNotEmpty()) item { ErrorCard(error) }
            if (events.isEmpty() && !loading) item { Text("Изменений пока нет. Новые события появятся после следующих проверок; прежние поиски не превращаются в историю задним числом.") }
            items(events, key = { it.id }) { event ->
                SoftCard(padding = 12.dp) {
                    Text(event.title(), style = MaterialTheme.typography.titleSmall)
                    Text(SimpleDateFormat("d MMM yyyy, HH:mm:ss", Locale.forLanguageTag("ru")).format(Date(event.time)), style = MaterialTheme.typography.labelSmall)
                    Text(event.address, style = MaterialTheme.typography.bodySmall)
                    if (event.before.isNotEmpty()) Text("Было: ${event.before}", style = MaterialTheme.typography.bodySmall)
                    Text("Стало: ${event.after}", style = MaterialTheme.typography.bodyMedium)
                    Text(event.source, style = MaterialTheme.typography.labelSmall)
                }
            }
            if (events.size >= limit) item { TextButton(onClick = { limit += 100 }, enabled = !loading) { Text("Показать ещё") } }
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Очистить всю историю изменений?") }, text = { Text("Карточки и последние результаты сохранятся. Удалённые события не восстановятся при повторном открытии.") }, confirmButton = { TextButton(onClick = { clear = false; reload { DeviceHistoryStore.clear(context) } }) { Text("Очистить") } }, dismissButton = { TextButton(onClick = { clear = false }) { Text("Отмена") } })
}
