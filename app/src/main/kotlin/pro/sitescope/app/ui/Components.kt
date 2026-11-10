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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
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
             padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
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
    Row(modifier.fillMaxWidth().padding(top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) TextButton(onClick = onAction, contentPadding = PaddingValues(horizontal = 8.dp)) { Text(action) }
    }
}

/** Строка «подпись — значение»; долгим нажатием или по значку значение копируется. */
@Composable
fun InfoRow(label: String, value: String, mono: Boolean = false, copyable: Boolean = true) {
    if (value.isBlank()) return
    val context = LocalContext.current
    Row(Modifier.fillMaxWidth().clickable(enabled = copyable) { copyText(context, value) }.padding(vertical = 7.dp), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(132.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, text: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconBadge(icon, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primaryContainer, 76.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        content()
    }
}

@Composable
fun BigField(value: String, onValue: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, onGo: () -> Unit = {},
             leading: ImageVector = Icons.Rounded.Search, keyboard: KeyboardType = KeyboardType.Uri, singleLine: Boolean = true, minLines: Int = 1, mono: Boolean = false) {
    OutlinedTextField(
        value = value, onValueChange = onValue, modifier = modifier.fillMaxWidth(), singleLine = singleLine, minLines = minLines,
        placeholder = { Text(placeholder, maxLines = if (singleLine) 1 else 4, overflow = TextOverflow.Ellipsis) },
        leadingIcon = if (singleLine) ({ Icon(leading, null) }) else null,
        trailingIcon = if (value.isNotEmpty() && singleLine) ({ IconButton(onClick = { onValue("") }) { Icon(Icons.Rounded.Clear, "Очистить") } }) else null,
        shape = RoundedCornerShape(18.dp),
        textStyle = if (mono) LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace) else LocalTextStyle.current,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = if (singleLine) ImeAction.Go else ImeAction.Default, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest, focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant)
    )
}

@Composable
fun ScreenHeader(title: String, subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        actions()
    }
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
