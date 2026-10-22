package app.focale.ui.editor

import app.focale.edit.BlurArea
import app.focale.edit.BlurShape
import app.focale.edit.BlurStyle
import app.focale.edit.EditState
import app.focale.edit.FilterPreset
import app.focale.edit.NormRect
import app.focale.edit.TextItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Historique de l'éditeur et outils Texte et Flouter, sans interface : étapes, sélection, saisie. */
class EditHistoryTest {

    private val history = EditHistory()
    private val texts = TextController(history)
    private val blurs = BlurController(history)

    @Test
    fun `une action forme une étape, un geste une seule, un état inchangé aucune`() {
        history.commit(history.current.copy(filter = FilterPreset.MONO))
        history.commit(history.current.copy(filter = FilterPreset.MONO))
        history.remember()
        repeat(10) { history.update(history.current.copy(brightness = it / 20f)) }
        assertTrue(history.canUndo.value)
        history.undo()
        assertEquals(0f, history.current.brightness)
        assertEquals(FilterPreset.MONO, history.current.filter)
        history.undo()
        assertEquals(EditState(), history.current)
        assertFalse(history.undo())
        assertTrue(history.redo())
        assertTrue(history.canRedo.value)
        // Une nouvelle action efface ce qui pouvait être rétabli.
        history.commit(history.current.copy(filter = FilterPreset.SEPIA))
        assertFalse(history.canRedo.value)
    }

    @Test
    fun `l'historique garde les cinquante dernières étapes`() {
        repeat(60) { history.commit(history.current.copy(brightness = (it + 1) / 100f)) }
        var steps = 0
        while (history.undo()) steps++
        assertEquals(50, steps)
        assertEquals(0.1f, history.current.brightness, 1e-6f)
    }

    @Test
    fun `une identité n'est jamais réutilisée, même après annulation`() {
        texts.startNew(0.5f, 0.5f)
        texts.confirm("Un", INK)
        val first = history.current.texts.single().id
        history.undo()
        texts.startNew(0.5f, 0.5f)
        texts.confirm("Deux", INK)
        blurs.add(BlurArea.Box(BlurShape.RECTANGLE, 0.5f, 0.5f, 0.1f, 0.1f))
        val ids = history.current.texts.map(TextItem::id) + history.current.blurs.map { it.id } + first
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `annuler l'ajout d'un texte oublie sa sélection, et sa saisie ouverte`() {
        texts.startNew(0.2f, 0.3f)
        texts.confirm("Bonjour", INK)
        val id = texts.selected.value!!
        texts.startEdit(id)
        history.undo()
        texts.sync()
        assertNull(texts.selected.value)
        assertNull(texts.draft.value)
        // La sélection d'un texte disparu n'est pas acceptée.
        texts.select(id)
        assertNull(texts.selected.value)
    }

    @Test
    fun `le texte saisi est rogné, ses réglages viennent des options`() {
        texts.setBackground(app.focale.edit.TextBackground.BOX)
        texts.setBold(true)
        texts.setSize(0.12f)
        texts.startNew(0.4f, 0.6f)
        texts.confirm("  Été 2026 \n", INK)
        val item = history.current.texts.single()
        assertEquals("Été 2026", item.text)
        assertEquals(app.focale.edit.TextBackground.BOX, item.background)
        assertTrue(item.bold)
        assertEquals(0.12f, item.size, 1e-6f)
        assertEquals(0f, item.rotation)
        // Les réglages changés sans texte sélectionné ne forment aucune étape.
        history.undo()
        assertFalse(history.canUndo.value)
    }

    @Test
    fun `un geste de taille au curseur forme une seule étape`() {
        texts.startNew(0.5f, 0.5f)
        texts.confirm("Bonjour", INK)
        texts.beginSize()
        listOf(0.08f, 0.1f, 0.2f, 0.25f).forEach(texts::setSize)
        assertEquals(0.25f, history.current.texts.single().size, 1e-6f)
        history.undo()
        assertEquals(TextItem.DEFAULT_SIZE, history.current.texts.single().size, 1e-6f)
        // Taille bornée.
        texts.setSize(5f)
        assertEquals(TextItem.MAX_SIZE, texts.options.value.size, 1e-6f)
    }

    @Test
    fun `un texte déplacé reste dans le recadrage`() {
        history.commit(history.current.copy(crop = NormRect(0.25f, 0.25f, 0.75f, 0.75f)))
        texts.startNew(0.5f, 0.5f)
        texts.confirm("Bonjour", INK)
        val id = texts.selected.value!!
        texts.beginGesture(id)
        texts.move(id, 0.9f, -0.9f)
        val item = history.current.texts.single()
        assertEquals(0.75f, item.x, 1e-6f)
        assertEquals(0.25f, item.y, 1e-6f)
    }

    @Test
    fun `une zone déplacée garde son centre sur la photo, ses réglages suivent la sélection`() {
        blurs.setStyle(BlurStyle.PIXELATE)
        blurs.setIntensity(0.8f)
        blurs.add(BlurArea.Box(BlurShape.ELLIPSE, 0.5f, 0.5f, 0.1f, 0.1f))
        val zone = history.current.blurs.single()
        assertEquals(BlurStyle.PIXELATE, zone.style)
        assertEquals(0.8f, zone.intensity, 1e-6f)
        assertNull(blurs.selected.value)
        blurs.beginMove(zone.id)
        blurs.move(zone.id, 2f, -2f)
        val moved = history.current.blurs.single().area as BlurArea.Box
        assertEquals(1f, moved.x, 1e-6f)
        assertEquals(0f, moved.y, 1e-6f)
        // Le style changé vaut pour la zone sélectionnée, en une étape.
        blurs.setStyle(BlurStyle.BLUR)
        assertEquals(BlurStyle.BLUR, history.current.blurs.single().style)
        history.undo()
        assertEquals(BlurStyle.PIXELATE, history.current.blurs.single().style)
        history.undo()
        assertEquals(0.5f, (history.current.blurs.single().area as BlurArea.Box).x, 1e-6f)
    }

    @Test
    fun `plusieurs visages forment une seule étape, et rien ne s'ajoute sans visage`() {
        blurs.addAll(emptyList())
        assertFalse(history.canUndo.value)
        blurs.addAll(
            listOf(
                BlurArea.Box(BlurShape.ELLIPSE, 0.2f, 0.3f, 0.05f, 0.07f),
                BlurArea.Box(BlurShape.ELLIPSE, 0.6f, 0.3f, 0.05f, 0.07f),
            ),
        )
        assertEquals(2, history.current.blurs.size)
        assertNotEquals(history.current.blurs[0].id, history.current.blurs[1].id)
        history.undo()
        assertTrue(history.current.blurs.isEmpty())
    }

    private companion object {
        const val INK = 0xFFFFFFFF.toInt()
    }
}
