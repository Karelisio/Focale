package app.focale.people

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Empreintes de visage : vecteurs de [DIMENSION] flottants de norme 1, stockés en BLOB
 * (flottants 32 bits petit-boutistes, colonne `face.embedding`). La similarité cosinus
 * de deux empreintes normalisées est leur produit scalaire.
 */
object FaceEmbeddings {

    /** Taille de la sortie de SFace. */
    const val DIMENSION = 128

    fun encode(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(vector)
        return buffer.array()
    }

    fun decode(bytes: ByteArray): FloatArray {
        require(bytes.size % Float.SIZE_BYTES == 0) { "Empreinte tronquée : ${bytes.size} octets" }
        val vector = FloatArray(bytes.size / Float.SIZE_BYTES)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(vector)
        return vector
    }

    /** Copie de norme 1 ; un vecteur nul (ou non fini) reste nul. */
    fun normalize(vector: FloatArray): FloatArray {
        var squares = 0.0
        for (value in vector) squares += value.toDouble() * value
        val norm = sqrt(squares)
        if (norm == 0.0 || !norm.isFinite()) return FloatArray(vector.size)
        return FloatArray(vector.size) { (vector[it] / norm).toFloat() }
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }
}
