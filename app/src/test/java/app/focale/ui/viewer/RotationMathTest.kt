package app.focale.ui.viewer

import app.focale.ui.viewer.RotationMath.gestureAngle
import app.focale.ui.viewer.RotationMath.nearestFullTurn
import app.focale.ui.viewer.RotationMath.nearestQuarter
import app.focale.ui.viewer.RotationMath.refitScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationMathTest {

    @Test
    fun `les petites rotations d'un pincement sont ignorées`() {
        assertEquals(0f, gestureAngle(0f), 0f)
        assertEquals(0f, gestureAngle(8f), 0f)
        assertEquals(0f, gestureAngle(-14f), 0f)
        assertEquals(0f, gestureAngle(RotationMath.THRESHOLD), 0f)
    }

    @Test
    fun `passé le seuil la photo démarre sans à-coup et rattrape les doigts au quart de tour`() {
        assertEquals(0f, gestureAngle(RotationMath.THRESHOLD + 0.01f), 0.05f)
        assertEquals(90f, gestureAngle(90f), 1e-4f)
        assertEquals(-90f, gestureAngle(-90f), 1e-4f)
        assertEquals(130f, gestureAngle(130f), 1e-4f)
        var previous = 0f
        for (tenth in 0..1800) {
            val angle = gestureAngle(tenth / 10f)
            assertTrue("angle décroissant à ${tenth / 10f}°", angle >= previous)
            previous = angle
        }
    }

    @Test
    fun `l'angle affiché garde le sens des doigts`() {
        for (fingers in listOf(20f, 45f, 70f, 100f)) {
            assertEquals(-gestureAngle(fingers), gestureAngle(-fingers), 1e-4f)
        }
    }

    @Test
    fun `alignement sur le quart de tour le plus proche`() {
        assertEquals(0, nearestQuarter(44f))
        assertEquals(1, nearestQuarter(46f))
        assertEquals(0, nearestQuarter(-44f))
        assertEquals(-1, nearestQuarter(-46f))
        assertEquals(2, nearestQuarter(170f))
        assertEquals(4, nearestQuarter(359f))
        // Geste repris depuis un quart de tour : 90° + 30° revient à 90°.
        assertEquals(1, nearestQuarter(120f))
    }

    @Test
    fun `un quart de tour demande un peu plus de la moitié d'un quart de tour des doigts`() {
        // 45° affichés pour 52,5° des doigts (seuil de 15° rattrapé d'ici 90°).
        assertEquals(45f, gestureAngle(52.5f), 1e-3f)
        assertEquals(0, nearestQuarter(gestureAngle(50f)))
        assertEquals(1, nearestQuarter(gestureAngle(55f)))
        assertEquals(-1, nearestQuarter(gestureAngle(-55f)))
        assertEquals(1, nearestQuarter(gestureAngle(90f)))
    }

    @Test
    fun `annuler revient au tour complet le plus proche`() {
        assertEquals(0, nearestFullTurn(0))
        assertEquals(0, nearestFullTurn(1))
        assertEquals(0, nearestFullTurn(-1))
        assertEquals(4, nearestFullTurn(2))
        assertEquals(4, nearestFullTurn(3))
        assertEquals(-4, nearestFullTurn(-3))
        assertEquals(4, nearestFullTurn(5))
    }

    @Test
    fun `après un quart de tour la photo est ajustée à l'écran`() {
        // Paysage 4:3 sur un écran 1080 × 2340 : 1080 × 810 ; debout, il occupe à nouveau la largeur.
        assertEquals(4f / 3f, refitScale(1080f, 810f, 1080f, 2340f), 1e-4f)
        // Portrait 3:4 : 1080 × 1440 ; couché, il doit tenir en largeur.
        assertEquals(0.75f, refitScale(1080f, 1440f, 1080f, 2340f), 1e-4f)
        // Retour : dans le cadre échangé (2340 × 1080), le paysage debout mesure 1440 × 1080.
        assertEquals(0.75f, refitScale(1440f, 1080f, 2340f, 1080f), 1e-4f)
    }
}
