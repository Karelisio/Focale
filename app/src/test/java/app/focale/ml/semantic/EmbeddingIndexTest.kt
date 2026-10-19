package app.focale.ml.semantic

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmbeddingIndexTest {

    private fun randomUnit(random: Random, dimension: Int = 512): FloatArray =
        EmbeddingCodec.normalize(FloatArray(dimension) { random.nextFloat() * 2 - 1 })

    @Test
    fun `sérialisation en flottants petit-boutistes`() {
        val vector = floatArrayOf(1f, -0.5f, 0.25f)
        val bytes = EmbeddingCodec.encode(vector)
        assertEquals(12, bytes.size)
        // 1.0f = 0x3F800000, octet de poids faible en premier.
        assertArrayEquals(byteArrayOf(0, 0, 0x80.toByte(), 0x3F), bytes.copyOfRange(0, 4))
        assertEquals(-0.5f, ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getFloat(4), 0f)
        assertArrayEquals(vector, EmbeddingCodec.decode(bytes), 0f)
    }

    @Test
    fun `aller-retour exact et normalisation`() {
        val random = Random(7)
        val vector = randomUnit(random)
        assertArrayEquals(vector, EmbeddingCodec.decode(EmbeddingCodec.encode(vector)), 0f)
        val norm = sqrt(vector.fold(0.0) { sum, v -> sum + v * v })
        assertEquals(1.0, norm, 1e-5)
        val out = FloatArray(vector.size)
        EmbeddingCodec.decodeInto(EmbeddingCodec.encode(vector), out)
        assertArrayEquals(vector, out, 0f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `dimension inattendue refusée`() {
        EmbeddingCodec.decodeInto(ByteArray(8), FloatArray(3))
    }

    @Test
    fun `similarités quantifiées proches des cosinus exacts`() {
        val random = Random(42)
        val vectors = List(3000) { randomUnit(random) }
        val builder = EmbeddingIndex.Builder(512)
        vectors.forEachIndexed { i, v -> builder.add(1000L + i, v) }
        val index = builder.build()
        assertEquals(3000, index.size)
        assertEquals(1000L, index.id(0))
        assertEquals(3999L, index.id(2999))

        val query = randomUnit(random)
        val scores = FloatArray(index.size)
        // Calcul en deux tranches, comme la recherche parallèle.
        index.score(query, scores, 0, 1500)
        index.score(query, scores, 1500, 3000)
        var worst = 0f
        for ((i, v) in vectors.withIndex()) {
            val exact = v.indices.fold(0f) { sum, k -> sum + v[k] * query[k] }
            worst = maxOf(worst, abs(exact - scores[i]))
        }
        assertTrue("écart $worst", worst < 0.003f)
    }

    @Test
    fun `une photo identique à la requête obtient le meilleur score`() {
        val random = Random(3)
        val vectors = List(50) { randomUnit(random, 64) }
        val builder = EmbeddingIndex.Builder(64)
        vectors.forEachIndexed { i, v -> builder.add(i.toLong(), v) }
        val index = builder.build()
        val scores = FloatArray(index.size)
        index.score(vectors[17], scores)
        assertEquals(17, scores.indices.maxBy { scores[it] })
        assertEquals(1f, scores[17], 0.01f)
    }

    @Test
    fun `vecteur nul sans erreur`() {
        val builder = EmbeddingIndex.Builder(4)
        builder.add(1L, FloatArray(4))
        val scores = FloatArray(1)
        builder.build().score(floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f), scores)
        assertEquals(0f, scores[0], 0f)
    }
}
