package pro.sitescope.app.radio

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.osmdroid.tileprovider.ExpirableBitmapDrawable
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.modules.IFilesystemCache
import org.osmdroid.tileprovider.modules.INetworkAvailablityCheck
import org.osmdroid.tileprovider.modules.MapTileApproximater
import org.osmdroid.tileprovider.modules.MapTileDownloader
import org.osmdroid.tileprovider.modules.TileDownloader
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

internal object FieldImageryTiles : OnlineTileSourceBase("EsriWorldImageryCoverageV2", 0, 21, 256, ".jpg", arrayOf(
    "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"
)) {
    override fun getTileURLString(tile: Long): String =
        "$baseUrl${MapTileIndex.getZoom(tile)}/${MapTileIndex.getY(tile)}/${MapTileIndex.getX(tile)}?blankTile=false"
}

internal fun parentTile(tile: Long, levels: Int): Long {
    val zoom = MapTileIndex.getZoom(tile)
    require(levels in 1..zoom)
    return MapTileIndex.getTileIndex(zoom - levels, MapTileIndex.getX(tile) shr levels, MapTileIndex.getY(tile) shr levels)
}

/** Use the native cache and keep a scaled parent temporary, so it cannot mask real detail later. */
internal class FieldMapTileProvider(context: Context) : MapTileProviderBasic(context) {
    override fun createDownloaderProvider(network: INetworkAvailablityCheck, source: ITileSource): MapTileDownloader =
        super.createDownloaderProvider(network, source).apply { setTileDownloader(ImageryDownloader()) }
}

private class ImageryDownloader : TileDownloader() {
    override fun downloadTile(tile: Long, redirects: Int, url: String, cache: IFilesystemCache, source: OnlineTileSourceBase): Drawable? {
        val original = super.downloadTile(tile, redirects, url, cache, source)
        val zoom = MapTileIndex.getZoom(tile)
        if (original != null || redirects != 0 || source !== FieldImageryTiles || zoom <= 12) return original

        fun scaled(parent: BitmapDrawable, levels: Int): Drawable? =
            MapTileApproximater.approximateTileFromLowerZoom(parent, tile, levels)?.let { bitmap ->
                ExpirableBitmapDrawable(bitmap).apply {
                    ExpirableBitmapDrawable.setState(this, ExpirableBitmapDrawable.SCALED)
                }
            }

        for (levels in 1..4) {
            val parent = runCatching { cache.loadTile(source, parentTile(tile, levels)) }.getOrNull() as? BitmapDrawable ?: continue
            scaled(parent, levels)?.let { return it }
        }
        val levels = (zoom - 17).coerceIn(1, 4)
        val parent = parentTile(tile, levels)
        val fetched = super.downloadTile(parent, 0, source.getTileURLString(parent), cache, source) as? BitmapDrawable
        return fetched?.let { scaled(it, levels) }
    }
}
