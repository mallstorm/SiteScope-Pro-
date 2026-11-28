package pro.sitescope.app

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import pro.sitescope.app.radio.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class StationMapTest {
    @Test fun matchingUsesFullNetworkIdentityAndHandlesAmbiguousSectors() {
        val key = StationKey("L", "262", "02", 1010, 4242)
        val exact = Tower("L", "262", "02", 1010, 4242, 71, null, 50.1, 8.1, 20, "A")
        val otherSector = exact.copy(pci = 72, lat = 50.2)
        assertNull(localStation(key, null, listOf(exact.copy(mnc = "002"), exact.copy(rat = "N"), exact.copy(area = 1011))))
        assertNull(localStation(key, null, listOf(exact, otherSector)))
        assertEquals(exact, localStation(key, 71, listOf(otherSector, exact)))
        assertNull(localStation(key, 70, listOf(exact)))
    }

    @Test fun dataSimAndFreshServingRolesArePreserved() {
        val registered = RadioCell("SIM 1", "LTE", true, "262", "02", 1010, 4242, 71, null, -90, "", 1_000L, RadioLink.Primary)
        val primary = registered.copy(sim = "SIM 2", cid = 4243L)
        val secondary = primary.copy(rat = "NR", cid = 68_719_476_735L, link = RadioLink.Secondary)
        val neighbor = primary.copy(registered = false, link = RadioLink.Neighbor, cid = 4244L)
        val frame = RadioFrame(1010L, emptyList(), listOf(registered, neighbor, secondary, primary), null, true, "", "SIM 2")
        assertEquals(listOf(primary, secondary, registered, neighbor), currentStationCells(frame))
        assertEquals("N", secondary.stationKey()?.rat)
        val position = StationPosition(Tower("L", "262", "02", 1010, 4243, 71, null, 50.1, 8.1, 20, "A"))
        assertTrue(stationOnMap(primary, position, 1_500L).canDrawLink)
        assertFalse(stationOnMap(primary, position, 65_000L).canDrawLink)
        assertFalse(stationOnMap(neighbor, position, 1_500L).canDrawLink)
        assertEquals(StationMarker.Secondary, stationOnMap(secondary, null, 1_500L).marker)
    }

    @Test fun onlineCoordinatesMustMatchRequestedRatAndIdentifiers() {
        val response = JSONObject("""{"radio":"LTE","mcc":262,"mnc":2,"lac":1010,"cellid":4242,"lat":50.1,"lon":8.2,"range":2450,"samples":8}""")
        val key = StationKey("L", "262", "02", 1010, 4242)
        val point = openCellPosition(response, key)!!
        assertEquals(key, point.tower.stationKey())
        assertEquals(8, point.samples)
        assertNull(point.tower.accuracy)
        assertTrue(point.online)
        assertNull(openCellPosition(response, key.copy(rat = "N")))
        assertNull(openCellPosition(response, key.copy(cid = 4243)))
        assertNull(openCellPosition(response.put("lat", 99.0), key))
    }
}
