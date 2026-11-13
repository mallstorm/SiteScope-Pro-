package pro.sitescope.app

import android.app.Application
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
class DeviceRegistryTest {
    @Before @After fun reset() = TestStores.reset()
    private val app get() = RuntimeEnvironment.getApplication() as Application
    private val lan = NetworkScope("test:home", "Дом", true)
    private val uuid = "b401d8fe-1a4e-4aef-bdd4-2778c6f04a66"
    private fun found(ip: String, id: String = "", scope: NetworkScope = lan, time: Long = 10) =
        DeviceRegistryStore.discovery(Models.DiscoveredDevice().apply {
            this.ip = ip; port = 1900; protocol = "SSDP/UPnP"; name = "Same model"; stableId = id
            location = "http://$ip:80/device.xml"; this.scope = scope
        }, scope, time)
    private fun record(vararg o: DeviceRegistryStore.Observation) = DeviceRegistryStore.recordBatch(app, o.toList())
    private fun all() = DeviceRegistryStore.list(app, "", 100).map { DeviceRegistryStore.get(app, it.id, 100) }
    private fun cardFor(id: String) = all().single { c -> c.observations.any { it.id == id } }

    @Test fun dhcpMoveRetainsCardAliasAddressesEvidenceAndSurvivesProcessRecreation() {
        val first = found("192.168.1.10", "ssdp:uuid:$uuid"); record(first)
        val original = cardFor(first.id).id
        DeviceRegistryStore.rename(app, original, "Камера у двери")
        record(found("192.168.1.25", first.stableId, time = 20))
        TestStores.reset()
        val card = all().single()
        assertEquals(original, card.id); assertEquals("Камера у двери", card.title)
        assertEquals(original, DeviceRegistryStore.list(app, "КАМЕРА", 10).single().id)
        assertTrue(card.addresses.contains("192.168.1.10")); assertTrue(card.addresses.contains("192.168.1.25"))
        assertEquals(2, card.memberCount); assertEquals(20L, card.updatedAt)
        assertEquals("SSDP/UPnP", card.observations.first().data.getString("protocol"))
    }

    @Test fun identicalNamesDifferentPortsOrNetworkNamespacesNeverIdentifyHardware() {
        val a = found("192.168.1.10", "ssdp:uuid:$uuid"); record(a)
        record(found(a.host, a.stableId, NetworkScope("test:office", "Офис", true)))
        val scan = Models.PortResult().apply { ip = a.host; port = 22; service = "SSH"; confidence = "high"; open = true; hostname = "Same model" }
        record(DeviceRegistryStore.scan(scan, lan, 12))
        record(found("192.168.1.11"))
        assertEquals(4, all().size)
    }

    @Test fun duplicateUuidWithinBatchStaysSeparateAndLaterObservationIsAmbiguous() {
        val a = found("192.168.1.10", "ssdp:uuid:$uuid")
        val b = found("192.168.1.11", a.stableId)
        record(a, b)
        assertEquals(2, all().size); assertTrue(all().flatMap { it.observations }.all { it.ambiguous })
        val third = found("192.168.1.12", a.stableId, time = 20); record(third)
        assertEquals(3, all().size); assertTrue(cardFor(third.id).observations.single().ambiguous)
    }

    @Test fun newUuidAtReusedAddressDoesNotReplacePreviousDevice() {
        val a = found("192.168.1.10", "ssdp:uuid:$uuid"); record(a)
        val b = found(a.host, "ssdp:uuid:41ef4cb5-82a2-423c-ac3f-3dbfa1aa241a", time = 20); record(b)
        assertEquals(2, all().size); assertNotEquals(cardFor(a.id).id, cardFor(b.id).id)
    }

    @Test fun mergeAndSplitAreStickyDuringRediscoveryAndKeepBothNames() {
        val a = found("192.168.1.10"); val b = found("192.168.1.11"); record(a, b)
        val ca = cardFor(a.id).id; val cb = cardFor(b.id).id
        DeviceRegistryStore.rename(app, ca, "Камера"); DeviceRegistryStore.rename(app, cb, "Вход")
        DeviceRegistryStore.merge(app, ca, cb)
        assertEquals(1, all().size); assertTrue(all().single().priorNames.contains("Вход"))
        a.seenAt = 30; b.seenAt = 30; record(a, b)
        assertEquals(2, all().single().memberCount)
        val split = DeviceRegistryStore.split(app, ca, listOf(b.id), "Другой сервис")
        a.seenAt = 40; b.seenAt = 40; record(a, b)
        assertEquals(ca, cardFor(a.id).id); assertEquals(split, cardFor(b.id).id)
        assertTrue(all().flatMap { it.observations }.all { it.manual })
    }

    @Test fun manualSplitOfStableIdPreventsFutureAutomaticRecombination() {
        val a = found("192.168.1.10", "ssdp:uuid:$uuid")
        val b = found("192.168.1.11", a.stableId, time = 20)
        record(a); record(b)
        val card = all().single()
        DeviceRegistryStore.split(app, card.id, listOf(b.id), "Отдельно")
        val c = found("192.168.1.12", a.stableId, time = 30); record(c)
        assertEquals(3, all().size); assertTrue(cardFor(c.id).observations.single().ambiguous)
    }

    @Test fun savedConnectionAndScanShareScopedEndpointWithoutCopyingCredentials() {
        val r = Models.PortResult().apply { ip = "192.168.1.10"; port = 22; service = "SSH"; confidence = "high"; open = true }
        record(DeviceRegistryStore.scan(r, lan, 10))
        val e = ServiceEndpoint.from(r).apply { scope = lan }
        ConnectionProfileStore.save(app, e); DeviceRegistryStore.sync(app)
        assertEquals(1, all().size); assertEquals(2, all().single().memberCount)
        val secret = ServiceEndpoint.from(r).apply { scope = lan; uri = "ssh://test:do-not-copy@192.168.1.10:22" }
        ConnectionProfileStore.save(app, secret); DeviceRegistryStore.sync(app)
        val graph = DeviceRegistryStore.exportGraph(AppDatabase.get(app).read()).toString()
        assertFalse(graph.contains("do-not-copy")); assertFalse(graph.contains("ssh://test:"))
        val profiles = all().flatMap { it.observations }.filter { it.kind == "profile" }
        assertEquals(2, profiles.size)
        assertTrue(profiles.any { it.endpoint(app).uri.contains("do-not-copy") })
        ConnectionProfileStore.remove(app, ConnectionProfileStore.idFor(secret)); DeviceRegistryStore.sync(app)
        assertEquals(1, all().flatMap { it.observations }.count { it.kind == "profile" })
    }

    @Test fun portableBackupRestoresManualAssignmentsAndRejectsDanglingLinksAtomically() {
        val a = found("192.168.1.10"); val b = found("192.168.1.11"); record(a, b)
        val target = cardFor(a.id).id
        DeviceRegistryStore.merge(app, target, cardFor(b.id).id); DeviceRegistryStore.rename(app, target, "Дом")
        val backup = AppDatabase.get(app).exportPortable()
        DeviceRegistryStore.split(app, target, listOf(b.id), "Временно")
        AppDatabase.get(app).restorePortable(backup)
        assertEquals(target, all().single().id); assertEquals("Дом", all().single().title)
        assertTrue(all().single().observations.all { it.manual })
        val broken = JSONObject(backup.toString())
        broken.getJSONObject("deviceRegistry").getJSONArray("observations").getJSONObject(0).put("cardId", "missing")
        assertThrows(Exception::class.java) { AppDatabase.get(app).restorePortable(broken) }
        assertEquals(target, all().single().id); assertEquals(2, all().single().memberCount)
        val old = JSONObject(backup.toString()).apply { remove("deviceRegistry") }
        AppDatabase.get(app).restorePortable(old); assertEquals(0, DeviceRegistryStore.count(app))
    }

    @Test fun discoveryAndScansPersistBeyondSourceHistoryRetentionAndRemainSearchable() {
        DeviceInventoryStore.saveSnapshot(app, listOf(Models.DiscoveredDevice().apply { ip = "192.168.1.1"; port = 22; protocol = "SSH"; name = "First"; scope = lan }), "First")
        val session = Models.ScanSession().apply {
            scope = lan; timestamp = 40; targetExpression = "192.168.1.2"
            openResults.add(Models.PortResult().apply { ip = "192.168.1.2"; port = 22; service = "SSH"; open = true; confidence = "high" })
        }
        DataStore(app).saveScan(session)
        DeviceInventoryStore.clear(app)
        AppDatabase.get(app).write().delete("scan_sessions", null, null)
        DeviceRegistryStore.sync(app)
        assertEquals(2, all().size)
        assertEquals(1, DeviceRegistryStore.list(app, "First", 10).size)
        assertEquals(1, DeviceRegistryStore.list(app, "192.168.1.2", 10).size)
    }

    @Test fun versionTwoUpgradePreservesProfilesAndAddsRegistry() {
        val e = ServiceEndpoint().apply { host = "192.168.1.15"; port = 22; protocol = "SSH"; uri = "ssh://192.168.1.15:22" }
        ConnectionProfileStore.save(app, e)
        val db = AppDatabase.get(app).write()
        db.execSQL("DROP TABLE device_observations"); db.execSQL("DROP TABLE device_cards"); db.version = 2
        TestStores.reset()
        DeviceRegistryStore.sync(app)
        assertEquals(4, AppDatabase.get(app).read().version)
        assertEquals(1, all().size); assertEquals(e.host, ConnectionProfileStore.list(app).single().host)
    }

    @Test fun oldIpAliasMigratesOnlyToLegacyDiscoveryCards() {
        DeviceInventoryStore.setAlias(app, "192.168.1.10", "Старое имя")
        DeviceInventoryStore.saveSnapshot(app, listOf(Models.DiscoveredDevice().apply { ip = "192.168.1.10"; port = 22; protocol = "SSH" }), "Old")
        DeviceRegistryStore.sync(app)
        assertEquals("Старое имя", all().single().title)
        record(found("192.168.1.10"))
        assertEquals(1, all().count { it.alias == "Старое имя" })
    }

    @Test fun completeBackupRoundTripPreservesProfileReferenceAfterRedaction() {
        val endpoint = ServiceEndpoint().apply { host = "192.168.1.9"; port = 22; protocol = "SSH"; scope = lan; uri = "ssh://user:never-export@192.168.1.9:22" }
        ConnectionProfileStore.save(app, endpoint); DeviceRegistryStore.sync(app)
        val original = all().single().id
        val backup = BackupManager.createBackup(app)
        assertFalse(backup.toString().contains("never-export"))
        BackupManager.restore(app, backup); DeviceRegistryStore.sync(app)
        assertEquals(original, all().single().id)
        val o = all().single().observations.single()
        assertEquals(ConnectionProfileStore.list(app).single().deviceRef, o.sourceId)
        assertNotNull(o.endpoint(app))
    }

    @Test fun uuidParserAcceptsOnlyStructuredIdentityAndRejectsZeroNameAndMessageId() {
        assertEquals("ssdp:uuid:$uuid", DiscoveryIdentity.ssdp("uuid:${uuid.uppercase()}::upnp:rootdevice"))
        assertEquals("", DiscoveryIdentity.ssdp("My Camera")); assertEquals("", DiscoveryIdentity.ssdp("uuid:00000000-0000-0000-0000-000000000000"))
        fun xml(body: String) = """<s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:a="http://schemas.xmlsoap.org/ws/2004/08/addressing"><s:Body><d:ProbeMatches>$body</d:ProbeMatches></s:Body></s:Envelope>"""
        val match = "<d:ProbeMatch><a:EndpointReference><a:Address>urn:uuid:$uuid</a:Address></a:EndpointReference></d:ProbeMatch>"
        assertEquals("onvif:uuid:$uuid", DiscoveryIdentity.onvif(xml(match)))
        assertEquals("", DiscoveryIdentity.onvif(xml(match + match)))
        assertEquals("", DiscoveryIdentity.onvif(xml(match.replace("a:EndpointReference", "d:EndpointReference"))))
        assertEquals("", DiscoveryIdentity.onvif(xml("<a:MessageID>uuid:$uuid</a:MessageID>")))
        assertEquals("", DiscoveryIdentity.onvif("<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///not-read'>]>" + xml(match)))
    }
}
