package app.focale.ml

import app.focale.core.model.MediaFilter
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchQueryParserTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 10, 19)
    private fun ms(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test
    fun `un mot français trouve l'étiquette anglaise`() {
        val query = SearchQueryParser.parse("plage", today, zone)
        assertEquals(1, query.terms.size)
        assertTrue("Beach" in query.terms.single().labels)
    }

    @Test
    fun `chien couvre les races détectées`() {
        val labels = SearchQueryParser.parse("chiens", today, zone).terms.single().labels
        assertTrue("Dog" in labels)
        assertTrue("Dalmatian" in labels)
        assertTrue("Basset hound" in labels)
    }

    @Test
    fun `année et mois deviennent une plage de dates`() {
        val query = SearchQueryParser.parse("chat juillet 2022", today, zone)
        assertEquals(ms(LocalDate.of(2022, 7, 1)), query.dateFrom)
        assertEquals(ms(LocalDate.of(2022, 8, 1)), query.dateTo)
        assertTrue("Cat" in query.terms.single().labels)
    }

    @Test
    fun `un mois seul filtre toutes les années`() {
        val query = SearchQueryParser.parse("août", today, zone)
        assertEquals(8, query.month)
        assertNull(query.dateFrom)
        assertTrue(query.terms.isEmpty())
    }

    @Test
    fun `dates numériques et relatives`() {
        val french = SearchQueryParser.parse("14/07/2021", today, zone)
        assertEquals(ms(LocalDate.of(2021, 7, 14)), french.dateFrom)
        val iso = SearchQueryParser.parse("2021-07-14", today, zone)
        assertEquals(ms(LocalDate.of(2021, 7, 15)), iso.dateTo)
        val yesterday = SearchQueryParser.parse("hier", today, zone)
        assertEquals(ms(today.minusDays(1)), yesterday.dateFrom)
        val todayQuery = SearchQueryParser.parse("aujourd'hui", today, zone)
        assertEquals(ms(today), todayQuery.dateFrom)
    }

    @Test
    fun `mots de type et favoris`() {
        val query = SearchQueryParser.parse("vidéos favorites", today, zone)
        assertEquals(MediaFilter.VIDEOS, query.filter)
        assertTrue(query.favoritesOnly)
        assertTrue(query.terms.isEmpty())
    }

    @Test
    fun `les mots vides sont ignorés et un pays est reconnu`() {
        val query = SearchQueryParser.parse("photos de la France", today, zone)
        assertEquals(MediaFilter.PHOTOS, query.filter)
        val term = query.terms.single()
        assertTrue("FR" in term.countries)
        assertEquals("france", term.place)
    }

    @Test
    fun `requête vide`() {
        assertTrue(SearchQueryParser.isEmpty(SearchQueryParser.parse("  de la  ", today, zone)))
    }

    private val people = mapOf(1L to "Hélène Dupont", 2L to "Marc", 3L to "Avril")

    @Test
    fun `le nom d'une personne trouve ses photos, sans accents ni casse`() {
        val term = SearchQueryParser.parse("helene", today, zone, people).terms.single()
        assertEquals(listOf(1L), term.personIds)
        // Les autres voies restent possibles (nom de fichier, texte reconnu).
        assertEquals("helene", term.text)
        // Nom complet : chaque mot désigne la même personne, combinés par ET.
        val full = SearchQueryParser.parse("Hélène Dupont plage", today, zone, people)
        assertEquals(listOf(listOf(1L), listOf(1L), emptyList()), full.terms.map { it.personIds })
    }

    @Test
    fun `un début de nom suffit, sans retenir une seule lettre`() {
        assertEquals(listOf(2L), SearchQueryParser.parse("mar", today, zone, people).terms.single().personIds)
        assertEquals(emptyList<Long>(), SearchQueryParser.matchPeople("m", people))
        assertTrue(SearchQueryParser.parse("mar", today, zone).terms.single().personIds.isEmpty())
    }

    @Test
    fun `un prénom l'emporte sur le mois homonyme`() {
        val withPerson = SearchQueryParser.parse("avril", today, zone, people)
        assertNull(withPerson.month)
        assertEquals(listOf(3L), withPerson.terms.single().personIds)
        // Sans personne de ce nom, « avril » reste le mois.
        assertEquals(4, SearchQueryParser.parse("avril", today, zone).month)
    }
}
