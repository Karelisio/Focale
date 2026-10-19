package app.focale.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageMetricsTest {

    @Test
    fun `dHash identique pour une image et sa version éclaircie`() {
        val gray = IntArray(72) { (it * 37) % 200 }
        val brighter = IntArray(72) { gray[it] + 40 }
        assertEquals(ImageMetrics.dHash(gray), ImageMetrics.dHash(brighter))
    }

    @Test
    fun `dHash très différent pour des images différentes`() {
        val gradient = IntArray(72) { (it % 9) * 25 }
        val inverse = IntArray(72) { 255 - (it % 9) * 25 }
        assertTrue(ImageMetrics.hammingDistance(ImageMetrics.dHash(gradient), ImageMetrics.dHash(inverse)) > 40)
    }

    @Test
    fun `un damier net a un laplacien bien plus fort qu'une version floutée`() {
        val size = 64
        val sharp = IntArray(size * size) { i -> if (((i % size) / 4 + (i / size) / 4) % 2 == 0) 230 else 20 }
        val blurred = boxBlur(boxBlur(sharp, size), size)
        val sharpScore = ImageMetrics.laplacianVariance(sharp, size, size)
        val blurredScore = ImageMetrics.laplacianVariance(blurred, size, size)
        assertTrue("net=$sharpScore flou=$blurredScore", sharpScore > blurredScore * 5)
    }

    @Test
    fun `image uniforme donne une variance nulle`() {
        assertEquals(0.0, ImageMetrics.laplacianVariance(IntArray(100) { 128 }, 10, 10), 1e-9)
    }

    @Test
    fun `redimensionnement en conservant le rapport`() {
        assertEquals(384 to 288, ImageMetrics.fit(4000, 3000, 384))
        assertEquals(100 to 50, ImageMetrics.fit(100, 50, 384))
    }

    private fun boxBlur(source: IntArray, size: Int): IntArray = IntArray(source.size) { i ->
        val x = i % size
        val y = i / size
        var sum = 0
        var count = 0
        for (dy in -2..2) for (dx in -2..2) {
            val nx = x + dx
            val ny = y + dy
            if (nx in 0 until size && ny in 0 until size) {
                sum += source[ny * size + nx]
                count++
            }
        }
        sum / count
    }
}
