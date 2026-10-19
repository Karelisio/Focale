package app.focale.edit

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Rendu réel (graphismes natifs Robolectric) de la géométrie d'édition. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class EditRendererTest {

    /** Image 4 × 2 : moitié gauche rouge, moitié droite bleue. */
    private fun source(): Bitmap = Bitmap.createBitmap(4, 2, Bitmap.Config.ARGB_8888).apply {
        for (x in 0 until 4) for (y in 0 until 2) setPixel(x, y, if (x < 2) Color.RED else Color.BLUE)
    }

    @Test
    fun `un quart de tour horaire place la gauche en haut`() {
        val out = EditRenderer.render(source(), EditState(quarterTurns = 1))
        assertEquals(2, out.width)
        assertEquals(4, out.height)
        assertEquals(Color.RED, out.getPixel(0, 0))
        assertEquals(Color.BLUE, out.getPixel(1, 3))
    }

    @Test
    fun `le miroir inverse gauche et droite`() {
        val out = EditRenderer.render(source(), EditState(flipHorizontal = true))
        assertEquals(Color.BLUE, out.getPixel(0, 0))
        assertEquals(Color.RED, out.getPixel(3, 1))
    }

    @Test
    fun `le recadrage garde la zone demandée`() {
        val out = EditRenderer.render(source(), EditState(crop = NormRect(0.5f, 0f, 1f, 1f)))
        assertEquals(2, out.width)
        assertEquals(2, out.height)
        assertEquals(Color.BLUE, out.getPixel(0, 0))
    }

    @Test
    fun `le filtre noir et blanc désature`() {
        val out = EditRenderer.render(source(), EditState(filter = FilterPreset.MONO))
        val pixel = out.getPixel(0, 0)
        assertEquals(Color.red(pixel), Color.green(pixel))
        assertEquals(Color.green(pixel), Color.blue(pixel))
    }
}
