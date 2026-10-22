package app.focale.collage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Rendu du collage sur des images de test : dimensions, fond dans l'espacement et autour, coins
 * arrondis, échange de cases, cadrage et agrandissement, orientation EXIF, décodage à la
 * résolution utile seulement (neuf photos de 50 Mpx, par bandes) et bandes raccordées sans couture.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class CollageRendererTest {

    private val red = solid(600, 400, Color.RED)
    private val blue = solid(600, 400, Color.BLUE)

    /** Photos en mémoire, non recyclées à la fermeture : un test peut rendre plusieurs fois. */
    private fun sources(vararg photos: Pair<Long, Bitmap>, orientation: Int = 1): (Long) -> CollageSource {
        val byId = photos.toMap()
        return { id -> BitmapSource(byId.getValue(id), orientation, owned = false) }
    }

    private fun sideBySide(spacing: Float = 0.05f, corners: Float = 0f) = CollageState(
        layoutId = "2-side",
        photos = listOf(1L, 2L),
        spacing = spacing,
        corners = corners,
        backgroundColor = GREEN,
    )

    @Test
    fun `dimensions, fond dans l'espacement et autour, chaque photo dans sa case`() {
        // 400 × 400, espacement de 5 % : 20 px. Cases (20, 20, 190, 380) et (210, 20, 380, 380).
        val collage = CollageRenderer.render(sideBySide(), 400, 400, sources(1L to red, 2L to blue))
        assertEquals(400, collage.width)
        assertEquals(400, collage.height)
        assertEquals(GREEN, collage.getPixel(10, 200))
        assertEquals(GREEN, collage.getPixel(200, 200))
        assertEquals(GREEN, collage.getPixel(390, 200))
        assertEquals(GREEN, collage.getPixel(100, 10))
        assertEquals(GREEN, collage.getPixel(100, 390))
        assertEquals(Color.RED, collage.getPixel(100, 200))
        assertEquals(Color.BLUE, collage.getPixel(300, 200))
        // Bords des cases au pixel près : premier et dernier pixel de chaque case, puis l'espacement.
        assertEquals(Color.RED, collage.getPixel(20, 20))
        assertEquals(Color.RED, collage.getPixel(189, 379))
        assertEquals(GREEN, collage.getPixel(190, 200))
        assertEquals(GREEN, collage.getPixel(209, 200))
        assertEquals(Color.BLUE, collage.getPixel(210, 200))
        assertEquals(GREEN, collage.getPixel(19, 200))
        assertEquals(GREEN, collage.getPixel(100, 380))
    }

    @Test
    fun `sans espacement, les cases se touchent et le fond ne se voit pas`() {
        val collage = CollageRenderer.render(sideBySide(spacing = 0f), 400, 400, sources(1L to red, 2L to blue))
        assertEquals(Color.RED, collage.getPixel(0, 0))
        assertEquals(Color.RED, collage.getPixel(199, 399))
        assertEquals(Color.BLUE, collage.getPixel(200, 0))
        assertEquals(Color.BLUE, collage.getPixel(399, 399))
    }

    @Test
    fun `coins arrondis, le fond apparaît dans les coins des cases`() {
        // Arrondi de 10 % du petit côté : 40 px.
        val rounded = CollageRenderer.render(sideBySide(corners = 0.1f), 400, 400, sources(1L to red, 2L to blue))
        assertEquals(GREEN, rounded.getPixel(21, 21))
        assertEquals(GREEN, rounded.getPixel(188, 378))
        assertEquals(GREEN, rounded.getPixel(211, 21))
        assertEquals(Color.RED, rounded.getPixel(60, 60))
        // Milieu des bords : intact.
        assertEquals(Color.RED, rounded.getPixel(20, 200))
        assertEquals(Color.RED, rounded.getPixel(100, 20))
        val square = CollageRenderer.render(sideBySide(corners = 0f), 400, 400, sources(1L to red, 2L to blue))
        assertEquals(Color.RED, square.getPixel(21, 21))
    }

    @Test
    fun `échanger deux cases échange leurs photos`() {
        val swapped = sideBySide().swapped(0, 1)
        val collage = CollageRenderer.render(swapped, 400, 400, sources(1L to red, 2L to blue))
        assertEquals(Color.BLUE, collage.getPixel(100, 200))
        assertEquals(Color.RED, collage.getPixel(300, 200))
    }

    @Test
    fun `cadrage et agrandissement, la case montre la partie choisie de la photo`() {
        // Photo en quatre quarts ; cases carrées de 200 px côte à côte, sans espacement (400 × 200).
        val quarters = quarters(400, 400)
        fun render(frame: CellFrame): Bitmap {
            val state = sideBySide(spacing = 0f).copy(frames = mapOf(1L to frame))
            return CollageRenderer.render(state, 400, 200, sources(1L to quarters, 2L to blue))
        }
        // Sans cadrage : la photo entière, ses quatre quarts.
        val whole = render(CellFrame.Default)
        assertEquals(Color.RED, whole.getPixel(50, 50))
        assertEquals(Color.BLUE, whole.getPixel(150, 50))
        assertEquals(GREEN, whole.getPixel(50, 150))
        assertEquals(Color.YELLOW, whole.getPixel(150, 150))
        // Agrandie deux fois sur le quart en haut à gauche : toute la case est rouge.
        val zoomed = render(CellFrame(zoom = 2f, centerX = 0.25f, centerY = 0.25f))
        for ((x, y) in listOf(2 to 2, 100 to 100, 197 to 197)) assertEquals(Color.RED, zoomed.getPixel(x, y))
        // Puis déplacée vers le quart en bas à droite.
        val moved = render(CellFrame(zoom = 2f, centerX = 0.75f, centerY = 0.75f))
        assertEquals(Color.YELLOW, moved.getPixel(100, 100))
    }

    @Test
    fun `orientation EXIF respectée, photo stockée de côté affichée droite`() {
        // Image stockée en paysage (300 × 200), orientation 6 : affichée en portrait (200 × 300),
        // tournée d'un quart de tour horaire. Case de 200 × 300 : la photo entière.
        val stored = quarters(300, 200)
        val state = sideBySide(spacing = 0f)
        val collage = CollageRenderer.render(state, 400, 300, sources(1L to stored, 2L to blue, orientation = 6))
        // Quart de tour horaire : bas gauche → haut gauche, haut gauche → haut droite, etc.
        assertEquals(GREEN, collage.getPixel(50, 75))
        assertEquals(Color.RED, collage.getPixel(150, 75))
        assertEquals(Color.YELLOW, collage.getPixel(50, 225))
        assertEquals(Color.BLUE, collage.getPixel(150, 225))
    }

    @Test
    fun `neuf photos de 50 Mpx, décodées réduites, par bandes, jamais entières`() {
        val decodes = ArrayList<Pair<Rect, Int>>()
        val colors = (1L..9L).associateWith { Color.rgb(20 * it.toInt(), 255 - 20 * it.toInt(), 100) }
        val open: (Long) -> CollageSource = { id -> LargeSource(colors.getValue(id), decodes) }
        val state = CollageState(layoutId = "9-grid", photos = (1L..9L).toList(), spacing = 0.01f, backgroundColor = Color.WHITE)
        val (width, height) = CollageAspect.SQUARE.size(CollageRenderer.LONG_SIDE)

        val collage = CollageRenderer.render(state, width, height, open)

        assertEquals(4096, collage.width)
        assertTrue(decodes.isNotEmpty())
        for ((region, sample) in decodes) {
            // Cases d'environ 1 340 px pour des photos de 8 160 × 6 120 : réduites par 4, au moins
            // un pixel décodé par pixel du collage, et jamais plus de BAND_PIXELS à la fois.
            assertEquals(4, sample)
            val pixels = (region.width() / sample).toLong() * (region.height() / sample)
            assertTrue("$region : $pixels px", pixels <= CollageRenderer.BAND_PIXELS)
            assertTrue(region.width() < LargeSource.WIDTH || region.height() < LargeSource.HEIGHT)
        }
        val gap = CollageGeometry.pixelGap(state.spacing, width, height)
        CollageGeometry.pixelCells(state.layout, width, height, gap).forEachIndexed { index, cell ->
            assertEquals(colors.getValue(index + 1L), collage.getPixel(cell.centerX.toInt(), cell.centerY.toInt()))
        }
        collage.recycle()
    }

    @Test
    fun `résolution utile, facteur de réduction le plus grand qui garde un pixel décodé par pixel du collage`() {
        assertEquals(1, CollageRenderer.sampleSize(3f))
        assertEquals(1, CollageRenderer.sampleSize(0.6f))
        assertEquals(2, CollageRenderer.sampleSize(0.5f))
        assertEquals(2, CollageRenderer.sampleSize(0.3f))
        assertEquals(4, CollageRenderer.sampleSize(0.2f))
        assertEquals(16, CollageRenderer.sampleSize(0.05f))
    }

    @Test
    fun `les bandes se raccordent sans couture, coins arrondis compris`() {
        // Photo détaillée (dégradés et ondulations fines), réduite par 2 dans sa case agrandie.
        val (photoWidth, photoHeight) = 2400 to 1800
        val pixels = IntArray(photoWidth * photoHeight) { index ->
            val x = index % photoWidth
            val y = index / photoWidth
            val ripple = (24 * sin(x / 5.0) * cos(y / 7.0)).toInt()
            Color.rgb(20 + x * 200 / photoWidth + ripple, 30 + y * 200 / photoHeight - ripple, 120 + ripple)
        }
        val photo = Bitmap.createBitmap(pixels, photoWidth, photoHeight, Bitmap.Config.ARGB_8888)
        for (corners in listOf(0f, 0.05f)) {
            val state = sideBySide(spacing = 0.02f, corners = corners).copy(frames = mapOf(1L to CellFrame(1.3f, 0.4f, 0.55f)))
            val single = CollageRenderer.render(state, 600, 600, sources(1L to photo, 2L to blue))
            // Bandes d'à peine 20 lignes décodées : une vingtaine pour la case.
            val banded = CollageRenderer.render(state, 600, 600, sources(1L to photo, 2L to blue), bandPixels = 20 * 600)
            var worst = 0
            for (y in 0 until 600) {
                for (x in 0 until 600) {
                    val a = single.getPixel(x, y)
                    val b = banded.getPixel(x, y)
                    worst = maxOf(worst, abs(Color.red(a) - Color.red(b)), abs(Color.green(a) - Color.green(b)), abs(Color.blue(a) - Color.blue(b)))
                }
            }
            // Seul écart possible : l'arrondi des poids du filtrage (au seizième de pixel), qui dépend
            // de l'origine de la bande ; aucune ligne de raccord.
            assertTrue("arrondi $corners : écart maximal $worst", worst <= 2)
        }
    }

    @Test
    fun `aperçu, orientation appliquée, grand côté borné`() {
        val stored = quarters(3000, 2000)
        val preview = CollageRenderer.preview(BitmapSource(stored, 6, owned = false), 960)
        assertEquals(640, preview.width)
        assertEquals(960, preview.height)
        assertEquals(Color.RED, preview.getPixel(480, 240))
        assertEquals(GREEN, preview.getPixel(160, 240))
    }

    /**
     * Photo de 50 Mpx simulée : chaque zone demandée est un aplat de la taille réduite demandée,
     * sans jamais allouer l'image entière.
     */
    private class LargeSource(private val color: Int, private val decodes: MutableList<Pair<Rect, Int>>) : CollageSource {
        override val width = WIDTH
        override val height = HEIGHT
        override val orientation = 1

        override fun decode(region: Rect, sampleSize: Int): Bitmap {
            decodes += Rect(region) to sampleSize
            return solid(region.width() / sampleSize, region.height() / sampleSize, color)
        }

        companion object {
            const val WIDTH = 8160
            const val HEIGHT = 6120
        }
    }

    private companion object {
        val GREEN = Color.rgb(0, 160, 0)

        fun solid(width: Int, height: Int, color: Int): Bitmap =
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

        /** Quatre quarts : rouge, bleu en haut ; vert, jaune en bas. */
        fun quarters(width: Int, height: Int): Bitmap = solid(width, height, Color.RED).also { bitmap ->
            val canvas = Canvas(bitmap)
            val paint = Paint()
            val halfW = width / 2f
            val halfH = height / 2f
            paint.color = Color.BLUE
            canvas.drawRect(halfW, 0f, width.toFloat(), halfH, paint)
            paint.color = GREEN
            canvas.drawRect(0f, halfH, halfW, height.toFloat(), paint)
            paint.color = Color.YELLOW
            canvas.drawRect(halfW, halfH, width.toFloat(), height.toFloat(), paint)
        }
    }
}
