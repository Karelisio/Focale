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

    /** Aplat orange opaque de 120 × 80. */
    private fun plain(): Bitmap = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(ORANGE) }

    private fun assertOrange(message: String, pixel: Int) {
        assertEquals(message, 255, Color.alpha(pixel))
        assertEquals(message, Color.red(ORANGE).toFloat(), Color.red(pixel).toFloat(), 2f)
        assertEquals(message, Color.green(ORANGE).toFloat(), Color.green(pixel).toFloat(), 2f)
        assertEquals(message, Color.blue(ORANGE).toFloat(), Color.blue(pixel).toFloat(), 2f)
    }

    @Test
    fun `après redressement, aucun pixel transparent ni noir sur les bords`() {
        for (angle in listOf(-45f, -17.5f, 0.3f, 3f, 30f, 45f)) {
            for (turns in 0..1) {
                for (flip in listOf(false, true)) {
                    val (width, height) = if (turns == 0) 120f to 80f else 80f to 120f
                    val crop = StraightenMath.fit(NormRect.Full, angle, width, height)
                    val out = EditRenderer.render(plain(), EditState(quarterTurns = turns, flipHorizontal = flip, straighten = angle, crop = crop))
                    for (x in 0 until out.width) {
                        for (y in listOf(0, out.height - 1)) assertOrange("($x, $y) à $angle°, $turns quart(s) de tour, miroir $flip", out.getPixel(x, y))
                    }
                    for (y in 0 until out.height) {
                        for (x in listOf(0, out.width - 1)) assertOrange("($x, $y) à $angle°, $turns quart(s) de tour, miroir $flip", out.getPixel(x, y))
                    }
                }
            }
        }
    }

    @Test
    fun `sans recadrage automatique, les coins redressés seraient vides`() {
        val out = EditRenderer.render(plain(), EditState(straighten = 20f))
        assertEquals(120, out.width)
        assertEquals(80, out.height)
        assertEquals(0, Color.alpha(out.getPixel(0, 0)))
        assertEquals(0, Color.alpha(out.getPixel(119, 79)))
        assertOrange("centre", out.getPixel(60, 40))
    }

    @Test
    fun `le redressement tourne l'image dans le sens horaire`() {
        // Moitié haute rouge, moitié basse bleue : tournée dans le sens horaire, la limite
        // monte à gauche et descend à droite.
        val source = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until 200) for (y in 0 until 100) setPixel(x, y, if (y < 50) Color.RED else Color.BLUE)
        }
        val crop = StraightenMath.fit(NormRect.Full, 20f, 200f, 100f)
        val out = EditRenderer.render(source, EditState(straighten = 20f, crop = crop))
        val middle = out.height / 2
        assertEquals(Color.RED, out.getPixel(0, 0))
        assertEquals(Color.BLUE, out.getPixel(0, middle))
        assertEquals(Color.RED, out.getPixel(out.width - 1, middle))
        assertEquals(Color.BLUE, out.getPixel(out.width - 1, out.height - 1))
    }

    private companion object {
        val ORANGE = Color.rgb(200, 120, 40)
    }
}
