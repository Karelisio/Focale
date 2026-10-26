package app.focale.collage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Détail d'une case agrandie ([CollageDetail]) : demandé seulement au-delà de la résolution de
 * l'aperçu, partie visible et sa marge, taille de l'écran bornée par la part du budget de la case,
 * jamais plus fine que la photo.
 */
class CollageDetailTest {

    @Test
    fun `aperçu assez fin, pas de détail`() {
        // Aperçu de 1 280 × 960 dans une case de 600 × 450 : réduit à l'écran.
        assertNull(CollageDetail.request(CellFrame(), 600f, 450f, 1280, 960, maxPixels = 1_000_000))
        // Agrandi d'un quart au plus : encore assez fin.
        assertNull(CollageDetail.request(CellFrame(zoom = 2.6f), 600f, 450f, 1280, 960, maxPixels = 1_000_000))
        assertNotNull(CollageDetail.request(CellFrame(zoom = 2.8f), 600f, 450f, 1280, 960, maxPixels = 1_000_000))
    }

    @Test
    fun `partie visible et sa marge, à la résolution de l'écran`() {
        // Case carrée de 500 px, aperçu 1 280 × 960 agrandi 4 fois : 240 × 240 px de l'aperçu visibles,
        // au centre, 2,08 px de l'écran chacun ; marge d'un quart de chaque côté.
        val request = requireNotNull(CollageDetail.request(CellFrame(zoom = 4f), 500f, 500f, 1280, 960, maxPixels = 4_000_000))
        assertEquals(460f / 1280, request.region.left, 1e-4f)
        assertEquals(820f / 1280, request.region.right, 1e-4f)
        assertEquals(300f / 960, request.region.top, 1e-4f)
        assertEquals(660f / 960, request.region.bottom, 1e-4f)
        assertEquals(750, request.width)
        assertEquals(750, request.height)
    }

    @Test
    fun `résolution demandée bornée par la part du budget de la case`() {
        val request = requireNotNull(CollageDetail.request(CellFrame(zoom = 4f), 500f, 500f, 1280, 960, maxPixels = 100_000))
        assertTrue("${request.pixels} px", request.pixels <= 100_000)
        assertTrue(request.pixels > 95_000)
        assertEquals(request.width, request.height)
        // Neuf cases d'un collage carré de 1 200 px : chacune un neuvième du budget, ensemble pas plus que lui.
        val share = CollageDetail.budget(400f, 400f, 1200f, 1200f)
        assertTrue("$share px", share in CollageDetail.BUDGET_PIXELS / 9L - 1..CollageDetail.BUDGET_PIXELS / 9L + 1)
        val all = (1..9).sumOf { requireNotNull(CollageDetail.request(CellFrame(zoom = 4f), 400f, 400f, 960, 720, share)).pixels }
        assertTrue("$all px", all <= CollageDetail.BUDGET_PIXELS)
        // Sans budget, rien.
        assertNull(CollageDetail.request(CellFrame(zoom = 4f), 500f, 500f, 1280, 960, maxPixels = 0))
        assertEquals(0L, CollageDetail.budget(400f, 400f, 0f, 0f))
    }

    @Test
    fun `au bord de la photo, la marge s'arrête à son bord`() {
        val request = requireNotNull(CollageDetail.request(CellFrame(zoom = 4f, centerX = 0f, centerY = 1f), 500f, 500f, 1280, 960, maxPixels = 4_000_000))
        assertEquals(0f, request.region.left, 0f)
        assertEquals(1f, request.region.bottom, 0f)
        assertTrue(request.region.right < 0.5f)
        assertTrue(request.region.top > 0.5f)
    }

    @Test
    fun `jamais plus fin que la photo`() {
        val request = DetailRequest(CellRect(0.4f, 0.4f, 0.5f, 0.5f), width = 1_000, height = 750)
        // Photo affichée de 4 000 × 3 000 : 400 × 300 px dans la zone.
        assertEquals(400 to 300, CollageDetail.size(request, 4_000, 3_000))
        assertEquals(1_000 to 750, CollageDetail.size(request, 12_000, 9_000))
    }
}
