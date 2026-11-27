package pro.sitescope.app

import org.junit.Assert.*
import org.junit.Test
import pro.sitescope.app.satellite.*
import pro.sitescope.app.radio.*

class RadioSatelliteTest {
    @Test fun observerAtSubsatellitePointHasZenithAndUndefinedAzimuth() {
        val angles = dishAngles(SitePoint(0.0, 36.0), 36.0)
        assertEquals(90.0, angles.elevation, 0.0001)
        assertNull(angles.azimuth)
        assertNull(angles.skew)
        assertTrue(dishAngles(SitePoint(0.0, -144.0), 36.0).elevation < 0)
    }
    @Test fun northernAndSouthernObserversFaceEquator() {
        assertEquals(180.0, dishAngles(SitePoint(45.0, 36.0), 36.0).azimuth!!, 0.01)
        assertEquals(0.0, dishAngles(SitePoint(-45.0, 36.0), 36.0).azimuth!!, 0.01)
        assertEquals(-2.0, wrapped(358.0), 0.001)
        assertNull(point("NaN", "30"))
        assertNull(point("91", "30"))
        assertEquals(45.5, point("45,5", "30")!!.lat, 0.001)
    }
    @Test fun enigmaChannelsRetainTuningAndProvider() {
        val list = DishCatalogue.parse(listOf("eDVB services /4/", "transponders",
            "00c00000:03e9:0001", "s 11214250:22000000:0:2:192:2:0", "/", "end",
            "services", "0001:00c00000:03e9:0001:1:0", "Channel name", "p:Provider", "end"))
        assertEquals(1, list.size)
        assertEquals(19.2, list.single().orbital, 0.001)
        assertEquals("Channel name", list.single().channel)
        assertEquals("Provider", list.single().provider)
        assertTrue(list.single().tuning.contains("22000"))
    }
    @Test fun legacyAndCurrentClfPreserveLeadingZeroMncAndLongNrIdentity() {
        assertEquals("02", parseTower("256301FE26202\tinfo")!!.mnc)
        assertEquals(9571L, parseTower("095710051026202\tinfo")!!.cid)
        assertEquals(9571L, parseTower("26202;0x2563;0x01FE;0x0000;-90;180;-1;info;0")!!.cid)
        val nr = parseTower("N;262;02;510;68719476735;1;631000;45.5;30;100;station;31")!!
        assertEquals(nr, parseTower(nr.clf()))
        assertNull(parseTower("N;262;02;510;68719476736;;;;;;station"))
        assertNull(parseTower("L;262;02;510;123;;;NaN;30;10;station"))
    }
}
