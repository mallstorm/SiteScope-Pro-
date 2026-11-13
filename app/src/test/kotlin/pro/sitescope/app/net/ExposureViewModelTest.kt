package pro.sitescope.app.net

import android.app.Application
import android.net.*
import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import pro.sitescope.app.*
import java.net.InetAddress
import java.net.ServerSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class, shadows = [ExposureViewModelTest.Manager::class, ExposureViewModelTest.ProbeNetwork::class])
class ExposureViewModelTest {
    @Implements(ConnectivityManager::class)
    class Manager {
        companion object { var requests = 0; var releases = 0; var available = true; var waiting = false; val network: Network get() = ShadowNetwork.newInstance(700) }
        @Implementation fun getActiveNetwork() = network
        @Implementation fun getNetworkCapabilities(n: Network): NetworkCapabilities = NetworkCapabilities().apply { Shadow.extract<ShadowNetworkCapabilities>(this).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR) }
        @Implementation fun requestNetwork(request: NetworkRequest, callback: ConnectivityManager.NetworkCallback, timeout: Int) {
            requests++; if (waiting) return
            if (available) callback.onAvailable(network) else callback.onUnavailable()
        }
        @Implementation fun unregisterNetworkCallback(callback: ConnectivityManager.NetworkCallback) { releases++ }
    }
    @Implements(Network::class)
    class ProbeNetwork : ShadowNetwork() {
        companion object { var resolutions = 0 }
        @Implementation fun getAllByName(host: String): Array<InetAddress> { resolutions++; return arrayOf(InetAddress.getByName("127.0.0.1")) }
        @Implementation fun getByName(host: String): InetAddress = InetAddress.getByName("127.0.0.1")
    }
    @Before @After fun reset() { TestStores.reset(); Manager.requests = 0; Manager.releases = 0; Manager.available = true; Manager.waiting = false; ProbeNetwork.resolutions = 0 }
    private val app get() = RuntimeEnvironment.getApplication() as Application
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 8000
        while (!condition() && System.currentTimeMillis() < deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10) }
        assertTrue(condition())
    }
    private fun model(store: ViewModelStore): ExposureViewModel = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[ExposureViewModel::class.java]
    @Test fun mobileLeaseBindsProbeRecordsLoopbackAsLocalAndAlwaysReleasesRequest() {
        val store = ViewModelStore()
        ServerSocket(0, 16, InetAddress.getByName("127.0.0.1")).use { server ->
            try {
                val vm = model(store); vm.host = "owned.example"; vm.ports = server.localPort.toString()
                vm.start(); assertEquals(0, Manager.requests)
                vm.ownership = true; vm.start(); waitFor { !vm.busy }
                assertEquals(1, Manager.requests); assertEquals(1, Manager.releases)
                val run = ExposureStore.runs(app).single(); assertEquals("cellular", run.getString("transport"))
                assertEquals("open", run.getJSONArray("results").getJSONObject(0).getString("state"))
                assertFalse(ExposurePoint.confirmed(run, run.getJSONArray("results").getJSONObject(0)))
            } finally { store.clear() }
        }
    }
    @Test fun unavailableMobileDoesNotFallBackToCurrentNetwork() {
        val store = ViewModelStore(); Manager.available = false
        try {
            val vm = model(store); vm.host = "owned.example"; vm.ports = "443"; vm.ownership = true; vm.start(); waitFor { !vm.busy }
            assertEquals(1, Manager.requests); assertEquals(1, Manager.releases); assertEquals(0, ProbeNetwork.resolutions)
            val run = ExposureStore.runs(app).single(); assertFalse(run.getBoolean("externalPoint")); assertTrue(run.getString("error").contains("Мобильная"))
        } finally { store.clear() }
    }
    @Test fun cancellationDuringNetworkAcquisitionReleasesCallbackAndSavesPartialRun() {
        val store = ViewModelStore(); Manager.waiting = true
        try {
            val vm = model(store); vm.host = "owned.example"; vm.ports = "443"; vm.ownership = true; vm.start(); waitFor { Manager.requests == 1 }
            vm.cancel(); waitFor { !vm.busy }
            assertEquals(1, Manager.releases); assertEquals(0, ProbeNetwork.resolutions); assertEquals("", vm.error)
            val run = ExposureStore.runs(app).single(); assertTrue(run.getBoolean("cancelled")); assertFalse(run.getBoolean("externalPoint"))
        } finally { store.clear() }
    }
}
