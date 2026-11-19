package pro.sitescope.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import pro.sitescope.app.HostsVpnService

/** Кнопка «назад», которую оболочка передаёт экранам раздела; шапки показывают её автоматически. */
val LocalBackAction = compositionLocalOf<(() -> Unit)?> { null }

/**
 * Единая шапка приложения: тёмный бирюзовый градиент со скруглёнными нижними углами.
 * Варианты: корневая (logo = true), «назад + заголовок» (onBack или LocalBackAction), с рядом пилюль/чипов в [content].
 */
@Composable
fun AppHeader(
    title: String, subtitle: String? = null, onBack: (() -> Unit)? = null, logo: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {}, content: (@Composable ColumnScope.() -> Unit)? = null
) {
    val t = tones
    val back = onBack ?: LocalBackAction.current
    Box(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(bottomStart = 26.dp, bottomEnd = 26.dp))
            .background(Brush.linearGradient(listOf(t.headerStart, t.headerEnd)))
    ) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width; val h = size.height
            val band = Path().apply { moveTo(w * 0.60f, 0f); lineTo(w * 0.88f, 0f); lineTo(w * 0.72f, h); lineTo(w * 0.44f, h); close() }
            drawPath(band, Brush.linearGradient(listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.07f), Color.White.copy(alpha = 0f))))
        }
        Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (back != null) {
                    IconButton(onClick = back) { Icon(Icons.Rounded.ArrowBack, "Назад", tint = Color.White) }
                } else if (logo) {
                    Icon(Icons.Rounded.Layers, null, tint = t.aqua, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.width(10.dp))
                }
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(title, color = Color.White, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) Text(subtitle, color = Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                CompositionLocalProvider(LocalContentColor provides Color.White) { actions() }
            }
            if (content != null) { Spacer(Modifier.height(8.dp)); Column(Modifier.padding(end = 10.dp), content = content) }
        }
    }
}

/** Меню «⋮» для шапки. */
@Composable
fun HeaderMenu(items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.MoreVert, "Меню", tint = Color.White) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((label, action) in items) DropdownMenuItem(text = { Text(label) }, onClick = { open = false; action() })
        }
    }
}

/** Пилюля состояния в шапке: рамка и значок подсвечиваются аквамарином, когда состояние активно. */
@Composable
fun HeaderPill(text: String, icon: ImageVector, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val t = tones
    val color = if (active) t.aqua else Color.White
    val shape = RoundedCornerShape(50)
    Box(modifier.heightIn(min = 44.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Row(
            Modifier.fillMaxWidth().height(36.dp).clip(shape).border(1.dp, if (active) t.aqua else Color.White.copy(alpha = 0.35f), shape)
                .background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(5.dp))
            Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Три пилюли: VPN, сеть и задачи. Нажатие открывает соответствующий экран. */
@Composable
fun StatusPills(state: AppState, modifier: Modifier = Modifier) {
    val vpn by rememberVpnLifecycle()
    val net by rememberNetLabel()
    val count = rememberActiveTaskCount()
    val vpnActive = vpn == HostsVpnService.STATE_ACTIVE
    val vpnText = when (vpn) {
        HostsVpnService.STATE_ACTIVE -> "VPN: подключён"
        HostsVpnService.STATE_STARTING -> "VPN: запуск…"
        HostsVpnService.STATE_STOPPING -> "VPN: остановка…"
        HostsVpnService.STATE_ERROR -> "VPN: ошибка"
        else -> "VPN: выключен"
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        HeaderPill(vpnText, if (vpnActive) Icons.Rounded.VerifiedUser else Icons.Rounded.Shield, vpnActive, Modifier.weight(1.3f)) { state.openRoute("vpn") }
        HeaderPill(net.text, if (net.connected) Icons.Rounded.Wifi else Icons.Rounded.WifiOff, false, Modifier.weight(1f)) { state.openRoute("wifi") }
        HeaderPill(if (count == 0) "Задачи: нет" else "Задачи: $count ${activeWord(count)}", Icons.Rounded.Checklist, count > 0, Modifier.weight(1.3f)) { state.selectTab(Tab.TASKS) }
    }
}

/** Ряд вкладок-чипов в шапке; выбранный чип — аквамариновый. Прокручивается по горизонтали, если не помещается. */
@Composable
fun HeaderChips(items: List<String>, selected: Int, onSelect: (Int) -> Unit, icons: List<ImageVector>? = null, modifier: Modifier = Modifier) {
    val t = tones
    Row(modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEachIndexed { index, label ->
            val on = index == selected
            val shape = RoundedCornerShape(50)
            Box(Modifier.heightIn(min = 44.dp).clip(shape).clickable { onSelect(index) }, contentAlignment = Alignment.Center) {
                Row(
                    Modifier.height(36.dp).clip(shape).then(if (on) Modifier.background(t.aqua) else Modifier.border(1.dp, Color.White.copy(alpha = 0.35f), shape))
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val icon = icons?.getOrNull(index)
                    if (icon != null) { Icon(icon, null, tint = if (on) t.onAqua else Color.White, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)) }
                    Text(label, color = if (on) t.onAqua else Color.White, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

/** Сегментированные вкладки на светлом фоне: белая панель, выбранный сегмент залит бирюзой. */
@Composable
fun SegmentedTabs(items: List<String>, selected: Int, onSelect: (Int) -> Unit, icons: List<ImageVector>? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().shadow(2.dp, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(4.dp)) {
        items.forEachIndexed { index, label ->
            val on = index == selected
            val color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
            Row(
                Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(18.dp))
                    .then(if (on) Modifier.background(Brush.linearGradient(listOf(tones.heroStart, tones.heroEnd))) else Modifier)
                    .clickable { onSelect(index) },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
            ) {
                val icon = icons?.getOrNull(index)
                if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)) }
                Text(label, color = color, style = MaterialTheme.typography.titleSmall, maxLines = 1)
            }
        }
    }
}

/** Умная строка: сайт, IP, сеть, ASN, плейлист или поток. Справа — вставка из буфера и запуск. */
@Composable
fun SmartSearchBar(value: String, onValue: (String) -> Unit, placeholder: String, onGo: () -> Unit, modifier: Modifier = Modifier, onPaste: (() -> Unit)? = null) {
    val transparent = Color.Transparent
    Row(
        modifier.fillMaxWidth().shadow(2.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLowest).padding(end = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextField(
            value = value, onValueChange = onValue, modifier = Modifier.weight(1f), singleLine = true,
            placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.primary) },
            trailingIcon = if (value.isNotEmpty()) ({ IconButton(onClick = { onValue("") }) { Icon(Icons.Rounded.Clear, "Очистить") } }) else null,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = transparent, unfocusedContainerColor = transparent, disabledContainerColor = transparent,
                focusedIndicatorColor = transparent, unfocusedIndicatorColor = transparent, disabledIndicatorColor = transparent
            ),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go, autoCorrectEnabled = false),
            keyboardActions = KeyboardActions(onGo = { onGo() })
        )
        if (onPaste != null && value.isEmpty()) {
            IconButton(onClick = onPaste) { Icon(Icons.Rounded.ContentPaste, "Вставить из буфера", tint = MaterialTheme.colorScheme.primary) }
        }
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.primary).clickable(onClick = onGo),
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Rounded.PlayArrow, "Запустить", tint = MaterialTheme.colorScheme.onPrimary) }
    }
}

/** Тёмная плитка с белым значком — фирменный значок разделов. */
@Composable
fun ToolIcon(icon: ImageVector, modifier: Modifier = Modifier, size: Dp = 44.dp) {
    val t = tones
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.28f)).background(Brush.linearGradient(listOf(t.tileStart, t.tileEnd))),
        contentAlignment = Alignment.Center
    ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.54f)) }
}

enum class BadgeKind { GOOD, WARN, BAD, INFO, NEUTRAL, NEW }

/** Статусный бейдж: «Успешно», «Завершено», «Активен», «Резерв», «новое» и т. п. */
@Composable
fun StatusBadge(text: String, kind: BadgeKind = BadgeKind.GOOD, icon: ImageVector? = null, modifier: Modifier = Modifier) {
    val t = tones
    val (color, soft) = when (kind) {
        BadgeKind.GOOD -> t.good to t.goodSoft
        BadgeKind.WARN -> t.warn to t.warnSoft
        BadgeKind.BAD -> t.bad to t.badSoft
        BadgeKind.INFO -> t.info to t.infoSoft
        BadgeKind.NEUTRAL -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.surfaceContainerHigh
        BadgeKind.NEW -> t.onAqua to t.aqua.copy(alpha = 0.28f)
    }
    Pill(text, color, soft, modifier, icon)
}

class Metric(val icon: ImageVector, val label: String, val value: String)

/** Полоса из нескольких показателей с разделителями («Текущая сеть · Активные проверки · Отчёты»). */
@Composable
fun MetricStrip(items: List<Metric>, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEachIndexed { index, item ->
            if (index > 0) Box(Modifier.width(1.dp).height(28.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Row(Modifier.weight(1f).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(item.icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Column {
                    Text(item.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.value, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Заголовок страницы-хаба: фирменная плитка, крупное название и подпись. */
@Composable
fun PageTitle(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ToolIcon(icon, size = 52.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing()
    }
}

/** Короткий заголовок блока с действием справа («История ›»). */
@Composable
fun CompactTitle(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Row(Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onAction).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Карточка раздела: значок, название, описание и до двух показателей справа. */
@Composable
fun HubCard(icon: ImageVector, title: String, subtitle: String, stats: List<Pair<ImageVector, String>>, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().shadow(1.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLowest).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ToolIcon(icon, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1.25f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.padding(horizontal = 8.dp).width(1.dp).height(34.dp).background(MaterialTheme.colorScheme.outlineVariant))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            for ((statIcon, text) in stats.take(2)) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(statIcon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
    }
}

/** Нижняя панель из четырёх вкладок на тёмном скруглённом основании. */
@Composable
fun BottomBar4(selected: Tab, taskCount: Int, onSelect: (Tab) -> Unit) {
    val t = tones
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .background(Brush.linearGradient(listOf(t.headerStart, t.headerEnd))).navigationBarsPadding().padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (tab in Tab.entries) {
            val on = tab == selected
            Column(
                Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(18.dp))
                    .then(if (on) Modifier.background(Brush.linearGradient(listOf(Color(0xFF0F8F99), Color(0xFF0B6B75)))) else Modifier)
                    .clickable { onSelect(tab) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
            ) {
                if (tab == Tab.TASKS && taskCount > 0) {
                    BadgedBox(badge = { Badge { Text(taskCount.toString()) } }) { Icon(tab.icon, null, tint = Color.White, modifier = Modifier.size(24.dp)) }
                } else Icon(tab.icon, null, tint = if (on) Color.White else Color.White.copy(alpha = 0.78f), modifier = Modifier.size(24.dp))
                Text(tab.title, color = if (on) Color.White else Color.White.copy(alpha = 0.78f), style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}
