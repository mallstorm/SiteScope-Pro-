@file:OptIn(ExperimentalMaterial3Api::class)

package pro.sitescope.app.tv

import android.app.PictureInPictureParams
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import pro.sitescope.app.MulticastRelay
import pro.sitescope.app.ui.AppTheme
import pro.sitescope.app.ui.Dot
import pro.sitescope.app.ui.tones
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrackOption(val title: String, val selected: Boolean, val apply: () -> Unit)

/** Плеер на Media3: HLS, DASH, MPEG-TS, RTSP и локальный UDP/RTP через прежний MulticastRelay. */
class TvPlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var relay: MulticastRelay? = null
    private val http = DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(12000).setReadTimeoutMs(15000)
    private val handler = Handler(Looper.getMainLooper())
    private var queue: List<Channel> = emptyList()
    private var triedAlternate = false
    private var forcedMime: String? = null
    private var droppedFrames = 0
    private var bandwidth = 0L
    private var sleepRunnable: Runnable? = null

    // Состояние для интерфейса
    private var index by mutableIntStateOf(0)
    private var playing by mutableStateOf(false)
    private var buffering by mutableStateOf(true)
    private var error by mutableStateOf("")
    private var status by mutableStateOf("")
    private var controls by mutableStateOf(true)
    private var panel by mutableStateOf(Panel.NONE)
    private var pip by mutableStateOf(false)
    private var resizeMode by mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    private var stats by mutableStateOf("")
    private var showStats by mutableStateOf(false)
    private var tracksVersion by mutableIntStateOf(0)
    private var archiveTitle by mutableStateOf("")
    private var sleepAt by mutableStateOf(0L)
    private var landscape by mutableStateOf(false)
    private var interaction by mutableIntStateOf(0)

    enum class Panel { NONE, CHANNELS, QUALITY, AUDIO, SUBTITLES, MORE, ARCHIVE }

    private val current: Channel? get() = queue.getOrNull(index)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        TvStore.load(this); Epg.loadCache(this)
        readQueue()
        val exo = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(DefaultDataSource.Factory(this, http)))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(8000, 40000, 1500, 3000).build())
            .build()
        player = exo
        exo.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_READY) { error = ""; status = ""; triedAlternate = true; current?.let { markWorking(it) } }
                if (state == Player.STATE_ENDED && archiveTitle.isNotEmpty()) { archiveTitle = ""; start() }
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onTracksChanged(tracks: Tracks) { tracksVersion++ }
            override fun onVideoSizeChanged(videoSize: VideoSize) { updateStats() }
            override fun onPlayerError(e: PlaybackException) { handleError(e) }
        })
        exo.addAnalyticsListener(object : AnalyticsListener {
            override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, dropped: Int, elapsedMs: Long) { droppedFrames += dropped; updateStats() }
            override fun onBandwidthEstimate(eventTime: AnalyticsListener.EventTime, totalLoadTimeMs: Int, totalBytesLoaded: Long, bitrateEstimate: Long) { bandwidth = bitrateEstimate; updateStats() }
        })
        setContent { AppTheme(dark = true) { PlayerScreen() } }
        start()
    }

    private fun readQueue() {
        val url = intent.getStringExtra("url")
        if (!url.isNullOrBlank()) {
            // Вызов из прежних экранов: один поток и, возможно, соседние
            fun make(u: String?, n: String?): Channel? = if (u.isNullOrBlank()) null else Channel().apply { this.url = u; name = n?.takeIf { it.isNotBlank() } ?: "Поток"; id = Playlist.channelId(u, name) }
            val list = ArrayList<Channel>()
            make(intent.getStringExtra("previous_url"), intent.getStringExtra("previous_name"))?.let { list.add(it) }
            val main = make(url, intent.getStringExtra("name"))!!
            list.add(main)
            make(intent.getStringExtra("next_url"), intent.getStringExtra("next_name"))?.let { list.add(it) }
            queue = list; index = list.indexOf(main)
        } else {
            queue = PlayerQueue.channels; index = PlayerQueue.index.coerceIn(0, maxOf(0, queue.size - 1))
        }
    }

    private fun markWorking(c: Channel) {
        if (c.status == StreamStatus.UNKNOWN || c.status == StreamStatus.DEAD) { c.status = StreamStatus.OK; c.detail = "Видео идёт"; c.checkedAt = System.currentTimeMillis() }
    }

    /** Запускает текущий канал с начала (прямой эфир). */
    private fun start(overrideUrl: String? = null) {
        val channel = current ?: run { error = "Нет потока для воспроизведения"; buffering = false; return }
        val exo = player ?: return
        stopRelay()
        error = ""; buffering = true; droppedFrames = 0; stats = ""
        if (overrideUrl == null) { archiveTitle = ""; triedAlternate = false; forcedMime = null }
        var target = overrideUrl ?: Playlist.playbackUrl(TvStore.udpxyBase, channel.url)
        try {
            if (target.startsWith("udp://", true) || target.startsWith("rtp://", true)) {
                status = "Подключаемся к потоку в локальной сети…"
                val r = MulticastRelay(this, target, object : MulticastRelay.Listener {
                    override fun onStatus(value: String?) {}
                    override fun onStats(packets: Long, bytes: Long, lostPackets: Long, networkInterface: String?) {}
                    override fun onError(message: String?) { runOnUiThread { error = "В вашей сети этот поток не идёт" + if (message.isNullOrBlank()) "" else " ($message)"; buffering = false } }
                })
                relay = r
                target = r.start()
                forcedMime = MimeTypes.VIDEO_MP2T
            }
            http.setUserAgent(channel.userAgent.ifBlank { StreamChecker.DEFAULT_UA })
            http.setDefaultRequestProperties(if (channel.referrer.isNotBlank()) mapOf("Referer" to channel.referrer) else emptyMap())
            val item = MediaItem.Builder().setUri(target)
            (forcedMime ?: guessMime(target, channel))?.let { if (it != MimeTypes.VIDEO_MP2T) item.setMimeType(it) }
            exo.setMediaItem(item.build())
            exo.prepare()
            exo.playWhenReady = true
        } catch (e: Exception) {
            error = "Не удалось открыть поток: " + (e.message ?: e.javaClass.simpleName); buffering = false
        }
    }

    private fun guessMime(url: String, channel: Channel): String? {
        val path = url.lowercase().substringBefore('?')
        return when {
            path.endsWith(".m3u8") || path.endsWith(".m3u") || channel.container == "HLS" -> MimeTypes.APPLICATION_M3U8
            path.endsWith(".mpd") || channel.container == "DASH" -> MimeTypes.APPLICATION_MPD
            else -> null
        }
    }

    private fun handleError(e: PlaybackException) {
        val channel = current
        // Ссылки без расширения: если не угадали формат — пробуем второй вариант
        if (!triedAlternate && channel != null && relay == null) {
            triedAlternate = true
            val target = Playlist.playbackUrl(TvStore.udpxyBase, channel.url)
            forcedMime = if (guessMime(target, channel) == MimeTypes.APPLICATION_M3U8) MimeTypes.VIDEO_MP2T else MimeTypes.APPLICATION_M3U8
            val keepArchive = archiveTitle
            start(if (keepArchive.isNotEmpty()) player?.currentMediaItem?.localConfiguration?.uri?.toString() else target)
            archiveTitle = keepArchive
            return
        }
        buffering = false
        error = when (e.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Нет связи с сервером канала"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "Сервер отказал в доступе к каналу"
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "Канал не найден на сервере"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "Формат потока не поддерживается"
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES -> "Телефон не умеет показывать это видео"
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> { player?.seekToDefaultPosition(); player?.prepare(); return }
            else -> "Не удалось показать канал"
        }
        status = e.errorCodeName
        controls = true
    }

    private fun switchTo(newIndex: Int) {
        if (queue.isEmpty()) return
        index = (newIndex + queue.size) % queue.size
        panel = Panel.NONE
        current?.let { c -> c.lastWatched = System.currentTimeMillis() }
        start(); touch()
    }

    private fun touch() { controls = true; interaction++ }

    private fun updateStats() {
        val exo = player ?: return
        val v = exo.videoFormat; val a = exo.audioFormat
        val parts = ArrayList<String>()
        if (v != null) {
            if (v.width > 0) parts.add("${v.width}×${v.height}")
            v.sampleMimeType?.let { parts.add(it.substringAfter('/').uppercase()) }
            if (v.frameRate > 0) parts.add("%.0f к/с".format(v.frameRate))
            if (v.bitrate > 0) parts.add(StreamChecker.fmtRate(v.bitrate / 1000L))
        }
        a?.sampleMimeType?.let { parts.add("звук " + it.substringAfter('/').uppercase()) }
        if (bandwidth > 0) parts.add("сеть " + StreamChecker.fmtRate(bandwidth / 1000))
        parts.add("буфер %.1f с".format(exo.totalBufferedDuration / 1000.0))
        if (droppedFrames > 0) parts.add("потеряно кадров $droppedFrames")
        stats = parts.joinToString("\n")
    }

    private fun trackOptions(type: Int): List<TrackOption> {
        val exo = player ?: return emptyList()
        val out = ArrayList<TrackOption>()
        val groups = exo.currentTracks.groups.filter { it.type == type }
        val hasOverride = exo.trackSelectionParameters.overrides.keys.any { it.type == type }
        val disabled = exo.trackSelectionParameters.disabledTrackTypes.contains(type)
        if (type == C.TRACK_TYPE_VIDEO) out.add(TrackOption("Авто (по скорости сети)", !hasOverride) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().clearOverridesOfType(type).build()
        })
        if (type == C.TRACK_TYPE_TEXT) out.add(TrackOption("Выключены", disabled || groups.none { it.isSelected }) {
            exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, true).build()
        })
        for (group in groups) for (i in 0 until group.length) {
            if (!group.isTrackSupported(i)) continue
            val f = group.getTrackFormat(i)
            val title = when (type) {
                C.TRACK_TYPE_VIDEO -> (if (f.height > 0) "${f.height}p" else "Видео") + (if (f.bitrate > 0) " · " + StreamChecker.fmtRate(f.bitrate / 1000L) else "")
                else -> listOfNotNull(f.label, f.language?.let { try { Locale.forLanguageTag(it).displayLanguage.replaceFirstChar { ch -> ch.uppercase() } } catch (e: Exception) { it } },
                    if (type == C.TRACK_TYPE_AUDIO && f.channelCount > 0) (if (f.channelCount >= 6) "5.1" else if (f.channelCount == 2) "стерео" else "моно") else null)
                    .distinct().joinToString(" · ").ifEmpty { "Дорожка ${out.size + 1}" }
            }
            val selected = group.isTrackSelected(i) && (type != C.TRACK_TYPE_VIDEO || hasOverride) && !disabled
            out.add(TrackOption(title, selected) {
                exo.trackSelectionParameters = exo.trackSelectionParameters.buildUpon().setTrackTypeDisabled(type, false)
                    .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i)).build()
            })
        }
        return if (type == C.TRACK_TYPE_VIDEO) out.distinctBy { it.title } else out
    }

    private fun setSleep(minutes: Int) {
        sleepRunnable?.let { handler.removeCallbacks(it) }
        sleepRunnable = null; sleepAt = 0
        if (minutes > 0) {
            val r = Runnable { finish() }
            sleepRunnable = r; sleepAt = System.currentTimeMillis() + minutes * 60_000L
            handler.postDelayed(r, minutes * 60_000L)
        }
    }

    private fun enterPip() {
        try {
            val size = player?.videoSize
            val ratio = if (size != null && size.width > 0 && size.height > 0) Rational(size.width, size.height).let { if (it.toFloat() in 0.42f..2.38f) it else Rational(16, 9) } else Rational(16, 9)
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(ratio).build())
        } catch (e: Exception) { }
    }

    override fun onUserLeaveHint() { super.onUserLeaveHint(); if (player?.isPlaying == true) enterPip() }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        pip = isInPictureInPictureMode
        if (isInPictureInPictureMode) panel = Panel.NONE
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
        if (queue === PlayerQueue.channels) try { TvStore.save(applicationContext) } catch (e: Exception) { }
    }

    private fun stopRelay() { try { relay?.stop() } catch (e: Exception) { }; relay = null }

    override fun onDestroy() {
        sleepRunnable?.let { handler.removeCallbacks(it) }
        stopRelay()
        player?.release(); player = null
        super.onDestroy()
    }

    // ================= Интерфейс =================

    @Composable
    private fun PlayerScreen() {
        val channel = current
        BackHandler(enabled = panel != Panel.NONE) { panel = Panel.NONE }
        LaunchedEffect(interaction, controls, playing, panel) {
            if (controls && playing && panel == Panel.NONE) { kotlinx.coroutines.delay(4500); controls = false }
        }
        LaunchedEffect(showStats) { while (showStats) { updateStats(); kotlinx.coroutines.delay(1000) } }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { context -> PlayerView(context).apply { useController = false; player = this@TvPlayerActivity.player; setShutterBackgroundColor(android.graphics.Color.BLACK) } },
                update = { it.resizeMode = resizeMode },
                modifier = Modifier.fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures(onTap = { if (panel != Panel.NONE) panel = Panel.NONE else { controls = !controls; interaction++ } },
                        onDoubleTap = { player?.let { p -> if (p.isPlaying) p.pause() else p.play() }; touch() }) }
                    .pointerInput(queue.size) {
                        var total = 0f
                        detectVerticalDragGestures(onDragStart = { total = 0f }, onDragEnd = {
                            if (queue.size > 1 && panel == Panel.NONE) { if (total < -140f) switchTo(index + 1) else if (total > 140f) switchTo(index - 1) }
                        }) { _, amount -> total += amount }
                    }
            )
            if (pip) return@Box

            if (buffering && error.isEmpty()) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White)
                if (status.isNotEmpty()) Text(status, color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
            }

            if (error.isNotEmpty()) Column(Modifier.align(Alignment.Center).padding(32.dp).clip(RoundedCornerShape(24.dp)).background(Color(0xE61E1A16)).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Rounded.SignalWifiOff, null, tint = tones.bad, modifier = Modifier.size(40.dp))
                Text(error, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
                if (status.isNotEmpty()) Text(status, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = { triedAlternate = false; start() }) { Text("Ещё раз") }
                    if (queue.size > 1) Button(onClick = { switchTo(index + 1) }) { Text("Следующий канал") }
                }
            }

            AnimatedVisibility(showStats && stats.isNotEmpty(), modifier = Modifier.align(Alignment.TopEnd).padding(top = 72.dp, end = 12.dp), enter = fadeIn(), exit = fadeOut()) {
                Text(stats, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xB3000000)).padding(10.dp))
            }

            AnimatedVisibility(controls && panel == Panel.NONE, enter = fadeIn(), exit = fadeOut()) { Controls(channel) }

            when (panel) {
                Panel.CHANNELS -> SidePanel("Каналы") { ChannelList() }
                Panel.QUALITY -> SidePanel("Качество") { key(tracksVersion) { Options(trackOptions(C.TRACK_TYPE_VIDEO), "У этого канала одно качество") } }
                Panel.AUDIO -> SidePanel("Звук") { key(tracksVersion) { Options(trackOptions(C.TRACK_TYPE_AUDIO), "Одна звуковая дорожка") } }
                Panel.SUBTITLES -> SidePanel("Субтитры") { key(tracksVersion) { Options(trackOptions(C.TRACK_TYPE_TEXT), "Субтитров нет") } }
                Panel.MORE -> SidePanel("Ещё") { MorePanel() }
                Panel.ARCHIVE -> SidePanel("Архив передач") { ArchivePanel() }
                Panel.NONE -> {}
            }
        }
    }

    @Composable
    private fun Controls(channel: Channel?) {
        val now = remember(index, interaction) { channel?.let { Epg.now(it) } }
        val next = remember(index, interaction) { channel?.let { Epg.next(it) } }
        Box(Modifier.fillMaxSize()) {
            // Верх
            Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))).statusBarsPadding().padding(horizontal = 6.dp, vertical = 8.dp).padding(bottom = 22.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", tint = Color.White) }
                Column(Modifier.weight(1f)) {
                    Text(channel?.name ?: "", color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = if (archiveTitle.isNotEmpty()) "Архив · $archiveTitle" else now?.title ?: channel?.group ?: ""
                    if (sub.isNotEmpty()) Text(sub, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (channel != null && queue === PlayerQueue.channels) IconButton(onClick = { channel.favorite = !channel.favorite; touch() }) {
                    key(interaction) { Icon(if (channel.favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, "Избранное", tint = if (channel.favorite) Color(0xFFFFC857) else Color.White) }
                }
                IconButton(onClick = { enterPip() }) { Icon(Icons.Rounded.PictureInPictureAlt, "Окно поверх", tint = Color.White) }
                if (queue.size > 1) IconButton(onClick = { panel = Panel.CHANNELS }) { Icon(Icons.Rounded.FormatListBulleted, "Каналы", tint = Color.White) }
            }
            // Центр
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                if (queue.size > 1) RoundButton(Icons.Rounded.SkipPrevious, 52.dp) { switchTo(index - 1) }
                if (error.isEmpty()) RoundButton(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, 72.dp) {
                    player?.let { p -> if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() } }; touch()
                }
                if (queue.size > 1) RoundButton(Icons.Rounded.SkipNext, 52.dp) { switchTo(index + 1) }
            }
            // Низ
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000)))).navigationBarsPadding().padding(horizontal = 16.dp).padding(top = 28.dp, bottom = 12.dp)) {
                if (now != null && archiveTitle.isEmpty()) {
                    val format = remember { SimpleDateFormat("HH:mm", Locale.US) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(format.format(Date(now.start)), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                        LinearProgressIndicator(progress = { now.progress(System.currentTimeMillis()) }, modifier = Modifier.weight(1f).padding(horizontal = 10.dp).height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = tones.tv, trackColor = Color.White.copy(alpha = 0.25f))
                        Text(format.format(Date(now.stop)), color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelMedium)
                    }
                    if (next != null) Text("Далее: ${next.title}", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(8.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    BarButton(Icons.Rounded.HighQuality, "Качество") { panel = Panel.QUALITY }
                    BarButton(Icons.Rounded.Audiotrack, "Звук") { panel = Panel.AUDIO }
                    BarButton(Icons.Rounded.Subtitles, "Субтитры") { panel = Panel.SUBTITLES }
                    BarButton(Icons.Rounded.AspectRatio, when (resizeMode) { AspectRatioFrameLayout.RESIZE_MODE_FIT -> "Вписать"; AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Заполнить"; else -> "Растянуть" }) {
                        resizeMode = when (resizeMode) { AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM; AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL; else -> AspectRatioFrameLayout.RESIZE_MODE_FIT }
                        touch()
                    }
                    BarButton(Icons.Rounded.MoreHoriz, "Ещё") { panel = Panel.MORE }
                }
            }
        }
    }

    @Composable
    private fun RoundButton(icon: ImageVector, size: androidx.compose.ui.unit.Dp, onClick: () -> Unit) {
        Box(Modifier.size(size).clip(CircleShape).background(Color(0x66000000)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(size * 0.55f))
        }
    }

    @Composable
    private fun BarButton(icon: ImageVector, label: String, onClick: () -> Unit) {
        Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(24.dp))
            Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, maxLines = 1)
        }
    }

    @Composable
    private fun SidePanel(title: String, content: @Composable ColumnScope.() -> Unit) {
        Box(Modifier.fillMaxSize().clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { panel = Panel.NONE }) {
            Column(Modifier.align(Alignment.CenterEnd).fillMaxHeight().widthIn(max = 360.dp).fillMaxWidth(0.86f).background(Color(0xF21B1713)).systemBarsPadding()
                .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {}.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { panel = Panel.NONE }) { Icon(Icons.Rounded.Close, "Закрыть", tint = Color.White) }
                }
                Spacer(Modifier.height(8.dp))
                content()
            }
        }
    }

    @Composable
    private fun Options(options: List<TrackOption>, empty: String) {
        if (options.size <= 1) { Text(empty, color = Color.White.copy(alpha = 0.7f)); return }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            for (o in options) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { o.apply(); tracksVersion++; panel = Panel.NONE }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(o.title, color = Color.White, modifier = Modifier.weight(1f), fontWeight = if (o.selected) FontWeight.Bold else FontWeight.Normal)
                if (o.selected) Icon(Icons.Rounded.Check, null, tint = tones.tv)
            }
        }
    }

    @Composable
    private fun ChannelList() {
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = maxOf(0, index - 2))
        LazyColumn(state = listState) {
            itemsIndexed(queue) { i, c ->
                val selected = i == index
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(if (selected) Color(0x33FFFFFF) else Color.Transparent).clickable { switchTo(i) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Dot(statusColor(c.status), 8.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                        val now = remember(c.id) { Epg.now(c) }
                        if (now != null) Text(now.title, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (c.favorite) Icon(Icons.Rounded.Star, null, tint = Color(0xFFFFC857), modifier = Modifier.size(16.dp))
                }
            }
        }
    }

    @Composable
    private fun MorePanel() {
        val channel = current
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (channel != null && Playlist.catchupUrl(channel, 0, 1) != null) MoreRow(Icons.Rounded.History, "Архив передач", "Посмотреть то, что уже прошло") { panel = Panel.ARCHIVE }
            if (archiveTitle.isNotEmpty()) MoreRow(Icons.Rounded.LiveTv, "Вернуться в прямой эфир", "") { panel = Panel.NONE; start() }
            MoreRow(Icons.Rounded.ScreenRotation, if (landscape) "Автоповорот" else "Закрепить горизонтально", "") {
                landscape = !landscape
                requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                panel = Panel.NONE
            }
            MoreRow(Icons.Rounded.QueryStats, if (showStats) "Скрыть сведения о потоке" else "Сведения о потоке", "Разрешение, кодек, скорость, буфер") { showStats = !showStats; panel = Panel.NONE }
            Text("Таймер сна" + if (sleepAt > 0) " · до " + SimpleDateFormat("HH:mm", Locale.US).format(Date(sleepAt)) else "", color = Color.White, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp, start = 12.dp))
            Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (m in listOf(0, 15, 30, 60, 90)) AssistChip(onClick = { setSleep(m); panel = Panel.NONE }, label = { Text(if (m == 0) "Выкл" else "$m мин", color = Color.White) })
            }
        }
    }

    @Composable
    private fun MoreRow(icon: ImageVector, title: String, text: String, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.White)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall)
                if (text.isNotEmpty()) Text(text, color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable
    private fun ArchivePanel() {
        val channel = current ?: return
        val now = System.currentTimeMillis()
        val past = remember(index) { Epg.forChannel(channel).filter { it.stop < now }.sortedByDescending { it.start }.take(40) }
        if (past.isEmpty()) { Text("Для архива нужна телепрограмма. Загрузите её в настройках раздела ТВ.", color = Color.White.copy(alpha = 0.7f)); return }
        val format = remember { SimpleDateFormat("d MMM, HH:mm", Locale("ru")) }
        LazyColumn {
            itemsIndexed(past) { _, p ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                    val url = Playlist.catchupUrl(channel, p.start, p.stop)
                    if (url != null) { panel = Panel.NONE; triedAlternate = false; forcedMime = null; start(url); archiveTitle = p.title }
                }.padding(12.dp)) {
                    Column {
                        Text(p.title, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(format.format(Date(p.start)), color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
