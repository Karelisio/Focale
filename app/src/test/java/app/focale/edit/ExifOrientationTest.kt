package app.focale.edit

import org.junit.Assert.assertEquals
import org.junit.Test

class ExifOrientationTest {

    /** Matrice 2 × 2 (repère écran, y vers le bas), lignes à la suite : a b / c d. */
    private data class Matrix(val a: Int, val b: Int, val c: Int, val d: Int) {
        operator fun times(o: Matrix) = Matrix(
            a * o.a + b * o.c,
            a * o.b + b * o.d,
            c * o.a + d * o.c,
            c * o.b + d * o.d,
        )
    }

    /** Quart de tour horaire : (x, y) → (-y, x). */
    private val quarter = Matrix(0, -1, 1, 0)

    /**
     * Transformation d'affichage de chaque valeur, d'après sa définition géométrique (et
     * non d'après la table de [ExifOrientation]) : identité, miroir horizontal, demi-tour,
     * miroir vertical, transposition, quart de tour horaire, transversale, quart de tour
     * antihoraire.
     */
    private val display = mapOf(
        1 to Matrix(1, 0, 0, 1),
        2 to Matrix(-1, 0, 0, 1),
        3 to Matrix(-1, 0, 0, -1),
        4 to Matrix(1, 0, 0, -1),
        5 to Matrix(0, 1, 1, 0),
        6 to Matrix(0, -1, 1, 0),
        7 to Matrix(0, -1, -1, 0),
        8 to Matrix(0, 1, -1, 0),
    )

    private fun turn(quarterTurns: Int): Matrix =
        (0 until Math.floorMod(quarterTurns, 4)).fold(display.getValue(1)) { acc, _ -> quarter * acc }

    @Test
    fun `quarts de tour horaires sans miroir`() {
        assertEquals(6, ExifOrientation.rotate(1, 1))
        assertEquals(3, ExifOrientation.rotate(6, 1))
        assertEquals(8, ExifOrientation.rotate(3, 1))
        assertEquals(1, ExifOrientation.rotate(8, 1))
        assertEquals(3, ExifOrientation.rotate(1, 2))
    }

    @Test
    fun `quarts de tour antihoraires sans miroir`() {
        assertEquals(8, ExifOrientation.rotate(1, -1))
        assertEquals(1, ExifOrientation.rotate(6, -1))
        assertEquals(6, ExifOrientation.rotate(3, -1))
        assertEquals(6, ExifOrientation.rotate(8, -2))
    }

    @Test
    fun `avec miroir l'image stockée tourne dans l'autre sens`() {
        // Miroir horizontal puis quart de tour horaire de l'affichage : transversale.
        assertEquals(7, ExifOrientation.rotate(2, 1))
        assertEquals(4, ExifOrientation.rotate(7, 1))
        assertEquals(5, ExifOrientation.rotate(4, 1))
        assertEquals(2, ExifOrientation.rotate(5, 1))
        assertEquals(5, ExifOrientation.rotate(2, -1))
    }

    @Test
    fun `chaque valeur compose comme la géométrie`() {
        for (orientation in 1..8) {
            for (quarterTurns in -5..5) {
                val rotated = ExifOrientation.rotate(orientation, quarterTurns)
                assertEquals(
                    "orientation $orientation, $quarterTurns quart(s) de tour",
                    turn(quarterTurns) * display.getValue(orientation),
                    display.getValue(rotated),
                )
            }
        }
    }

    @Test
    fun `un tour complet ou un aller retour ne change rien`() {
        for (orientation in 1..8) {
            assertEquals(orientation, ExifOrientation.rotate(orientation, 4))
            assertEquals(orientation, ExifOrientation.rotate(ExifOrientation.rotate(orientation, 1), -1))
        }
    }

    @Test
    fun `une orientation absente ou invalide compte comme normale`() {
        assertEquals(6, ExifOrientation.rotate(0, 1))
        assertEquals(8, ExifOrientation.rotate(42, -1))
    }
}
