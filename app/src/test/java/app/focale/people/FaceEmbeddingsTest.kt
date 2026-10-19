package app.focale.people

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FaceEmbeddingsTest {

    @Test
    fun `l'encodage est en flottants 32 bits petit-boutistes`() {
        val bytes = FaceEmbeddings.encode(floatArrayOf(1f, -2f))
        // 1.0f = 0x3F800000, -2.0f = 0xC0000000, octet de poids faible en premier.
        assertArrayEquals(byteArrayOf(0, 0, 0x80.toByte(), 0x3F, 0, 0, 0, 0xC0.toByte()), bytes)
    }

    @Test
    fun `décodage et encodage sont réciproques`() {
        val vector = FloatArray(FaceEmbeddings.DIMENSION) { (it - 64) / 37f }
        val bytes = FaceEmbeddings.encode(vector)
        assertEquals(FaceEmbeddings.DIMENSION * 4, bytes.size)
        assertArrayEquals(vector, FaceEmbeddings.decode(bytes), 0f)
    }

    @Test
    fun `une empreinte tronquée est refusée`() {
        assertThrows(IllegalArgumentException::class.java) { FaceEmbeddings.decode(ByteArray(7)) }
    }

    @Test
    fun `la normalisation donne une norme 1 et laisse le vecteur nul intact`() {
        val unit = FaceEmbeddings.normalize(floatArrayOf(3f, 4f))
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), unit, 1e-6f)
        assertEquals(1f, FaceEmbeddings.dot(unit, unit), 1e-6f)
        assertArrayEquals(floatArrayOf(0f, 0f), FaceEmbeddings.normalize(floatArrayOf(0f, 0f)), 0f)
    }
}
