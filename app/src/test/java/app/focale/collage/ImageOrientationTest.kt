package app.focale.collage

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Orientation EXIF : passage de l'image stockée à l'image affichée et retour, pour les huit
 * valeurs (quarts de tour, miroirs), sur une image de 40 × 30 px.
 */
class ImageOrientationTest {

    private fun display(orientation: Int, x: Float, y: Float): Pair<Float, Float> {
        val m = ImageOrientation(40, 30, orientation).toDisplay
        return (m[0] * x + m[1] * y + m[2]) to (m[3] * x + m[4] * y + m[5])
    }

    @Test
    fun `dimensions affichées, échangées par un quart de tour`() {
        for (orientation in 1..8) {
            val shown = ImageOrientation(40, 30, orientation)
            val turned = orientation in 5..8
            assertEquals(if (turned) 30 else 40, shown.displayWidth)
            assertEquals(if (turned) 40 else 30, shown.displayHeight)
        }
        // Valeur absente ou invalide : image telle quelle.
        assertEquals(40, ImageOrientation(40, 30, 0).displayWidth)
    }

    @Test
    fun `coin supérieur gauche stocké, pour chaque orientation`() {
        // Où s'affiche le coin (0, 0) de l'image stockée.
        val expected = mapOf(
            1 to (0f to 0f),
            2 to (40f to 0f), // miroir horizontal
            3 to (40f to 30f), // demi-tour
            4 to (0f to 30f), // miroir vertical
            5 to (0f to 0f), // transposée
            6 to (30f to 0f), // quart de tour horaire : le haut passe à droite
            7 to (30f to 40f), // transverse
            8 to (0f to 40f), // quart de tour antihoraire
        )
        for ((orientation, corner) in expected) assertEquals("orientation $orientation", corner, display(orientation, 0f, 0f))
    }

    @Test
    fun `l'aller et le retour rendent le point de départ`() {
        for (orientation in 1..8) {
            val shown = ImageOrientation(40, 30, orientation)
            for ((x, y) in listOf(0f to 0f, 12f to 7f, 40f to 30f, 3.5f to 29f)) {
                val (u, v) = display(orientation, x, y)
                val (backX, backY) = shown.toStored(u, v)
                assertEquals("orientation $orientation", x, backX, 1e-4f)
                assertEquals("orientation $orientation", y, backY, 1e-4f)
            }
        }
    }

    @Test
    fun `un rectangle affiché correspond au rectangle stocké qui l'affiche`() {
        val shown = ImageOrientation(40, 30, 6)
        // Bande du haut de l'image affichée (30 × 40) : colonne de gauche de l'image stockée.
        val stored = shown.toStored(CellRect(0f, 0f, 30f, 10f))
        assertEquals(CellRect(0f, 0f, 10f, 30f), stored)
        assertEquals(CellRect(0f, 0f, 30f, 10f), shown.toDisplay(stored))
    }
}
