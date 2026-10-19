package app.focale.edit

import org.junit.Assert.assertEquals
import org.junit.Test

class CropMathTest {

    private fun assertRect(expected: NormRect, actual: NormRect) {
        assertEquals(expected.left, actual.left, 1e-4f)
        assertEquals(expected.top, actual.top, 1e-4f)
        assertEquals(expected.right, actual.right, 1e-4f)
        assertEquals(expected.bottom, actual.bottom, 1e-4f)
    }

    @Test
    fun `quatre quarts de tour reviennent au point de départ`() {
        val rect = NormRect(0.1f, 0.2f, 0.6f, 0.9f)
        var current = rect
        repeat(4) { current = CropMath.rotateClockwise(current) }
        assertRect(rect, current)
    }

    @Test
    fun `un quart de tour envoie le coin haut gauche en haut à droite`() {
        assertRect(NormRect(0.8f, 0f, 1f, 0.3f), CropMath.rotateClockwise(NormRect(0f, 0f, 0.3f, 0.2f)))
    }

    @Test
    fun `le miroir est involutif`() {
        val rect = NormRect(0.1f, 0.2f, 0.6f, 0.9f)
        assertRect(rect, CropMath.flipHorizontal(CropMath.flipHorizontal(rect)))
    }

    @Test
    fun `un carré dans une image 4 sur 3 occupe toute la hauteur`() {
        val ratio = CropMath.normalizedRatio(CropAspect.SQUARE, 4000, 3000, 4f / 3f)!!
        assertEquals(0.75f, ratio, 1e-4f)
        assertRect(NormRect(0.125f, 0f, 0.875f, 1f), CropMath.fit(ratio))
    }

    @Test
    fun `le déplacement reste dans l'image`() {
        val moved = CropMath.drag(NormRect(0.5f, 0.5f, 0.9f, 0.9f), CropHandle.MOVE, 0.5f, -0.7f, null)
        assertRect(NormRect(0.6f, 0f, 1f, 0.4f), moved)
    }

    @Test
    fun `une poignée respecte la taille minimale`() {
        val rect = CropMath.drag(NormRect(0.2f, 0.2f, 0.8f, 0.8f), CropHandle.TOP_LEFT, 0.9f, 0.9f, null)
        assertEquals(CropMath.MIN_SIZE, rect.width, 1e-4f)
        assertEquals(CropMath.MIN_SIZE, rect.height, 1e-4f)
    }

    @Test
    fun `avec un format imposé le rapport est conservé`() {
        val ratio = 1.5f
        val rect = CropMath.drag(CropMath.fit(ratio), CropHandle.BOTTOM_RIGHT, -0.2f, -0.05f, ratio)
        assertEquals(ratio, rect.width / rect.height, 1e-3f)
    }
}
