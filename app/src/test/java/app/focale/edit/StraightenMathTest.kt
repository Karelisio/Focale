package app.focale.edit

import app.focale.edit.StraightenMath.clamp
import app.focale.edit.StraightenMath.fit
import app.focale.edit.StraightenMath.fits
import app.focale.edit.StraightenMath.gestureAngle
import app.focale.edit.StraightenMath.tick
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class StraightenMathTest {

    /** Cadres (px) : paysage 4:3, portrait 3:4, carré, paysage 16:9. */
    private val frames = listOf(4000f to 3000f, 3000f to 4000f, 2000f to 2000f, 1600f to 900f)

    private fun assertRect(expected: NormRect, actual: NormRect, delta: Float = 1e-4f) {
        assertEquals(expected.left, actual.left, delta)
        assertEquals(expected.top, actual.top, delta)
        assertEquals(expected.right, actual.right, delta)
        assertEquals(expected.bottom, actual.bottom, delta)
    }

    /**
     * Vérification indépendante de [StraightenMath.fits] : chaque coin, ramené dans le repère
     * de l'image par la rotation inverse, est dans l'image ; et dans le cadre.
     */
    private fun assertCovered(rect: NormRect, angle: Float, width: Float, height: Float) {
        val radians = Math.toRadians(angle.toDouble())
        for (x in listOf(rect.left, rect.right)) {
            for (y in listOf(rect.top, rect.bottom)) {
                val px = (x - 0.5) * width
                val py = (y - 0.5) * height
                val u = px * cos(radians) + py * sin(radians)
                val v = -px * sin(radians) + py * cos(radians)
                val inside = abs(u) <= width / 2 * 1.0002 && abs(v) <= height / 2 * 1.0002 &&
                    x in -1e-4f..1.0001f && y in -1e-4f..1.0001f
                assertTrue("coin ($x, $y) vide à $angle° dans ${width}x$height", inside)
            }
        }
    }

    /** Proportion en pixels d'un rectangle normalisé. */
    private fun ratio(rect: NormRect, width: Float, height: Float) = rect.width * width / (rect.height * height)

    private fun grown(rect: NormRect, factor: Float): NormRect {
        val cx = (rect.left + rect.right) / 2f
        val cy = (rect.top + rect.bottom) / 2f
        return NormRect(cx - rect.width / 2f * factor, cy - rect.height / 2f * factor, cx + rect.width / 2f * factor, cy + rect.height / 2f * factor)
    }

    @Test
    fun `à 0° le recadrage reste tel quel`() {
        val rect = NormRect(0.1f, 0.2f, 0.7f, 0.9f)
        assertSame(rect, fit(rect, 0f, 4000f, 3000f))
        assertSame(NormRect.Full, fit(NormRect.Full, 0f, 4000f, 3000f))
        assertTrue(fits(NormRect.Full, 0f, 4000f, 3000f))
        assertFalse(fits(NormRect.Full, 0.5f, 4000f, 3000f))
    }

    @Test
    fun `le plus grand rectangle inscrit garde la proportion de l'image, centré`() {
        for ((width, height) in frames) {
            for (angle in listOf(-45f, -12.5f, 0.1f, 5f, 30f, 45f)) {
                val rect = fit(NormRect.Full, angle, width, height)
                assertEquals(width / height, ratio(rect, width, height), 1e-3f)
                assertEquals(0.5f, (rect.left + rect.right) / 2f, 1e-5f)
                assertEquals(0.5f, (rect.top + rect.bottom) / 2f, 1e-5f)
                assertCovered(rect, angle, width, height)
                // Le plus grand : un demi-pour-cent de plus et un coin est vide.
                assertFalse("$angle° dans ${width}x$height", fits(grown(rect, 1.005f), angle, width, height))
            }
        }
    }

    @Test
    fun `valeurs exactes à 45°`() {
        // Carré : côté divisé par √2.
        assertEquals(1f / sqrt(2f), fit(NormRect.Full, 45f, 1000f, 1000f).width, 1e-4f)
        // 4:3 : la hauteur limite, 3 / ((4 + 3) × sin 45°).
        assertEquals(3f / (7f * sqrt(0.5f)), fit(NormRect.Full, 45f, 4000f, 3000f).height, 1e-4f)
        assertEquals(3f / (7f * sqrt(0.5f)), fit(NormRect.Full, -45f, 3000f, 4000f).width, 1e-4f)
    }

    @Test
    fun `aucun coin vide, à tout angle et pour tout cadre`() {
        val references = listOf(
            NormRect.Full,
            NormRect(0f, 0f, 0.5f, 0.5f),
            NormRect(0.6f, 0.1f, 1f, 0.95f),
            NormRect(0.02f, 0.4f, 0.98f, 0.6f),
        )
        for ((width, height) in frames) {
            for (reference in references) {
                for (tenths in -450..450 step 15) {
                    val angle = tenths / 10f
                    assertCovered(fit(reference, angle, width, height), angle, width, height)
                }
            }
        }
    }

    @Test
    fun `un recadrage décentré garde sa proportion et se rapproche du centre juste assez`() {
        val reference = NormRect(0f, 0f, 0.5f, 0.5f)
        val rect = fit(reference, 10f, 4000f, 3000f)
        assertEquals(ratio(reference, 4000f, 3000f), ratio(rect, 4000f, 3000f), 1e-3f)
        val centerX = (rect.left + rect.right) / 2f
        val centerY = (rect.top + rect.bottom) / 2f
        assertTrue(centerX > 0.25f && centerX < 0.5f)
        // Sur la droite qui relie le centre voulu au centre du cadre.
        assertEquals((0.5f - centerX) / 0.25f, (0.5f - centerY) / 0.25f, 1e-3f)
        // Il touche le bord : un peu plus loin du centre, un coin serait vide.
        val further = NormRect(rect.left - 0.002f, rect.top - 0.002f, rect.right - 0.002f, rect.bottom - 0.002f)
        assertFalse(fits(further, 10f, 4000f, 3000f))
        // Un recadrage qui tient déjà ne bouge pas.
        val small = NormRect(0.4f, 0.4f, 0.6f, 0.6f)
        assertSame(small, fit(small, 10f, 4000f, 3000f))
    }

    @Test
    fun `un format imposé est respecté`() {
        val normalized = CropMath.normalizedRatio(CropAspect.SIXTEEN_NINE, 4000, 3000, 4f / 3f)!!
        val portrait = CropMath.normalizedRatio(CropAspect.NINE_SIXTEEN, 4000, 3000, 4f / 3f)!!
        for (angle in listOf(-30f, 12f, 45f)) {
            val wide = fit(CropMath.fit(normalized), angle, 4000f, 3000f)
            assertEquals(16f / 9f, ratio(wide, 4000f, 3000f), 1e-3f)
            assertCovered(wide, angle, 4000f, 3000f)
            val tall = fit(CropMath.fit(portrait), angle, 4000f, 3000f)
            assertEquals(9f / 16f, ratio(tall, 4000f, 3000f), 1e-3f)
            assertCovered(tall, angle, 4000f, 3000f)
        }
    }

    @Test
    fun `quart de tour et miroir s'accordent avec le recadrage automatique`() {
        val reference = NormRect(0.1f, 0.05f, 0.8f, 0.7f)
        for (angle in listOf(-30f, 7.5f, 45f)) {
            val fitted = fit(reference, angle, 4000f, 3000f)
            // Quart de tour : même angle, cadre aux dimensions échangées.
            assertRect(CropMath.rotateClockwise(fitted), fit(CropMath.rotateClockwise(reference), angle, 3000f, 4000f))
            // Miroir : angle opposé.
            assertRect(CropMath.flipHorizontal(fitted), fit(CropMath.flipHorizontal(reference), -angle, 4000f, 3000f))
        }
    }

    @Test
    fun `une poignée tirée vers un coin vide s'arrête au bord de l'image`() {
        val angle = 10f
        val start = fit(NormRect.Full, angle, 4000f, 3000f).let { NormRect(it.left, it.top, it.right - 0.1f, it.bottom - 0.1f) }
        val dragged = StraightenMath.drag(start, CropHandle.BOTTOM_RIGHT, 0.3f, 0.3f, null, angle, 4000f, 3000f)
        assertCovered(dragged, angle, 4000f, 3000f)
        assertTrue(dragged.right > start.right && dragged.bottom > start.bottom)
        assertEquals(start.left, dragged.left, 1e-6f)
        assertEquals(start.top, dragged.top, 1e-6f)
    }

    @Test
    fun `un déplacement glisse le long d'un bord incliné sans changer de taille`() {
        val angle = 15f
        val start = NormRect(0.4f, 0.4f, 0.6f, 0.6f)
        var rect = start
        // Vers la droite et un peu vers le haut : bloqué à droite par le bord incliné, le cadre
        // continue de monter en le longeant.
        repeat(60) { rect = StraightenMath.drag(rect, CropHandle.MOVE, 0.02f, -0.004f, null, angle, 4000f, 3000f) }
        assertCovered(rect, angle, 4000f, 3000f)
        assertEquals(start.width, rect.width, 1e-5f)
        assertEquals(start.height, rect.height, 1e-5f)
        assertTrue(rect.left > start.left + 0.15f)
        assertTrue(rect.top < start.top - 0.1f)
    }

    @Test
    fun `avec un format imposé le glissement garde le rapport`() {
        val ratio = CropMath.normalizedRatio(CropAspect.THREE_TWO, 4000, 3000, 4f / 3f)!!
        val start = fit(CropMath.fit(ratio), 20f, 4000f, 3000f).let { NormRect(it.left, it.top, it.left + it.width * 0.6f, it.top + it.height * 0.6f) }
        val dragged = StraightenMath.drag(start, CropHandle.BOTTOM_RIGHT, 0.4f, 0.4f, ratio, 20f, 4000f, 3000f)
        assertEquals(ratio, dragged.width / dragged.height, 1e-3f)
        assertCovered(dragged, 20f, 4000f, 3000f)
        assertTrue(dragged.width > start.width)
    }

    @Test
    fun `à 0° le glissement est celui du recadrage simple`() {
        val rect = NormRect(0.2f, 0.2f, 0.8f, 0.8f)
        for (handle in CropHandle.entries) {
            assertEquals(CropMath.drag(rect, handle, 0.3f, -0.1f, null), StraightenMath.drag(rect, handle, 0.3f, -0.1f, null, 0f, 4000f, 3000f))
        }
    }

    @Test
    fun `angle borné à 45° et arrondi au dixième, jamais -0`() {
        assertEquals(45f, clamp(60f), 0f)
        assertEquals(-45f, clamp(-50f), 0f)
        assertEquals(3.5f, clamp(3.46f), 0f)
        assertEquals(-12.3f, clamp(-12.34f), 0f)
        assertEquals(0f.toBits(), clamp(-0.04f).toBits())
        assertEquals(0f.toBits(), clamp(-0f).toBits())
    }

    @Test
    fun `les petites rotations d'un pincement sont ignorées`() {
        assertEquals(0f, gestureAngle(4f), 0f)
        assertEquals(0f, gestureAngle(-StraightenMath.GESTURE_THRESHOLD), 0f)
        // Passé le seuil, l'angle suit les doigts sans à-coup.
        assertEquals(0.5f, gestureAngle(StraightenMath.GESTURE_THRESHOLD + 0.5f), 1e-5f)
        assertEquals(-10f, gestureAngle(-StraightenMath.GESTURE_THRESHOLD - 10f), 1e-5f)

        // Doigts d'abord écartés : un pincement, qui ne redresse rien ensuite.
        val pinch = StraightenGesture()
        assertNull(pinch.update(2f, 1.2f))
        assertNull(pinch.update(30f, 1f))
        assertTrue(pinch.pinched)

        // Rotation : retenue au-delà du seuil, puis l'écartement ne l'arrête plus.
        val rotation = StraightenGesture()
        assertNull(rotation.update(4f, 1.03f))
        assertEquals(1f, rotation.update(2f, 0.98f)!!, 1e-4f)
        assertEquals(6f, rotation.update(5f, 1.5f)!!, 1e-4f)
        assertTrue(rotation.engaged)
    }

    @Test
    fun `retour haptique à 0° et à chaque degré entier`() {
        assertEquals(StraightenTick.ZERO, tick(-0.3f, 0.2f))
        assertEquals(StraightenTick.ZERO, tick(0.4f, 0f))
        assertEquals(StraightenTick.ZERO, tick(1.5f, -2.5f))
        assertEquals(StraightenTick.NONE, tick(0f, 0.3f))
        assertEquals(StraightenTick.NONE, tick(0f, -0.3f))
        assertEquals(StraightenTick.DEGREE, tick(0.9f, 1.1f))
        assertEquals(StraightenTick.DEGREE, tick(2.1f, 2f))
        assertEquals(StraightenTick.DEGREE, tick(-3.9f, -4.2f))
        assertEquals(StraightenTick.NONE, tick(2f, 1.8f))
        assertEquals(StraightenTick.NONE, tick(1.2f, 1.8f))
        assertEquals(StraightenTick.NONE, tick(1f, 1f))
    }
}
