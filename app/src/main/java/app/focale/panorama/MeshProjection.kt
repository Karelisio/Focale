package app.focale.panorama

import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater
import kotlin.math.sqrt

/**
 * Maillage d'une vidéo Spherical Video V2 (boîte `mshp`, ou `ytmp` des premiers fichiers de
 * YouTube) : c'est ainsi que YouTube décrit ses cubes équi-angulaires (EAC, marges comprises),
 * et toute autre projection. Contenu de la boîte : version, CRC, puis l'encodage (`raw ` : tel
 * quel ; `dfl8` : deflate brut, sans en-tête zlib) des boîtes `mesh`, la première pour l'œil
 * gauche (ou les deux yeux), la seconde pour l'œil droit, ignorée.
 *
 * Une boîte `mesh` : les coordonnées distinctes (flottants), les sommets (cinq index x, y, z, u,
 * v dans ces coordonnées, écarts au précédent en zigzag sur ⌈log₂(2 × coordonnées)⌉ bits), puis
 * des listes d'index de sommets (triangles, bande ou éventail, écarts sur ⌈log₂(2 × sommets)⌉
 * bits), chacune pour une texture : 0 est l'image de la vidéo, les autres (images fixes) ne sont
 * pas dessinées. Positions en repère OpenGL (-Z devant, Y en haut), comme [SphereMesh] ; mises à
 * l'échelle du rayon le plus grand, l'image projetée ne change pas et reste entre les plans de
 * coupe. Coordonnées de texture d'OpenGL (origine en bas à gauche) retournées : v = 0 en haut,
 * comme les autres projections ; la stéréoscopie s'applique ensuite ([VideoFrame]).
 *
 * Le CRC n'est pas vérifié (pas plus que par ExoPlayer) ; des tailles ou des index hors bornes, un
 * flux deflate invalide ou trop grand donnent null, sans erreur.
 */
internal object MeshProjection {

    /** Boîte `mshp` lue au plus : celles de YouTube font quelques kilo-octets. */
    const val MAX_BOX = 1 shl 20

    private const val MAX_INFLATED = 8 shl 20
    private const val MAX_COORDINATES = 1 shl 18

    /** Sommets adressables par des index de 16 bits (OpenGL ES 2). */
    private const val MAX_VERTICES = 0xFFFF
    private const val MAX_INDICES = 1 shl 21
    private const val MAX_BOXES = 64
    private const val MAX_LISTS = 4096

    private const val TRIANGLES = 0
    private const val STRIP = 1
    private const val FAN = 2

    /** Maillage de l'œil gauche du contenu de la boîte `mshp` [box] (après son en-tête), ou null. */
    fun decode(box: ByteArray): TexturedMesh? = try {
        if (box.size < 12 || box[0].toInt() != 0) {
            null
        } else {
            val payload = when (String(box, 8, 4, Charsets.ISO_8859_1)) {
                "raw " -> box.copyOfRange(12, box.size)
                "dfl8" -> inflate(box, 12)
                else -> null
            }
            payload?.let(::firstMesh)
        }
    } catch (malformed: IndexOutOfBoundsException) {
        null
    } catch (malformed: DataFormatException) {
        null
    }

    private fun inflate(data: ByteArray, from: Int): ByteArray? {
        val inflater = Inflater(true)
        try {
            inflater.setInput(data, from, data.size - from)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                output.write(buffer, 0, count)
                if (output.size() > MAX_INFLATED) return null
            }
            return output.toByteArray()
        } finally {
            inflater.end()
        }
    }

    /** Première boîte `mesh` des boîtes de [data] ; les autres boîtes sont ignorées. */
    private fun firstMesh(data: ByteArray): TexturedMesh? {
        var position = 0
        repeat(MAX_BOXES) {
            if (position + 8 > data.size) return null
            val size = u32(data, position)
            if (size < 8 || size > data.size - position) return null
            if (String(data, position + 4, 4, Charsets.ISO_8859_1) == "mesh") {
                return mesh(BitReader(data, position + 8, position + size.toInt()))
            }
            position += size.toInt()
        }
        return null
    }

    private fun mesh(input: BitReader): TexturedMesh? {
        val coordinateCount = input.count(MAX_COORDINATES) ?: return null
        val coordinates = FloatArray(coordinateCount) { Float.fromBits(input.bits(32).toInt()) }
        val vertexCount = input.count(MAX_VERTICES) ?: return null
        val coordinateBits = bitsFor(coordinateCount)
        val vertices = FloatArray(vertexCount * 5)
        val previous = IntArray(5)
        for (vertex in 0 until vertexCount) {
            for (component in 0 until 5) {
                val index = previous[component] + zigzag(input.bits(coordinateBits))
                if (index !in 0 until coordinateCount) return null
                previous[component] = index
                vertices[vertex * 5 + component] = coordinates[index]
            }
        }
        input.align()
        val listCount = input.count(MAX_LISTS) ?: return null
        val vertexBits = bitsFor(vertexCount)
        val triangles = Triangles()
        for (list in 0 until listCount) {
            val texture = input.bits(8).toInt()
            val type = input.bits(8).toInt()
            val count = input.count(MAX_INDICES) ?: return null
            val indices = IntArray(count)
            var index = 0
            for (position in 0 until count) {
                index += zigzag(input.bits(vertexBits))
                if (index !in 0 until vertexCount) return null
                indices[position] = index
            }
            input.align()
            // Image fixe à la place d'une partie non filmée, ou type inconnu : non dessinés.
            if (texture == 0) triangles.add(type, indices)
            if (triangles.size > MAX_INDICES) return null
        }
        if (triangles.size == 0) return null
        return textured(vertices, vertexCount, triangles.toArray())
    }

    /** Sommets mis à l'échelle du plus éloigné, coordonnées de texture retournées (v = 0 en haut). */
    private fun textured(vertices: FloatArray, vertexCount: Int, indices: ShortArray): TexturedMesh? {
        var radius = 0f
        for (vertex in 0 until vertexCount) {
            val x = vertices[vertex * 5]
            val y = vertices[vertex * 5 + 1]
            val z = vertices[vertex * 5 + 2]
            radius = maxOf(radius, sqrt(x * x + y * y + z * z))
        }
        if (!radius.isFinite() || radius <= 0f) return null
        val positions = FloatArray(vertexCount * 3)
        val texCoords = FloatArray(vertexCount * 2)
        for (vertex in 0 until vertexCount) {
            for (axis in 0 until 3) positions[vertex * 3 + axis] = vertices[vertex * 5 + axis] / radius
            texCoords[vertex * 2] = vertices[vertex * 5 + 3]
            texCoords[vertex * 2 + 1] = 1f - vertices[vertex * 5 + 4]
        }
        if (texCoords.any { !it.isFinite() }) return null
        return TexturedMesh(positions, texCoords, indices)
    }

    /** Triangles des listes de sommets, bandes et éventails dépliés (sens de parcours gardé). */
    private class Triangles {
        private var values = ShortArray(256)
        var size = 0
            private set

        fun add(type: Int, indices: IntArray) {
            when (type) {
                TRIANGLES -> for (first in 0..indices.size - 3 step 3) triangle(indices[first], indices[first + 1], indices[first + 2])
                STRIP -> for (first in 0..indices.size - 3) {
                    if (first % 2 == 0) {
                        triangle(indices[first], indices[first + 1], indices[first + 2])
                    } else {
                        triangle(indices[first + 1], indices[first], indices[first + 2])
                    }
                }
                FAN -> for (first in 1..indices.size - 2) triangle(indices[0], indices[first], indices[first + 1])
            }
        }

        private fun triangle(a: Int, b: Int, c: Int) {
            if (size + 3 > values.size) values = values.copyOf(values.size * 2)
            values[size++] = a.toShort()
            values[size++] = b.toShort()
            values[size++] = c.toShort()
        }

        fun toArray(): ShortArray = values.copyOf(size)
    }

    /** Lecture bit à bit, poids fort d'abord, de [data] entre [from] et [to] ; au-delà : [IndexOutOfBoundsException]. */
    private class BitReader(private val data: ByteArray, from: Int, private val to: Int) {
        private var bit = from.toLong() * 8

        fun bits(count: Int): Long {
            var value = 0L
            repeat(count) {
                val byte = (bit ushr 3).toInt()
                if (byte >= to) throw IndexOutOfBoundsException("fin du maillage")
                value = (value shl 1) or ((data[byte].toInt() ushr (7 - (bit and 7).toInt())) and 1).toLong()
                bit++
            }
            return value
        }

        /** Nombre sur 32 bits dont le bit de poids fort, réservé, est nul ; null s'il dépasse [max]. */
        fun count(max: Int): Int? = bits(32).takeIf { it <= max }?.toInt()

        fun align() {
            bit = (bit + 7) and 7L.inv()
        }
    }

    /** ⌈log₂(2 × [count])⌉ : bits d'un écart en zigzag entre deux index de [0, count[. */
    private fun bitsFor(count: Int): Int {
        var bits = 0
        while ((1L shl bits) < 2L * count) bits++
        return bits
    }

    private fun zigzag(value: Long): Int = ((value ushr 1) xor -(value and 1)).toInt()

    private fun u32(data: ByteArray, at: Int): Long =
        ((data[at].toLong() and 0xFF) shl 24) or ((data[at + 1].toLong() and 0xFF) shl 16) or
            ((data[at + 2].toLong() and 0xFF) shl 8) or (data[at + 3].toLong() and 0xFF)
}
