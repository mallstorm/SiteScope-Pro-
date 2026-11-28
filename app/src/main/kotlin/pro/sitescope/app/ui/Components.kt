@file:OptIn(ExperimentalMaterial3Api::class)

package pro.sitescope.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

fun copyText(context: Context, text: String, label: String = "Скопировано") {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    manager.setPrimaryClip(ClipData.newPlainText("SiteScope", text))
    Toast.makeText(context, label, Toast.LENGTH_SHORT).show()
}

fun shareText(context: Context, text: String, subject: String = "") {
    val intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    if (subject.isNotEmpty()) intent.putExtra(Intent.EXTRA_SUBJECT, subject)
    try { context.startActivity(Intent.createChooser(intent, "Поделиться")) } catch (e: Exception) { }
}

fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()

@Composable
fun scoreColor(score: Int): Color = when { score >= 80 -> tones.good; score >= 55 -> tones.warn; else -> tones.bad }

/** Кольцо с оценкой 0–100. */
@Composable
fun ScoreRing(score: Int, modifier: Modifier = Modifier, size: Dp = 120.dp, stroke: Dp = 12.dp, caption: String? = null, onColor: Color? = null) {
    val animated by animateFloatAsState(targetValue = score.coerceIn(0, 100) / 100f, animationSpec = tween(900), label = "score")
    val color = onColor ?: scoreColor(score)
    val track = (onColor ?: MaterialTheme.colorScheme.onSurface).copy(alpha = 0.12f)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val arcSize = Size(this.size.width - s, this.size.height - s)
            val topLeft = Offset(s / 2, s / 2)
            drawArc(track, -90f, 360f, false, topLeft, arcSize, style = Stroke(s, cap = StrokeCap.Round))
            drawArc(color, -90f, 360f * animated, false, topLeft, arcSize, style = Stroke(s, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(score.toString(), fontSize = (size.value * 0.30f).sp, fontWeight = FontWeight.Bold, color = onColor ?: MaterialTheme.colorScheme.onSurface)
            if (caption != null) Text(caption, style = MaterialTheme.typography.labelMedium, color = (onColor ?: MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = 0.85f))
        }
    }
}

@Composable
fun Pill(text: String, color: Color, soft: Color, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(modifier.clip(RoundedCornerShape(50)).background(soft).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = color, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)) }
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun Dot(color: Color, size: Dp = 10.dp) = Box(Modifier.size(size).clip(CircleShape).background(color))

@Composable
fun SoftCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, color: Color = MaterialTheme.colorScheme.surfaceContainerLowest,
             padding: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.large
    val colors = CardDefaults.cardColors(containerColor = color)
    val elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    if (onClick != null) Card(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, colors = colors, elevation = elevation) { Column(Modifier.padding(padding), content = content) }
    else Card(modifier = modifier.fillMaxWidth(), shape = shape, colors = colors, elevation = elevation) { Column(Modifier.padding(padding), content = content) }
}

@Composable
fun IconBadge(icon: ImageVector, color: Color, soft: Color, size: Dp = 44.dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 3)).background(soft), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.52f))
    }
}

@Composable
fun SectionTitle(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(action) }
    }
}

/** Строка «подпись — значение»; долгим нажатием или по значку значение копируется. */
@Composable
fun InfoRow(label: String, value: String, mono: Boolean = false, copyable: Boolean = true) {
    if (value.isBlank()) return
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable(enabled = copyable) { copyText(context, value) }.padding(vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBadge(icon, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer, 52.dp)
        Spacer(Modifier.height(10.dp))
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
fun BigField(value: String, onValue: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onGo: () -> Unit = {},
             leading: ImageVector = Icons.Rounded.Search, keyboard: KeyboardType = KeyboardType.Uri, singleLine: Boolean = true, minLines: Int = 1, mono: Boolean = false) {
    val shape = RoundedCornerShape(14.dp)
    var focused by remember { mutableStateOf(false) }
    val style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontFamily = if (mono) FontFamily.Monospace else null)
    Row(
        modifier.fillMaxWidth().heightIn(min = if (singleLine) 40.dp else 0.dp).clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLowest)
            .border(if (focused) 2.dp else 1.dp, if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, shape)
            .padding(start = 10.dp, end = if (singleLine) 2.dp else 10.dp, top = if (singleLine) 0.dp else 8.dp, bottom = if (singleLine) 0.dp else 8.dp),
        verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top
    ) {
        if (singleLine) { Icon(leading, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Box(Modifier.weight(1f), contentAlignment = if (singleLine) Alignment.CenterStart else Alignment.TopStart) {
            if (value.isEmpty()) Text(placeholder, style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant), maxLines = if (singleLine) 1 else 3, overflow = TextOverflow.Ellipsis)
            androidx.compose.foundation.text.BasicTextField(
                value = value, onValueChange = onValue, singleLine = singleLine, minLines = minLines, textStyle = style,
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
                cursorBrush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (singleLine) ImeAction.Go else ImeAction.Default, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onGo = { onGo() })
            )
        }
        if (value.isNotEmpty() && singleLine) IconButton(onClick = { onValue("") }, modifier = Modifier.size(34.dp)) { Icon(Icons.Rounded.Clear, "Очистить", modifier = Modifier.size(16.dp)) }
    }
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    AppHeader(title = title, subtitle = subtitle, actions = actions)
}

/** Раскрывающийся блок технических подробностей. */
@Composable
fun TechDetails(text: String, modifier: Modifier = Modifier) {
    if (text.isBlank()) return
    var open by remember { mutableStateOf(false) }
    val context = LocalContext.current
    Column(modifier) {
        Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { open = !open }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (open) "Скрыть подробности" else "Для специалиста", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Icon(if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        AnimatedVisibility(open) {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { copyText(context, text) }.padding(10.dp))
        }
    }
}

@Composable
fun LoadingCard(text: String, detail: String = "", onCancel: (() -> Unit)? = null) {
    SoftCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(text, style = MaterialTheme.typography.titleSmall)
                if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (onCancel != null) TextButton(onClick = onCancel) { Text("Стоп") }
        }
    }
}

@Composable
fun ErrorCard(text: String) {
    SoftCard(color = tones.badSoft) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = tones.bad)
            Spacer(Modifier.width(10.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun MonoBlock(text: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp,
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh).clickable { copyText(context, text) }.padding(12.dp))
}


@Composable
fun ConfirmDialog(title: String, text: String, confirmLabel: String, onDismiss: (() -> Unit)?, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = { (onDismiss ?: onConfirm)() }, title = { Text(title) }, text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = if (onDismiss != null) ({ TextButton(onClick = onDismiss) { Text("Отмена") } }) else null)
}

@Composable
fun TextDialog(title: String, initial: String, placeholder: String, onClose: () -> Unit, onSave: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onClose, title = { Text(title) },
        text = { OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, placeholder = { Text(placeholder) }, shape = RoundedCornerShape(14.dp)) },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Отмена") } })
}

/** Шапка экранов второго уровня: стрелка «назад» и заголовок на фирменном градиенте. */
@Composable
fun BackHeader(title: String, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    AppHeader(title = title, onBack = onBack, actions = actions)
}

/** Тонкая дорожка и бегунок-капля с жестами и доступностью обычного Slider. */
@Composable
fun AppSlider(
    value: Float, onValueChange: (Float) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f, steps: Int = 0, onValueChangeFinished: (() -> Unit)? = null,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.surfaceVariant
) {
    val active = if (enabled) activeColor else activeColor.copy(alpha = 0.38f)
    val inactive = if (enabled) inactiveColor else inactiveColor.copy(alpha = 0.5f)
    val range = if (valueRange.endInclusive > valueRange.start) valueRange else 0f..1f
    Slider(
        value = value.coerceIn(range.start, range.endInclusive), onValueChange = onValueChange, modifier = modifier, enabled = enabled,
        valueRange = range, steps = steps.coerceAtLeast(0), onValueChangeFinished = onValueChangeFinished,
        thumb = {
            Canvas(Modifier.size(width = 12.dp, height = 16.dp)) {
                val w = size.width
                val h = size.height
                drawPath(Path().apply {
                    moveTo(w * 0.5f, 0f)
                    cubicTo(w * 0.8f, 0f, w, h * 0.17f, w, h * 0.39f)
                    cubicTo(w, h * 0.63f, w * 0.71f, h * 0.84f, w * 0.55f, h * 0.97f)
                    quadraticBezierTo(w * 0.5f, h, w * 0.45f, h * 0.97f)
                    cubicTo(w * 0.29f, h * 0.84f, 0f, h * 0.63f, 0f, h * 0.39f)
                    cubicTo(0f, h * 0.17f, w * 0.2f, 0f, w * 0.5f, 0f)
                    close()
                }, active)
            }
        },
        track = { state ->
            Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                val span = state.valueRange.endInclusive - state.valueRange.start
                val fraction = if (span > 0f) ((state.value - state.valueRange.start) / span).coerceIn(0f, 1f) else 0f
                val radius = androidx.compose.ui.geometry.CornerRadius(size.height / 2, size.height / 2)
                drawRoundRect(inactive, size = size, cornerRadius = radius)
                if (fraction > 0f) {
                    val width = size.width * fraction
                    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - width else 0f
                    drawRoundRect(active, topLeft = Offset(x, 0f), size = Size(width, size.height), cornerRadius = radius)
                }
            }
        }
    )
}
