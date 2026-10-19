package app.focale.people

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceAlignmentTest {

    private val template = FaceAlignment.TEMPLATE

    private fun assertAffine(expected: DoubleArray, actual: Affine, tolerance: Double) {
        val values = doubleArrayOf(actual.a, actual.b, actual.c, actual.d, actual.e, actual.f)
        for (i in values.indices) assertEquals("coefficient $i", expected[i], values[i], tolerance)
    }

    @Test
    fun `la similitude retrouve une rotation, une échelle et une translation connues`() {
        // Visage réel = gabarit tourné de 20°, agrandi 2,5 fois et décalé.
        val angle = 20 * PI / 180
        val scale = 2.5
        val source = template.map { p ->
            FacePoint(
                (scale * (cos(angle) * p.x - sin(angle) * p.y) + 140).toFloat(),
                (scale * (sin(angle) * p.x + cos(angle) * p.y) + 60).toFloat(),
            )
        }
        val transform = FaceAlignment.similarity(source)
        assertEquals(1 / scale, transform.scale, 1e-6)
        source.zip(template).forEach { (from, to) ->
            val mapped = transform.apply(from)
            assertEquals(to.x, mapped.x, 1e-3f)
            assertEquals(to.y, mapped.y, 1e-3f)
        }
    }

    @Test
    fun `la matrice est celle de FaceRecognizerSF d'OpenCV`() {
        // Valeurs de référence : portage exact de getSimilarityTransformMatrix (Umeyama par SVD).
        val enlarged = listOf(
            FacePoint(176.5892f, 143.3926f), FacePoint(247.0636f, 143.0028f), FacePoint(212.0504f, 183.4732f),
            FacePoint(183.0986f, 224.731f), FacePoint(241.4598f, 224.4082f),
        )
        assertAffine(doubleArrayOf(0.5, 0.0, -50.0, 0.0, 0.5, -20.0), FaceAlignment.similarity(enlarged), 1e-4)

        val tilted = listOf(
            FacePoint(261.255571f, 161.760559f), FacePoint(351.996853f, 215.209994f), FacePoint(277.560572f, 241.122686f),
            FacePoint(207.20773f, 271.90429f), FacePoint(284.363253f, 316.455861f),
        )
        assertAffine(
            doubleArrayOf(0.2871072935, 0.1683685738, -63.9780735538, -0.1683685738, 0.2871072935, 49.1744905388),
            FaceAlignment.similarity(tilted),
            1e-4,
        )

        val detected = listOf(
            FacePoint(412.3f, 380.1f), FacePoint(530.8f, 391.6f), FacePoint(470.2f, 455.0f),
            FacePoint(420.9f, 522.4f), FacePoint(517.5f, 531.0f),
        )
        assertAffine(
            doubleArrayOf(0.2916243559, 0.0150117711, -87.9820664277, -0.0150117711, 0.2916243559, -54.0251045303),
            FaceAlignment.similarity(detected),
            1e-4,
        )
    }

    @Test
    fun `l'inverse ramène chaque point à sa place`() {
        val transform = Affine(0.8, -0.3, 12.0, 0.3, 0.8, -7.0)
        val point = FacePoint(33f, -12f)
        val back = transform.inverse().apply(transform.apply(point))
        assertEquals(point.x, back.x, 1e-4f)
        assertEquals(point.y, back.y, 1e-4f)
    }

    @Test
    fun `les repères d'un visage en miroir sont remis dans l'ordre du gabarit`() {
        val (eye0, eye1, nose, mouth0, mouth1) = template
        // Convention ML Kit, visage de face : l'œil droit de la personne est à gauche de l'image.
        assertEquals(template, FaceAlignment.ordered(eye0, eye1, nose, mouth0, mouth1))
        // Photo retournée (ou convention inverse) : les paires sont permutées.
        assertEquals(template, FaceAlignment.ordered(eye1, eye0, nose, mouth1, mouth0))
        // Coins de la bouche seuls inversés.
        assertEquals(template, FaceAlignment.ordered(eye0, eye1, nose, mouth1, mouth0))
    }

    @Test
    fun `le rééchantillonnage interpole et met du noir hors de l'image`() {
        // Image 4 × 1 : niveaux de gris 0, 100, 200, 250.
        val gray = intArrayOf(0, 100, 200, 250).map { 0xFF shl 24 or (it shl 16) or (it shl 8) or it }.toIntArray()
        // Sortie 2 × 2 décalée d'un demi-pixel : x' = x - 0,5.
        val shifted = Affine(1.0, 0.0, -0.5, 0.0, 1.0, 0.0)
        val out = FaceAlignment.warp(gray, 4, 1, shifted, size = 2)
        assertEquals(50, out[0] and 0xFF)
        assertEquals(150, out[1] and 0xFF)
        // Deuxième ligne : à moitié hors de l'image vers le bas, mêlée au noir.
        assertEquals(0, out[2] and 0xFF)
        assertEquals(0xFF, out[3] ushr 24)
    }

    @Test
    fun `le tenseur range les plans rouge, vert puis bleu en valeurs brutes`() {
        val pixels = intArrayOf(0xFF102030.toInt(), 0xFFFFFFFF.toInt())
        val tensor = FaceAlignment.tensor(pixels)
        assertEquals(listOf(16f, 255f, 32f, 255f, 48f, 255f), tensor.toList())
    }

    @Test
    fun `une image unie est floue, un damier est net`() {
        val size = FaceAlignment.SIZE
        val flat = IntArray(size * size) { 0xFF808080.toInt() }
        val checker = IntArray(size * size) { i -> if ((i % size + i / size) % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        assertEquals(0.0, FaceAlignment.sharpness(flat), 1e-9)
        assertTrue(FaceAlignment.sharpness(checker) > 1_000)
    }
}
