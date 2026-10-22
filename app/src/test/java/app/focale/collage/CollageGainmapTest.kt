package app.focale.collage

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.createBitmap
import app.focale.core.image.GainmapParams
import app.focale.core.image.UltraHdr
import kotlin.math.ln
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Gainmap d'un collage de photos Ultra HDR : paramètres communs, réécriture du gain de chaque
 * photo, puis composition case par case par le rendu du collage (cases HDR et SDR, fond,
 * coins arrondis, dimensions), en graphismes natifs.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CollageGainmapTest {

    private fun params(min: Float, max: Float, gamma: Float = 1f, channels: Int = 1) = GainmapParams(
        ratioMin = listOf(min, min, min),
        ratioMax = listOf(max, max, max),
        gamma = listOf(gamma, gamma, gamma),
        epsilonSdr = listOf(EPSILON, EPSILON, EPSILON),
        epsilonHdr = listOf(EPSILON, EPSILON, EPSILON),
        displayRatioHdr = max,
        displayRatioSdr = 1f,
        channels = channels,
    )

    /** Gain jusqu'à 4 (2 diaphragmes), et de 0,5 à 8 : bornes communes ln 0,5 à ln 8. */
    private val upToFour = params(1f, 4f)
    private val wide = params(0.5f, 8f)

    private fun solid(width: Int, height: Int, color: Int): Bitmap = createBitmap(width, height).apply { eraseColor(color) }

    /** Gainmap à un canal, de valeur stockée [value] partout. */
    private fun gray(width: Int, height: Int, value: Int): Bitmap = createBitmap(width, height, Bitmap.Config.ALPHA_8).apply {
        eraseColor(Color.argb(value, 0, 0, 0))
    }

    private fun sideBySide(spacing: Float = 0.05f, corners: Float = 0f) = CollageState(
        layoutId = "2-side",
        photos = listOf(1L, 2L),
        spacing = spacing,
        corners = corners,
        backgroundColor = CollageState.WHITE,
    )

    /** Rend un collage de 400 × 400 px et sa gainmap, photos de [sources] (non recyclées). */
    private fun render(state: CollageState, target: GainmapParams, sources: Map<Long, () -> CollageSource>): CollageGainLayer {
        val layer = CollageGainLayer(400, 400, target)
        CollageRenderer.render(state, 400, 400, open = { sources.getValue(it)() }, gainmap = layer).recycle()
        return layer
    }

    @Test
    fun `paramètres communs, du gain neutre aux bornes de toutes les photos`() {
        val combined = CollageGainmap.combine(listOf(upToFour, null, wide))!!
        assertEquals(0.5f, combined.ratioMin[0], 1e-5f)
        assertEquals(8f, combined.ratioMax[2], 1e-4f)
        assertEquals(listOf(1f, 1f, 1f), combined.gamma)
        assertEquals(8f, combined.displayRatioHdr, 0f)
        assertEquals(1, combined.channels)
        // Gain jamais sous 1 : la borne basse reste le gain neutre.
        assertEquals(1f, CollageGainmap.combine(listOf(upToFour))!!.ratioMin[1], 1e-6f)
        // Sans photo HDR, ou sans gain réel : pas de gainmap.
        assertNull(CollageGainmap.combine(listOf(null, null)))
        assertNull(CollageGainmap.combine(listOf(params(1f, 1f))))
        assertEquals(3, CollageGainmap.combine(listOf(upToFour, params(1f, 2f, channels = 3)))!!.channels)
    }

    @Test
    fun `chaque gain garde sa valeur dans les paramètres communs, une photo SDR a le gain neutre`() {
        val target = CollageGainmap.combine(listOf(upToFour, wide))!!
        // Neutre : ln 1 entre ln 0,5 et ln 8, soit un quart.
        val neutral = CollageGainmap.neutral(target)
        assertEquals(64, neutral[0])
        val four = CollageGainmap.table(upToFour, target)
        assertEquals(64, four[0][0])
        // Gain 4 : (ln 4 − ln 0,5) / (ln 8 − ln 0,5) = 3/4.
        assertEquals(191, four[1][255])
        val same = CollageGainmap.table(wide, target)
        assertEquals(0, same[2][0])
        assertEquals(255, same[2][255])
        assertEquals(128, same[0][128])
        // Gamma 2 : la valeur stockée 64 vaut la moitié du chemin, gain 2.
        val gamma = CollageGainmap.table(params(1f, 4f, gamma = 2f), target)
        val expected = ((ln(2f) - ln(0.5f)) / (ln(8f) - ln(0.5f)) * 255f)
        assertEquals(expected, gamma[0][64].toFloat(), 1.5f)
        val sdr = CollageGainmap.table(null, target)
        assertEquals(setOf(64), sdr[0].toSet())
    }

    @Test
    fun `case HDR et case SDR, fond et espacement neutres, gainmap au quart du collage`() {
        val target = CollageGainmap.combine(listOf(wide, null))!!
        val layer = render(
            sideBySide(),
            target,
            mapOf(
                1L to { BitmapSource(solid(600, 400, Color.RED), 1, gainmapContents = gray(150, 100, 255), gainmap = wide) },
                2L to { BitmapSource(solid(600, 400, Color.BLUE), 1) },
            ),
        )
        assertEquals(100, layer.width)
        assertEquals(100, layer.height)
        val neutral = CollageGainmap.neutral(target)[0]
        // Cases (20, 20, 190, 380) et (210, 20, 380, 380) du collage, soit au quart.
        assertEquals(255, layer.value(25, 50, 0))
        assertEquals(255, layer.value(10, 90, 2))
        assertEquals(neutral, layer.value(75, 50, 0))
        assertEquals(neutral, layer.value(50, 50, 0))
        assertEquals(neutral, layer.value(2, 50, 1))
        assertEquals(neutral, layer.value(25, 98, 0))
        // Une seule voie : la gainmap enregistrée est en ALPHA_8.
        val contents = layer.contents()
        assertEquals(Bitmap.Config.ALPHA_8, contents.config)
        assertEquals(255, Color.alpha(contents.getPixel(25, 50)))
        assertEquals(neutral, Color.alpha(contents.getPixel(75, 50)))
    }

    @Test
    fun `deux photos HDR aux gainmaps différentes, à trois canaux et réduite, coins arrondis neutres`() {
        val rgb = params(1f, 4f, channels = 3)
        val target = CollageGainmap.combine(listOf(rgb, wide))!!
        // Gainmap ARGB au huitième de la photo : rouge au maximum, vert au neutre, bleu à mi-chemin.
        val colored = createBitmap(75, 50).apply { eraseColor(Color.rgb(255, 0, 128)) }
        val layer = render(
            sideBySide(spacing = 0f, corners = 0.1f),
            target,
            mapOf(
                1L to { BitmapSource(solid(600, 400, Color.RED), 1, gainmapContents = colored, gainmap = rgb) },
                2L to { BitmapSource(solid(600, 400, Color.BLUE), 1, gainmapContents = gray(300, 200, 0), gainmap = wide) },
            ),
        )
        val table = CollageGainmap.table(rgb, target)
        assertEquals(table[0][255], layer.value(25, 50, 0))
        assertEquals(table[1][0], layer.value(25, 50, 1))
        assertEquals(table[2][128], layer.value(25, 50, 2))
        // Photo de droite au gain stocké 0 : 0,5 dans les paramètres communs, soit 0.
        assertEquals(0, layer.value(75, 50, 0))
        // Coin arrondi (40 px du collage) : gain neutre, comme le fond.
        val neutral = CollageGainmap.neutral(target)
        assertEquals(neutral[0], layer.value(0, 0, 0))
        assertEquals(neutral[2], layer.value(99, 99, 2))
        assertEquals(Bitmap.Config.ARGB_8888, layer.contents().config)
    }

    @Test
    fun `une photo HDR sans gainmap décodée reste au gain neutre`() {
        val target = CollageGainmap.combine(listOf(wide))!!
        val layer = render(
            sideBySide(),
            target,
            mapOf(
                1L to { BitmapSource(solid(600, 400, Color.RED), 1, gainmap = wide) },
                2L to { BitmapSource(solid(600, 400, Color.BLUE), 1) },
            ),
        )
        assertEquals(CollageGainmap.neutral(target)[0], layer.value(25, 50, 0))
    }

    @Test
    fun `gainmap Android créée avec les paramètres communs`() {
        val target = CollageGainmap.combine(listOf(upToFour, wide))!!
        val gainmap = UltraHdr.gainmap(gray(4, 4, 64), target)
        assertNotNull(gainmap.gainmapContents)
        assertEquals(target, UltraHdr.params(gainmap))
    }

    private companion object {
        const val EPSILON = 1f / 64f
    }
}
