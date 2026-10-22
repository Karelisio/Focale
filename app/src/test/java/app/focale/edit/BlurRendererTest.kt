package app.focale.edit

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Rendu réel (graphismes natifs Robolectric) des zones floutées par le pipeline d'export :
 * irréversibilité (aucun pixel d'origine, bords nets), intensité, pleine résolution, et zones
 * attachées à la photo sous rotation, miroir et redressement. Un échantillon est écrit dans
 * `build/tmp/` pour contrôle visuel.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class BlurRendererTest {

    /** Damier noir et blanc d'un pixel : tout pixel d'origine se reconnaît à sa couleur pure. */
    private fun checkerboard(width: Int, height: Int): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(IntArray(width * height) { if ((it % width + it / width) % 2 == 0) Color.BLACK else Color.WHITE }, 0, width, 0, 0, width, height)
    }

    /** Dégradé (rouge selon x, vert selon y) : chaque pixel a sa couleur. */
    private fun gradient(width: Int, height: Int): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(IntArray(width * height) { Color.rgb(it % width * 255 / width, it / width * 255 / height, 90) }, 0, width, 0, 0, width, height)
    }

    /** Rectangle droit de [left] à [right] (exclu) × [top] à [bottom] (exclu) px dans un cadre [width] × [height]. */
    private fun rectangle(left: Int, top: Int, right: Int, bottom: Int, width: Int, height: Int) = BlurArea.Box(
        shape = BlurShape.RECTANGLE,
        x = (left + right) / 2f / width,
        y = (top + bottom) / 2f / height,
        halfWidth = (right - left) / 2f / min(width, height),
        halfHeight = (bottom - top) / 2f / min(width, height),
    )

    private fun zone(area: BlurArea, style: BlurStyle = BlurStyle.PIXELATE, intensity: Float = 0.5f, id: Long = 1) = BlurRegion(id, area, style, intensity)

    private fun Bitmap.pixels(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    private fun gray(pixel: Int): Boolean =
        listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel)).all { it in 85..170 } && Color.alpha(pixel) == 255

    @Test
    fun `la mosaïque ne garde aucun pixel d'origine dans la zone, et rien hors d'elle ne change`() {
        val source = checkerboard(200, 120)
        val out = EditRenderer.render(source, EditState(blurs = listOf(zone(rectangle(60, 30, 140, 90, 200, 120)))))
        for (x in 0 until 200) {
            for (y in 0 until 120) {
                val inside = x in 60 until 140 && y in 30 until 90
                if (inside) {
                    assertTrue("($x, $y) : ${Integer.toHexString(out.getPixel(x, y))}", gray(out.getPixel(x, y)))
                } else {
                    assertEquals("($x, $y)", source.getPixel(x, y), out.getPixel(x, y))
                }
            }
        }
    }

    @Test
    fun `les bords de la zone sont nets, sans fondu`() {
        val source = checkerboard(200, 120)
        val out = EditRenderer.render(source, EditState(blurs = listOf(zone(rectangle(60, 30, 140, 90, 200, 120), style = BlurStyle.BLUR))))
        for (y in 30 until 90) {
            assertEquals(source.getPixel(59, y), out.getPixel(59, y))
            assertTrue(gray(out.getPixel(60, y)))
            assertTrue(gray(out.getPixel(139, y)))
            assertEquals(source.getPixel(140, y), out.getPixel(140, y))
        }
        for (x in 60 until 140) {
            assertEquals(source.getPixel(x, 29), out.getPixel(x, 29))
            assertTrue(gray(out.getPixel(x, 30)))
            assertTrue(gray(out.getPixel(x, 89)))
            assertEquals(source.getPixel(x, 90), out.getPixel(x, 90))
        }
    }

    @Test
    fun `chaque bloc de la mosaïque est uniforme et vaut la moyenne de ses pixels`() {
        val source = gradient(300, 200)
        val region = zone(rectangle(40, 20, 260, 180, 300, 200), intensity = 0.2f)
        val out = EditRenderer.render(source, EditState(blurs = listOf(region)))
        val grid = BlurMath.grid(region, 300, 200)!!
        val pixels = source.pixels()
        for (row in 0 until grid.rows) {
            for (column in 0 until grid.columns) {
                val xs = (grid.left until grid.right).filter { grid.column(it) == column }
                val ys = (grid.top until grid.bottom).filter { grid.row(it) == row }
                // Bloc d'au moins quelques pixels de côté : jamais un pixel isolé recopié.
                assertTrue(xs.size >= 3 && ys.size >= 3)
                var red = 0L
                var green = 0L
                for (x in xs) for (y in ys) {
                    red += Color.red(pixels[y * 300 + x])
                    green += Color.green(pixels[y * 300 + x])
                }
                val count = xs.size * ys.size
                val expected = out.getPixel(xs.first(), ys.first())
                assertEquals(((red + count / 2) / count).toInt(), Color.red(expected))
                assertEquals(((green + count / 2) / count).toInt(), Color.green(expected))
                for (x in xs) for (y in ys) assertEquals("($x, $y)", expected, out.getPixel(x, y))
            }
        }
    }

    @Test
    fun `le flou ne garde aucun pixel d'origine`() {
        val source = checkerboard(240, 160)
        val out = EditRenderer.render(source, EditState(blurs = listOf(zone(rectangle(20, 20, 220, 140, 240, 160), style = BlurStyle.BLUR))))
        for (x in 20 until 220) for (y in 20 until 140) assertTrue("($x, $y)", gray(out.getPixel(x, y)))
    }

    @Test
    fun `le flou est lisse là où la mosaïque fait des marches`() {
        val source = gradient(240, 160)
        fun largestStep(style: BlurStyle): Int {
            val out = EditRenderer.render(source, EditState(blurs = listOf(zone(rectangle(20, 20, 220, 140, 240, 160), style = style))))
            var largest = 0
            for (x in 21 until 220) for (y in 20 until 140) largest = max(largest, abs(Color.red(out.getPixel(x, y)) - Color.red(out.getPixel(x - 1, y))))
            return largest
        }
        val blur = largestStep(BlurStyle.BLUR)
        val mosaic = largestStep(BlurStyle.PIXELATE)
        assertTrue("flou : $blur", blur <= 4)
        assertTrue("mosaïque : $mosaic", mosaic >= 15)
    }

    @Test
    fun `plus d'intensité, plus gros blocs`() {
        val source = gradient(300, 300)
        fun distinct(intensity: Float): Int {
            val out = EditRenderer.render(source, EditState(blurs = listOf(zone(rectangle(30, 30, 270, 270, 300, 300), intensity = intensity))))
            val colors = HashSet<Int>()
            for (x in 30 until 270) for (y in 30 until 270) colors += out.getPixel(x, y)
            return colors.size
        }
        val fine = distinct(0f)
        val coarse = distinct(1f)
        assertEquals(64, fine)
        assertEquals(9, coarse)
    }

    @Test
    fun `une zone remplace aussi la transparence d'origine`() {
        // Bande gauche transparente, reste rouge opaque.
        val source = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(120 * 80) { if (it % 120 < 50) Color.TRANSPARENT else Color.RED }, 0, 120, 0, 0, 120, 80)
        }
        val region = zone(rectangle(0, 0, 120, 80, 120, 80), intensity = 1f)
        val out = EditRenderer.render(source, EditState(blurs = listOf(region)))
        val grid = BlurMath.grid(region, 120, 80)!!
        // Le bloc à cheval sur la limite a une opacité moyenne, uniforme : la limite d'origine a disparu.
        val column = grid.column(50)
        val xs = (0 until 120).filter { grid.column(it) == column }
        assertTrue(xs.first() < 50 && xs.last() >= 50)
        val alpha = Color.alpha(out.getPixel(xs.first(), 10))
        assertTrue(alpha in 1..254)
        for (x in xs) assertEquals(alpha, Color.alpha(out.getPixel(x, 10)))
    }

    @Test
    fun `la zone est calculée en pleine résolution`() {
        // Damier de 3 000 × 2 000 : zone de 1 200 px de côté, six blocs de 200 px.
        val source = checkerboard(3000, 2000)
        val out = EditRenderer.render(source, EditState(blurs = listOf(zone(rectangle(900, 400, 2100, 1600, 3000, 2000), intensity = 0.4f))))
        val grid = BlurMath.grid(zone(rectangle(900, 400, 2100, 1600, 3000, 2000), intensity = 0.4f), 3000, 2000)!!
        assertEquals(6, grid.columns)
        assertEquals(200f, grid.cellWidth, 1e-3f)
        // Une ligne de la zone : six paliers exactement, aux limites des blocs.
        val runs = (900 until 2100).map { out.getPixel(it, 1000) }.zipWithNext().count { (a, b) -> a != b }
        assertTrue(runs <= 5)
        assertTrue(gray(out.getPixel(1500, 1000)))
        assertEquals(source.getPixel(899, 1000), out.getPixel(899, 1000))
    }

    /**
     * Fond bleu et, en ([left], [top]), un « visage » de 40 × 40 px : damier noir et blanc de cases
     * de 4 px, dont l'intérieur des cases reste pur même photo tournée.
     */
    private fun marked(width: Int, height: Int, left: Int, top: Int): Bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        setPixels(
            IntArray(width * height) {
                val x = it % width - left
                val y = it / width - top
                when {
                    x !in 0 until 40 || y !in 0 until 40 -> Color.BLUE
                    (x / 4 + y / 4) % 2 == 0 -> Color.BLACK
                    else -> Color.WHITE
                }
            },
            0, width, 0, 0, width, height,
        )
    }

    /**
     * Pixels du damier encore visibles : noir ou blanc purs et opaques (une moyenne de blocs n'en
     * donne pas ; les coins vides d'une photo redressée sans recadrage ne comptent pas).
     */
    private fun revealed(bitmap: Bitmap): Int = bitmap.pixels().count { pixel ->
        val channels = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
        Color.alpha(pixel) == 255 && (channels.all { it <= 12 } || channels.all { it >= 243 })
    }

    @Test
    fun `la zone pivote avec la photo redressée et cache toujours son contenu`() {
        val source = marked(400, 300, 260, 110)
        // Zone posée sur le damier (centre (280, 130)), un peu plus grande que lui.
        val area = rectangle(250, 100, 310, 160, 400, 300)
        for (angle in listOf(-30f, -8.5f, 12f, 45f)) {
            val plain = EditRenderer.render(source, EditState(straighten = angle))
            assertTrue("$angle°", revealed(plain) > 200)
            assertEquals("$angle°", 0, revealed(EditRenderer.render(source, EditState(straighten = angle, blurs = listOf(zone(area))))))
            // Recadrée sans coin vide, la photo garde son damier caché.
            val crop = StraightenMath.fit(NormRect.Full, angle, 400f, 300f)
            assertEquals("$angle°", 0, revealed(EditRenderer.render(source, EditState(straighten = angle, crop = crop, blurs = listOf(zone(area))))))
        }
    }

    @Test
    fun `la zone suit rotations et miroirs de l'édition, redressée ou non`() {
        val source = marked(400, 300, 260, 110)
        for (angle in listOf(0f, 14f)) {
            var state = EditState(straighten = angle, blurs = listOf(zone(rectangle(250, 100, 310, 160, 400, 300))))
            repeat(5) { step ->
                // Comme EditorViewModel.rotate() puis flip(), en alternance.
                state = if (step % 2 == 0) state.rotated() else state.mirrored()
                assertEquals("$angle°, étape $step", 0, revealed(EditRenderer.render(source, state)))
                assertTrue(revealed(EditRenderer.render(source, state.copy(blurs = emptyList()))) > 200)
            }
        }
    }

    @Test
    fun `recadrer garde la zone sur le même détail`() {
        // Damier dans la moitié droite, zone posée dessus, puis seule la moitié droite est gardée.
        val source = marked(400, 300, 260, 110)
        val state = EditState(crop = NormRect(0.5f, 0f, 1f, 1f), blurs = listOf(zone(rectangle(250, 100, 310, 160, 400, 300))))
        val out = EditRenderer.render(source, state)
        assertEquals(200, out.width)
        assertEquals(0, revealed(out))
        // La zone occupe toujours les px 250 à 310 de la photo, soit 50 à 110 du recadrage.
        assertTrue(gray(out.getPixel(80, 130)) || Color.blue(out.getPixel(80, 130)) < 250)
        assertEquals(Color.BLUE, out.getPixel(45, 105))
        assertEquals(Color.BLUE, out.getPixel(115, 105))
    }

    @Test
    fun `la zone prend le filtre de couleur de la photo`() {
        val source = gradient(200, 200)
        val out = EditRenderer.render(source, EditState(filter = FilterPreset.MONO, blurs = listOf(zone(rectangle(50, 50, 150, 150, 200, 200)))))
        val pixel = out.getPixel(100, 100)
        assertEquals(Color.red(pixel), Color.green(pixel))
        assertEquals(Color.green(pixel), Color.blue(pixel))
    }

    @Test
    fun `formes et styles sont rendus pour contrôle visuel`() {
        val source = gradient(600, 400)
        val zones = listOf(
            zone(rectangle(30, 40, 200, 180, 600, 400), style = BlurStyle.PIXELATE, id = 1),
            zone(BlurArea.Box(BlurShape.ELLIPSE, 0.55f, 0.3f, 0.2f, 0.15f, angle = 20f), style = BlurStyle.BLUR, id = 2),
            zone(BlurArea.Brush(floatArrayOf(0.1f, 0.8f, 0.3f, 0.65f, 0.5f, 0.85f, 0.8f, 0.7f), 0.08f), style = BlurStyle.PIXELATE, intensity = 1f, id = 3),
        )
        val out = EditRenderer.render(source, EditState(blurs = zones))
        File("build/tmp/blur-zones.png").apply { parentFile?.mkdirs() }.outputStream().use {
            out.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertNotEquals(source.getPixel(100, 100), out.getPixel(100, 100))
        assertEquals(source.getPixel(590, 390), out.getPixel(590, 390))
    }
}
