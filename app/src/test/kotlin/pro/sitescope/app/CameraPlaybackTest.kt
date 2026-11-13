package pro.sitescope.app

import android.app.Activity
import android.app.Application
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class CameraPlaybackTest {
    @Before @After fun reset() = TestStores.reset()
    @Test fun intentAndJournalNeverReceiveTheCredentialBearingPlaybackUri() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            val activity = controller.get()
            CameraPlayback.open(activity, "http://127.0.0.1/device", "rtsp://127.0.0.1:8554/live", "owner", "private-test-password", "Main")
            val intent = shadowOf(activity).nextStartedActivity
            assertEquals(setOf(CameraPlayback.EXTRA, "name"), intent.extras!!.keySet())
            assertFalse(intent.extras.toString().contains("private-test-password"))
            val token = intent.getStringExtra(CameraPlayback.EXTRA)!!
            val uri = CameraPlayback.consume(token)
            assertEquals("rtsp://owner:private-test-password@127.0.0.1:8554/live", uri)
            assertNull(CameraPlayback.consume(token))
            assertTrue(ConnectionSessionStore.list(RuntimeEnvironment.getApplication()).isEmpty())
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun credentialsAreEncodedAndOldEmbeddedCredentialsAreRemoved() {
        val token = CameraPlayback.prepare("http://camera.test/device", "rtsp://old:old@camera.test/live", "new@user", "a:b /?#")
        assertEquals("rtsp://new%40user:a%3Ab%20%2F%3F%23@camera.test/live", CameraPlayback.consume(token))
    }
    @Test fun handoffRetainsSelectedNetworkOnlyInMemory() {
        val network = org.robolectric.shadow.api.Shadow.newInstanceOf(android.net.Network::class.java)
        val token = CameraPlayback.prepare("http://camera.test/", "rtsp://camera.test/live", "", "", network)
        val session = CameraPlayback.consumeSession(token)
        assertSame(network, session.network); assertEquals("rtsp://camera.test/live", session.uri)
        assertNull(CameraPlayback.consumeSession(token))
    }

    @Test fun expiredDiscardedAndExcessTokensCannotStartPlayback() {
        val first = CameraPlayback.prepare("http://camera.test/", "rtsp://camera.test/live", "u", "p")
        ShadowSystemClock.advanceBy(Duration.ofMinutes(3)); assertNull(CameraPlayback.consume(first))
        val tokens = (0..8).map { CameraPlayback.prepare("http://camera.test/", "rtsp://camera.test/live", "u", "p") }
        assertNull(CameraPlayback.consume(tokens.first()))
        CameraPlayback.discard(tokens.last()); assertNull(CameraPlayback.consume(tokens.last()))
        tokens.forEach { CameraPlayback.discard(it) }
    }
}
