package app.focale.edit

import app.focale.ui.editor.TextOptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Géométrie pure des textes : repère des traits, rotation, miroir, recadrage, saisie au doigt. */
class TextMathTest {

    private fun item(x: Float = 0.3f, y: Float = 0.2f, rotation: Float = 0f, size: Float = 0.1f) =
        TextItem(id = 1, text = "Bonjour", x = x, y = y, color = 0xFFFF0000.toInt(), size = size, rotation = rotation)

    @Test
    fun `l'angle est ramené dans l'intervalle -180 exclu 180 inclus, sans -0`() {
        assertEquals(90f, TextMath.normalizeAngle(450f), 1e-4f)
        assertEquals(180f, TextMath.normalizeAngle(-180f), 1e-4f)
        assertEquals(-90f, TextMath.normalizeAngle(270f), 1e-4f)
        assertEquals(0f, TextMath.normalizeAngle(-0f))
        assertEquals(0f.toBits(), TextMath.normalizeAngle(-360f).toBits())
    }

    @Test
    fun `un quart de tour déplace le texte comme un trait et le fait tourner avec l'image`() {
        val text = item(rotation = 10f)
        val stroke = DrawingStroke(DrawTool.PEN, 0, 0.01f, floatArrayOf(text.x, text.y)).rotateClockwise()
        val rotated = text.rotateClockwise()
        assertEquals(stroke.x(0), rotated.x, 1e-6f)
        assertEquals(stroke.y(0), rotated.y, 1e-6f)
        assertEquals(100f, rotated.rotation, 1e-4f)
        // Taille, texte et style inchangés.
        assertEquals(text.copy(x = rotated.x, y = rotated.y, rotation = rotated.rotation), rotated)
    }

    @Test
    fun `quatre quarts de tour et deux miroirs ramènent le texte à sa place`() {
        val text = item(rotation = 33f)
        val turned = text.rotateClockwise().rotateClockwise().rotateClockwise().rotateClockwise()
        assertEquals(text.x, turned.x, 1e-6f)
        assertEquals(text.y, turned.y, 1e-6f)
        assertEquals(text.rotation, turned.rotation, 1e-3f)
        assertEquals(text, text.flipHorizontal().flipHorizontal())
    }

    @Test
    fun `le miroir change le texte de côté et inverse son inclinaison sans le retourner`() {
        val flipped = item(x = 0.3f, rotation = 20f).flipHorizontal()
        assertEquals(0.7f, flipped.x, 1e-6f)
        assertEquals(0.2f, flipped.y, 1e-6f)
        // Lettres lues de gauche à droite : l'inclinaison s'inverse, le sens de lecture reste.
        assertEquals(-20f, flipped.rotation, 1e-4f)
        assertEquals(0f, item(rotation = 0f).flipHorizontal().rotation)
    }

    @Test
    fun `l'échelle suit le petit côté du cadre`() {
        val text = item(size = 0.1f)
        assertEquals(0.1f * 300f / TextMath.LAYOUT_SIZE, TextMath.scale(text, 400f, 300f), 1e-6f)
        assertEquals(0.1f * 300f / TextMath.LAYOUT_SIZE, TextMath.scale(text, 300f, 900f), 1e-6f)
    }

    @Test
    fun `le bloc d'un texte incliné se touche dans son propre repère`() {
        // Bloc de 200 × 40 px de mise en page, corps 0,1 : 20 % du petit côté (100 px) → 20 × 4 px.
        val flat = item(x = 0.5f, y = 0.5f, size = 0.1f)
        assertTrue(TextMath.contains(flat, 100f, 20f, 50f + 9f, 50f, 100f, 100f, slop = 0f))
        assertFalse(TextMath.contains(flat, 100f, 20f, 50f, 50f + 9f, 100f, 100f, slop = 0f))
        // Tourné d'un quart de tour : le bloc devient vertical.
        val upright = flat.copy(rotation = 90f)
        assertFalse(TextMath.contains(upright, 100f, 20f, 50f + 9f, 50f, 100f, 100f, slop = 0f))
        assertTrue(TextMath.contains(upright, 100f, 20f, 50f, 50f + 9f, 100f, 100f, slop = 0f))
        // La marge de saisie élargit la prise.
        assertTrue(TextMath.contains(flat, 100f, 20f, 50f, 50f + 5f, 100f, 100f, slop = 4f))
    }

    @Test
    fun `les coins du bloc tournent autour de son centre`() {
        val text = item(x = 0.5f, y = 0.5f, size = 0.1f, rotation = 90f)
        val corners = TextMath.corners(text, 100f, 20f, 100f, 100f)
        // Haut gauche (-10, -2) tourné de 90° horaire : (2, -10) autour du centre (50, 50).
        assertEquals(52f, corners[0], 1e-3f)
        assertEquals(40f, corners[1], 1e-3f)
        // Bas droit (10, 2) : (-2, 10).
        assertEquals(48f, corners[4], 1e-3f)
        assertEquals(60f, corners[5], 1e-3f)
    }

    @Test
    fun `un geste garde le centre dans le recadrage et le corps dans ses bornes`() {
        val crop = NormRect(0.2f, 0.1f, 0.8f, 0.9f)
        val moved = TextMath.transform(item(x = 0.5f, y = 0.5f), dx = 0.6f, dy = -0.7f, zoom = 1f, rotation = 0f, bounds = crop)
        assertEquals(0.8f, moved.x, 1e-6f)
        assertEquals(0.1f, moved.y, 1e-6f)
        val grown = TextMath.transform(item(size = 0.1f), 0f, 0f, zoom = 100f, rotation = 200f, bounds = NormRect.Full)
        assertEquals(TextItem.MAX_SIZE, grown.size, 1e-6f)
        assertEquals(-160f, grown.rotation, 1e-4f)
        val shrunk = TextMath.transform(item(size = 0.1f), 0f, 0f, zoom = 0.001f, rotation = 0f, bounds = NormRect.Full)
        assertEquals(TextItem.MIN_SIZE, shrunk.size, 1e-6f)
    }

    @Test
    fun `le curseur de taille couvre toute la plage et revient sur ses pas`() {
        assertEquals(0f, TextOptions.sliderPosition(TextItem.MIN_SIZE), 1e-6f)
        assertEquals(1f, TextOptions.sliderPosition(TextItem.MAX_SIZE), 1e-6f)
        for (size in listOf(0.02f, 0.05f, 0.07f, 0.13f, 0.4f)) {
            assertEquals(size, TextOptions.sizeAt(TextOptions.sliderPosition(size)), 1e-5f)
        }
        // Échelle en racine : le corps par défaut n'est pas tassé au début du curseur.
        assertTrue(TextOptions.sliderPosition(TextItem.DEFAULT_SIZE) > 0.2f)
    }
}
