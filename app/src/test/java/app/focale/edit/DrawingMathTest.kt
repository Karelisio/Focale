package app.focale.edit

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class DrawingMathTest {

    private fun stroke(tool: DrawTool, vararg points: Float, width: Float = 0.02f) = DrawingStroke(tool, 0xFFFF0000.toInt(), width, points)

    @Test
    fun `l'image entière est centrée dans le conteneur`() {
        val frame = ImageFrame.fit(1000f, 1000f, 400, 300, NormRect.Full)
        assertEquals(0f, frame.left, 1e-3f)
        assertEquals(125f, frame.top, 1e-3f)
        assertEquals(1000f, frame.width, 1e-3f)
        assertEquals(750f, frame.height, 1e-3f)
    }

    @Test
    fun `le centre de l'aperçu recadré tombe au centre du recadrage`() {
        val crop = NormRect(0.5f, 0f, 1f, 1f)
        val frame = ImageFrame.fit(1000f, 1000f, 400, 300, crop)
        // Zone de 200 × 300 px agrandie à 666,7 × 1000 : l'image entière déborde à gauche.
        assertEquals(1000f / 300f * 400f, frame.width, 1e-2f)
        assertEquals(0.75f, frame.normalizedX(500f), 1e-5f)
        assertEquals(0.5f, frame.normalizedY(500f), 1e-5f)
        // Les bords visibles de l'aperçu sont ceux du recadrage.
        assertEquals((1000f - 1000f / 300f * 200f) / 2f, frame.screenX(crop.left), 1e-2f)
        assertEquals(0f, frame.screenY(crop.top), 1e-3f)
    }

    @Test
    fun `la conversion entre écran et image est réversible`() {
        val frame = ImageFrame.fit(1080f, 1500f, 1600, 1200, NormRect(0.1f, 0.2f, 0.7f, 0.9f))
        for ((x, y) in listOf(0f to 0f, 333f to 777f, 1080f to 1500f)) {
            assertEquals(x, frame.screenX(frame.normalizedX(x)), 1e-2f)
            assertEquals(y, frame.screenY(frame.normalizedY(y)), 1e-2f)
        }
    }

    @Test
    fun `quatre quarts de tour ramènent le trait à son point de départ`() {
        val original = stroke(DrawTool.PEN, 0.1f, 0.2f, 0.35f, 0.8f, 0.9f, 0.6f)
        var current = original
        repeat(4) { current = current.rotateClockwise() }
        assertArrayEquals(original.points, current.points, 1e-6f)
    }

    @Test
    fun `un trait tourne avec le cadre de recadrage`() {
        val crop = NormRect(0.1f, 0.2f, 0.6f, 0.9f)
        // Trait posé sur le coin haut gauche du recadrage : après un quart de tour horaire,
        // ce coin devient le coin haut droit du recadrage pivoté.
        val rotated = stroke(DrawTool.PEN, crop.left, crop.top).rotateClockwise()
        val rotatedCrop = CropMath.rotateClockwise(crop)
        assertEquals(rotatedCrop.right, rotated.x(0), 1e-6f)
        assertEquals(rotatedCrop.top, rotated.y(0), 1e-6f)
    }

    @Test
    fun `le miroir est involutif et suit le recadrage`() {
        val crop = NormRect(0.1f, 0.2f, 0.6f, 0.9f)
        val original = stroke(DrawTool.LINE, crop.left, crop.top, crop.right, crop.bottom)
        val flipped = original.flipHorizontal()
        val flippedCrop = CropMath.flipHorizontal(crop)
        assertEquals(flippedCrop.right, flipped.x(0), 1e-6f)
        assertEquals(flippedCrop.left, flipped.x(1), 1e-6f)
        assertArrayEquals(original.points, flipped.flipHorizontal().points, 1e-6f)
    }

    @Test
    fun `deux traits de même contenu sont égaux`() {
        val a = stroke(DrawTool.ARROW, 0.1f, 0.1f, 0.5f, 0.5f)
        val b = stroke(DrawTool.ARROW, 0.1f, 0.1f, 0.5f, 0.5f)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == stroke(DrawTool.LINE, 0.1f, 0.1f, 0.5f, 0.5f))
    }

    @Test
    fun `un dessin compte comme une modification`() {
        assertTrue(EditState().isIdentity)
        assertFalse(EditState(strokes = listOf(stroke(DrawTool.PEN, 0.5f, 0.5f))).isIdentity)
    }

    @Test
    fun `l'épaisseur se mesure sur le petit côté de l'image`() {
        val pen = stroke(DrawTool.PEN, 0.5f, 0.5f, width = 0.01f)
        assertEquals(30f, DrawingMath.strokeWidth(pen, 4000f, 3000f), 1e-3f)
        assertEquals(30f, DrawingMath.strokeWidth(pen.rotateClockwise(), 3000f, 4000f), 1e-3f)
    }

    @Test
    fun `la gomme touche un trait à main levée près de son tracé`() {
        // Trait horizontal de 2 px d'épaisseur dans un cadre de 100 × 100 px.
        val pen = stroke(DrawTool.PEN, 0.1f, 0.5f, 0.5f, 0.5f, 0.9f, 0.5f)
        assertEquals(0, DrawingMath.hitTest(listOf(pen), 50f, 55f, 100f, 100f, slop = 5f))
        assertEquals(-1, DrawingMath.hitTest(listOf(pen), 50f, 57f, 100f, 100f, slop = 5f))
        assertEquals(-1, DrawingMath.hitTest(listOf(pen), 97f, 50f, 100f, 100f, slop = 5f))
    }

    @Test
    fun `la gomme ignore l'intérieur d'un rectangle ou d'une ellipse`() {
        val rectangle = stroke(DrawTool.RECTANGLE, 0.8f, 0.8f, 0.2f, 0.2f)
        assertEquals(0, DrawingMath.hitTest(listOf(rectangle), 20f, 50f, 100f, 100f, slop = 3f))
        assertEquals(-1, DrawingMath.hitTest(listOf(rectangle), 50f, 50f, 100f, 100f, slop = 3f))
        // Ellipse inscrite dans 120 × 40 px, centrée en (100, 50) d'un cadre de 200 × 100 px.
        val ellipse = stroke(DrawTool.ELLIPSE, 0.2f, 0.3f, 0.8f, 0.7f)
        assertEquals(0, DrawingMath.hitTest(listOf(ellipse), 100f, 30f, 200f, 100f, slop = 3f))
        assertEquals(0, DrawingMath.hitTest(listOf(ellipse), 160f, 50f, 200f, 100f, slop = 3f))
        assertEquals(-1, DrawingMath.hitTest(listOf(ellipse), 100f, 50f, 200f, 100f, slop = 3f))
    }

    @Test
    fun `la gomme choisit le trait le plus récent`() {
        val below = stroke(DrawTool.LINE, 0.1f, 0.5f, 0.9f, 0.5f)
        val above = stroke(DrawTool.LINE, 0.5f, 0.1f, 0.5f, 0.9f)
        assertEquals(1, DrawingMath.hitTest(listOf(below, above), 50f, 50f, 100f, 100f, slop = 2f))
        assertEquals(0, DrawingMath.hitTest(listOf(below, above), 20f, 50f, 100f, 100f, slop = 2f))
    }

    @Test
    fun `la pointe de flèche est symétrique et ne dépasse pas la moitié du trait`() {
        val head = DrawingMath.arrowHead(0f, 50f, 100f, 50f, strokeWidth = 4f, reference = 100f)
        assertNotNull(head)
        head!!
        // Branches à égale distance de part et d'autre du trait, en retrait de la pointe.
        assertEquals(head[0], head[2], 1e-4f)
        assertEquals(50f - head[1], head[3] - 50f, 1e-4f)
        assertTrue(head[0] < 100f)
        val length = hypot(100f - head[0], 50f - head[1])
        assertEquals(4f * 3f + 100f * 0.012f, length, 1e-3f)
        // Flèche très courte : la pointe se limite à la moitié de sa longueur.
        val short = DrawingMath.arrowHead(0f, 0f, 6f, 0f, strokeWidth = 4f, reference = 100f)!!
        assertEquals(3f, hypot(6f - short[0], short[1]), 1e-3f)
        assertNull(DrawingMath.arrowHead(5f, 5f, 5f, 5f, strokeWidth = 4f, reference = 100f))
    }

    @Test
    fun `la gomme touche aussi la pointe d'une flèche`() {
        val arrow = stroke(DrawTool.ARROW, 0.1f, 0.5f, 0.9f, 0.5f, width = 0.04f)
        val head = DrawingMath.arrowHead(10f, 50f, 90f, 50f, strokeWidth = 4f, reference = 100f)!!
        assertEquals(0, DrawingMath.hitTest(listOf(arrow), head[0], head[1], 100f, 100f, slop = 1f))
    }
}
