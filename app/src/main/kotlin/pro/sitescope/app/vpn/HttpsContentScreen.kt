package pro.sitescope.app.vpn

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pro.sitescope.app.ui.*
import java.io.File

/** Manual, locally processed PCAPNG view. No HTTP payload is written back to disk. */
@Composable
fun HttpsContentScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var captures by remember { mutableStateOf(CaptureHub.files(File(context.filesDir, "captures")).filter { it.extension == "pcapng" }) }
    var selected by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<HttpsCaptureDecoder.Report?>(null) }
    var expanded by remember { mutableIntStateOf(-1) }
    var query by remember { mutableStateOf("") }
    val records = remember(report, query) {
        report?.entries?.filter { query.isBlank() || it.title.contains(query, true) ||
            it.remote.contains(query, true) || it.text.contains(query, true) } ?: emptyList()
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        SoftCard {
            Text("HTTPS · просмотр содержимого", style = MaterialTheme.typography.titleLarge)
            Text("Откройте готовый PCAPNG после остановки записи. Расшифровка выполняется только на этом устройстве.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Text("Поддерживается TLS 1.3 + HTTP/1.1 с ключами Xray MITM. HTTP/2, HTTP/3 и TLS 1.2 пока требуют другого декодера.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = {
                captures = CaptureHub.files(File(context.filesDir, "captures")).filter { it.extension == "pcapng" }
            }) {
                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Обновить записи")
            }
        }
        Spacer(Modifier.height(10.dp))
        SectionTitle("Записанные файлы")
        if (captures.isEmpty()) SoftCard {
            Text("Файлов нет. В VPN включите «Расшифровка TLS», установите доверенный сертификат, начните запись и откройте HTTPS-сайт. Затем остановите запись.")
        }
        captures.take(15).forEach { file ->
            SoftCard(modifier = Modifier.padding(bottom = 8.dp), padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Description, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text((file.length() / 1024).toString() + " КБ",
                            style = MaterialTheme.typography.labelSmall)
                    }
                    TextButton(onClick = {
                        selected = file.name
                        working = true
                        report = null
                        scope.launch {
                            val result = withContext(Dispatchers.IO) { HttpsCaptureDecoder.decode(file) }
                            report = result
                            working = false
                            expanded = -1
                        }
                    }, enabled = !working) { Text("Открыть") }
                }
            }
        }
        if (working) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text("Обрабатывается " + selected, style = MaterialTheme.typography.bodySmall)
        }
        report?.let { result ->
            Spacer(Modifier.height(12.dp))
            Text("Содержимое · " + selected, style = MaterialTheme.typography.titleMedium)
            Text(result.note, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Расшифрованных TLS-сеансов: " + result.decryptedConnections +
                " · HTTP/1.1-сообщений: " + result.entries.size,
                style = MaterialTheme.typography.labelMedium)
            if (result.entries.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(query, { query = it.take(120) }, modifier = Modifier.fillMaxWidth(),
                    label = { Text("Поиск по запросам и адресам") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
            }
            records.forEachIndexed { index, entry ->
                SoftCard(modifier = Modifier.padding(bottom = 8.dp), padding = 12.dp) {
                    Row(Modifier.fillMaxWidth().clickable { expanded = if (expanded == index) -1 else index },
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.direction + " · " + entry.title,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(entry.remote + " · " + entry.cipher,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(if (expanded == index) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null)
                    }
                    if (expanded == index) {
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Text(entry.text, style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
