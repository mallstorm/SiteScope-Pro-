package pro.sitescope.app.net

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pro.sitescope.app.ExposureStore

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class ExposureWatchTest {
    private val saved = listOf(ExposureStore.Target().apply {
        host = "service.example"
        name = "Мой сервер"
        ports = "443,8443"
    })

    private fun run(
        time: Long,
        state: String,
        address: String = "1.1.1.1",
        port: Int = 443,
        transport: String = "cellular",
        external: Boolean = transport == "cellular",
        host: String = "service.example",
    ) = JSONObject()
        .put("id", time.toString())
        .put("time", time)
        .put("host", host)
        .put("source", transport)
        .put("transport", transport)
        .put("externalPoint", external)
        .put("total", 1)
        .put("results", JSONArray().put(JSONObject().put("address", address).put("port", port).put("state", state)))

    @Test fun transitionsRequireEarlierDefinitiveObservation() {
        val data = ExposureWatch.summarize(saved, listOf(run(4, "open"), run(1, "open"), run(3, "refused"), run(2, "timeout")))
        assertEquals(listOf(ExposureWatch.State.Open, ExposureWatch.State.Refused), data.events.map { it.current.state })
        assertEquals(listOf(3L, 1L), data.events.map { it.previous.time })
        assertEquals(4L, data.latest.single().time)
        assertTrue(data.latest.single().matches(listOf("СЕРВЕР", "443", "1.1.1.1")))
        assertFalse(data.latest.single().matches(listOf("8443")))
        assertTrue(data.latest.single().matches(listOf("port:443", "ip:1.1.1.1", "host:service")))
        assertFalse(data.latest.single().matches(listOf("port:43")))
    }

    @Test fun uncertaintyIsVisibleWithoutCreatingPortChange() {
        val data = ExposureWatch.summarize(saved, listOf(run(1, "open"), run(2, "timeout"), run(3, "refused").put("cancelled", true), run(4, "refused").put("total", 2)))
        assertEquals(ExposureWatch.State.NoResponse, data.latest.single().state)
        assertTrue(data.events.isEmpty())
    }

    @Test fun comparesOnlySavedEndpointsWithComparableSources() {
        val data = ExposureWatch.summarize(
            saved, listOf(
                run(1, "open"),
                run(2, "refused", transport = "wifi"),
                run(3, "refused", external = false),
                run(4, "refused", address = "1.0.0.1"),
                run(5, "refused", port = 8443),
                run(6, "refused", port = 9443),
                run(7, "refused", host = "other.example"),
            ),
        )
        assertEquals(5, data.latest.size)
        assertTrue(data.events.isEmpty())
        assertTrue(ExposureWatch.summarize(emptyList(), listOf(run(1, "open"))).latest.isEmpty())
    }
}
