package pro.sitescope.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Крупная строка-действие: значок, название, пояснение простыми словами и стрелка. Единый вид меню «Ещё» во всех разделах. */
@Composable
fun ActionRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = { Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.outline) }) {
    SoftCard(padding = 12.dp, onClick = onClick, modifier = modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon, tones.info, MaterialTheme.colorScheme.surfaceContainerLow, 42.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}
