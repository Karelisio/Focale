package app.focale.people

import java.io.File
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test

/** Exécute réellement le modèle embarqué (ONNX Runtime pour la JVM). */
class SFaceModelTest {

    /** Image de synthèse 112 × 112 : dégradés et motifs, déterministe. */
    private fun synthetic(phase: Float): IntArray = IntArray(FaceAlignment.SIZE * FaceAlignment.SIZE) { i ->
        val x = i % FaceAlignment.SIZE
        val y = i / FaceAlignment.SIZE
        val r = (127 + 120 * sin(x / 9f + phase)).toInt()
        val g = (127 + 120 * sin(y / 7f - phase)).toInt()
        val b = ((x + y) * 255 / (2 * FaceAlignment.SIZE))
        0xFF shl 24 or (r shl 16) or (g shl 8) or b
    }

    @Test
    fun `l'empreinte a 128 valeurs finies et une norme 1`() {
        val embedding = model.embed(FaceAlignment.tensor(synthetic(0f)))
        assertEquals(FaceEmbeddings.DIMENSION, embedding.size)
        assertTrue(embedding.all { it.isFinite() })
        assertEquals(1.0, sqrt(embedding.sumOf { it.toDouble() * it }), 1e-4)
        assertTrue("empreinte nulle", embedding.any { it != 0f })
    }

    @Test
    fun `le calcul est déterministe et dépend de l'image`() {
        val first = model.embed(FaceAlignment.tensor(synthetic(0f)))
        val again = model.embed(FaceAlignment.tensor(synthetic(0f)))
        val other = model.embed(FaceAlignment.tensor(synthetic(2.5f)))
        assertEquals(1f, FaceEmbeddings.dot(first, again), 1e-5f)
        assertTrue(FaceEmbeddings.dot(first, other) < 0.99f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `une entrée de mauvaise taille est refusée`() {
        model.embed(FloatArray(10))
    }

    companion object {
        private lateinit var model: SFaceModel

        @JvmStatic
        @BeforeClass
        fun load() {
            val file = listOf("src/main/assets", "app/src/main/assets")
                .map { File(it, SFaceModel.ASSET) }
                .first { it.exists() }
            model = SFaceModel(file.readBytes(), threads = 1)
        }

        @JvmStatic
        @AfterClass
        fun close() {
            model.close()
        }
    }
}
