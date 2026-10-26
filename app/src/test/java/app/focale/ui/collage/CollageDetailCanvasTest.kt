package app.focale.ui.collage

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.focale.collage.CellFrame
import app.focale.collage.CollageDetail
import app.focale.collage.CollageGeometry
import app.focale.collage.CollageState
import app.focale.collage.DetailRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Aperçu d'un collage dont une case est agrandie : seule elle demande un détail, à une résolution
 * bornée par sa part du budget, et le détail reçu est dessiné à sa place, par-dessus l'aperçu.
 * Densité 1 : collage de 400 × 200 px, deux cases côte à côte.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h700dp-mdpi")
class CollageDetailCanvasTest {

    @get:Rule
    val compose = createComposeRule()

    private val state = CollageState(
        layoutId = "2-side",
        photos = listOf(1L, 2L),
        frames = mapOf(1L to CellFrame(zoom = 4f)),
        spacing = 0.05f,
        backgroundColor = Color.White.toArgb(),
    )

    private fun solid(width: Int, height: Int, color: Color): ImageBitmap = ImageBitmap(width, height).also {
        Canvas(it).drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { this.color = color })
    }

    @Test
    fun `seule la case agrandie demande un détail, borné, dessiné à sa place`() {
        var requests: Map<Long, DetailRequest> = emptyMap()
        var details by mutableStateOf<Map<Long, CollageDetailImage>>(emptyMap())
        val previews = mapOf(1L to solid(400, 300, Color.Blue), 2L to solid(400, 300, Color.Green))
        compose.setContent {
            Box(Modifier.size(400.dp, 200.dp).testTag("collage")) {
                CollageCanvas(
                    state = state,
                    items = emptyMap(),
                    previews = previews,
                    onFrameStart = {},
                    onFrame = { _, _, _, _, _, _ -> },
                    onSwap = { _, _ -> },
                    onZoomStep = { _, _, _, _ -> },
                    onResetFrame = {},
                    modifier = Modifier.size(400.dp, 200.dp),
                    details = details,
                    onDetails = { requests = it },
                )
            }
        }
        compose.waitForIdle()
        val request = requireNotNull(requests[1L])
        assertEquals("la case qui montre son aperçu à sa résolution n'en demande pas", setOf(1L), requests.keys)
        val cells = CollageGeometry.cells(state.layout, 400f, 200f, CollageGeometry.gap(state.spacing, 400f, 200f))
        val cell = cells[0]
        assertTrue("${request.pixels} px", request.pixels <= CollageDetail.budget(cell.width, cell.height, 400f, 200f))
        // Partie visible (un quart de l'aperçu, au centre) et sa marge.
        assertTrue(request.region.left > 0.25f && request.region.right < 0.75f)

        details = mapOf(1L to CollageDetailImage(request, request.region, solid(request.width, request.height, Color.Magenta)))
        compose.waitForIdle()
        val pixels = compose.onNodeWithTag("collage").captureToImage().toPixelMap()
        assertEquals(Color.Magenta, pixels[cell.centerX.toInt(), cell.centerY.toInt()])
        assertNotEquals(Color.Magenta, pixels[cells[1].centerX.toInt(), cells[1].centerY.toInt()])
    }
}
