package app.focale.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import app.focale.edit.BlurArea
import app.focale.edit.BlurRegion
import app.focale.edit.BlurShape
import app.focale.edit.BlurStyle
import app.focale.edit.ImageFrame
import app.focale.edit.PhotoPlacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Outil Flouter de bout en bout, comme dans l'éditeur : couche de gestes réels sur l'aperçu,
 * panneau et historique ([BlurController], [EditHistory]). Densité 1 : 1 dp = 1 px ; l'image
 * pivotée entière (200 × 300 px) est posée en (100, 50).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h700dp-mdpi")
class BlurToolTest {

    @get:Rule
    val compose = createComposeRule()

    private val history = EditHistory()
    private val tool = BlurController(history)
    private val frame = ImageFrame(100f, 50f, 200f, 300f)

    private val zones: List<BlurRegion> get() = history.current.blurs

    private fun show(angle: Float = 0f, bleed: Float = 1f) {
        val viewport = EditorViewport(frame, Rect(100f, 50f, 300f, 350f), PhotoPlacement(frame, angle, bleed))
        compose.setContent {
            val state by history.state.collectAsState()
            val selected by tool.selected.collectAsState()
            val options by tool.options.collectAsState()
            Column {
                Box(Modifier.size(400.dp).testTag("apercu")) {
                    BlurOverlay(
                        viewport = viewport,
                        regions = state.blurs,
                        selected = selected,
                        shape = options.shape,
                        brushSize = options.brushSize.dp,
                        accent = Color.Red,
                        onAdd = tool::add,
                        onSelect = tool::select,
                        onMoveStart = tool::beginMove,
                        onMove = tool::move,
                        onDelete = tool::delete,
                    )
                }
                BlurPanel(
                    options = options,
                    hasSelection = selected != null,
                    detectingFaces = false,
                    onShape = tool::setShape,
                    onStyle = tool::setStyle,
                    onIntensityStart = tool::beginIntensity,
                    onIntensity = tool::setIntensity,
                    onBrushSize = tool::setBrushSize,
                    onFaces = {},
                    onDelete = tool::deleteSelected,
                )
            }
        }
    }

    @Test
    fun `glisser trace un rectangle flouté du point de départ au doigt`() {
        show()
        compose.onNodeWithTag("apercu").performTouchInput { swipe(Offset(150f, 100f), Offset(250f, 200f), durationMillis = 300) }
        compose.waitForIdle()
        val zone = zones.single()
        val area = zone.area as BlurArea.Box
        assertEquals(BlurShape.RECTANGLE, area.shape)
        assertEquals(0.5f, area.x, 1e-3f)
        assertEquals(100f / 300f, area.y, 1e-3f)
        // Demi-côtés de 50 px rapportés au petit côté de l'image (200 px).
        assertEquals(0.25f, area.halfWidth, 1e-3f)
        assertEquals(0.25f, area.halfHeight, 1e-3f)
        assertEquals(0f, area.angle, 0f)
        assertEquals(BlurOptions().style, zone.style)
        assertEquals(BlurOptions.DEFAULT_INTENSITY, zone.intensity, 0f)
        // Une zone nouvelle n'est pas sélectionnée : un second tracé peut partir d'elle.
        assertNull(tool.selected.value)
        history.undo()
        assertTrue(zones.isEmpty())
        history.redo()
        assertEquals(zone, zones.single())
    }

    @Test
    fun `sur une photo redressée, le cadre tracé reste droit à l'écran et pivote avec la photo`() {
        show(angle = 20f, bleed = 1.05f)
        compose.onNodeWithContentDescription("Ellipse").performClick()
        compose.onNodeWithTag("apercu").performTouchInput { swipe(Offset(150f, 150f), Offset(250f, 230f), durationMillis = 300) }
        compose.waitForIdle()
        val area = zones.single().area as BlurArea.Box
        assertEquals(BlurShape.ELLIPSE, area.shape)
        // Inclinée de -20° dans la photo, que l'écran tourne de 20° : droite à l'écran.
        assertEquals(-20f, area.angle, 1e-4f)
        val placement = PhotoPlacement(frame, 20f, 1.05f)
        assertEquals(200f, placement.screenX(area.x, area.y), 0.5f)
        assertEquals(190f, placement.screenY(area.x, area.y), 0.5f)
        assertEquals(50f, placement.screenLength(area.halfWidth), 0.5f)
        assertEquals(40f, placement.screenLength(area.halfHeight), 0.5f)
    }

    @Test
    fun `le pinceau trace une zone de son épaisseur`() {
        show()
        compose.onNodeWithContentDescription("Brush").performClick()
        compose.onNodeWithTag("apercu").performTouchInput { swipe(Offset(120f, 200f), Offset(280f, 220f), durationMillis = 300) }
        compose.waitForIdle()
        val brush = zones.single().area as BlurArea.Brush
        assertTrue(brush.pointCount >= 2)
        assertEquals(0.1f, brush.x(0), 1e-3f)
        assertEquals(0.9f, brush.x(brush.pointCount - 1), 1e-3f)
        assertEquals(BlurOptions.DEFAULT_BRUSH / 200f, brush.width, 1e-4f)
    }

    @Test
    fun `un appui sélectionne la zone, un glisser la déplace en une étape, la corbeille la supprime`() {
        tool.add(BlurArea.Box(BlurShape.RECTANGLE, 0.5f, 0.5f, 0.2f, 0.2f))
        show()
        // Glisser sur une zone non sélectionnée en trace une autre : d'abord la sélectionner.
        compose.onNodeWithTag("apercu").performTouchInput { click(Offset(200f, 200f)) }
        compose.waitForIdle()
        val id = zones.single().id
        assertEquals(id, tool.selected.value)
        compose.onNodeWithTag("apercu").performTouchInput { swipe(Offset(200f, 200f), Offset(240f, 170f), durationMillis = 300) }
        compose.waitForIdle()
        val moved = zones.single().area as BlurArea.Box
        assertEquals(0.7f, moved.x, 1e-3f)
        assertEquals(0.4f, moved.y, 1e-3f)
        history.undo()
        assertEquals(0.5f, (zones.single().area as BlurArea.Box).x, 1e-6f)
        history.redo()
        compose.onNodeWithContentDescription("Delete zone").performClick()
        compose.waitForIdle()
        assertTrue(zones.isEmpty())
        history.undo()
        assertEquals(0.7f, (zones.single().area as BlurArea.Box).x, 1e-3f)
    }

    @Test
    fun `un appui hors des zones désélectionne, un petit glisser ne trace rien`() {
        tool.add(BlurArea.Box(BlurShape.RECTANGLE, 0.5f, 0.5f, 0.2f, 0.2f))
        tool.select(zones.single().id)
        show()
        compose.onNodeWithTag("apercu").performTouchInput { click(Offset(120f, 330f)) }
        compose.onNodeWithTag("apercu").performTouchInput { swipe(Offset(120f, 330f), Offset(126f, 360f), durationMillis = 200) }
        compose.waitForIdle()
        assertNull(tool.selected.value)
        assertEquals(1, zones.size)
    }

    @Test
    fun `style et intensité s'appliquent à la zone sélectionnée`() {
        tool.add(BlurArea.Box(BlurShape.RECTANGLE, 0.5f, 0.5f, 0.2f, 0.2f))
        tool.select(zones.single().id)
        show()
        compose.onNodeWithText("Pixels").performClick()
        compose.waitForIdle()
        assertEquals(BlurStyle.PIXELATE, zones.single().style)
        tool.beginIntensity()
        tool.setIntensity(0.9f)
        assertEquals(0.9f, zones.single().intensity, 0f)
        history.undo()
        assertEquals(BlurOptions.DEFAULT_INTENSITY, zones.single().intensity, 0f)
        history.undo()
        assertEquals(BlurOptions().style, zones.single().style)
    }

    @Test
    fun `chaque zone est un élément accessible qui se supprime`() {
        tool.add(BlurArea.Box(BlurShape.ELLIPSE, 0.5f, 0.5f, 0.2f, 0.2f))
        show()
        compose.onNodeWithContentDescription("Blurred zone 1 (Ellipse)").performCustomAccessibilityActionWithLabel("Delete")
        compose.waitForIdle()
        assertTrue(zones.isEmpty())
        history.undo()
        assertEquals(BlurShape.ELLIPSE, zones.single().area.shape)
        // Activer l'élément sélectionne la zone.
        compose.onNodeWithContentDescription("Blurred zone 1 (Ellipse)").performClick()
        compose.waitForIdle()
        assertEquals(zones.single().id, tool.selected.value)
        assertFalse(zones.isEmpty())
    }
}
