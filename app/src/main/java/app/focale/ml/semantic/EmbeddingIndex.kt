package app.focale.ml.semantic

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** (Dé)sérialisation des empreintes : flottants 32 bits petit-boutistes, de norme 1. */
object EmbeddingCodec {

    fun encode(vector: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(vector.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        buffer.asFloatBuffer().put(vector)
        return buffer.array()
    }

    fun decode(bytes: ByteArray): FloatArray = FloatArray(bytes.size / Float.SIZE_BYTES).also { decodeInto(bytes, it) }

    /** Décode [bytes] dans [out] (même dimension), sans allocation. */
    fun decodeInto(bytes: ByteArray, out: FloatArray) {
        require(bytes.size == out.size * Float.SIZE_BYTES) { "Dimension inattendue : ${bytes.size / Float.SIZE_BYTES}" }
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
    }

    /** Ramène [vector] à la norme 1, sur place. */
    fun normalize(vector: FloatArray): FloatArray {
        var sum = 0.0
        for (v in vector) sum += v.toDouble() * v
        val norm = sqrt(sum).toFloat()
        if (norm > 0f) for (i in vector.indices) vector[i] /= norm
        return vector
    }
}

/**
 * Empreintes de la bibliothèque en mémoire, pour comparer une requête à toutes les photos.
 *
 * Chaque ligne est quantifiée sur 8 bits avec sa propre échelle (512 octets par photo au
 * lieu de 2 Ko) : l'erreur sur le cosinus reste de l'ordre du millième, sans effet sur le
 * classement.
 */
class EmbeddingIndex private constructor(
    private val ids: LongArray,
    private val codes: ByteArray,
    private val scales: FloatArray,
    val size: Int,
    val dimension: Int,
) {
    fun id(row: Int): Long = ids[row]

    /** Similarité cosinus de [query] (norme 1) avec les lignes `[from, to)`, écrite dans [scores]. */
    fun score(query: FloatArray, scores: FloatArray, from: Int = 0, to: Int = size) {
        require(query.size == dimension)
        for (row in from until to) {
            val offset = row * dimension
            var sum = 0f
            for (i in 0 until dimension) sum += query[i] * codes[offset + i]
            scores[row] = sum * scales[row]
        }
    }

    class Builder(private val dimension: Int) {
        private var ids = LongArray(INITIAL_CAPACITY)
        private var codes = ByteArray(INITIAL_CAPACITY * dimension)
        private var scales = FloatArray(INITIAL_CAPACITY)
        private var count = 0

        fun add(id: Long, vector: FloatArray) {
            require(vector.size == dimension)
            if (count == ids.size) {
                val capacity = ids.size * 2
                ids = ids.copyOf(capacity)
                codes = codes.copyOf(capacity * dimension)
                scales = scales.copyOf(capacity)
            }
            var maxAbs = 0f
            for (v in vector) maxAbs = maxOf(maxAbs, abs(v))
            val scale = maxAbs / Byte.MAX_VALUE
            val offset = count * dimension
            if (scale > 0f) {
                for (i in 0 until dimension) codes[offset + i] = (vector[i] / scale).roundToInt().coerceIn(-127, 127).toByte()
            }
            ids[count] = id
            scales[count] = scale
            count++
        }

        fun build(): EmbeddingIndex = EmbeddingIndex(ids, codes, scales, count, dimension)
    }

    private companion object {
        const val INITIAL_CAPACITY = 1024
    }
}
