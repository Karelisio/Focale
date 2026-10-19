package app.focale.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Rendu réel (graphismes natifs Robolectric) du dessin par le pipeline d'export, et
 * comparaison avec le tracé de l'aperçu. Un échantillon de tous les outils est écrit dans
 * `build/tmp/` pour contrôle visuel.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DrawingRendererTest {

    private fun blank(width: Int, height: Int, color: Int = Color.WHITE): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun stroke(tool: DrawTool, vararg points: Float, width: Float = 0.1f, color: Int = Color.RED) =
        DrawingStroke(tool, color, width, points)

    private fun render(source: Bitmap, vararg strokes: DrawingStroke) = EditRenderer.render(source, EditState(strokes = strokes.toList()))

    /** Couverture rouge cumulée d'une colonne (somme des fractions de pixel peintes). */
    private fun coverage(bitmap: Bitmap, x: Int): Float = (0 until bitmap.height).sumOf { (255 - Color.green(bitmap.getPixel(x, it))) / 255.0 }.toFloat()

    @Test
    fun `un trait au stylo couvre son tracé et rien d'autre`() {
        val out = render(blank(200, 100), stroke(DrawTool.PEN, 0.1f, 0.5f, 0.5f, 0.5f, 0.9f, 0.5f))
        assertEquals(Color.RED, out.getPixel(100, 50))
        assertEquals(Color.RED, out.getPixel(100, 46))
        assertEquals(Color.WHITE, out.getPixel(100, 30))
        assertEquals(Color.WHITE, out.getPixel(195, 50))
    }

    @Test
    fun `un simple appui au stylo marque un point`() {
        val out = render(blank(100, 100), stroke(DrawTool.PEN, 0.5f, 0.5f))
        assertEquals(Color.RED, out.getPixel(50, 50))
        assertEquals(Color.RED, out.getPixel(47, 50))
        assertEquals(Color.WHITE, out.getPixel(50, 57))
    }

    @Test
    fun `le stylo lisse le tracé entre les points`() {
        // Zigzag : la courbe passe par les milieux des segments et s'arrondit sous le sommet.
        val out = render(blank(200, 200), stroke(DrawTool.PEN, 0.1f, 0.5f, 0.3f, 0.2f, 0.5f, 0.5f, width = 0.02f))
        assertEquals(Color.RED, out.getPixel(60, 55))
        assertEquals(Color.WHITE, out.getPixel(60, 40))
    }

    @Test
    fun `l'épaisseur est proportionnelle à la taille de l'image`() {
        val line = stroke(DrawTool.LINE, 0.1f, 0.5f, 0.9f, 0.5f, width = 0.05f)
        assertEquals(5f, coverage(render(blank(100, 100), line), 50), 0.2f)
        assertEquals(20f, coverage(render(blank(400, 400), line), 200), 0.2f)
        // Sur une image en longueur, l'unité reste le petit côté.
        assertEquals(10f, coverage(render(blank(800, 200), line), 400), 0.2f)
    }

    @Test
    fun `le surligneur ne cumule pas l'opacité là où le trait se recoupe`() {
        val once = stroke(DrawTool.HIGHLIGHTER, 0.1f, 0.5f, 0.9f, 0.5f)
        val retraced = stroke(DrawTool.HIGHLIGHTER, 0.1f, 0.5f, 0.9f, 0.5f, 0.1f, 0.5f, 0.9f, 0.5f)
        val single = render(blank(200, 100), once).getPixel(100, 50)
        assertEquals(single, render(blank(200, 100), retraced).getPixel(100, 50))
        // Semi-transparent : ni le fond, ni la couleur pure.
        assertNotEquals(Color.WHITE, single)
        assertNotEquals(Color.RED, single)
        assertEquals(255 - DrawingRenderer.HIGHLIGHTER_ALPHA * 255, Color.green(single).toFloat(), 2f)
        // Deux traits distincts, eux, se superposent.
        assertTrue(Color.green(render(blank(200, 100), once, once).getPixel(100, 50)) < Color.green(single))
    }

    @Test
    fun `rectangle et ellipse sont tracés en contour`() {
        val rectangle = render(blank(100, 100), stroke(DrawTool.RECTANGLE, 0.8f, 0.8f, 0.2f, 0.2f, width = 0.04f))
        assertEquals(Color.RED, rectangle.getPixel(20, 50))
        assertEquals(Color.RED, rectangle.getPixel(50, 79))
        assertEquals(Color.WHITE, rectangle.getPixel(50, 50))
        val ellipse = render(blank(200, 100), stroke(DrawTool.ELLIPSE, 0.2f, 0.3f, 0.8f, 0.7f, width = 0.04f))
        assertEquals(Color.RED, ellipse.getPixel(100, 30))
        assertEquals(Color.RED, ellipse.getPixel(160, 50))
        assertEquals(Color.WHITE, ellipse.getPixel(100, 50))
    }

    @Test
    fun `la flèche porte une pointe à son extrémité seulement`() {
        val out = render(blank(200, 100), stroke(DrawTool.ARROW, 0.1f, 0.5f, 0.9f, 0.5f, width = 0.03f))
        val head = DrawingMath.arrowHead(20f, 50f, 180f, 50f, strokeWidth = 3f, reference = 100f)!!
        // Milieu de chaque branche.
        assertEquals(Color.RED, out.getPixel(((180f + head[0]) / 2f).toInt(), ((50f + head[1]) / 2f).toInt()))
        assertEquals(Color.RED, out.getPixel(((180f + head[2]) / 2f).toInt(), ((50f + head[3]) / 2f).toInt()))
        assertEquals(Color.WHITE, out.getPixel(22, 45))
    }

    @Test
    fun `les traits ne prennent ni filtre ni réglage`() {
        val state = EditState(filter = FilterPreset.MONO, brightness = 0.5f, strokes = listOf(stroke(DrawTool.LINE, 0.1f, 0.5f, 0.9f, 0.5f)))
        val out = EditRenderer.render(blank(200, 100, Color.BLUE), state)
        assertEquals(Color.RED, out.getPixel(100, 50))
        val background = out.getPixel(100, 10)
        assertEquals(Color.red(background), Color.green(background))
    }

    @Test
    fun `l'aperçu géométrique ne contient pas le dessin`() {
        val state = EditState(strokes = listOf(stroke(DrawTool.LINE, 0.1f, 0.5f, 0.9f, 0.5f)))
        val out = EditRenderer.render(blank(200, 100), state, includeCrop = false, includeColor = false, includeDrawing = false)
        assertEquals(Color.WHITE, out.getPixel(100, 50))
    }

    @Test
    fun `recadrer après avoir dessiné garde le trait sur le même détail`() {
        // Repère vert dans la moitié droite de la photo, point rouge posé en son centre.
        val source = marked(200, 100, 140, 40)
        val dot = stroke(DrawTool.PEN, 0.75f, 0.5f)
        assertEquals(Color.RED, render(source, dot).getPixel(150, 50))
        val cropped = EditRenderer.render(source, EditState(crop = NormRect(0.5f, 0f, 1f, 1f), strokes = listOf(dot)))
        assertEquals(100, cropped.width)
        assertEquals(Color.RED, cropped.getPixel(50, 50))
        assertEquals(Color.GREEN, cropped.getPixel(42, 42))
        assertEquals(Color.WHITE, cropped.getPixel(5, 5))
    }

    @Test
    fun `pivoter après avoir dessiné fait tourner le trait avec la photo`() {
        // Repère vert autour de (40, 50) dans une photo de 400 × 200, point rouge dessus.
        val source = marked(400, 200, 30, 40)
        val dot = stroke(DrawTool.PEN, 0.1f, 0.25f, width = 0.02f)
        // Comme EditorViewModel.rotate() : quart de tour, traits pivotés dans le repère affiché.
        val out = EditRenderer.render(source, EditState(quarterTurns = 1, strokes = listOf(dot.rotateClockwise())))
        assertEquals(200, out.width)
        // (x, y) de l'original arrive en (200 - y, x) : le repère est désormais autour de (150, 40).
        assertEquals(Color.RED, out.getPixel(150, 40))
        assertEquals(Color.GREEN, out.getPixel(156, 40))
    }

    @Test
    fun `miroir puis rotation laissent le trait sur le même détail`() {
        val source = marked(400, 200, 30, 40)
        val dot = stroke(DrawTool.PEN, 0.1f, 0.25f, width = 0.02f)
        // Comme flip() puis rotate() : avec miroir, le quart de tour affiché retire un quart de tour à l'original.
        val state = EditState(quarterTurns = 3, flipHorizontal = true, strokes = listOf(dot.flipHorizontal().rotateClockwise()))
        val out = EditRenderer.render(source, state)
        assertEquals(Color.RED, out.getPixel(150, 360))
        assertEquals(Color.GREEN, out.getPixel(156, 360))
    }

    @Test
    fun `l'aperçu et l'export placent les traits au même endroit`() {
        val state = EditState(
            crop = NormRect(0.5f, 0f, 1f, 1f),
            strokes = listOf(
                stroke(DrawTool.PEN, 0.55f, 0.2f, 0.7f, 0.4f, 0.8f, 0.3f, 0.95f, 0.6f, width = 0.03f),
                stroke(DrawTool.ARROW, 0.6f, 0.9f, 0.9f, 0.7f, width = 0.02f),
                stroke(DrawTool.RECTANGLE, 0.4f, 0.45f, 0.85f, 0.55f, width = 0.02f),
                stroke(DrawTool.ELLIPSE, 0.6f, 0.6f, 0.95f, 0.95f, width = 0.015f),
            ),
        )
        // Export à 2 000 × 1 500 (recadré en 1 000 × 1 500) ; écran de 540 × 700 px montrant
        // un aperçu de 400 × 300 px, tracé comme dans EditorCanvas.
        val export = EditRenderer.render(blank(2000, 1500), state)
        val previewWidth = 540
        val previewHeight = 700
        val frame = ImageFrame.fit(previewWidth.toFloat(), previewHeight.toFloat(), 400, 300, state.crop)
        // Zone visible alignée sur les pixels de l'image, comme dans EditorCanvas.
        val left = frame.screenX(state.crop.left).roundToInt()
        val top = frame.screenY(state.crop.top).roundToInt()
        val right = left + (state.crop.width * frame.width).roundToInt()
        val bottom = top + (state.crop.height * frame.height).roundToInt()
        val preview = blank(previewWidth, previewHeight)
        Canvas(preview).apply {
            clipRect(left, top, right, bottom)
            translate(frame.left, frame.top)
            // Chemins préparés en pixels de l'aperçu puis mis à l'échelle de l'écran.
            scale(frame.width / 400f, frame.height / 300f)
            state.strokes.forEach { DrawingRenderer.prepare(it, 400f, 300f).draw(this) }
        }
        // Tout pixel franchement peint (ou vierge) de l'export l'est aussi au même point de
        // l'aperçu, hors du liseré d'un pixel où le découpage arrondit.
        var painted = 0
        for (x in 0 until export.width step 7) {
            for (y in 0 until export.height step 7) {
                val u = state.crop.left + (x + 0.5f) / export.width * state.crop.width
                val v = state.crop.top + (y + 0.5f) / export.height * state.crop.height
                val px = frame.screenX(u).toInt()
                val py = frame.screenY(v).toInt()
                if (px <= left || px >= right - 1 || py <= top || py >= bottom - 1) continue
                val inExport = export.getPixel(x, y)
                val inPreview = preview.getPixel(px, py)
                if (inExport == Color.RED) painted++
                if (inExport == Color.RED) assertNotEquals("($x, $y)", Color.WHITE, inPreview)
                if (inExport == Color.WHITE && inPreview == Color.RED) {
                    // Le bord d'un trait peut tomber entre deux pixels d'aperçu : seul un vrai décalage échoue.
                    assertTrue("($x, $y)", export.isNear(x, y, radius = 4))
                }
            }
        }
        assertTrue(painted > 100)
    }

    @Test
    fun `tous les outils sont rendus pour contrôle visuel`() {
        val source = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888).apply {
            for (x in 0 until 600) for (y in 0 until 400) setPixel(x, y, Color.rgb(40 + x / 5, 60 + y / 5, 120))
        }
        val yellow = Color.rgb(253, 216, 53)
        val strokes = listOf(
            stroke(DrawTool.PEN, 0.05f, 0.2f, 0.1f, 0.1f, 0.15f, 0.25f, 0.2f, 0.12f, 0.25f, 0.22f, width = 0.015f),
            stroke(DrawTool.HIGHLIGHTER, 0.35f, 0.15f, 0.45f, 0.18f, 0.4f, 0.12f, 0.55f, 0.16f, width = 0.05f, color = yellow),
            stroke(DrawTool.LINE, 0.65f, 0.1f, 0.95f, 0.3f, width = 0.01f, color = Color.WHITE),
            stroke(DrawTool.ARROW, 0.05f, 0.9f, 0.3f, 0.5f, width = 0.012f, color = Color.BLACK),
            stroke(DrawTool.RECTANGLE, 0.4f, 0.5f, 0.6f, 0.9f, width = 0.01f, color = Color.GREEN),
            stroke(DrawTool.ELLIPSE, 0.7f, 0.5f, 0.95f, 0.9f, width = 0.02f, color = Color.BLUE),
            stroke(DrawTool.PEN, 0.5f, 0.35f, width = 0.03f),
        )
        val out = EditRenderer.render(source, EditState(strokes = strokes))
        File("build/tmp/drawing-tools.png").apply { parentFile?.mkdirs() }.outputStream().use {
            out.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertEquals(Color.RED, out.getPixel(300, 140))
        assertEquals(Color.GREEN, out.getPixel(240, 300))
    }

    /** Photo blanche avec un repère vert de 20 × 20 px au coin ([left], [top]). */
    private fun marked(width: Int, height: Int, left: Int, top: Int): Bitmap = blank(width, height).apply {
        for (x in left until left + 20) for (y in top until top + 20) setPixel(x, y, Color.GREEN)
    }

    /** Un pixel peint se trouve à moins de [radius] px (bord de trait). */
    private fun Bitmap.isNear(x: Int, y: Int, radius: Int): Boolean {
        for (dx in -radius..radius) for (dy in -radius..radius) {
            val px = (x + dx).coerceIn(0, width - 1)
            val py = (y + dy).coerceIn(0, height - 1)
            if (getPixel(px, py) != Color.WHITE) return true
        }
        return false
    }
}
