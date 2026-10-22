package app.focale.ui.collage

import android.content.Context
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.test.core.app.ApplicationProvider
import app.focale.R
import app.focale.collage.CellFrame
import app.focale.collage.CollageAspect
import app.focale.collage.CollageBackground
import app.focale.collage.CollageState
import app.focale.core.settings.AppSettings
import app.focale.ui.theme.FocaleTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Écran de collage : cases lues avec leur photo, choix d'une disposition, cadrage au doigt
 * (glisser, pincer), échange de deux photos par appui long puis glisser, actions TalkBack des
 * cases, réglages nommés, enregistrement (avec ou sans partage) et fermeture confirmée.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class CollageScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val ids = listOf(1L, 2L, 3L, 4L)
    private val editor = CollageEditor(ids)
    private val saves = ArrayList<Boolean>()
    private var closed = 0

    private fun show(tool: CollageTool = CollageTool.LAYOUT) {
        compose.setContent {
            FocaleTheme(AppSettings(dynamicColor = false)) {
                CollageContent(
                    editor = editor,
                    items = ids.associateWith { CollageTestPhotos.item(it) },
                    // Photos en paysage (4:3) : de la marge à cadrer dans les cases carrées.
                    previews = ids.associateWith { CollageTestPhotos.scene(0) },
                    saving = false,
                    onClose = { closed++ },
                    onSave = { saves += it },
                    initialTool = tool,
                )
            }
        }
    }

    private fun cell(index: Int): SemanticsNodeInteraction = compose.onNodeWithTag("$CELL_TAG$index")

    private fun description(index: Int): String =
        cell(index).fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().joinToString()

    /** Centre de la case [index], dans le repère de la case [from]. */
    private fun centerOf(index: Int, from: Int): Offset {
        val target = cell(index).fetchSemanticsNode().boundsInRoot
        val origin = cell(from).fetchSemanticsNode().boundsInRoot
        return target.center - origin.topLeft
    }

    @Test
    fun `chaque case est lue avec sa photo, son nom et sa date`() {
        show()
        assertTrue(description(0), description(0).startsWith("Cell 1 of 4: IMG_0001.jpg, "))
        assertTrue(description(3), description(3).contains("IMG_0004.jpg"))
        assertTrue(description(3).contains("2025"))
    }

    @Test
    fun `choisir une disposition, puis l'annuler`() {
        show()
        compose.onNodeWithContentDescription("Large on top, 3 below").performClick()
        compose.waitForIdle()
        assertEquals("4-large-top", editor.current.layoutId)
        // La grande case du haut reçoit la première photo, sur toute la largeur.
        val large = cell(0).fetchSemanticsNode().boundsInRoot
        val small = cell(1).fetchSemanticsNode().boundsInRoot
        assertTrue(large.width > 2.5f * small.width)

        compose.onNodeWithContentDescription(context.getString(R.string.editor_undo)).performClick()
        compose.waitForIdle()
        assertEquals("4-grid", editor.current.layoutId)
    }

    @Test
    fun `appui long puis glisser sur une autre case, les deux photos s'échangent`() {
        show()
        val target = centerOf(3, from = 0)
        cell(0).performTouchInput {
            down(center)
            // Immobile au-delà du délai de l'appui long, puis glissé jusqu'à la case 4.
            repeat(8) { moveBy(Offset.Zero, delayMillis = 100) }
            moveTo((center + target) / 2f)
            moveTo(target)
            up()
        }
        compose.waitForIdle()
        assertEquals(listOf(4L, 2L, 3L, 1L), editor.current.photos)
        assertTrue(description(0).contains("IMG_0004.jpg"))
        assertTrue(description(3).contains("IMG_0001.jpg"))
        assertTrue(editor.canUndo.value)
    }

    @Test
    fun `un glisser rapide cadre la photo au lieu de l'échanger`() {
        show()
        cell(0).performTouchInput { swipe(center, center + Offset(width * 0.3f, 0f), durationMillis = 150) }
        compose.waitForIdle()
        assertEquals(ids, editor.current.photos)
        // Doigt vers la droite : la partie visible part vers la gauche de la photo.
        assertTrue("${editor.current.frame(1L)}", editor.current.frame(1L).centerX < 0.5f)
        assertEquals(CellFrame.Default, editor.current.frame(2L))
        // Un geste, une étape.
        assertTrue(editor.undo())
        assertEquals(CellFrame.Default, editor.current.frame(1L))
        assertFalse(editor.canUndo.value)
    }

    @Test
    fun `pincer agrandit la photo de la case`() {
        show()
        cell(1).performTouchInput {
            pinch(
                start0 = center - Offset(20f, 0f),
                end0 = center - Offset(120f, 0f),
                start1 = center + Offset(20f, 0f),
                end1 = center + Offset(120f, 0f),
            )
        }
        compose.waitForIdle()
        assertTrue("${editor.current.frame(2L)}", editor.current.frame(2L).zoom > 2f)
    }

    @Test
    fun `actions TalkBack des cases, échanger, agrandir, recentrer`() {
        show()
        cell(0).performCustomAccessibilityActionWithLabel("Swap with next cell")
        compose.waitForIdle()
        assertEquals(listOf(2L, 1L, 3L, 4L), editor.current.photos)

        cell(2).performCustomAccessibilityActionWithLabel("Zoom in")
        compose.waitForIdle()
        assertEquals(1.5f, editor.current.frame(3L).zoom, 1e-4f)
        cell(2).performCustomAccessibilityActionWithLabel("Reset framing")
        compose.waitForIdle()
        assertEquals(CellFrame.Default, editor.current.frame(3L))
    }

    @Test
    fun `réglages nommés, format, bordures et fond`() {
        show(CollageTool.FORMAT)
        compose.onNodeWithContentDescription("Portrait (4:5)").performClick()
        compose.waitForIdle()
        assertEquals(CollageAspect.PORTRAIT, editor.current.aspect)

        compose.onNodeWithContentDescription(context.getString(R.string.collage_tool_borders)).performClick()
        compose.onNodeWithContentDescription("Spacing").performSemanticsAction(SemanticsActions.SetProgress) { it(0.04f) }
        compose.onNodeWithContentDescription("Rounded corners").performSemanticsAction(SemanticsActions.SetProgress) { it(0.06f) }
        compose.waitForIdle()
        assertEquals(0.04f, editor.current.spacing, 1e-4f)
        assertEquals(0.06f, editor.current.corners, 1e-4f)

        compose.onNodeWithContentDescription(context.getString(R.string.collage_tool_background)).performClick()
        compose.onNodeWithContentDescription("Black").performClick()
        compose.waitForIdle()
        assertEquals(CollageBackground.BLACK, editor.current.background)
        assertEquals(CollageState.BLACK, editor.current.backgroundColor)
        compose.onNodeWithContentDescription("Theme: tertiary").assertExists()
    }

    @Test
    fun `enregistrer, ou enregistrer et partager`() {
        show()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithContentDescription("Save and share").performClick()
        compose.waitForIdle()
        assertEquals(listOf(false, true), saves)
    }

    @Test
    fun `fermer un collage modifié demande confirmation, pas un collage intact`() {
        show()
        compose.onNodeWithContentDescription(context.getString(R.string.action_close)).performClick()
        compose.waitForIdle()
        assertEquals(1, closed)

        compose.onNodeWithContentDescription("Pinwheel", substring = true).assertDoesNotExist()
        editor.setAspect(CollageAspect.LANDSCAPE)
        compose.onNodeWithContentDescription(context.getString(R.string.action_close)).performClick()
        compose.onNodeWithText("Discard this collage?").assertExists()
        compose.onNodeWithText("Discard").performClick()
        compose.waitForIdle()
        assertEquals(2, closed)
    }
}
