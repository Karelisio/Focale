package app.focale.backup

import app.focale.core.model.MediaFilter
import app.focale.data.SearchChip
import app.focale.data.SearchFilters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Historique de recherche d'une sauvegarde : personnes désignées sur ce téléphone, doublons reconnus. */
class SearchHistoryMergeTest {

    @Test
    fun `les personnes d'une recherche désignent celles recréées, les absentes plus aucune`() {
        val key = SearchFilters(text = "plage", chips = listOf(SearchChip.Person(3, "Alice"), SearchChip.Person(4, "Bob"))).historyKey
        val remapped = requireNotNull(SearchFilters.fromHistory(SearchHistoryMerge.remap(key) { mapOf(3L to 30L)[it] }))
        assertEquals("plage", remapped.text)
        // Bob, absent de la sauvegarde, sera retrouvé par son nom s'il existe sur ce téléphone.
        assertEquals(listOf(SearchChip.Person(30, "Alice"), SearchChip.Person(0, "Bob")), remapped.chips)
    }

    @Test
    fun `texte seul, pastilles sans personne et recherche illisible restent telles quelles`() {
        assertEquals("plage", SearchHistoryMerge.remap("plage") { 99 })
        val videos = SearchFilters(text = "chat", chips = listOf(SearchChip.Type(MediaFilter.VIDEOS))).historyKey
        assertEquals(videos, SearchHistoryMerge.remap(videos) { 99 })
        // Pastille d'une version ultérieure.
        val future = """{"chips":[{"kind":"robot","id":3}]}"""
        assertEquals(future, SearchHistoryMerge.remap(future) { 99 })
    }

    @Test
    fun `une même recherche a la même forme, quels que soient les identifiants de ses personnes`() {
        fun person(id: Long, name: String) = SearchFilters(chips = listOf(SearchChip.Person(id, name))).historyKey
        assertEquals(SearchHistoryMerge.canonical(person(3, "Alice")), SearchHistoryMerge.canonical(person(42, "Alice")))
        assertNotEquals(SearchHistoryMerge.canonical(person(3, "Alice")), SearchHistoryMerge.canonical(person(3, "Bob")))
        assertEquals("plage", SearchHistoryMerge.canonical("plage"))
    }
}
