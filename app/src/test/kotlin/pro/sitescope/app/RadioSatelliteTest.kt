package pro.sitescope.app

import android.hardware.SensorManager
import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import pro.sitescope.app.satellite.*
import pro.sitescope.app.radio.*

class RadioSatelliteTest {
    @Test fun visorAimingUsesCameraAxesDeclinationAndDisplayRotation() {
        val cameraNorth = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        val target = DishAngles(17.0, 1.0, null, 42000.0)
        val portrait = cameraAim(target, cameraNorth, Surface.ROTATION_0, 15.0)!!
        assertEquals(2.0, portrait.horizontal, 0.001)
        assertEquals(1.0, portrait.vertical, 0.001)
        val landscape = cameraAim(target, cameraNorth, Surface.ROTATION_90, 15.0)!!
        assertEquals(1.0, landscape.horizontal, 0.002)
        assertEquals(-2.0, landscape.vertical, 0.002)
        assertEquals(portrait.error, landscape.error, 1e-6)
        val behind = cameraAim(target.copy(azimuth = 195.0, elevation = 0.0), cameraNorth, Surface.ROTATION_0, 15.0)!!
        assertTrue(behind.forward < 0)
        assertEquals(180.0, behind.error, 1e-6)
    }

    @Test fun visorHandlesZenithEvenWhenSatelliteAzimuthIsUndefined() {
        val cameraUp = floatArrayOf(1f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, -1f)
        val zenith = DishAngles(null, 90.0, null, 35786.0)
        assertEquals(0.0, cameraAim(zenith, cameraUp, Surface.ROTATION_0, 0.0)!!.error, 1e-6)
        assertNull(cameraAim(zenith.copy(elevation = 20.0), cameraUp, Surface.ROTATION_0, 0.0))
    }

    @Test fun visorLocksOnlyAfterStableAlignmentAndReleasesWithHysteresis() {
        val normal = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        assertTrue(reliableAim(normal, 3.0))
        assertFalse(reliableAim(normal, 12.0))
        assertFalse(reliableAim(SensorManager.SENSOR_STATUS_ACCURACY_LOW, 1.0))
        val aiming = advanceAimLock(AimLock(), 0.8, true, 10L)
        assertFalse(advanceAimLock(aiming, 0.7, true, 299_000_010L).aligned)
        assertEquals(AimLock(), advanceAimLock(aiming, 1.3, true, 200_000_010L))
        val aligned = advanceAimLock(aiming, 0.7, true, 300_000_010L)
        assertTrue(aligned.aligned)
        assertTrue(advanceAimLock(aligned, 1.5, true, 400_000_010L).aligned)
        assertFalse(advanceAimLock(aligned, 2.0, true, 400_000_010L).aligned)
        assertFalse(advanceAimLock(aligned, 0.1, false, 400_000_010L).aligned)
    }

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
