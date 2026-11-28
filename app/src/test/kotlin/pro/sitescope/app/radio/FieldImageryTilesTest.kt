package pro.sitescope.app.radio

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.util.MapTileIndex
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class)
class FieldImageryTilesTest {
    @Test fun missingTileRequestAndParentUseCorrectArcGisCoordinates() {
        val tile = MapTileIndex.getTileIndex(21, 855_231, 1_289_671)
        assertTrue(FieldImageryTiles.getTileURLString(tile).endsWith("/21/1289671/855231?blankTile=false"))
        val parent = parentTile(tile, 4)
        assertEquals(MapTileIndex.getTileIndex(17, 53_451, 80_604), parent)
        assertTrue(FieldImageryTiles.getTileURLString(parent).endsWith("/17/80604/53451?blankTile=false"))
    }
}
