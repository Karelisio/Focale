package app.focale.ui.viewer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import app.focale.panorama.PanoramaKind
import app.focale.ui.actions.SelectionActionsBar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.saket.telephoto.zoomable.ZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Commandes des photos panoramiques et de l'impression, en français : bouton de la visionneuse,
 * habillage des vues 360° et panoramique (« Bouger le téléphone » absent sans capteur), parcours
 * à plat (image à la hauteur de l'écran, inertie, défilement du capteur), « Imprimer » dans les
 * menus de la visionneuse et de la sélection.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "fr-w400dp-h800dp")
class PanoramaControlsTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `bouton 360 degrés et bouton Vue panoramique`() {
        var kind by mutableStateOf(PanoramaKind.SPHERE)
        val opened = mutableListOf<PanoramaKind>()
        compose.setContent { PanoramaButton(kind = kind, onClick = { opened += kind }) }
        compose.onNodeWithText("360°").assertIsDisplayed().performClick()
        kind = PanoramaKind.FLAT
        compose.onNodeWithText("Vue panoramique").assertIsDisplayed().performClick()
        assertEquals(listOf(PanoramaKind.SPHERE, PanoramaKind.FLAT), opened)
    }

    @Test
    fun `habillage, Bouger le téléphone désactivé par défaut, vue initiale et retour`() {
        var motion by mutableStateOf(false)
        var resets = 0
        var closed = false
        compose.setContent {
            PanoramaChrome(
                title = "Photo 360°",
                chromeVisible = true,
                motionAvailable = true,
                motion = motion,
                onMotionChange = { motion = it },
                onReset = { resets++ },
                onClose = { closed = true },
            ) {}
        }
        compose.onNodeWithText("Photo 360°").assertIsDisplayed()
        compose.onNodeWithText("Bouger le téléphone").assertIsOff().performClick()
        assertTrue(motion)
        compose.onNodeWithText("Bouger le téléphone").assertIsOn()
        compose.onNodeWithText("Vue initiale").performClick()
        assertEquals(1, resets)
        compose.onNodeWithContentDescription("Retour").performClick()
        assertTrue(closed)
    }

    @Test
    fun `sans capteur de rotation, pas de Bouger le téléphone, commandes masquables`() {
        var visible by mutableStateOf(true)
        compose.setContent {
            PanoramaChrome(
                title = "Vue panoramique",
                chromeVisible = visible,
                motionAvailable = false,
                motion = false,
                onMotionChange = {},
                onReset = {},
                onClose = {},
            ) {}
        }
        compose.onNodeWithText("Bouger le téléphone").assertDoesNotExist()
        compose.onNodeWithText("Vue initiale").assertIsDisplayed()
        visible = false
        compose.waitForIdle()
        compose.onNodeWithText("Vue initiale").assertDoesNotExist()
    }

    /** Panorama 6:1 dans un écran de 400 × 800 dp : rend l'état du zoom et une portée de coroutines. */
    private fun showStrip(): Pair<ZoomableImageState, CoroutineScope> {
        lateinit var state: ZoomableImageState
        lateinit var scope: CoroutineScope
        compose.setContent {
            state = rememberZoomableImageState(rememberZoomableState())
            scope = rememberCoroutineScope()
            Box(Modifier.fillMaxSize().testTag("strip")) {
                PanoramaStrip(image = remember { PainterImageSource(fakePanoramaPainter()) }, contentDescription = "Panorama", state = state, onClick = {})
            }
        }
        compose.waitForIdle()
        return state to scope
    }

    @Test
    fun `vue panoramique, l'image remplit la hauteur et se parcourt au doigt`() {
        val (state, _) = showStrip()
        val viewport = compose.onNodeWithTag("strip").fetchSemanticsNode().size
        val bounds = state.zoomableState.transformedContentBounds
        assertEquals(viewport.height.toFloat(), bounds.height, 1f)
        assertEquals(bounds.height * 6, bounds.width, 2f)
        // Centrée à l'ouverture.
        assertEquals((viewport.width - bounds.width) / 2, bounds.left, 2f)
        compose.onNodeWithTag("strip").performTouchInput { swipeLeft(startX = right * 0.8f, endX = right * 0.3f, durationMillis = 400) }
        compose.waitForIdle()
        val moved = state.zoomableState.transformedContentBounds
        assertTrue("l'image suit le doigt vers la gauche", moved.left < bounds.left - viewport.width * 0.4f)
        // Jamais au-delà du bord droit de l'image.
        repeat(6) {
            compose.onNodeWithTag("strip").performTouchInput { swipeLeft(durationMillis = 100) }
            compose.waitForIdle()
        }
        val end = state.zoomableState.transformedContentBounds
        assertEquals(viewport.width.toFloat(), end.right, 2f)
    }

    @Test
    fun `vue panoramique, un geste rapide continue sur sa lancée, vue initiale recentre`() {
        val (state, scope) = showStrip()
        val start = state.zoomableState.transformedContentBounds.left
        compose.onNodeWithTag("strip").performTouchInput { swipeLeft(startX = right * 0.7f, endX = right * 0.5f, durationMillis = 1_000) }
        compose.waitForIdle()
        val slow = start - state.zoomableState.transformedContentBounds.left
        // Vue initiale, après un pincement : zoom de base, image recentrée.
        val viewport = compose.onNodeWithTag("strip").fetchSemanticsNode().size
        scope.launch {
            state.zoomableState.zoomBy(1.5f, Offset(50f, 400f))
            state.zoomableState.recenter(viewport.width.toFloat())
        }
        compose.waitForIdle()
        assertEquals(start, state.zoomableState.transformedContentBounds.left, 1f)
        assertEquals(viewport.height.toFloat(), state.zoomableState.transformedContentBounds.height, 1f)
        compose.onNodeWithTag("strip").performTouchInput { swipeLeft(startX = right * 0.7f, endX = right * 0.5f, durationMillis = 40) }
        compose.waitForIdle()
        val fast = start - state.zoomableState.transformedContentBounds.left
        assertTrue("inertie : $fast contre $slow", fast > slow * 1.5f)
    }

    @Test
    fun `vue panoramique, tourner à droite fait défiler l'image vers la gauche`() {
        val (state, scope) = showStrip()
        val before = state.zoomableState.transformedContentBounds
        val shift = panoramaShift(turnedDegrees = 10.0, contentWidth = before.width, coverageDegrees = 180.0)
        scope.launch { state.zoomableState.panBy(Offset(shift, 0f)) }
        compose.waitForIdle()
        val after = state.zoomableState.transformedContentBounds
        assertEquals(before.left + shift, after.left, 2f)
        assertTrue(after.left < before.left)
    }

    @Test
    fun `Imprimer dans le menu de la sélection, seulement s'il est proposé`() {
        var printed = 0
        var printable by mutableStateOf(true)
        compose.setContent {
            SelectionActionsBar(
                visible = true,
                selectionCount = 3,
                onShare = {},
                onFavorite = {},
                onAddToAlbum = {},
                onTrash = {},
                onCopy = {},
                onMove = {},
                onRename = {},
                onMoveToVault = {},
                onSelectAll = {},
                onPrint = if (printable) ({ printed++ }) else null,
            )
        }
        compose.onNodeWithContentDescription("Plus").performClick()
        compose.onNodeWithText("Imprimer").assertIsDisplayed().performClick()
        assertEquals(1, printed)
        compose.onNodeWithText("Imprimer").assertDoesNotExist()
        printable = false
        compose.onNodeWithContentDescription("Plus").performClick()
        compose.onNodeWithText("Déplacer vers le coffre").assertExists()
        compose.onNodeWithText("Imprimer").assertDoesNotExist()
    }

    @Test
    fun `Imprimer dans le menu de la visionneuse`() {
        var printed = 0
        compose.setContent {
            ViewerToolbar(
                isFavorite = false,
                onShare = {},
                onToggleFavorite = {},
                onTrash = {},
                onEdit = {},
                menu = { dismiss ->
                    PrintMenuItem {
                        dismiss()
                        printed++
                    }
                },
            )
        }
        compose.onNodeWithContentDescription("Plus").performClick()
        compose.onNodeWithText("Imprimer").assertIsDisplayed().performClick()
        assertEquals(1, printed)
        compose.onNodeWithText("Imprimer").assertDoesNotExist()
    }
}
