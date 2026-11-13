package pro.sitescope.app

import org.junit.Assert.*
import org.junit.Test

class DeviceFingerprintTest {
    @Test fun reviewedFixturesRecognizeEightVendorsAndOnlyReportedModels() {
        val input = javaClass.getResourceAsStream("/fingerprints/http-identities.tsv")!!
        input.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith("#") }.forEach { line ->
                val v = line.split('|'); assertEquals(7, v.size)
                val d = DeviceFingerprintEngine.fromHttp(v[1], v[2], v[3], "")
                assertEquals(v[0], v[4], d.manufacturer)
                assertEquals(v[0], v[5], d.family)
                assertEquals(v[0], v[6], d.model)
                assertEquals("medium", d.confidence)
                assertTrue(d.findings.all { it.source.startsWith("HTTP") })
            }
        }
    }

    @Test fun genericSoftwareAndMarketingTitlesDoNotIdentifyHardware() {
        for (server in listOf("nginx", "Apache/2.4", "Boa/0.94", "App-webs", "GoAhead-Webs")) {
            val d = DeviceFingerprintEngine.fromHttp(server, "", "Welcome", "")
            assertFalse(d.isKnown); assertEquals("", d.model)
        }
        assertFalse(DeviceFingerprintEngine.fromHttp("nginx", "", "ASUS official website", "").isKnown)
        assertFalse(DeviceFingerprintEngine.fromHttp("", "", "Buy Hikvision accessories", "").isKnown)
    }

    @Test fun vendorDoesNotInventFamilyOrModel() {
        val d = DeviceFingerprintEngine.fromHttp("MikroTik", "", "", "")
        assertEquals("MikroTik", d.manufacturer)
        assertEquals("", d.family); assertEquals("", d.model)
        assertTrue(d.summary().contains("модель не определена"))
    }

    @Test fun combinesExplicitHttpBrandWithModelInTheSameResponseButNotWithCertificateAlone() {
        val http = DeviceFingerprintEngine.fromHttp("ASUS", "", "RT-AX58U", "")
        assertEquals("RT-AX58U", http.model)
        assertEquals("HTTP title", http.findings.single { it.field == "model" }.source)
        val tls = DeviceFingerprintEngine.fromHttp("nginx", "", "RT-AX58U", "O=ASUS")
        assertEquals("ASUS", tls.manufacturer); assertEquals("", tls.model); assertEquals("low", tls.confidence)
    }

    @Test fun certificateCnDoesNotIdentifyHardwareAndOrganizationRemainsUnverified() {
        assertFalse(DeviceFingerprintEngine.fromHttp("", "", "", "CN=ASUS RT-AX58U,O=Example Ltd").isKnown)
        val d = DeviceFingerprintEngine.fromHttp("", "", "", "CN=router,O=ASUSTeK COMPUTER INC.,C=TW")
        assertEquals("ASUS", d.manufacturer); assertEquals("low", d.confidence)
        assertEquals("", d.model); assertEquals("", d.family)
        assertTrue(d.findings.single().source.contains("не проверен"))
    }

    @Test fun conflictingVendorsAndModelsRemainUnresolvedWithEvidence() {
        val vendor = DeviceFingerprintEngine.fromHttp("Hikvision-Webs", "", "Dahua IPC-HFW1230S WebClient", "")
        assertTrue(vendor.conflicting); assertFalse(vendor.isKnown); assertEquals("", vendor.model)
        assertEquals(setOf("Hikvision", "Dahua"), vendor.findings.filter { it.field == "manufacturer" }.map { it.value }.toSet())
        val models = DeviceFingerprintEngine.fromHttp("", "", "ASUS RT-AX58U RT-AX86U Router", "")
        assertTrue(models.conflicting); assertEquals("", models.model)
    }

    @Test fun discoveryHardwareIdentifierIsNotAConfirmedModel() {
        val d = DeviceFingerprintEngine.fromOnvifScopes("hardware-id-123")
        assertEquals("ONVIF", d.family); assertEquals("", d.manufacturer); assertEquals("", d.model)
        assertEquals("hardware-id-123", d.findings.single { it.field == "hardware" }.value)
        assertFalse(DeviceFingerprintEngine.fromDiscoveryServer("Linux/3.10 UPnP/1.0 IP Camera").isKnown)
    }
}
