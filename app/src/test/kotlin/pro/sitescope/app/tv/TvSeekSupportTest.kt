package pro.sitescope.app.tv

import android.app.Application
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class TvSeekSupportTest {
    private class Playback {
        var duration = 120000L; var position = 30000L; var buffered = 50000L; var live = false; var seekable = true; var command = true
        var item = MediaItem.fromUri("https://example.test/video.mp4"); var lastSeek: Long? = null
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args -> when (method.name) {
            "getDuration" -> duration; "getCurrentPosition" -> position; "getBufferedPosition" -> buffered
            "isCurrentMediaItemLive" -> live; "isCurrentMediaItemSeekable" -> seekable; "isCommandAvailable" -> command
            "getCurrentLiveOffset" -> if (live) 15000L else C.TIME_UNSET
            "getCurrentMediaItem" -> item
            "seekTo" -> { lastSeek = args!![0] as Long; null }
            else -> when (method.returnType) { java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L; else -> null }
        } } as Player
    }
    @Test fun vodTimelineUsesMediaPositionAndCommitsOnlyTheSelectedPosition() {
        val p = Playback(); val state = SeekSupport.read(p.player)
        assertEquals(0.25f, state.fraction, .0001f); assertEquals(120000L, state.duration); assertTrue(state.seekable)
        assertTrue(SeekSupport.seek(p.player, .75f, p.item)); assertEquals(90000L, p.lastSeek)
    }
    @Test fun unknownZeroAndNegativeDurationNeverEnableSeekingOrCreateInvalidFractions() {
        for (duration in listOf(C.TIME_UNSET, 0L, -1L)) {
            val p = Playback().apply { this.duration = duration }
            val state = SeekSupport.read(p.player); assertFalse(state.seekable); assertEquals(0f, state.fraction, .0001f)
            assertFalse(SeekSupport.seek(p.player, .5f, p.item)); assertNull(p.lastSeek)
        }
    }
    @Test fun liveDvrIsSeekableButLiveWithoutDvrOrPlayerCommandIsDisabled() {
        val p = Playback().apply { live = true }
        assertTrue(SeekSupport.read(p.player).seekable); assertEquals(15000L, SeekSupport.read(p.player).liveOffset)
        p.seekable = false; assertFalse(SeekSupport.read(p.player).seekable)
        p.seekable = true; p.command = false; assertFalse(SeekSupport.seek(p.player, .5f, p.item))
        assertTrue(SeekSupport.read(null, true).live)
    }
    @Test fun draggingCannotSeekAnotherChannelOrRestartOfTheSameUrl() {
        val p = Playback(); val previous = p.item
        p.item = MediaItem.fromUri("https://example.test/another.mp4")
        assertFalse(SeekSupport.seek(p.player, .5f, previous))
        p.item = previous
        assertFalse(SeekSupport.seek(p.player, .5f, previous, 3, 4)); assertNull(p.lastSeek)
    }
    @Test fun changedDvrWindowClampsToItsLatestDurationAndRejectsNan() {
        val p = Playback(); val item = p.item
        p.duration = 40000
        assertTrue(SeekSupport.seek(p.player, 1.2f, item)); assertEquals(40000L, p.lastSeek)
        assertTrue(SeekSupport.seek(p.player, -1f, item)); assertEquals(0L, p.lastSeek)
        assertFalse(SeekSupport.seek(p.player, Float.NaN, item)); assertFalse(SeekSupport.seek(p.player, Float.POSITIVE_INFINITY, item))
    }
    @Test fun timeAndPositionsAreValidForLongRecordingsAndUnloadedPlayers() {
        assertEquals("1:02:03", SeekSupport.time(3723000)); assertEquals("00:00", SeekSupport.time(-100))
        val p = Playback().apply { position = 500000L; buffered = -1L }
        val state = SeekSupport.read(p.player); assertEquals(120000L, state.position); assertEquals(0L, state.buffered)
        assertFalse(SeekSupport.read(p.player, true).live) // A known VOD duration is not turned into a live stream by its transport.
        assertEquals(0L, SeekSupport.read(null).duration)
    }
}
