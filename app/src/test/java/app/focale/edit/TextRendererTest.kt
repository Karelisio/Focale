package app.focale.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Rendu réel (graphismes natifs Robolectric, police de l'application) des textes par le pipeline
 * d'export : position en pleine résolution, mise en page indépendante de la résolution, accord
 * avec l'aperçu, rotation, miroir, redressement et ordre de rendu (net sur une zone floutée).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class TextRendererTest {

    private val faces by lazy { TextRenderer.faces(ApplicationProvider.getApplicationContext()) }

    private fun blank(width: Int, height: Int, color: Int = Color.WHITE): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    private fun text(
        value: String = "Focale",
        x: Float = 0.5f,
        y: Float = 0.5f,
        size: Float = 0.08f,
        color: Int = Color.RED,
        background: TextBackground = TextBackground.BOX,
        bold: Boolean = false,
        rotation: Float = 0f,
    ) = TextItem(id = 1, text = value, x = x, y = y, color = color, size = size, rotation = rotation, background = background, bold = bold)

    private fun render(source: Bitmap, state: EditState) = EditRenderer.render(source, state, faces = faces)

    private fun red(pixel: Int) = Color.red(pixel) > 200 && Color.green(pixel) < 80 && Color.blue(pixel) < 80

    /** Boîte (bornes droite et basse exclues) des pixels qui vérifient [test]. */
    private fun bounds(bitmap: Bitmap, test: (Int) -> Boolean): Rect? {
        var left = Int.MAX_VALUE
        var top = Int.MAX_VALUE
        var right = -1
        var bottom = -1
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (!test(pixels[y * bitmap.width + x])) continue
                left = minOf(left, x)
                top = minOf(top, y)
                right = maxOf(right, x + 1)
                bottom = maxOf(bottom, y + 1)
            }
        }
        return if (right < 0) null else Rect(left, top, right, bottom)
    }

    @Test
    fun `la police de l'application est chargée et change le rendu`() {
        assertNotNull(faces.typeface)
        val state = EditState(texts = listOf(text(value = "Focale gag", size = 0.3f, background = TextBackground.NONE, color = Color.BLACK)))
        val system = EditRenderer.render(blank(1200, 400), state)
        val app = render(blank(1200, 400), state)
        assertFalse(system.sameAs(app))
        // Graisse variable : le gras de Google Sans Flex élargit le texte.
        val dark = { pixel: Int -> Color.red(pixel) < 128 }
        val bold = render(blank(1200, 400), state.copy(texts = state.texts.map { it.copy(bold = true) }))
        assertTrue(bounds(bold, dark)!!.width() > bounds(app, dark)!!.width())
    }

    @Test
    fun `le texte encadré est posé au bon endroit, en pleine résolution`() {
        val item = text(x = 0.3f, y = 0.6f, size = 0.08f)
        val out = render(blank(1600, 1200, Color.GRAY), EditState(texts = listOf(item)))
        val box = bounds(out, ::red)!!
        assertEquals(480f, (box.left + box.right) / 2f, 2f)
        assertEquals(720f, (box.top + box.bottom) / 2f, 2f)
        // Pavé à l'échelle du corps : 8 % du petit côté (96 px) pour 100 px de mise en page.
        val prepared = TextRenderer.prepare(item, faces)
        val scale = TextMath.scale(item, 1600f, 1200f)
        assertEquals(2f * prepared.halfWidth * scale, box.width().toFloat(), 3f)
        assertEquals(2f * prepared.halfHeight * scale, box.height().toFloat(), 3f)
        // Lettres en blanc sur le pavé rouge, trop sombre pour du noir.
        val letters = bounds(out) { Color.red(it) > 240 && Color.green(it) > 240 }!!
        assertTrue(box.contains(letters))
    }

    @Test
    fun `la mise en page est la même à toute résolution`() {
        val item = text(value = "Bonjour\nà tous", x = 0.4f, y = 0.45f, size = 0.1f, rotation = 12f)
        val small = bounds(render(blank(400, 300), EditState(texts = listOf(item))), ::red)!!
        val large = bounds(render(blank(1600, 1200), EditState(texts = listOf(item))), ::red)!!
        assertEquals(small.left / 400f, large.left / 1600f, 0.004f)
        assertEquals(small.right / 400f, large.right / 1600f, 0.004f)
        assertEquals(small.top / 300f, large.top / 1200f, 0.004f)
        assertEquals(small.bottom / 300f, large.bottom / 1200f, 0.004f)
    }

    @Test
    fun `l'aperçu et l'export placent le texte au même endroit`() {
        val state = EditState(
            crop = NormRect(0.2f, 0.1f, 0.9f, 0.8f),
            texts = listOf(
                text(value = "Vacances\n2026", x = 0.5f, y = 0.4f, size = 0.09f, rotation = -15f),
                text(value = "Lisbonne", x = 0.6f, y = 0.7f, size = 0.06f, background = TextBackground.OUTLINE, bold = true).copy(id = 2),
            ),
        )
        // Export à 2 000 × 1 500 ; écran de 540 × 700 px montrant un aperçu de 400 × 300 px, tracé
        // comme dans EditorCanvas.
        val export = render(blank(2000, 1500), state)
        val frame = ImageFrame.fit(540f, 700f, 400, 300, state.crop)
        val left = frame.screenX(state.crop.left).roundToInt()
        val top = frame.screenY(state.crop.top).roundToInt()
        val right = left + (state.crop.width * frame.width).roundToInt()
        val bottom = top + (state.crop.height * frame.height).roundToInt()
        val preview = blank(540, 700)
        Canvas(preview).apply {
            clipRect(left, top, right, bottom)
            translate(frame.left, frame.top)
            state.texts.forEach { TextRenderer.draw(this, TextRenderer.prepare(it, faces), it, frame.width, frame.height) }
        }
        var painted = 0
        for (x in 0 until export.width step 5) {
            for (y in 0 until export.height step 5) {
                val u = state.crop.left + (x + 0.5f) / export.width * state.crop.width
                val v = state.crop.top + (y + 0.5f) / export.height * state.crop.height
                val px = frame.screenX(u).toInt()
                val py = frame.screenY(v).toInt()
                if (px <= left || px >= right - 1 || py <= top || py >= bottom - 1) continue
                if (!red(export.getPixel(x, y))) continue
                painted++
                // Le pavé de l'export est rouge au même point de l'aperçu, à un pixel d'aperçu près (bords lissés).
                assertTrue("($x, $y)", isNear(preview, px, py, ::red))
            }
        }
        assertTrue(painted > 500)
    }

    private fun isNear(bitmap: Bitmap, x: Int, y: Int, test: (Int) -> Boolean): Boolean {
        for (dx in -1..1) for (dy in -1..1) if (test(bitmap.getPixel((x + dx).coerceIn(0, bitmap.width - 1), (y + dy).coerceIn(0, bitmap.height - 1)))) return true
        return false
    }

    /** Masque des pixels sombres de [bitmap] dans [box]. */
    private fun mask(bitmap: Bitmap, box: Rect): List<Boolean> =
        (box.top until box.bottom).flatMap { y -> (box.left until box.right).map { x -> Color.red(bitmap.getPixel(x, y)) < 100 } }

    @Test
    fun `le miroir déplace le texte sans retourner ses lettres`() {
        val item = text(value = "F", x = 0.3f, y = 0.5f, size = 0.4f, color = Color.BLACK, background = TextBackground.NONE)
        val dark = { pixel: Int -> Color.red(pixel) < 100 }
        val original = render(blank(400, 300), EditState(texts = listOf(item)))
        // Comme EditorViewModel.flip() : la photo et le texte passent dans le repère retourné.
        val flipped = render(blank(400, 300), EditState(texts = listOf(item)).mirrored())
        val a = bounds(original, dark)!!
        val b = bounds(flipped, dark)!!
        // Le texte a changé de côté (son centre passe de 30 % à 70 % de la largeur)…
        assertEquals(160, b.left - a.left)
        assertEquals(a.top, b.top)
        // … et ses lettres sont identiques, pas retournées.
        val shifted = Rect(b.left, b.top, b.left + a.width(), b.top + a.height())
        val same = mask(original, a).zip(mask(flipped, shifted)).count { (p, q) -> p == q }
        assertTrue(same > 0.98 * a.width() * a.height())
        val mirrored = mask(original, a).chunked(a.width()).flatMap { it.reversed() }
        val asMirror = mirrored.zip(mask(flipped, shifted)).count { (p, q) -> p == q }
        assertTrue(asMirror < 0.9 * a.width() * a.height())
    }

    @Test
    fun `un quart de tour fait tourner le texte avec l'image`() {
        val item = text(x = 0.3f, y = 0.2f, size = 0.1f)
        val flat = bounds(render(blank(400, 300), EditState(texts = listOf(item))), ::red)!!
        assertTrue(flat.width() > flat.height())
        val turned = bounds(render(blank(400, 300), EditState(texts = listOf(item)).rotated()), ::red)!!
        assertTrue(turned.height() > turned.width())
        // (x, y) arrive en (1 - y, x) dans l'image tournée de 300 × 400.
        assertEquals(0.8f * 300f, (turned.left + turned.right) / 2f, 2f)
        assertEquals(0.3f * 400f, (turned.top + turned.bottom) / 2f, 2f)
        assertEquals(flat.width().toFloat(), turned.height().toFloat(), 2f)
    }

    @Test
    fun `le redressement fait pivoter la photo sous le texte, qui reste en place`() {
        val item = text(x = 0.4f, y = 0.6f)
        val straight = bounds(render(blank(400, 300), EditState(texts = listOf(item))), ::red)
        val tilted = bounds(render(blank(400, 300), EditState(straighten = 10f, texts = listOf(item))), ::red)
        assertEquals(straight, tilted)
        // Miroir d'une photo redressée : l'angle s'inverse, le texte change seulement de côté.
        val mirrored = bounds(render(blank(400, 300), EditState(straighten = 10f, texts = listOf(item)).mirrored()), ::red)!!
        assertEquals(400f - (straight!!.left + straight.right) / 2f, (mirrored.left + mirrored.right) / 2f, 1.5f)
        assertEquals(straight.top, mirrored.top)
    }

    @Test
    fun `recadrer après avoir posé un texte le garde sur le même détail`() {
        val item = text(x = 0.75f, y = 0.5f, size = 0.2f)
        val whole = bounds(render(blank(400, 200), EditState(texts = listOf(item))), ::red)!!
        val cropped = render(blank(400, 200), EditState(crop = NormRect(0.5f, 0f, 1f, 1f), texts = listOf(item)))
        assertEquals(200, cropped.width)
        val box = bounds(cropped, ::red)!!
        // Même taille (le corps suit le petit côté de l'image entière), décalé du recadrage.
        assertEquals(whole.left - 200, box.left)
        assertEquals(whole.top, box.top)
        assertEquals(whole.width(), box.width())
    }

    @Test
    fun `le texte posé sur une zone floutée reste net`() {
        // Damier d'un pixel sous une zone en mosaïque, texte encadré rouge au-dessus.
        val source = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(400 * 300) { if ((it % 400 + it / 400) % 2 == 0) Color.BLACK else Color.WHITE }, 0, 400, 0, 0, 400, 300)
        }
        val zone = BlurRegion(1, BlurArea.Box(BlurShape.RECTANGLE, 0.5f, 0.5f, 0.6f, 0.4f), BlurStyle.PIXELATE, 0.5f)
        val item = text(x = 0.5f, y = 0.5f, size = 0.1f)
        val out = render(source, EditState(blurs = listOf(zone), texts = listOf(item)))
        // Pavé du texte d'un rouge pur, au-dessus du flou…
        val box = bounds(out) { it == Color.RED }!!
        assertEquals(200f, (box.left + box.right) / 2f, 2f)
        assertTrue(box.width() > 60)
        // … et, autour, une mosaïque sans un seul pixel du damier.
        val around = Rect(box).apply { inset(-3, -3) }
        for (x in 100 until 300 step 3) {
            for (y in 100 until 200 step 3) {
                if (around.contains(x, y)) continue
                assertTrue("($x, $y)", Color.red(out.getPixel(x, y)) in 60..200)
            }
        }
    }

    @Test
    fun `contour, gras et lignes multiples`() {
        val outline = render(blank(600, 400, Color.GRAY), EditState(texts = listOf(text(value = "Aa", size = 0.3f, color = Color.WHITE, background = TextBackground.OUTLINE))))
        val area = bounds(outline) { Color.red(it) > 240 }!!
        // Lettres blanches cernées de noir (le blanc appelle un contour sombre).
        assertNotNull(bounds(outline) { Color.red(it) < 30 })
        assertTrue(bounds(outline) { Color.red(it) < 30 }!!.let { Rect.intersects(it, area) })
        fun ink(bold: Boolean) = render(blank(600, 400), EditState(texts = listOf(text(value = "Aa", size = 0.3f, color = Color.BLACK, background = TextBackground.NONE, bold = bold))))
            .let { bitmap -> IntArray(600 * 400).also { bitmap.getPixels(it, 0, 600, 0, 0, 600, 400) }.count { Color.red(it) < 128 } }
        assertTrue(ink(bold = true) > 1.15 * ink(bold = false))
        // Deux lignes : bloc deux fois plus haut qu'une seule, centré au même point.
        val one = bounds(render(blank(800, 600), EditState(texts = listOf(text(value = "Été")))), ::red)!!
        val two = bounds(render(blank(800, 600), EditState(texts = listOf(text(value = "Été\nÉté")))), ::red)!!
        assertTrue(two.height() > 1.6 * one.height())
        assertEquals((one.top + one.bottom) / 2f, (two.top + two.bottom) / 2f, 2f)
        assertEquals(one.width().toFloat(), two.width().toFloat(), 2f)
    }

    @Test
    fun `styles de texte rendus pour contrôle visuel`() {
        val source = Bitmap.createBitmap(600, 400, Bitmap.Config.ARGB_8888).apply {
            setPixels(IntArray(600 * 400) { Color.rgb(40 + it % 600 / 5, 60 + it / 600 / 5, 120) }, 0, 600, 0, 0, 600, 400)
        }
        val texts = listOf(
            text(value = "Sans fond", x = 0.3f, y = 0.2f, color = Color.WHITE, background = TextBackground.NONE),
            text(value = "Encadré", x = 0.7f, y = 0.35f, color = Color.rgb(253, 216, 53), rotation = -8f).copy(id = 2),
            text(value = "Contour\ngras", x = 0.35f, y = 0.65f, size = 0.1f, color = Color.WHITE, background = TextBackground.OUTLINE, bold = true).copy(id = 3),
        )
        val zone = BlurRegion(4, BlurArea.Box(BlurShape.ELLIPSE, 0.75f, 0.75f, 0.2f, 0.15f), BlurStyle.BLUR, 0.6f)
        val out = render(source, EditState(texts = texts, blurs = listOf(zone)))
        File("build/tmp/text-styles.png").apply { parentFile?.mkdirs() }.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertNotNull(bounds(out) { Color.red(it) > 245 && Color.green(it) > 200 && Color.blue(it) < 80 })
    }
}
