package app.focale.ui.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import app.focale.edit.ImageFrame
import app.focale.edit.NormRect
import app.focale.edit.PhotoPlacement
import app.focale.edit.TextBackground
import app.focale.edit.TextItem
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
 * Outil Texte de bout en bout, monté comme dans l'éditeur : gestes réels sur la couche de
 * l'aperçu, saisie, panneau et historique ([TextController], [EditHistory]), en graphismes natifs
 * (vraie mise en page du texte, dont dépend la prise au doigt). Densité 1 : 1 dp = 1 px ; l'image
 * pivotée entière (200 × 300 px) est posée en (100, 50) d'un aperçu de 400 × 400. Écran de
 * 600 dp de large : à 400 dp, Robolectric remesure sans fin la fenêtre d'un dialogue qui contient
 * un champ de texte (voir AlbumOptionsTest).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w600dp-h1200dp-mdpi")
class TextToolTest {

    @get:Rule
    val compose = createComposeRule()

    private val history = EditHistory()
    private val tool = TextController(history)
    private val frame = ImageFrame(100f, 50f, 200f, 300f)
    private val viewport = EditorViewport(frame, Rect(100f, 50f, 300f, 350f), PhotoPlacement(frame, angle = 0f, bleed = 1f))

    private val texts: List<TextItem> get() = history.current.texts

    private fun show() {
        compose.setContent {
            val state by history.state.collectAsState()
            val selected by tool.selected.collectAsState()
            val draft by tool.draft.collectAsState()
            val options by tool.options.collectAsState()
            val layouts = rememberTextLayoutCache()
            Column(Modifier.width(400.dp)) {
                Box(Modifier.size(400.dp).testTag(PREVIEW)) {
                    TextOverlay(
                        viewport = viewport,
                        texts = state.texts,
                        layouts = layouts,
                        selected = selected,
                        onAdd = tool::startNew,
                        onEdit = tool::startEdit,
                        onSelect = tool::select,
                        onGestureStart = tool::beginGesture,
                        onTransform = tool::transform,
                        onDelete = tool::delete,
                    )
                }
                TextPanel(
                    options = options,
                    ink = options.ink,
                    hasSelection = selected != null,
                    onAdd = tool::startNewCentered,
                    onInk = { tool.setInk(it, INK) },
                    onBackground = tool::setBackground,
                    onBold = tool::setBold,
                    onSizeStart = tool::beginSize,
                    onSize = tool::setSize,
                    onDelete = tool::deleteSelected,
                )
            }
            draft?.let { current ->
                TextInputDialog(
                    draft = current,
                    onConfirm = { tool.confirm(it, INK) },
                    onDelete = {
                        tool.cancel()
                        current.id?.let(tool::delete)
                    },
                    onDismiss = tool::cancel,
                )
            }
        }
    }

    /** Texte « Bonjour » au centre de l'image, déjà validé (et donc sélectionné). */
    private fun addBonjour() {
        tool.startNew(0.5f, 0.5f)
        tool.confirm("Bonjour", INK)
    }

    @Test
    fun `un appui sur la photo ajoute un texte à cet endroit, sur plusieurs lignes`() {
        show()
        compose.onNodeWithTag(PREVIEW).performTouchInput { click(Offset(160f, 230f)) }
        compose.onNodeWithTag(TEXT_FIELD_TAG).performTextInput("Bonjour\nà tous")
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        val item = texts.single()
        assertEquals("Bonjour\nà tous", item.text)
        assertEquals(0.3f, item.x, 1e-4f)
        assertEquals(0.6f, item.y, 1e-4f)
        assertEquals(INK, item.color)
        assertEquals(TextOptions().background, item.background)
        assertNull(tool.draft.value)
        // Le nouveau texte est sélectionné, et son ajout s'annule.
        assertEquals(item.id, tool.selected.value)
        history.undo()
        assertTrue(texts.isEmpty())
    }

    @Test
    fun `une saisie annulée ou vide n'ajoute rien, un appui hors de la photo non plus`() {
        show()
        compose.onNodeWithTag(PREVIEW).performTouchInput { click(Offset(200f, 200f)) }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag(PREVIEW).performTouchInput { click(Offset(200f, 200f)) }
        compose.onNodeWithTag(TEXT_FIELD_TAG).performTextInput("   ")
        // Rien à ajouter : « OK » reste inactif.
        compose.onNodeWithText("OK").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag(PREVIEW).performTouchInput { click(Offset(50f, 20f)) }
        compose.waitForIdle()
        assertTrue(texts.isEmpty())
        assertNull(tool.draft.value)
        assertFalse(history.canUndo.value)
    }

    @Test
    fun `un appui sur un texte le modifie, le vider le supprime, annuler revient en arrière`() {
        addBonjour()
        show()
        val id = texts.single().id
        compose.onNodeWithTag(PREVIEW).performTouchInput { click(Offset(205f, 203f)) }
        compose.onNodeWithTag(TEXT_FIELD_TAG).performTextReplacement("Salut")
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertEquals("Salut", texts.single().text)
        assertEquals(id, texts.single().id)
        compose.onNodeWithTag(PREVIEW).performTouchInput { click(Offset(200f, 200f)) }
        compose.onNodeWithTag(TEXT_FIELD_TAG).performTextReplacement("")
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertTrue(texts.isEmpty())
        history.undo()
        assertEquals("Salut", texts.single().text)
        history.undo()
        assertEquals("Bonjour", texts.single().text)
    }

    @Test
    fun `Supprimer dans la saisie retire le texte`() {
        addBonjour()
        show()
        compose.onNodeWithTag(PREVIEW).performTouchInput { click(Offset(200f, 200f)) }
        compose.onNodeWithText("Delete").performClick()
        compose.waitForIdle()
        assertTrue(texts.isEmpty())
        assertNull(tool.draft.value)
        history.undo()
        assertEquals("Bonjour", texts.single().text)
    }

    @Test
    fun `glisser déplace le texte, en une seule étape d'historique`() {
        addBonjour()
        show()
        compose.onNodeWithTag(PREVIEW).performTouchInput { swipe(Offset(200f, 200f), Offset(250f, 260f), durationMillis = 300) }
        compose.waitForIdle()
        val moved = texts.single()
        assertEquals(0.75f, moved.x, 1e-3f)
        assertEquals(0.7f, moved.y, 1e-3f)
        assertNull(tool.draft.value)
        history.undo()
        assertEquals(0.5f, texts.single().x, 1e-6f)
        assertEquals(0.5f, texts.single().y, 1e-6f)
        history.redo()
        assertEquals(0.75f, texts.single().x, 1e-3f)
    }

    @Test
    fun `glisser hors des textes ne déplace rien et n'ouvre pas de saisie`() {
        addBonjour()
        show()
        compose.onNodeWithTag(PREVIEW).performTouchInput { swipe(Offset(130f, 300f), Offset(280f, 320f), durationMillis = 300) }
        compose.waitForIdle()
        assertEquals(0.5f, texts.single().x, 0f)
        assertNull(tool.draft.value)
    }

    @Test
    fun `pincer à deux doigts agrandit et fait tourner le texte`() {
        addBonjour()
        show()
        val size = texts.single().size
        compose.onNodeWithTag(PREVIEW).performTouchInput {
            pinch(Offset(190f, 200f), Offset(170f, 200f), Offset(210f, 200f), Offset(230f, 200f), durationMillis = 300)
        }
        compose.waitForIdle()
        val pinched = texts.single()
        // Doigts de 20 à 60 px d'écart : trois fois plus grand ; ni déplacé ni tourné.
        assertEquals(3f * size, pinched.size, 0.15f * size)
        assertEquals(0.5f, pinched.x, 0.01f)
        assertEquals(0f, pinched.rotation, 1f)
        // Puis un quart de tour des doigts autour du texte.
        compose.onNodeWithTag(PREVIEW).performTouchInput {
            pinch(Offset(170f, 200f), Offset(200f, 170f), Offset(230f, 200f), Offset(200f, 230f), durationMillis = 300)
        }
        compose.waitForIdle()
        assertEquals(90f, texts.single().rotation, 3f)
        assertNull(tool.draft.value)
        history.undo()
        history.undo()
        assertEquals(size, texts.single().size, 1e-6f)
        assertEquals(0f, texts.single().rotation, 0f)
    }

    @Test
    fun `la corbeille supprime le texte sélectionné, et annuler le rétablit`() {
        addBonjour()
        show()
        compose.onNodeWithContentDescription("Delete text").performClick()
        compose.waitForIdle()
        assertTrue(texts.isEmpty())
        assertNull(tool.selected.value)
        history.undo()
        tool.sync()
        assertEquals("Bonjour", texts.single().text)
    }

    @Test
    fun `chaque texte est un élément accessible qui se modifie et se supprime`() {
        addBonjour()
        show()
        compose.onNodeWithContentDescription("Text: “Bonjour”").performCustomAccessibilityActionWithLabel("Delete")
        compose.waitForIdle()
        assertTrue(texts.isEmpty())
        history.undo()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Text: “Bonjour”").performClick()
        compose.waitForIdle()
        assertEquals(texts.single().id, tool.draft.value?.id)
        compose.onNodeWithTag(TEXT_FIELD_TAG).performTextReplacement("Bonsoir")
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertEquals("Bonsoir", texts.single().text)
    }

    @Test
    fun `le bouton Ajouter ouvre la saisie au centre de la zone visible`() {
        history.commit(history.current.copy(crop = NormRect(0.2f, 0.2f, 0.6f, 0.4f)))
        show()
        compose.onNodeWithContentDescription("Add text").performClick()
        compose.onNodeWithTag(TEXT_FIELD_TAG).performTextInput("Ici")
        compose.onNodeWithText("OK").performClick()
        compose.waitForIdle()
        assertEquals(0.4f, texts.single().x, 1e-5f)
        assertEquals(0.3f, texts.single().y, 1e-5f)
    }

    @Test
    fun `un réglage changé s'applique au texte sélectionné, en une étape chacun`() {
        addBonjour()
        show()
        compose.onNodeWithContentDescription("Bold").performClick()
        compose.onNodeWithContentDescription("Box").performClick()
        compose.waitForIdle()
        val item = texts.single()
        assertTrue(item.bold)
        assertEquals(TextBackground.BOX, item.background)
        // Les réglages valent aussi pour le prochain texte.
        assertEquals(TextBackground.BOX, tool.options.value.background)
        history.undo()
        assertEquals(TextOptions().background, texts.single().background)
        assertTrue(texts.single().bold)
    }

    private companion object {
        const val PREVIEW = "apercu"
        const val INK = 0xFFE53935.toInt()
    }
}
