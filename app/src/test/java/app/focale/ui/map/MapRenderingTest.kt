package app.focale.ui.map

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.focale.map.BasemapReader
import app.focale.ui.theme.seededColorScheme
import app.focale.ui.theme.toAmoled
import com.materialkolor.PaletteStyle
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderArray
import org.osmdroid.tileprovider.modules.MapTileModuleProviderBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Rendu réel (graphismes natifs) de la carte hors ligne, comme dans l'application :
 * vue osmdroid sans module de tuiles, fond vectoriel aux couleurs du thème. Les aperçus
 * sont écrits dans `build/tmp/` pour contrôle visuel.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class MapRenderingTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val basemap = File("src/main/assets/geo/basemap.bin").inputStream().use(BasemapReader::read)
    private val seed = Color(0xFF3F6FD8)

    @Test
    fun `thème clair, terres et mer distinctes`() = check("light", MapPalette.from(seededColorScheme(seed, false, PaletteStyle.TonalSpot)))

    @Test
    fun `thème sombre, terres et mer distinctes`() = check("dark", MapPalette.from(seededColorScheme(seed, true, PaletteStyle.TonalSpot)))

    @Test
    fun `noir pur, terres et mer distinctes`() =
        check("amoled", MapPalette.from(seededColorScheme(seed, true, PaletteStyle.TonalSpot).toAmoled()))

    private fun check(name: String, palette: MapPalette) {
        Configuration.getInstance().apply {
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }
        val mapView = MapView(context, MapTileProviderArray(TileSourceFactory.MAPNIK, null, emptyArray<MapTileModuleProviderBase>()))
        mapView.overlayManager.tilesOverlay.isEnabled = false
        mapView.setMinZoomLevel(2.0)
        mapView.setMaxZoomLevel(17.0)
        mapView.overlays.add(BasemapOverlay(basemap, 2f).apply { setColors(palette.land, palette.water, palette.border) })
        mapView.overlays.add(CountryLabelOverlay(basemap.labels, french = true, context).apply { setColors(palette.label, palette.land) })
        mapView.measure(
            View.MeasureSpec.makeMeasureSpec(WIDTH, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(HEIGHT, View.MeasureSpec.EXACTLY),
        )
        mapView.layout(0, 0, WIDTH, HEIGHT)
        mapView.controller.setZoom(3.0)
        mapView.controller.setCenter(GeoPoint(30.0, 10.0))

        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        mapView.draw(Canvas(bitmap))
        File("build/tmp/map-$name.png").apply { parentFile?.mkdirs() }.outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }

        val projection = mapView.projection
        val sahara = projection.toPixels(GeoPoint(24.0, 13.0), null)
        val mediterranean = projection.toPixels(GeoPoint(34.5, 18.0), null)
        assertEquals(palette.land, bitmap.getPixel(sahara.x, sahara.y))
        assertEquals(palette.water, bitmap.getPixel(mediterranean.x, mediterranean.y))
    }

    private companion object {
        const val WIDTH = 720
        const val HEIGHT = 1000
    }
}
