package pro.sitescope.app

import android.app.Application
import android.content.Intent
import androidx.compose.runtime.State
import androidx.media3.common.PlaybackException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pro.sitescope.app.tv.Channel
import pro.sitescope.app.tv.TvPlayerActivity

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class CameraPlayerStateTest {
    private fun state(player: TvPlayerActivity, name: String): Any? = (TvPlayerActivity::class.java.getDeclaredField(name + "\$delegate").apply { isAccessible = true }.get(player) as State<*>).value
    private fun error(code: Int, message: String): TvPlayerActivity {
        val player = Robolectric.buildActivity(TvPlayerActivity::class.java).get()
        val channel = Channel().apply { url = "rtsp://owner:do-not-show@127.0.0.1/live"; name = "Camera" }
        TvPlayerActivity::class.java.getDeclaredField("queue").apply { isAccessible = true }.set(player, listOf(channel))
        val method = TvPlayerActivity::class.java.getDeclaredMethod("handleError", PlaybackException::class.java).apply { isAccessible = true }
        method.invoke(player, PlaybackException(message, null, code))
        assertFalse(TvPlayerActivity::class.java.getDeclaredField("triedAlternate").apply { isAccessible = true }.getBoolean(player))
        assertEquals(false, state(player, "buffering"))
        assertFalse(state(player, "error").toString().contains("do-not-show"))
        return player
    }
    @Test fun rtspAuthorizationErrorDoesNotTryHlsOrExposeCredentials() {
        val player = error(PlaybackException.ERROR_CODE_IO_UNSPECIFIED, "RTSP 401 Unauthorized rtsp://owner:do-not-show@127.0.0.1/live")
        assertTrue(state(player, "error").toString().contains("авторизацию"))
    }
    @Test fun rtspTimeoutAndCodecFailureHaveSeparateMessages() {
        assertTrue(state(error(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, "timeout"), "error").toString().contains("таймаут"))
        assertTrue(state(error(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, "codec"), "error").toString().contains("Кодек"))
    }
    @Test fun expiredCameraHandoffCannotFallBackToUnrelatedTvPlaylist() {
        val player = Robolectric.buildActivity(TvPlayerActivity::class.java).get()
        player.intent = Intent().putExtra(CameraPlayback.EXTRA, "expired-token")
        TvPlayerActivity::class.java.getDeclaredMethod("readQueue").apply { isAccessible = true }.invoke(player)
        assertTrue((TvPlayerActivity::class.java.getDeclaredField("queue").apply { isAccessible = true }.get(player) as List<*>).isEmpty())
        TvPlayerActivity::class.java.getDeclaredMethod("start", String::class.java).apply { isAccessible = true }.invoke(player, null)
        assertTrue(state(player, "error").toString().contains("истёк"))
    }
}
