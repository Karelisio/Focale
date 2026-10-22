package app.focale.ui.collage

import app.focale.collage.CellFrame
import app.focale.collage.CollageAspect
import app.focale.collage.CollageBackground
import app.focale.collage.CollageState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Réglage d'un collage et son historique : chaque choix forme une étape, un geste (curseur,
 * cadrage) une seule ; l'échange de deux cases emmène le cadrage de chaque photo ; annuler et
 * rétablir parcourent les étapes.
 */
class CollageEditorTest {

    private val editor = CollageEditor(listOf(11L, 12L, 13L, 14L))

    @Test
    fun `nouveau collage, première disposition pour ce nombre de photos, fond blanc, rien à annuler`() {
        val state = editor.current
        assertEquals("4-grid", state.layout.id)
        assertEquals(listOf(11L, 12L, 13L, 14L), state.photos)
        assertEquals(CollageAspect.SQUARE, state.aspect)
        assertEquals(CollageBackground.WHITE, state.background)
        assertFalse(editor.canUndo.value)
        assertEquals(editor.layouts.map { it.id }.first(), state.layoutId)
    }

    @Test
    fun `choisir une disposition, une proportion et un fond, puis annuler et rétablir`() {
        editor.selectLayout("4-large-top")
        editor.setAspect(CollageAspect.STORY)
        editor.setBackground(CollageBackground.PRIMARY, 0xFF336699.toInt())
        assertEquals("4-large-top", editor.current.layoutId)
        assertEquals(0xFF336699.toInt(), editor.current.backgroundColor)

        assertTrue(editor.undo())
        assertEquals(CollageBackground.WHITE, editor.current.background)
        assertTrue(editor.undo())
        assertEquals(CollageAspect.SQUARE, editor.current.aspect)
        assertTrue(editor.redo())
        assertEquals(CollageAspect.STORY, editor.current.aspect)
        assertTrue(editor.canRedo.value)

        // Une disposition d'un autre nombre de photos est ignorée.
        editor.selectLayout("9-grid")
        assertEquals("4-large-top", editor.current.layoutId)
    }

    @Test
    fun `un glissement de curseur forme une seule étape, borné au maximum`() {
        editor.beginGesture()
        listOf(0.01f, 0.03f, 0.2f).forEach(editor::setSpacing)
        assertEquals(CollageState.MAX_SPACING, editor.current.spacing, 0f)
        assertTrue(editor.undo())
        assertEquals(CollageState.DEFAULT_SPACING, editor.current.spacing, 0f)
        assertFalse(editor.canUndo.value)
    }

    @Test
    fun `l'échange emmène le cadrage de chaque photo, et s'annule`() {
        // Photo 11 agrandie dans la case 0 (300 × 300, photo 4 000 × 3 000).
        editor.beginGesture()
        editor.frame(0, panX = 0f, panY = 0f, zoom = 2f, focusX = 150f, focusY = 150f, 300f, 300f, 4000f, 3000f)
        val zoomed = editor.current.frame(11L)
        assertEquals(2f, zoomed.zoom, 1e-6f)

        editor.swap(0, 3)
        assertEquals(listOf(14L, 12L, 13L, 11L), editor.current.photos)
        assertEquals(zoomed, editor.current.frame(11L))
        assertEquals(CellFrame.Default, editor.current.frame(14L))

        assertTrue(editor.undo())
        assertEquals(listOf(11L, 12L, 13L, 14L), editor.current.photos)
        assertTrue(editor.undo())
        assertEquals(CellFrame.Default, editor.current.frame(11L))
    }

    @Test
    fun `agrandir pas à pas puis recentrer`() {
        editor.zoomStep(1, 1.5f, 300f, 300f, 4000f, 3000f)
        assertEquals(1.5f, editor.current.frame(12L).zoom, 1e-6f)
        editor.resetFrame(1)
        assertEquals(CellFrame.Default, editor.current.frame(12L))
        assertTrue(editor.undo())
        assertEquals(1.5f, editor.current.frame(12L).zoom, 1e-6f)
    }

    @Test
    fun `un échange d'une case avec elle-même n'ajoute pas d'étape`() {
        editor.swap(2, 2)
        assertFalse(editor.canUndo.value)
    }
}
