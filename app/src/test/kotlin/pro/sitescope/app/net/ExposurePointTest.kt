package pro.sitescope.app.net

import android.app.Application
import android.net.NetworkCapabilities
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNetworkCapabilities

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class ExposurePointTest {
    private fun caps(type: Int) = NetworkCapabilities().apply { Shadow.extract<ShadowNetworkCapabilities>(this).addTransportType(type) }
    @Test fun onlyKnownCellularPointWithoutVpnCanConfirmExternalAccess() {
        val cell = caps(NetworkCapabilities.TRANSPORT_CELLULAR); val wifi = caps(NetworkCapabilities.TRANSPORT_WIFI); val vpn = caps(NetworkCapabilities.TRANSPORT_VPN)
        assertTrue(ExposurePoint.describe(cell, wifi).external)
        assertFalse(ExposurePoint.describe(wifi, cell).external)
        assertFalse(ExposurePoint.describe(cell, vpn).external); assertFalse(ExposurePoint.describe(vpn, cell).external)
        val mixed = caps(NetworkCapabilities.TRANSPORT_CELLULAR).apply { Shadow.extract<ShadowNetworkCapabilities>(this).addTransportType(NetworkCapabilities.TRANSPORT_WIFI) }
        assertFalse(ExposurePoint.describe(mixed, cell).external)
        assertFalse(ExposurePoint.describe(cell, null).external); assertFalse(ExposurePoint.describe(null, cell).external)
    }
    @Test fun openPrivateEndpointTimeoutAndPartialRunAreNotInternetProof() {
        val run = JSONObject().put("transport", "cellular").put("externalPoint", true).put("cancelled", false).put("error", "")
        val row = JSONObject().put("state", "open").put("address", "1.1.1.1")
        assertTrue(ExposurePoint.confirmed(run, row))
        row.put("address", "192.168.1.10"); assertFalse(ExposurePoint.confirmed(run, row)); row.put("address", "1.1.1.1")
        row.put("state", "timeout"); assertFalse(ExposurePoint.confirmed(run, row)); row.put("state", "open")
        run.put("cancelled", true); assertFalse(ExposurePoint.confirmed(run, row)); run.put("cancelled", false)
        run.put("error", "Lost network"); assertFalse(ExposurePoint.confirmed(run, row)); run.put("error", "")
        run.put("transport", "wifi"); assertFalse(ExposurePoint.confirmed(run, row))
    }
}
