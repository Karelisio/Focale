package app.focale.ui.viewer

import androidx.media3.common.C
import app.focale.ui.viewer.VideoSeekMath.SERIES_TIMEOUT_MS
import app.focale.ui.viewer.VideoSeekMath.direction
import app.focale.ui.viewer.VideoSeekMath.target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSeekMathTest {

    @Test
    fun `la moitié gauche recule, la droite avance`() {
        assertEquals(SeekDirection.BACKWARD, direction(0f, 400f))
        assertEquals(SeekDirection.BACKWARD, direction(199.9f, 400f))
        assertEquals(SeekDirection.FORWARD, direction(200f, 400f))
        assertEquals(SeekDirection.FORWARD, direction(399f, 400f))
    }

    @Test
    fun `un saut de 10 s reste entre le début et la fin`() {
        assertEquals(30_000L, target(20_000, 10_000, 60_000))
        assertEquals(10_000L, target(20_000, -10_000, 60_000))
        // Près des bords : la position s'arrête au début ou à la fin, sans erreur.
        assertEquals(60_000L, target(55_000, 10_000, 60_000))
        assertEquals(60_000L, target(60_000, 10_000, 60_000))
        assertEquals(0L, target(4_000, -10_000, 60_000))
        assertEquals(0L, target(0, -10_000, 60_000))
        // Vidéo vide : rien ne bouge.
        assertEquals(0L, target(0, 10_000, 0))
    }

    @Test
    fun `durée encore inconnue, seul le début borne`() {
        assertEquals(30_000L, target(20_000, 10_000, C.TIME_UNSET))
        assertEquals(0L, target(4_000, -10_000, C.TIME_UNSET))
    }

    @Test
    fun `les appuis rapprochés cumulent 10 s de plus du même côté`() {
        val series = SeekSeries()
        assertEquals(30_000L, series.seek(SeekDirection.FORWARD, 20_000, 60_000, time = 1_000))
        assertEquals(SeekDirection.FORWARD, series.direction)
        assertEquals(10, series.seconds)

        assertTrue(series.continues(1_400))
        assertEquals(40_000L, series.seek(SeekDirection.FORWARD, 30_000, 60_000, time = 1_400))
        assertEquals(50_000L, series.seek(SeekDirection.FORWARD, 40_000, 60_000, time = 2_000))
        assertEquals(3, series.steps)
        assertEquals(30, series.seconds)
    }

    @Test
    fun `de l'autre côté, le cumul repart de 10 s dans l'autre sens`() {
        val series = SeekSeries()
        series.seek(SeekDirection.FORWARD, 20_000, 60_000, time = 1_000)
        series.seek(SeekDirection.FORWARD, 30_000, 60_000, time = 1_300)

        assertEquals(30_000L, series.seek(SeekDirection.BACKWARD, 40_000, 60_000, time = 1_600))
        assertEquals(SeekDirection.BACKWARD, series.direction)
        assertEquals(10, series.seconds)
    }

    @Test
    fun `la série se ferme passé le délai, et un nouveau double appui repart de 10 s`() {
        val series = SeekSeries()
        series.seek(SeekDirection.FORWARD, 20_000, 60_000, time = 1_000)

        assertTrue(series.continues(1_000 + SERIES_TIMEOUT_MS))
        assertFalse(series.continues(1_001 + SERIES_TIMEOUT_MS))
        // Horloge en arrière (autre source d'appui) : pas de série.
        assertFalse(series.continues(999))

        assertEquals(40_000L, series.seek(SeekDirection.FORWARD, 30_000, 60_000, time = 3_000))
        assertEquals(10, series.seconds)
    }

    @Test
    fun `contre une borne, rien ne s'ajoute mais la série reste ouverte`() {
        val series = SeekSeries()
        assertEquals(60_000L, series.seek(SeekDirection.FORWARD, 55_000, 60_000, time = 1_000))

        assertNull(series.seek(SeekDirection.FORWARD, 60_000, 60_000, time = 1_500))
        assertEquals(10, series.seconds)
        // Le délai repart du dernier appui : 600 ms après lui, 1,1 s après le saut.
        assertTrue(series.continues(2_100))

        // Retour possible de l'autre côté, sans nouveau double appui.
        assertEquals(50_000L, series.seek(SeekDirection.BACKWARD, 60_000, 60_000, time = 2_100))
        assertEquals(SeekDirection.BACKWARD, series.direction)
        assertEquals(10, series.seconds)
    }

    @Test
    fun `un double appui contre une borne n'ouvre pas de série`() {
        val series = SeekSeries()
        assertNull(series.seek(SeekDirection.BACKWARD, 0, 60_000, time = 1_000))
        assertNull(series.direction)
        assertFalse(series.continues(1_100))
    }

    @Test
    fun `fin de série`() {
        val series = SeekSeries()
        series.seek(SeekDirection.BACKWARD, 20_000, 60_000, time = 1_000)
        series.end()

        assertNull(series.direction)
        assertEquals(0, series.seconds)
        assertFalse(series.continues(1_100))
    }
}
