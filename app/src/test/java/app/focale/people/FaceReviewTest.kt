package app.focale.people

import java.util.Random
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceReviewTest {

    private val random = Random(7)

    private fun gaussian(): FloatArray =
        FaceEmbeddings.normalize(FloatArray(FaceEmbeddings.DIMENSION) { random.nextGaussian().toFloat() })

    /** Visage de [identity], avec le bruit propre à chaque photo (pose, lumière…). */
    private fun face(identity: FloatArray): FloatArray {
        val noise = gaussian()
        return FaceEmbeddings.normalize(FloatArray(FaceEmbeddings.DIMENSION) { identity[it] + 0.85f * noise[it] })
    }

    private fun axis(index: Int) = FloatArray(FaceEmbeddings.DIMENSION).also { it[index] = 1f }

    @Test
    fun `les intrus passent en tête, du moins au plus ressemblant`() {
        val alice = gaussian()
        val bob = gaussian()
        val faces = List(8) { face(alice) } + face(bob) + List(3) { face(alice) } + face(bob)
        val order = FaceReview.leastSimilarFirst(faces)
        assertEquals(setOf(8, 12), order.take(2).map { it.index }.toSet())
        assertEquals(faces.indices.toList(), order.map { it.index }.sorted())
        assertEquals(order.map { it.value }.sorted(), order.map { it.value })
    }

    @Test
    fun `la similarité est le cosinus au centroïde normalisé`() {
        // Centroïde de (x, x, y) : (2, 1) / √5.
        val similarities = FaceReview.similarities(listOf(axis(0), axis(0), axis(1)))
        assertEquals(2 / sqrt(5f), similarities[0], 1e-6f)
        assertEquals(2 / sqrt(5f), similarities[1], 1e-6f)
        assertEquals(1 / sqrt(5f), similarities[2], 1e-6f)
    }

    @Test
    fun `à égalité l'ordre d'origine est conservé, une empreinte illisible passe en premier`() {
        val x = axis(3)
        val order = FaceReview.leastSimilarFirst(listOf(x, x, FloatArray(2), x))
        assertEquals(listOf(2, 0, 1, 3), order.map { it.index })
        assertEquals(0f, order.first().value)
        assertEquals(1f, order.last().value, 1e-6f)
    }

    @Test
    fun `sans visage, la revue est vide`() {
        assertTrue(FaceReview.leastSimilarFirst(emptyList()).isEmpty())
    }
}
