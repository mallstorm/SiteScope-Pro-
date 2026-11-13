package pro.sitescope.app.tv

import pro.sitescope.app.ui.AppSlider
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import kotlinx.coroutines.delay
import java.util.Locale

internal object SeekSupport {
    data class Position(val duration: Long = 0, val position: Long = 0, val buffered: Long = 0, val seekable: Boolean = false, val live: Boolean = false, val liveOffset: Long = C.TIME_UNSET) {
        val fraction: Float get() = if (duration > 0) (position.toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f
    }
    fun read(player: Player?, forceLive: Boolean = false): Position {
        if (player == null) return Position(live = forceLive)
        val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0
        return Position(duration, player.currentPosition.coerceIn(0, duration), player.bufferedPosition.coerceIn(0, duration),
            duration > 0 && player.isCurrentMediaItemSeekable && player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM),
            player.isCurrentMediaItemLive || forceLive && duration == 0L, player.currentLiveOffset)
    }
    fun seek(player: Player, fraction: Float, expected: MediaItem?, expectedEpoch: Long = 0, currentEpoch: Long = 0): Boolean {
        if (expectedEpoch != currentEpoch || !fraction.isFinite() || expected == null || player.currentMediaItem != expected) return false
        val p = read(player); if (!p.seekable) return false
        player.seekTo((fraction.coerceIn(0f, 1f).toDouble() * p.duration).toLong().coerceIn(0, p.duration)); return true
    }
    fun time(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return if (seconds >= 3600) String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
        else String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
    }
}

@Composable
internal fun TvSeekControls(player: Player?, epoch: Long, forceLive: Boolean, currentEpoch: () -> Long, onDragging: (Boolean) -> Unit, onTouch: () -> Unit) {
    var position by remember(epoch) { mutableStateOf(SeekSupport.read(player, forceLive)) }
    var preview by remember(epoch) { mutableStateOf<Float?>(null) }
    var item by remember(epoch) { mutableStateOf<MediaItem?>(null) }
    DisposableEffect(epoch) { onDispose { onDragging(false) } }
    LaunchedEffect(player, epoch, forceLive) {
        while (true) { position = SeekSupport.read(player, forceLive); delay(400) }
    }
    Column(Modifier.fillMaxWidth()) {
        key(epoch) { AppSlider(value = preview ?: position.fraction, onValueChange = { value ->
            if (preview == null) { item = player?.currentMediaItem; onTouch() }
            preview = value; onDragging(true)
        }, onValueChangeFinished = {
            val value = preview; if (value != null && player != null) SeekSupport.seek(player, value, item, epoch, currentEpoch())
            preview = null; onDragging(false); onTouch()
        }, enabled = position.seekable, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Перемотка видео" },
            activeColor = Color.White, inactiveColor = Color.White.copy(alpha = 0.3f)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shown = preview?.let { (it.toDouble() * position.duration).toLong() } ?: position.position
            Text(if (position.duration > 0) SeekSupport.time(shown) else "—:—", color = Color.White, style = MaterialTheme.typography.labelMedium)
            Text(when {
                position.live && !position.seekable -> "Прямой эфир · без перемотки"
                position.live && position.liveOffset > 0 && position.liveOffset != C.TIME_UNSET -> "−${SeekSupport.time(position.liveOffset)} от эфира"
                position.live -> "Прямой эфир"
                position.duration > 0 -> SeekSupport.time(position.duration) + if (!position.seekable) " · без перемотки" else ""
                else -> "Длительность пока неизвестна"
            }, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
            if (position.live && position.seekable && player?.isCommandAvailable(Player.COMMAND_SEEK_TO_DEFAULT_POSITION) == true) {
                TextButton(onClick = { preview = null; onDragging(false); if (currentEpoch() == epoch) player.seekToDefaultPosition(); onTouch() }) { Text("В эфир", color = Color.White) }
            }
        }
    }
}
