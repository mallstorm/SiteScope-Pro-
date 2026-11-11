package pro.sitescope.app.net

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import pro.sitescope.app.ConnectionProfileStore
import pro.sitescope.app.DeviceInventoryStore
import pro.sitescope.app.Models
import pro.sitescope.app.ServiceEndpoint
import pro.sitescope.app.TestStores

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class NetViewModelTest {
    @Before @After fun resetSingletons() = TestStores.reset()
    @Test
    fun opensWithEmptyDataAndRestoresSavedNetworkDataAfterRecreation() {
        val app = RuntimeEnvironment.getApplication() as Application
        val factory = ViewModelProvider.AndroidViewModelFactory(app)
        var store = ViewModelStore()
        try {
            // The same factory used by the network screen must construct the model.
            var vm = ViewModelProvider(store, factory)[NetViewModel::class.java]
            assertTrue(vm.profiles.isEmpty())
            assertTrue(vm.wol.isEmpty())
            assertEquals(0, vm.snapshotCount)
            assertNull(vm.latest)
            assertFalse(vm.discovering)

            val endpoint = ServiceEndpoint().apply {
                protocol = "SSH"; host = "192.168.1.10"; port = 22
                displayName = "Home server"
            }
            ConnectionProfileStore.save(app, endpoint)
            val device = Models.DiscoveredDevice().apply {
                ip = endpoint.host; name = endpoint.displayName; protocol = "SSH"; port = 22
            }
            DeviceInventoryStore.saveSnapshot(app, listOf(device), "Test LAN")
            assertTrue(vm.addWol("Home server", "AA:BB:CC:DD:EE:0F"))

            store.clear()
            store = ViewModelStore()
            vm = ViewModelProvider(store, factory)[NetViewModel::class.java]
            assertEquals("Home server", vm.profiles.single().name)
            assertEquals(endpoint.host, vm.profiles.single().host)
            assertEquals("AA:BB:CC:DD:EE:0F", vm.wol.single().mac)
            assertEquals("Home server", vm.wol.single().name)
            assertEquals(1, vm.snapshotCount)
            assertEquals(endpoint.host, vm.latest!!.devices.single().ip)
            assertEquals(endpoint.host, vm.aggregates(vm.latest).single().ip)
        } finally {
            store.clear()
        }
    }
}
