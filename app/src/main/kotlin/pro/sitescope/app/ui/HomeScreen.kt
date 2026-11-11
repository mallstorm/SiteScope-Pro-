@file:OptIn(ExperimentalLayoutApi::class)

package pro.sitescope.app.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pro.sitescope.app.DataStore
import pro.sitescope.app.HostsActivity
import pro.sitescope.app.ScannerActivity
import pro.sitescope.app.core.SmartInput
import pro.sitescope.app.tv.StreamStatus
import pro.sitescope.app.tv.TvStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private class RecentSite(val target: String, val host: String, val score: Int, val time: Long)

@Composable
fun HomeScreen(state: AppState) {
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    var recent by remember { mutableStateOf(listOf<RecentSite>()) }
    var tvTotal by remember { mutableIntStateOf(0) }
    var tvWorking by remember { mutableIntStateOf(0) }
    val detected = remember(input) { SmartInput.detect(input) }

    LaunchedEffect(state.tab) {
        withContext(Dispatchers.IO) {
            try {
                val seen = HashSet<String>()
                recent = DataStore(context).audits().filter { it.host.isNotEmpty() && seen.add(it.host) }.take(4)
                    .map { RecentSite(it.target, it.host, it.score, it.timestamp) }
            } catch (e: Exception) { }
            try {
                TvStore.load(context)
                val list = TvStore.snapshot()
                tvTotal = list.size; tvWorking = list.count { it.status == StreamStatus.OK || it.status == StreamStatus.SLOW }
            } catch (e: Exception) { }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(top = 12.dp, bottom = 24.dp)) {
        // Главная карточка с умной строкой
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp))
                .background(Brush.linearGradient(listOf(tones.heroStart, tones.heroEnd))).padding(20.dp)
        ) {
            Text("SiteScope", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Text("Вставьте адрес сайта, IP, подсеть или ссылку на плейлист — подберём нужный инструмент.",
                color = Color.White.copy(alpha = 0.92f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp, bottom = 14.dp))
            TextField(
                value = input, onValueChange = { input = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("example.com · 8.8.8.8 · 10.0.0.0/24") },
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                trailingIcon = {
                    if (input.isNotEmpty()) IconButton(onClick = { input = "" }) { Icon(Icons.Rounded.Clear, "Очистить") }
                    else {
                        val clipboard = LocalClipboardManager.current
                        IconButton(onClick = { clipboard.getText()?.text?.let { input = it.trim().take(500) } }) { Icon(Icons.Rounded.ContentPaste, "Вставить") }
                    }
                },
                shape = RoundedCornerShape(18.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { state.open(input) })
            )
            if (detected.kind != SmartInput.Kind.EMPTY) {
                Spacer(Modifier.height(12.dp))
                Button(onClick = { state.open(input) }, modifier = Modifier.fillMaxWidth().height(50.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = tones.heroStart), shape = RoundedCornerShape(16.dp)) {
                    Text(actionTitle(detected.kind), fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, modifier = Modifier.size(18.dp))
                }
            }
        }

        SectionTitle("Что сделать")
        val t = tones
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionTile(Icons.Rounded.Language, "Проверить сайт", "Скорость, поиск, защита", t.site, t.siteSoft, Modifier.weight(1f)) { state.tab = Tab.SITE }
            ActionTile(Icons.Rounded.LiveTv, "Смотреть ТВ", if (tvTotal > 0) "Каналов: $tvTotal" + (if (tvWorking > 0) " · работают $tvWorking" else "") else "Плейлисты и проверка", t.tv, t.tvSoft, Modifier.weight(1f)) { state.tab = Tab.TV }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionTile(Icons.Rounded.MyLocation, "Мой IP", "Адрес и провайдер", t.ip, t.ipSoft, Modifier.weight(1f)) { state.ipRequest = "@me"; state.tab = Tab.IP }
            ActionTile(Icons.Rounded.Calculate, "Подсети", "Маски и деление сетей", t.ip, t.ipSoft, Modifier.weight(1f)) { state.subnetRequest = ""; state.tab = Tab.IP }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionTile(Icons.Rounded.Wifi, "Моя сеть", "Устройства и сервисы", t.net, t.netSoft, Modifier.weight(1f)) { open(context, ToolActivity::class.java) { putExtra("screen", "network") } }
            ActionTile(Icons.Rounded.Shield, "VPN и адреса", "Туннель и правила", t.info, t.infoSoft, Modifier.weight(1f)) { open(context, ToolActivity::class.java) { putExtra("screen", "vpn") } }
        }

        if (recent.isNotEmpty()) {
            SectionTitle("Недавние проверки", action = "Все", onAction = { state.tab = Tab.SITE })
            SoftCard(padding = 6.dp) {
                recent.forEachIndexed { index, site ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { state.siteRequest = site.target; state.tab = Tab.SITE }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        ScoreRing(site.score, size = 46.dp, stroke = 5.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(site.host, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(SimpleDateFormat("d MMM, HH:mm", Locale("ru")).format(Date(site.time)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Rounded.Refresh, "Проверить снова", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (index < recent.size - 1) HorizontalDivider(Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                }
            }
        }
    }
}

private fun actionTitle(kind: SmartInput.Kind): String = when (kind) {
    SmartInput.Kind.URL, SmartInput.Kind.DOMAIN -> "Проверить сайт"
    SmartInput.Kind.IPV4, SmartInput.Kind.IPV6 -> "Узнать всё об адресе"
    SmartInput.Kind.CIDR -> "Рассчитать подсеть"
    SmartInput.Kind.ASN -> "Найти сети провайдера"
    SmartInput.Kind.PLAYLIST -> "Добавить плейлист"
    SmartInput.Kind.STREAM -> "Открыть поток"
    SmartInput.Kind.TEXT -> "Искать провайдера"
    SmartInput.Kind.EMPTY -> ""
}

fun open(context: Context, target: Class<*>, configure: Intent.() -> Unit = {}) {
    try { context.startActivity(Intent(context, target).apply(configure)) } catch (e: Exception) { toast(context, "Не удалось открыть раздел") }
}

@Composable
private fun ActionTile(icon: ImageVector, title: String, subtitle: String, color: Color, soft: Color, modifier: Modifier, onClick: () -> Unit) {
    SoftCard(modifier = modifier, onClick = onClick, padding = 14.dp) {
        IconBadge(icon, color, soft)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
