package app.focale.collage

import app.focale.core.util.UiText
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dispositions et géométrie des cases : pour chaque nombre de photos, plusieurs dispositions dont
 * les cases pavent exactement le collage ; avec l'espacement, à chaque proportion, deux cases
 * voisines sont à un espacement l'une de l'autre et les cases du bord à un espacement du bord, sans
 * chevauchement ni vide, en pixels flottants (aperçu) comme entiers (export).
 */
class CollageLayoutsTest {

    private val layouts = (CollageLayouts.MIN_PHOTOS..CollageLayouts.MAX_PHOTOS).flatMap(CollageLayouts::forCount)

    @Test
    fun `plusieurs dispositions pour chaque nombre de photos, une case par photo`() {
        for (count in 2..9) {
            val forCount = CollageLayouts.forCount(count)
            assertTrue("$count photos : ${forCount.size} dispositions", forCount.size >= 4)
            forCount.forEach { assertEquals(it.id, count, it.cells.size) }
        }
        assertEquals("identifiants uniques", layouts.size, layouts.map { it.id }.toSet().size)
        layouts.forEach { assertEquals(it, CollageLayouts.byId(it.id)) }
    }

    @Test
    fun `chaque disposition est nommée, et les noms d'un même nombre de photos diffèrent`() {
        for (count in 2..9) {
            val names = CollageLayouts.forCount(count).map { it.name }
            names.forEach { name ->
                val id = when (name) {
                    is UiText.Res -> name.id
                    is UiText.Plural -> name.id
                    else -> 0
                }
                assertTrue("$name", id != 0)
            }
            assertEquals("$count photos", names.size, names.toSet().size)
        }
    }

    @Test
    fun `les cases normalisées pavent exactement le carré unité`() {
        for (layout in layouts) {
            val cells = layout.cells.map { CellRect(it.left, it.top, it.right, it.bottom) }
            assertTiles(layout.id, cells, CellRect(0f, 0f, 1f, 1f), EPSILON)
        }
    }

    @Test
    fun `avec l'espacement, les cases agrandies d'un demi-espacement pavent le collage, à chaque proportion`() {
        for (layout in layouts) {
            for (aspect in CollageAspect.entries) {
                for ((width, height) in listOf(aspect.size(CollageRenderer.LONG_SIDE), aspect.size(360))) {
                    for (spacing in listOf(0f, 0.013f, CollageState.MAX_SPACING)) {
                        val w = width.toFloat()
                        val h = height.toFloat()
                        val gap = CollageGeometry.gap(spacing, w, h)
                        val cells = CollageGeometry.cells(layout, w, h, gap)
                        val label = "${layout.id} $aspect ${width}x$height espacement $spacing"
                        cells.forEach { assertTrue("$label : case vide $it", it.width > 0f && it.height > 0f) }
                        val inflated = cells.map { CellRect(it.left - gap / 2, it.top - gap / 2, it.right + gap / 2, it.bottom + gap / 2) }
                        assertTiles(label, inflated, CellRect(gap / 2, gap / 2, w - gap / 2, h - gap / 2), max(w, h) * EPSILON)
                    }
                }
            }
        }
    }

    @Test
    fun `à l'export, bords au pixel près et espacement entier exactement le même partout`() {
        for (layout in layouts) {
            for (aspect in CollageAspect.entries) {
                val (width, height) = aspect.size(CollageRenderer.LONG_SIDE)
                for (spacing in listOf(0f, 0.011f, CollageState.MAX_SPACING)) {
                    val gap = CollageGeometry.pixelGap(spacing, width, height)
                    val cells = CollageGeometry.pixelCells(layout, width, height, gap)
                    val label = "${layout.id} $aspect espacement $gap px"
                    for (cell in cells) {
                        for (edge in listOf(cell.left, cell.top, cell.right, cell.bottom)) assertEquals(label, edge, Math.round(edge).toFloat())
                        assertTrue("$label : case vide", cell.width >= 1f && cell.height >= 1f)
                    }
                    // En demi-pixels, pour un espacement impair : pavage exact, sans tolérance.
                    val doubled = cells.map { CellRect(2 * it.left - gap, 2 * it.top - gap, 2 * it.right + gap, 2 * it.bottom + gap) }
                    assertTiles(label, doubled, CellRect(gap.toFloat(), gap.toFloat(), 2f * width - gap, 2f * height - gap), 0f)
                    // Mêmes cases qu'en flottants, à un pixel près.
                    CollageGeometry.cells(layout, width.toFloat(), height.toFloat(), gap.toFloat()).zip(cells).forEach { (exact, snapped) ->
                        assertTrue(label, abs(exact.left - snapped.left) <= 1f && abs(exact.right - snapped.right) <= 1f)
                        assertTrue(label, abs(exact.top - snapped.top) <= 1f && abs(exact.bottom - snapped.bottom) <= 1f)
                    }
                }
            }
        }
    }

    @Test
    fun `deux cases voisines sont séparées d'exactement un espacement, et les cases du bord en sont à un espacement`() {
        val layout = CollageLayouts.byId("3-large-left")!!
        val cells = CollageGeometry.pixelCells(layout, 4096, 4096, 40)
        // Grande case à gauche, deux à droite l'une sur l'autre : 40 px partout, cases de même largeur.
        assertEquals(CellRect(40f, 40f, 2028f, 4056f), cells[0])
        assertEquals(CellRect(2068f, 40f, 4056f, 2028f), cells[1])
        assertEquals(CellRect(2068f, 2068f, 4056f, 4056f), cells[2])
    }

    @Test
    fun `un point du collage n'appartient qu'à une case, aucune dans l'espacement`() {
        val layout = CollageLayouts.byId("4-grid")!!
        val cells = CollageGeometry.cells(layout, 1000f, 1000f, 20f)
        assertEquals(0, CollageGeometry.cellAt(cells, 100f, 100f))
        assertEquals(3, CollageGeometry.cellAt(cells, 900f, 900f))
        assertEquals(null, CollageGeometry.cellAt(cells, 500f, 100f))
        assertEquals(null, CollageGeometry.cellAt(cells, 5f, 5f))
        // Bord gauche inclus, bord droit exclu.
        assertEquals(1, CollageGeometry.cellAt(cells, cells[1].left, cells[1].top))
        assertEquals(null, CollageGeometry.cellAt(cells, cells[0].right, cells[0].top))
    }

    @Test
    fun `l'arrondi est une fraction du petit côté, au plus la moitié de la case`() {
        val cell = CellRect(0f, 0f, 100f, 40f)
        assertEquals(16f, CollageGeometry.radius(0.04f, 400f, 800f, cell), 0f)
        assertEquals(20f, CollageGeometry.radius(CollageState.MAX_CORNERS, 4096f, 4096f, cell), 0f)
        assertEquals(0f, CollageGeometry.radius(0f, 4096f, 4096f, cell), 0f)
    }

    @Test
    fun `dimensions de l'export, 4 096 px sur le grand côté`() {
        assertEquals(4096 to 4096, CollageAspect.SQUARE.size(4096))
        assertEquals(3277 to 4096, CollageAspect.PORTRAIT.size(4096))
        assertEquals(3072 to 4096, CollageAspect.PHOTO.size(4096))
        assertEquals(2304 to 4096, CollageAspect.STORY.size(4096))
        assertEquals(4096 to 2304, CollageAspect.LANDSCAPE.size(4096))
    }

    /**
     * [cells] pavent exactement [area] : toutes dedans, sans chevauchement deux à deux, et la somme
     * de leurs aires vaut celle de [area] (à [tolerance] près sur les longueurs).
     */
    private fun assertTiles(label: String, cells: List<CellRect>, area: CellRect, tolerance: Float) {
        for (cell in cells) {
            assertTrue("$label : $cell hors de $area", cell.left >= area.left - tolerance && cell.top >= area.top - tolerance)
            assertTrue("$label : $cell hors de $area", cell.right <= area.right + tolerance && cell.bottom <= area.bottom + tolerance)
        }
        for (i in cells.indices) {
            for (j in i + 1 until cells.size) {
                val a = cells[i]
                val b = cells[j]
                val overlapX = min(a.right, b.right) - max(a.left, b.left)
                val overlapY = min(a.bottom, b.bottom) - max(a.top, b.top)
                assertTrue("$label : cases $i et $j se chevauchent", overlapX <= tolerance || overlapY <= tolerance)
            }
        }
        val total = cells.sumOf { it.width.toDouble() * it.height }
        val expected = area.width.toDouble() * area.height
        assertEquals(label, expected, total, expected * 1e-5 + tolerance * (area.width + area.height))
    }

    private companion object {
        const val EPSILON = 1e-5f
    }
}
