package pro.sitescope.app.site

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import pro.sitescope.app.ui.*

@Composable
fun BatchScreen(vm: BatchViewModel = viewModel()) {
    val context = LocalContext.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) try { context.contentResolver.openOutputStream(uri)?.use { it.write(vm.exportText().toByteArray()) }; toast(context, "Список сохранён") } catch (e: Exception) { toast(context, "Не удалось сохранить") }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SoftCard {
                Text("Список сайтов", style = MaterialTheme.typography.titleSmall)
                Text("По одному адресу в строке. Проверим доступность, код ответа и срок сертификата у каждого.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp, top = 2.dp))
                BigField(vm.input, { vm.input = it }, "example.com\nanother-site.ru\n...", singleLine = false, minLines = 5, keyboard = KeyboardType.Text)
                Spacer(Modifier.height(10.dp))
                if (vm.running) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(progress = { if (vm.total > 0) vm.done.toFloat() / vm.total else 0f }, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(10.dp))
                        Text("${vm.done}/${vm.total}", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { vm.cancel() }) { Text("Стоп") }
                    }
                } else Button(onClick = { vm.run() }, enabled = vm.input.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.PlaylistAddCheck, null); Spacer(Modifier.width(8.dp)); Text("Проверить все")
                }
            }
        }
        if (vm.results.isEmpty() && !vm.running) item {
            EmptyState(Icons.Rounded.Dns, "Проверьте несколько сайтов сразу", "Вставьте список доменов выше — удобно для клиентских проектов или своей сети сайтов.")
        }
        items(vm.results) { r ->
            SoftCard(padding = 12.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val t = tones
                    val (color, soft, icon) = when {
                        !r.done -> Triple(MaterialTheme.colorScheme.outline, MaterialTheme.colorScheme.surfaceContainerHigh, Icons.Rounded.Schedule)
                        r.up -> Triple(t.good, t.goodSoft, Icons.Rounded.CheckCircle)
                        else -> Triple(t.bad, t.badSoft, Icons.Rounded.Cancel)
                    }
                    IconBadge(icon, color, soft, 38.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.host, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val text = when {
                            !r.done -> "Ожидает проверки"
                            r.up -> "Код ${r.code} · ${r.ms} мс" + (if (r.certDays in 0..30) " · сертификат: ${r.certDays} дн." else "")
                            else -> r.error.ifEmpty { "Код ${r.code}" }
                        }
                        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (vm.results.isNotEmpty() && !vm.running) item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { shareText(context, vm.exportText(), "Проверка сайтов") }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Share, null); Spacer(Modifier.width(6.dp)); Text("Поделиться") }
                OutlinedButton(onClick = { save.launch("sitescope-batch.txt") }, modifier = Modifier.weight(1f)) { Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(6.dp)); Text("Сохранить") }
            }
        }
    }
}
