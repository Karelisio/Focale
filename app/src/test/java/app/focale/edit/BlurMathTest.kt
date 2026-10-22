package app.focale.edit

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Géométrie pure des zones floutées (repère de la photo sous rotation, miroir et redressement,
 * blocs, saisie au doigt) et calcul des blocs sur une image de synthèse.
 */
class BlurMathTest {

    private fun box(x: Float = 0.3f, y: Float = 0.4f, halfWidth: Float = 0.1f, halfHeight: Float = 0.05f, angle: Float = 0f, shape: BlurShape = BlurShape.RECTANGLE) =
        BlurArea.Box(shape, x, y, halfWidth, halfHeight, angle)

    private fun region(area: BlurArea, intensity: Float = 0.5f, style: BlurStyle = BlurStyle.PIXELATE) = BlurRegion(1, area, style, intensity)

    @Test
    fun `les pixels du cadre et de la source se correspondent dans les huit orientations`() {
        val width = 5
        val height = 3
        for (turns in 0..3) {
            for (flip in listOf(false, true)) {
                val seen = HashSet<Pair<Int, Int>>()
                for (sx in 0 until width) {
                    for (sy in 0 until height) {
                        val fx = BlurMath.frameX(sx, sy, width, height, turns, flip)
                        val fy = BlurMath.frameY(sx, sy, width, height, turns)
                        assertEquals("$turns, $flip", sx to sy, BlurMath.sourcePixel(fx, fy, width, height, turns, flip))
                        seen += fx to fy
                    }
                }
                // Une permutation des pixels du cadre tourné.
                val (frameWidth, frameHeight) = if (turns % 2 == 0) width to height else height to width
                assertEquals(frameWidth * frameHeight, seen.size)
                assertTrue(seen.all { (x, y) -> x in 0 until frameWidth && y in 0 until frameHeight })
            }
        }
    }

    @Test
    fun `une zone tournée ou retournée avec l'édition reste sur le même pixel de la photo`() {
        // Pixel (7, 2) d'une source de 10 × 6 : zone centrée dessus, puis l'édition tourne et se retourne.
        val width = 10
        val height = 6
        fun center(fx: Int, fy: Int, frameWidth: Int, frameHeight: Int) = (fx + 0.5f) / frameWidth to (fy + 0.5f) / frameHeight
        var state = EditState(blurs = listOf(region(center(7, 2, width, height).let { (x, y) -> box(x, y) })))
        repeat(6) { step ->
            // Comme EditorViewModel : quart de tour puis miroir, en alternance.
            state = if (step % 2 == 0) state.rotated() else state.mirrored()
            val turns = state.quarterTurns
            val flip = state.flipHorizontal
            val (frameWidth, frameHeight) = if (turns % 2 == 0) width to height else height to width
            val fx = BlurMath.frameX(7, 2, width, height, turns, flip)
            val fy = BlurMath.frameY(7, 2, width, height, turns)
            val (x, y) = center(fx, fy, frameWidth, frameHeight)
            assertEquals("étape $step", x, state.blurs.single().area.anchorX, 1e-5f)
            assertEquals("étape $step", y, state.blurs.single().area.anchorY, 1e-5f)
        }
    }

    @Test
    fun `un quart de tour échange les côtés d'un cadre, un miroir inverse son inclinaison`() {
        val rotated = box(halfWidth = 0.2f, halfHeight = 0.05f, angle = 15f).rotateClockwise() as BlurArea.Box
        assertEquals(0.6f, rotated.x, 1e-6f)
        assertEquals(0.3f, rotated.y, 1e-6f)
        assertEquals(0.05f, rotated.halfWidth, 1e-6f)
        assertEquals(0.2f, rotated.halfHeight, 1e-6f)
        assertEquals(15f, rotated.angle, 1e-6f)
        val flipped = box(angle = 15f).flipHorizontal() as BlurArea.Box
        assertEquals(0.7f, flipped.x, 1e-6f)
        assertEquals(-15f, flipped.angle, 1e-6f)
        val brush = BlurArea.Brush(floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f), 0.05f)
        val turned = brush.rotateClockwise().rotateClockwise().rotateClockwise().rotateClockwise() as BlurArea.Brush
        assertArrayEquals(brush.points, turned.points, 1e-6f)
        assertArrayEquals(brush.points, (brush.flipHorizontal().flipHorizontal() as BlurArea.Brush).points, 1e-6f)
        val moved = brush.translate(0.1f, -0.1f) as BlurArea.Brush
        assertArrayEquals(floatArrayOf(0.2f, 0.1f, 0.4f, 0.3f), moved.points, 1e-6f)
        assertEquals(0.2f, moved.anchorX, 1e-6f)
        assertEquals(0.05f, moved.width, 0f)
    }

    @Test
    fun `l'écran et la photo redressée se correspondent, déplacements compris`() {
        val frame = ImageFrame(left = 40f, top = 100f, width = 300f, height = 200f)
        val placement = PhotoPlacement(frame, angle = 12f, bleed = 1.02f)
        for ((u, v) in listOf(0.5f to 0.5f, 0.1f to 0.9f, 0.8f to 0.2f)) {
            val x = placement.screenX(u, v)
            val y = placement.screenY(u, v)
            assertEquals(u, placement.photoX(x, y), 1e-5f)
            assertEquals(v, placement.photoY(x, y), 1e-5f)
        }
        // Le centre du cadre ne bouge pas ; un point à sa droite descend (rotation horaire).
        assertEquals(190f, placement.screenX(0.5f, 0.5f), 1e-3f)
        assertEquals(200f, placement.screenY(0.5f, 0.5f), 1e-3f)
        assertTrue(placement.screenY(0.9f, 0.5f) > 200f)
        // Un glissé à l'écran déplace la zone du même vecteur une fois ramené dans la photo.
        val u = 0.3f
        val v = 0.6f
        val dx = placement.photoDeltaX(25f, -10f)
        val dy = placement.photoDeltaY(25f, -10f)
        assertEquals(placement.screenX(u, v) + 25f, placement.screenX(u + dx, v + dy), 1e-3f)
        assertEquals(placement.screenY(u, v) - 10f, placement.screenY(u + dx, v + dy), 1e-3f)
        // Longueurs : petit côté du cadre, agrandissement compris.
        assertEquals(0.1f * 200f * 1.02f, placement.screenLength(0.1f), 1e-4f)
        assertEquals(0.1f, placement.photoLength(placement.screenLength(0.1f)), 1e-6f)
    }

    @Test
    fun `les blocs suivent la taille de la zone et l'intensité`() {
        assertEquals(BlurMath.MAX_BLOCKS, BlurMath.blocksAcross(0f), 1e-6f)
        assertEquals(BlurMath.MIN_BLOCKS, BlurMath.blocksAcross(1f), 1e-6f)
        assertTrue(BlurMath.blocksAcross(0.3f) > BlurMath.blocksAcross(0.7f))
        // Un petit et un grand visage comptent autant de blocs : les blocs grandissent avec la zone.
        val small = BlurMath.grid(region(box(0.5f, 0.5f, 0.05f, 0.05f), intensity = 0.4f), 1000, 1000)!!
        val large = BlurMath.grid(region(box(0.5f, 0.5f, 0.3f, 0.3f), intensity = 0.4f), 1000, 1000)!!
        assertEquals(small.columns, large.columns)
        assertEquals(6f * small.cellWidth, large.cellWidth, 1f)
        // Plus d'intensité, moins de blocs (plus gros).
        val fine = BlurMath.grid(region(box(0.5f, 0.5f, 0.3f, 0.3f), intensity = 0f), 1000, 1000)!!
        val coarse = BlurMath.grid(region(box(0.5f, 0.5f, 0.3f, 0.3f), intensity = 1f), 1000, 1000)!!
        assertEquals(8, fine.columns)
        assertEquals(3, coarse.columns)
        // Jamais de blocs de moins de quelques pixels, même pour une zone minuscule.
        val tiny = BlurMath.grid(region(box(0.5f, 0.5f, 0.004f, 0.004f), intensity = 0f), 1000, 1000)!!
        assertTrue(tiny.cellWidth >= BlurMath.MIN_BLOCK_PIXELS - 1e-3f)
    }

    @Test
    fun `la grille couvre la boîte de la zone limitée au cadre, en blocs égaux`() {
        val grid = BlurMath.grid(region(box(0.05f, 0.5f, 0.1f, 0.1f)), 400, 200)!!
        // Zone de 40 × 40 px centrée en (20, 100) : la moitié gauche sort du cadre.
        assertEquals(0, grid.left)
        assertEquals(80, grid.top)
        assertEquals(40, grid.right)
        assertEquals(120, grid.bottom)
        val sizes = (grid.left until grid.right).groupingBy(grid::column).eachCount().values
        assertTrue(sizes.max() - sizes.min() <= 1)
        assertNull(BlurMath.grid(region(box(1.5f, 0.5f, 0.1f, 0.1f)), 400, 200))
    }

    @Test
    fun `la boîte englobante d'une forme inclinée l'enveloppe exactement`() {
        val rect = BlurMath.bounds(box(0.5f, 0.5f, 0.2f, 0.1f, angle = 90f), 100f, 100f)
        assertEquals(40f, rect[0], 1e-3f)
        assertEquals(30f, rect[1], 1e-3f)
        assertEquals(60f, rect[2], 1e-3f)
        assertEquals(70f, rect[3], 1e-3f)
        val ellipse = BlurMath.bounds(box(0.5f, 0.5f, 0.2f, 0.1f, angle = 45f, shape = BlurShape.ELLIPSE), 100f, 100f)
        // Demi-étendue d'une ellipse à 45° : √((a² + b²) / 2).
        assertEquals(50f - 15.811f, ellipse[0], 1e-2f)
        val brush = BlurMath.bounds(BlurArea.Brush(floatArrayOf(0.2f, 0.2f, 0.6f, 0.4f), 0.1f), 100f, 200f)
        assertArrayEquals(floatArrayOf(15f, 35f, 65f, 85f), brush, 1e-3f)
    }

    @Test
    fun `une zone se touche selon sa forme, son inclinaison et son épaisseur`() {
        val rect = box(0.5f, 0.5f, 0.2f, 0.1f)
        assertTrue(BlurMath.contains(rect, 69f, 59f, 100f, 100f))
        assertFalse(BlurMath.contains(rect, 50f, 62f, 100f, 100f))
        assertTrue(BlurMath.contains(rect, 50f, 62f, 100f, 100f, slop = 3f))
        val tilted = rect.copy(angle = 90f)
        assertFalse(BlurMath.contains(tilted, 69f, 50f, 100f, 100f))
        assertTrue(BlurMath.contains(tilted, 50f, 69f, 100f, 100f))
        val ellipse = rect.copy(shape = BlurShape.ELLIPSE)
        assertTrue(BlurMath.contains(ellipse, 69f, 50f, 100f, 100f))
        // Coin du rectangle, hors de l'ellipse inscrite.
        assertFalse(BlurMath.contains(ellipse, 68f, 58f, 100f, 100f))
        val brush = BlurArea.Brush(floatArrayOf(0.1f, 0.5f, 0.9f, 0.5f), 0.1f)
        assertTrue(BlurMath.contains(brush, 50f, 54f, 100f, 100f))
        assertFalse(BlurMath.contains(brush, 50f, 56f, 100f, 100f))
        assertTrue(BlurMath.contains(BlurArea.Brush(floatArrayOf(0.5f, 0.5f), 0.1f), 52f, 52f, 100f, 100f))
    }

    @Test
    fun `chaque bloc garde la moyenne de ses pixels, lue par bandes dans n'importe quelle orientation`() {
        // Source de 12 × 8 où chaque pixel vaut une couleur unique.
        val width = 12
        val height = 8
        val pixels = IntArray(width * height) { (0xFF shl 24) or ((it * 7 % 256) shl 16) or ((it * 13 % 256) shl 8) or (it * 29 % 256) }
        for (turns in 0..3) {
            for (flip in listOf(false, true)) {
                val (frameWidth, frameHeight) = if (turns % 2 == 0) width to height else height to width
                val zone = region(box(0.5f, 0.5f, 0.5f * frameWidth / min(frameWidth, frameHeight), 0.5f * frameHeight / min(frameWidth, frameHeight), angle = 0f))
                val grid = BlurMath.grid(zone, frameWidth, frameHeight)!!
                val results = listOf(1, 3, 64).map { strip ->
                    BlurMath.blockAverages(grid, width, height, turns, flip, stripRows = strip) { top, rows, left, stripWidth, buffer ->
                        for (r in 0 until rows) for (c in 0 until stripWidth) buffer[r * stripWidth + c] = pixels[(top + r) * width + left + c]
                    }
                }
                assertTrue("$turns, $flip", results.all { it.contentEquals(results[0]) })
                // Moyenne attendue de chaque bloc, calculée pixel du cadre par pixel du cadre.
                val sums = Array(grid.columns * grid.rows) { LongArray(4) }
                val counts = IntArray(grid.columns * grid.rows)
                for (fx in grid.left until grid.right) {
                    for (fy in grid.top until grid.bottom) {
                        val (sx, sy) = BlurMath.sourcePixel(fx, fy, width, height, turns, flip)
                        val color = pixels[sy * width + sx]
                        val cell = grid.row(fy) * grid.columns + grid.column(fx)
                        for (k in 0 until 4) sums[cell][k] += (color ushr (24 - 8 * k) and 0xFF).toLong()
                        counts[cell]++
                    }
                }
                for (cell in counts.indices) {
                    for (k in 0 until 4) {
                        val expected = (sums[cell][k] + counts[cell] / 2) / counts[cell]
                        assertEquals("$turns, $flip, bloc $cell", expected.toInt(), results[0][cell] ushr (24 - 8 * k) and 0xFF)
                    }
                }
            }
        }
    }

    @Test
    fun `sur une photo de 80 Mpx, une zone ne lit que son rectangle, par bandes`() {
        // Source virtuelle de 10 000 × 8 000 px, tournée d'un quart de tour : rien n'est alloué
        // à sa taille, seules les lectures demandées sont observées.
        val width = 10_000
        val height = 8_000
        val zone = region(box(0.8f, 0.3f, 0.05f, 0.04f))
        // Cadre tourné : 8 000 × 10 000 px.
        val grid = BlurMath.grid(zone, height, width)!!
        var largestBuffer = 0
        var pixelsRead = 0L
        val rows = HashSet<Int>()
        BlurMath.blockAverages(grid, width, height, quarterTurns = 1, flip = false) { top, count, left, stripWidth, buffer ->
            assertTrue(count <= 64)
            assertTrue(left >= 0 && left + stripWidth <= width && top >= 0 && top + count <= height)
            largestBuffer = max(largestBuffer, buffer.size)
            pixelsRead += count.toLong() * stripWidth
            for (row in top until top + count) assertTrue("ligne $row lue deux fois", rows.add(row))
            buffer.fill(0xFF808080.toInt(), 0, count * stripWidth)
        }
        // Exactement les pixels de la zone, une fois chacun ; tampon d'une bande.
        val zonePixels = (grid.right - grid.left).toLong() * (grid.bottom - grid.top)
        assertEquals(zonePixels, pixelsRead)
        assertTrue(zonePixels < width.toLong() * height / 100)
        assertTrue(largestBuffer <= max(grid.right - grid.left, grid.bottom - grid.top) * 64)
    }

    @Test
    fun `le flou ne dépend que des moyennes des blocs et reste dans leurs bornes`() {
        val colors = intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0xFF808080.toInt(), 0xFF204060.toInt())
        val smooth = BlurMath.smooth(colors, columns = 2, rows = 2)
        assertEquals(2 * BlurMath.SMOOTHING * 2 * BlurMath.SMOOTHING, smooth.size)
        for (color in smooth) {
            assertEquals(0xFF, color ushr 24)
            for (shift in listOf(16, 8, 0)) assertTrue((color shr shift and 0xFF) in 0..255)
        }
        // Une grille uniforme reste uniforme.
        val flat = BlurMath.smooth(IntArray(6) { 0xFF336699.toInt() }, columns = 3, rows = 2)
        assertTrue(flat.all { it == 0xFF336699.toInt() })
        // Lissé : deux cellules voisines diffèrent bien moins que le noir et le blanc d'origine.
        val stripe = BlurMath.smooth(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), columns = 2, rows = 1)
        var largest = 0
        for (i in 1 until 2 * BlurMath.SMOOTHING) largest = max(largest, abs((stripe[i] and 0xFF) - (stripe[i - 1] and 0xFF)))
        assertTrue("saut de $largest", largest < 90)
    }

    @Test
    fun `un visage détecté devient une ellipse sur la photo tournée et retournée`() {
        val face = NormRect(0.1f, 0.2f, 0.3f, 0.5f)
        val plain = BlurMath.faceArea(face, quarterTurns = 0, flip = false, width = 400f, height = 300f)
        assertEquals(BlurShape.ELLIPSE, plain.shape)
        assertEquals(0.2f, plain.x, 1e-6f)
        assertEquals(0.35f, plain.y, 1e-6f)
        assertEquals(0.2f * 400f / 2f * BlurMath.FACE_MARGIN / 300f, plain.halfWidth, 1e-6f)
        assertEquals(0.3f * 300f / 2f * BlurMath.FACE_MARGIN / 300f, plain.halfHeight, 1e-6f)
        // Quart de tour puis miroir : même passage que le recadrage.
        val turned = BlurMath.faceArea(face, quarterTurns = 1, flip = true, width = 300f, height = 400f)
        val expected = CropMath.flipHorizontal(CropMath.rotateClockwise(face))
        assertEquals((expected.left + expected.right) / 2f, turned.x, 1e-6f)
        assertEquals((expected.top + expected.bottom) / 2f, turned.y, 1e-6f)
        // Le visage garde sa taille en pixels.
        assertEquals(plain.halfWidth, turned.halfHeight, 1e-6f)
        assertEquals(plain.halfHeight, turned.halfWidth, 1e-6f)
        assertNotNull(turned)
    }
}
