package app.focale.ml.semantic

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SimilarityThresholdTest {

    private val threshold = SimilarityThreshold(minimum = 0.24f, margin = 0.07f, limit = 3)

    @Test
    fun `garde les scores proches du meilleur, du plus au moins proche`() {
        val scores = floatArrayOf(0.20f, 0.31f, 0.29f, 0.25f, 0.33f)
        // Plancher : max(0,24 ; 0,33 - 0,07 = 0,26).
        assertArrayEquals(intArrayOf(4, 1, 2), threshold.select(scores))
    }

    @Test
    fun `rien quand aucun score n'atteint le seuil absolu`() {
        assertEquals(0, threshold.select(floatArrayOf(0.12f, 0.23f, 0.19f)).size)
        assertEquals(0, threshold.select(FloatArray(0)).size)
    }

    @Test
    fun `le seuil absolu prime sur l'écart au meilleur`() {
        // Meilleur 0,27 : 0,27 - 0,07 = 0,20, mais rien sous 0,24.
        assertArrayEquals(intArrayOf(0, 2), threshold.select(floatArrayOf(0.27f, 0.22f, 0.245f)))
    }

    @Test
    fun `nombre de résultats limité, égalités dans l'ordre d'origine`() {
        val scores = floatArrayOf(0.30f, 0.30f, 0.30f, 0.30f, 0.30f)
        assertArrayEquals(intArrayOf(0, 1, 2), threshold.select(scores))
    }

    @Test
    fun `seules les premières lignes comptent`() {
        val scores = floatArrayOf(0.30f, 0.28f, 0.90f)
        assertArrayEquals(intArrayOf(0, 1), threshold.select(scores, count = 2))
    }

    @Test
    fun `seuils par défaut calibrés`() {
        val default = SimilarityThreshold.Default
        assertEquals(0.37f, default.minimum, 0f)
        assertEquals(0.06f, default.margin, 0f)
        assertEquals(400, default.limit)
    }
}
