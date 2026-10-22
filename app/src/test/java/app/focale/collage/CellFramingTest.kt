package app.focale.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cadrage d'une photo dans sa case : la photo remplit la case, centrée ; un glisser la déplace
 * jusqu'à son bord, jamais au-delà ; un pincement l'agrandit autour des doigts, de 1 à 4 fois.
 * Normalisé, le même cadrage montre la même partie de la photo à toutes les tailles de case.
 */
class CellFramingTest {

    // Photo 4:3 (4 000 × 3 000) dans une case carrée de 300 px : 400 × 300 px à l'écran à l'échelle 1.
    private val image = 4000f to 3000f

    private fun visible(frame: CellFrame, cell: Float = 300f) = CellFraming.visible(frame, cell, cell, image.first, image.second)

    @Test
    fun `la photo remplit la case, centrée, comme un recadrage`() {
        val shown = visible(CellFrame.Default)
        // Hauteur entière, largeur coupée des deux côtés.
        assertRect(CellRect(500f, 0f, 3500f, 3000f), shown)
        // Case large et basse : largeur entière.
        assertRect(CellRect(0f, 1000f, 4000f, 2000f), CellFraming.visible(CellFrame.Default, 400f, 100f, image.first, image.second))
    }

    @Test
    fun `un glisser déplace la photo avec le doigt, jusqu'à son bord`() {
        // 1 px de la case = 10 px de la photo. Doigt vers la droite : on voit plus à gauche.
        val moved = CellFraming.pan(CellFrame.Default, 30f, 0f, 300f, 300f, image.first, image.second)
        assertRect(CellRect(200f, 0f, 3200f, 3000f), visible(moved))
        // Au-delà du bord gauche : la photo s'arrête contre lui.
        val far = CellFraming.pan(moved, 500f, 0f, 300f, 300f, image.first, image.second)
        assertRect(CellRect(0f, 0f, 3000f, 3000f), visible(far))
        // Verticalement, la photo remplit déjà la case : rien ne bouge.
        assertEquals(far, CellFraming.pan(far, 0f, 80f, 300f, 300f, image.first, image.second))
    }

    @Test
    fun `un pincement agrandit autour des doigts, qui gardent leur point de la photo`() {
        // Doigts au quart gauche de la case, en haut.
        val focusX = 75f
        val focusY = 60f
        val before = visible(CellFrame.Default)
        val pointX = before.left + focusX / 300f * before.width
        val pointY = before.top + focusY / 300f * before.height
        val zoomed = CellFraming.zoom(CellFrame.Default, 2f, focusX, focusY, 300f, 300f, image.first, image.second)
        assertEquals(2f, zoomed.zoom, 1e-6f)
        val after = visible(zoomed)
        assertEquals(1500f, after.width, 0.01f)
        assertEquals(pointX, after.left + focusX / 300f * after.width, 0.5f)
        assertEquals(pointY, after.top + focusY / 300f * after.height, 0.5f)
    }

    @Test
    fun `l'agrandissement reste entre 1 et 4, et la partie visible dans la photo`() {
        val big = CellFraming.zoom(CellFrame.Default, 10f, 150f, 150f, 300f, 300f, image.first, image.second)
        assertEquals(CellFraming.MAX_ZOOM, big.zoom, 0f)
        val small = CellFraming.zoom(big, 0.01f, 0f, 0f, 300f, 300f, image.first, image.second)
        assertEquals(1f, small.zoom, 0f)
        // Pincement près d'un coin : le cadrage s'arrête au bord au lieu de montrer du vide.
        val corner = CellFraming.zoom(CellFrame(2f, 0.2f, 0.2f), 0.6f, 0f, 0f, 300f, 300f, image.first, image.second)
        val shown = visible(corner)
        assertTrue("$shown", shown.left >= -0.01f && shown.top >= -0.01f && shown.right <= 4000.01f && shown.bottom <= 3000.01f)
        // Un cadrage hors bornes (autre disposition, autre case) est ramené dedans.
        val clamped = CellFraming.clamp(CellFrame(9f, -1f, 2f), 300f, 300f, image.first, image.second)
        assertEquals(4f, clamped.zoom, 0f)
        assertEquals(375f / 4000f, clamped.centerX, 1e-6f)
        assertEquals(1f - 375f / 3000f, clamped.centerY, 1e-6f)
    }

    @Test
    fun `le même cadrage montre la même partie de la photo, quelle que soit la taille de la case`() {
        val frame = CellFrame(2.5f, 0.3f, 0.6f)
        val onScreen = visible(frame, 300f)
        val exported = visible(frame, 2028f)
        assertRect(onScreen, exported)
        // Aperçu réduit (1 280 × 960) : même partie, à l'échelle près.
        val preview = CellFraming.visible(frame, 300f, 300f, 1280f, 960f)
        assertRect(CellRect(onScreen.left * 0.32f, onScreen.top * 0.32f, onScreen.right * 0.32f, onScreen.bottom * 0.32f), preview)
    }

    @Test
    fun `glisser puis pincer dans une case en hauteur`() {
        // Photo en paysage dans une case en portrait : seule la largeur peut défiler à l'échelle 1.
        val cell = 200f to 600f
        val frame = CellFraming.pan(CellFrame.Default, -100f, 0f, cell.first, cell.second, image.first, image.second)
        val shown = CellFraming.visible(frame, cell.first, cell.second, image.first, image.second)
        assertEquals(3000f, shown.height, 0.01f)
        assertEquals(1000f, shown.width, 0.01f)
        // Doigt vers la gauche de 100 px (500 px de la photo) : on voit plus à droite.
        assertEquals(2000f + 500f, shown.centerX, 0.01f)
    }

    private fun assertRect(expected: CellRect, actual: CellRect) {
        val tolerance = 0.05f
        assertEquals("gauche", expected.left, actual.left, tolerance)
        assertEquals("haut", expected.top, actual.top, tolerance)
        assertEquals("droite", expected.right, actual.right, tolerance)
        assertEquals("bas", expected.bottom, actual.bottom, tolerance)
    }
}
