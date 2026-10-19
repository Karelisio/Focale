package app.focale.edit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMatricesTest {

    @Test
    fun `l'état neutre donne la matrice identité`() {
        assertArrayEquals(ColorMatrices.identity, EditState().colorMatrix(), 1e-5f)
    }

    @Test
    fun `une saturation nulle égalise les trois canaux`() {
        val (r, g, b) = ColorMatrices.apply(ColorMatrices.saturation(0f), 200f, 40f, 90f)
        assertEquals(r, g, 0.01f)
        assertEquals(g, b, 0.01f)
    }

    @Test
    fun `la luminosité décale toutes les composantes`() {
        val (r, g, b) = ColorMatrices.apply(EditState(brightness = 0.5f).colorMatrix(), 100f, 100f, 100f)
        assertTrue(r > 100f && g > 100f && b > 100f)
    }

    @Test
    fun `le contraste écarte les tons du gris moyen`() {
        val matrix = EditState(contrast = 1f).colorMatrix()
        val (dark, _, _) = ColorMatrices.apply(matrix, 64f, 64f, 64f)
        val (light, _, _) = ColorMatrices.apply(matrix, 192f, 192f, 192f)
        assertTrue(dark < 64f && light > 192f)
        val (mid, _, _) = ColorMatrices.apply(matrix, 128f, 128f, 128f)
        assertEquals(128f, mid, 0.5f)
    }

    @Test
    fun `la composition applique d'abord le second argument`() {
        val brighter = ColorMatrices.brightness(20f)
        val doubled = ColorMatrices.scale(2f, 2f, 2f)
        val (r, _, _) = ColorMatrices.apply(ColorMatrices.compose(doubled, brighter), 10f, 10f, 10f)
        assertEquals(60f, r, 0.01f)
    }

    @Test
    fun `la chaleur réchauffe le rouge et refroidit le bleu`() {
        val (r, _, b) = ColorMatrices.apply(EditState(warmth = 1f).colorMatrix(), 100f, 100f, 100f)
        assertTrue(r > 100f && b < 100f)
    }
}
