package pro.sitescope.app

import android.app.Application
import android.content.Intent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class DeviceIdentityPersistenceTest {
    @Before @After fun reset() = TestStores.reset()
    private fun response(fields: String) = """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"><s:Body><d:GetDeviceInformationResponse xmlns:d="http://www.onvif.org/ver10/device/wsdl">$fields</d:GetDeviceInformationResponse></s:Body></s:Envelope>"""

    @Test fun structuredOnvifReportsManufacturerModelAndSource() {
        val d = DeviceFingerprintEngine.fromOnvifXml(response("<d:Manufacturer>Hikvision</d:Manufacturer><d:Model>DS-2CD2143G0-I</d:Model>"))
        assertEquals("Hikvision", d.manufacturer); assertEquals("DS-2CD2143G0-I", d.model)
        assertEquals("high", d.confidence)
        assertTrue(d.findings.all { it.source == "ONVIF GetDeviceInformation" })
    }

    @Test fun ignoresUnrelatedXmlNamespacesFaultsAndExternalEntities() {
        assertFalse(DeviceFingerprintEngine.fromOnvifXml("<page><Manufacturer>ASUS</Manufacturer><Model>RT-AX58U</Model></page>").isKnown)
        assertFalse(DeviceFingerprintEngine.fromOnvifXml(response("<x:Model xmlns:x=\"urn:unrelated\">RT-AX58U</x:Model>")).isKnown)
        assertFalse(DeviceFingerprintEngine.fromOnvifXml("<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///not-read'>]>" + response("<d:Manufacturer>&x;</d:Manufacturer>")).isKnown)
        val fault = """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope"><s:Body><s:Fault><Model>ASUS</Model></s:Fault></s:Body></s:Envelope>"""
        assertFalse(DeviceFingerprintEngine.fromOnvifXml(fault).isKnown)
        val conflicting = DeviceFingerprintEngine.fromOnvifXml(response("<d:Manufacturer>ASUS</d:Manufacturer><d:Manufacturer>TP-Link</d:Manufacturer>"))
        assertTrue(conflicting.conflicting); assertEquals("", conflicting.manufacturer)
    }

    @Test fun scanHistoryAndEndpointIntentPreserveSeparateHardwareAndTlsFacts() {
        val d = DeviceFingerprintEngine.fromHttp("nginx", "", "ASUSWRT ASUS RT-AX58U Router", "")
        d.rulesVersion = 7
        val result = Models.PortResult().apply { target = "127.0.0.1"; port = 443; service = "HTTPS"; confidence = "high"; identity = d; tlsTrust = "not_checked" }
        val restored = Models.PortResult.fromJson(result.toJson())
        assertEquals("RT-AX58U", restored.identity.model)
        assertEquals("medium", restored.identity.confidence)
        assertEquals("high", restored.confidence)
        assertEquals("not_checked", restored.tlsTrust)
        assertEquals(7, restored.identity.rulesVersion)
        val intent = Intent(); ServiceEndpoint.from(restored).put(intent)
        val endpoint = ServiceEndpoint.from(intent)
        assertEquals(restored.identity.findings.size, endpoint.identity.findings.size)
        assertEquals("RT-AX58U", endpoint.identity.model)
        assertEquals("not_checked", endpoint.tlsTrust)
        val old = Models.PortResult.fromJson(JSONObject("{\"port\":443,\"service\":\"HTTPS\"}"))
        assertFalse(old.identity.isKnown)
    }

    @Test fun discoverySnapshotPreservesEvidenceAndUpgradesOlderAmbiguousFields() {
        val app = RuntimeEnvironment.getApplication() as Application
        val device = Models.DiscoveredDevice().apply {
            ip = "192.168.1.15"; protocol = "ONVIF WS-Discovery"
            identity = DeviceFingerprintEngine.fromOnvifScopes("hardware-42")
        }
        DeviceInventoryStore.saveSnapshot(app, listOf(device), "Test")
        val restored = DeviceInventoryStore.list(app).first().devices.single()
        assertEquals("hardware-42", restored.identity.findings.single { it.field == "hardware" }.value)
        assertEquals("", restored.model)
        val old = JSONObject("""{"devices":[{"ip":"192.168.1.15","protocol":"ONVIF WS-Discovery","model":"hardware-42"}]}""")
        val migrated = DeviceInventoryStore.Snapshot.fromJson(old).devices.single()
        assertEquals("", migrated.model)
        assertEquals(0L, migrated.identity.observedAt)
        assertEquals("hardware-42", migrated.identity.findings.single { it.field == "hardware" }.value)
    }
}
