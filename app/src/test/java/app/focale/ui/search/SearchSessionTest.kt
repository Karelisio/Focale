package app.focale.ui.search

import androidx.lifecycle.SavedStateHandle
import app.focale.core.model.MediaFilter
import app.focale.core.model.MediaQuery
import app.focale.data.SearchChip
import app.focale.data.SearchFilters
import app.focale.ml.SearchQueryParser
import app.focale.people.photosTogether
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recherche en cours : requête immédiate des pastilles, texte en analyse, état sauvegardé, historique. */
class SearchSessionTest {

    private val handle = SavedStateHandle()
    private val remembered = mutableListOf<Pair<String, String?>>()
    private val alice = SearchChip.Person(1, "Alice")
    private val videos = SearchChip.Type(MediaFilter.VIDEOS)

    private fun TestScope.session(
        state: SavedStateHandle = handle,
        resolve: suspend (String) -> MediaQuery = { SearchQueryParser.parse(it) },
    ) = SearchSession(state, backgroundScope, resolve, remember = { key, replacing -> remembered += key to replacing })

    @Test
    fun `une pastille seule donne aussitôt une requête, la recherche vidée n'en a plus`() = runTest(UnconfinedTestDispatcher()) {
        val session = session()
        assertNull(session.query.value)
        session.update(session.filters.value + alice)
        assertEquals(photosTogether(listOf(1L)), session.query.value)
        session.update(session.filters.value + videos)
        assertEquals(MediaFilter.VIDEOS, session.query.value?.filter)
        session.update(SearchFilters())
        assertNull(session.query.value)
    }

    @Test
    fun `pendant l'analyse d'un texte validé, la requête précédente reste affichée`() = runTest(UnconfinedTestDispatcher()) {
        val analysis = CompletableDeferred<MediaQuery>()
        val session = session(resolve = { analysis.await() })
        session.update(SearchFilters() + alice)
        val chipsOnly = session.query.value
        session.update(session.filters.value.copy(text = "plage"))
        assertTrue(session.searching.value)
        assertEquals(chipsOnly, session.query.value)

        analysis.complete(SearchQueryParser.parse("plage"))
        runCurrent()
        assertFalse(session.searching.value)
        assertEquals((SearchFilters("plage") + alice).query(SearchQueryParser.parse("plage")), session.query.value)
    }

    @Test
    fun `la recherche revient telle quelle après la mort du processus`() = runTest(UnconfinedTestDispatcher()) {
        val first = session()
        first.update(SearchFilters("plage") + alice + SearchChip.Period.year(2023))
        // Nouveau processus : seul l'état sauvegardé subsiste.
        val restored = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
        val second = session(state = restored)
        assertEquals(first.filters.value, second.filters.value)
        assertEquals(first.query.value, second.query.value)
    }

    @Test
    fun `l'historique garde une entrée par recherche, remplacée à mesure qu'elle s'affine`() = runTest {
        val session = session()
        session.update(SearchFilters() + alice)
        advanceTimeBy(2_000)
        session.update(session.filters.value + videos)
        advanceTimeBy(500)
        session.update(session.filters.value.copy(text = "plage"))
        advanceTimeBy(2_000)
        val first = (SearchFilters() + alice).historyKey
        val refined = (SearchFilters("plage") + alice + videos).historyKey
        assertEquals(listOf(first to null, refined to first), remembered)

        // Recherche quittée : la suivante a sa propre entrée.
        session.update(SearchFilters())
        session.update(SearchFilters("chat"))
        advanceTimeBy(2_000)
        assertEquals("chat" to null, remembered.last())
    }

    @Test
    fun `une recherche relancée depuis l'historique reprend son entrée`() = runTest {
        val session = session()
        session.restore(RecentSearch("plage", SearchFilters("plage")))
        advanceTimeBy(2_000)
        session.update(session.filters.value + alice)
        advanceTimeBy(2_000)
        assertEquals(listOf("plage" to "plage", (SearchFilters("plage") + alice).historyKey to "plage"), remembered)
    }

    @Test
    fun `sans résultat, chaque pastille puis le texte peuvent être retirés`() = runTest(UnconfinedTestDispatcher()) {
        val session = session()
        session.update(SearchFilters("plage") + alice + videos)
        val relaxations = session.relaxations.first()
        assertEquals(listOf(alice, videos, null), relaxations.map { it.first })
        assertEquals((SearchFilters("plage") + videos).query(SearchQueryParser.parse("plage")), relaxations[0].second)
        assertEquals((SearchFilters() + alice + videos).query(), relaxations[2].second)
        // Une seule pastille sans texte : la retirer quitterait la recherche.
        session.update(SearchFilters() + alice)
        assertEquals(emptyList<Pair<SearchChip?, MediaQuery>>(), session.relaxations.first())
    }
}
