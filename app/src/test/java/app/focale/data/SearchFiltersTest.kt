package app.focale.data

import app.focale.core.model.DateBucket
import app.focale.core.model.MediaFilter
import app.focale.core.model.SearchTerm
import app.focale.ml.SearchQueryParser
import app.focale.people.photosTogether
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pastilles de la recherche : ajout, combinaison avec le texte, état sauvegardé et historique. */
class SearchFiltersTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 10, 21)
    private fun ms(year: Int, month: Int, day: Int) = LocalDate.of(year, month, day).atStartOfDay(zone).toInstant().toEpochMilli()
    private fun parse(text: String) = SearchQueryParser.parse(text, today, zone)

    private val alice = SearchChip.Person(1, "Alice")
    private val bob = SearchChip.Person(2, "Bob")
    private val paris = SearchChip.Place("Paris", "FR")
    private val lyon = SearchChip.Place("Lyon", "FR")
    private val july2023 = SearchChip.Period.month(YearMonth.of(2023, 7))
    private val videos = SearchChip.Type(MediaFilter.VIDEOS)
    private val photos = SearchChip.Type(MediaFilter.PHOTOS)

    @Test
    fun `une personne s'ajoute aux autres sans doublon, lieu période et type se remplacent sur place`() {
        val filters = SearchFilters() + alice + paris + bob + alice + lyon + videos + photos
        assertEquals(listOf(alice, lyon, bob, photos), filters.chips)
        assertEquals(listOf(lyon, bob, photos), (filters - alice).chips)
    }

    @Test
    fun `des pastilles sans texte forment une recherche`() {
        assertTrue(SearchFilters().isEmpty)
        assertTrue(SearchFilters(text = "  ").isEmpty)
        assertFalse((SearchFilters() + videos).isEmpty)
        assertFalse(SearchFilters(text = "plage").isEmpty)
    }

    @Test
    fun `les pastilles restreignent par ET la requête du texte`() {
        val query = (SearchFilters("plage") + alice + bob + paris + july2023 + videos).query(parse("plage"), zone)
        // « plage », Alice, Bob, lieu exact, pays : un terme chacun.
        assertEquals(5, query.terms.size)
        assertTrue("Beach" in query.terms[0].labels)
        assertEquals(listOf(listOf(1L), listOf(2L)), query.terms.subList(1, 3).map { it.personIds })
        assertEquals(SearchTerm(place = "paris", exactPlace = true), query.terms[3])
        assertEquals(SearchTerm(countries = listOf("FR")), query.terms[4])
        assertEquals(ms(2023, 7, 1), query.dateFrom)
        assertEquals(ms(2023, 8, 1), query.dateTo)
        assertEquals(MediaFilter.VIDEOS, query.filter)
        assertNull(query.mediaIds)
    }

    @Test
    fun `plusieurs personnes donnent la requête des photos ensemble`() {
        assertEquals(photosTogether(listOf(1L, 2L)), (SearchFilters() + alice + bob).query(zone = zone))
    }

    @Test
    fun `la période s'ajoute à celle du texte, et deux périodes disjointes ne donnent rien`() {
        // « juillet » (tous les ans) et la pastille 2023 : juillet 2023.
        val july = (SearchFilters("juillet") + SearchChip.Period.year(2023)).query(parse("juillet"), zone)
        assertEquals(7, july.month)
        assertEquals(ms(2023, 1, 1), july.dateFrom)
        assertEquals(ms(2024, 1, 1), july.dateTo)
        // « 14/07/2023 » et juillet 2023 : le 14 seulement.
        val day = (SearchFilters("14/07/2023") + july2023).query(parse("14/07/2023"), zone)
        assertEquals(ms(2023, 7, 14), day.dateFrom)
        assertEquals(ms(2023, 7, 15), day.dateTo)
        // « 2022 » et 2023 : aucune date commune (la requête SQL ne renvoie rien).
        val disjoint = (SearchFilters("2022") + SearchChip.Period.year(2023)).query(parse("2022"), zone)
        assertTrue(disjoint.dateFrom!! >= disjoint.dateTo!!)
    }

    @Test
    fun `types ou mois inconciliables donnent une requête vide`() {
        assertEquals(emptyList<Long>(), (SearchFilters("vidéos") + photos).query(parse("vidéos"), zone).mediaIds)
        assertEquals(emptyList<Long>(), (SearchFilters("août") + SearchChip.Period.everyYear(7)).query(parse("août"), zone).mediaIds)
        // Compatibles : les GIF sont des photos, le même mois ne change rien.
        assertEquals(MediaFilter.GIFS, (SearchFilters("photos") + SearchChip.Type(MediaFilter.GIFS)).query(parse("photos"), zone).filter)
        assertNull((SearchFilters("juillet") + SearchChip.Period.everyYear(7)).query(parse("juillet"), zone).mediaIds)
    }

    @Test
    fun `intersection des types de média`() {
        assertEquals(MediaFilter.VIDEOS, intersect(MediaFilter.ALL, MediaFilter.VIDEOS))
        assertEquals(MediaFilter.VIDEOS, intersect(MediaFilter.VIDEOS, MediaFilter.ALL))
        assertEquals(MediaFilter.GIFS, intersect(MediaFilter.PHOTOS, MediaFilter.GIFS))
        assertEquals(MediaFilter.SCREENSHOTS, intersect(MediaFilter.SCREENSHOTS, MediaFilter.PHOTOS))
        assertNull(intersect(MediaFilter.VIDEOS, MediaFilter.PHOTOS))
        assertNull(intersect(MediaFilter.GIFS, MediaFilter.VIDEOS))
    }

    @Test
    fun `une période se construit dans l'ordre, quel que soit celui des jours choisis`() {
        val period = SearchChip.Period.days(LocalDate.of(2024, 8, 12), LocalDate.of(2024, 8, 3))
        assertEquals(LocalDate.of(2024, 8, 3), period.firstDay)
        assertEquals(LocalDate.of(2024, 8, 12), period.lastDay)
        assertEquals(SearchChip.Period.days(LocalDate.of(2023, 1, 1), LocalDate.of(2023, 12, 31)), SearchChip.Period.year(2023))
    }

    @Test
    fun `l'état sauvegardé se relit à l'identique`() {
        val filters = SearchFilters("plage", listOf(alice, paris, july2023, videos))
        assertEquals(filters, SearchFilters.decode(filters.encode()))
        val translated = SearchFilters(chips = listOf(SearchChip.Place("Köln", "DE", french = "Cologne", english = "Cologne")))
        assertEquals(translated, SearchFilters.decode(translated.encode()))
        assertEquals(SearchFilters(text = "x", chips = listOf(SearchChip.Period.everyYear(8))), SearchFilters.decode(SearchFilters("x", listOf(SearchChip.Period.everyYear(8))).encode()))
        assertEquals(SearchFilters(), SearchFilters.decode(null))
        assertEquals(SearchFilters(), SearchFilters.decode("pas du JSON"))
    }

    @Test
    fun `historique, le texte seul comme avant et la recherche combinée relisible`() {
        assertEquals("plage", SearchFilters(" plage ").historyKey)
        assertEquals(SearchFilters("plage"), SearchFilters.fromHistory("plage"))
        val combined = SearchFilters("plage ", listOf(alice, july2023))
        val key = combined.historyKey
        assertTrue(key.startsWith("{"))
        assertEquals(combined.copy(text = "plage"), SearchFilters.fromHistory(key))
        // Entrée d'une version future, illisible : écartée plutôt qu'affichée en JSON.
        assertNull(SearchFilters.fromHistory("{\"chips\":[{\"kind\":\"album\",\"id\":3}]}"))
    }

    @Test
    fun `une personne disparue est retrouvée par son nom, sans deviner entre deux homonymes`() {
        val entry = SearchFilters(chips = listOf(SearchChip.Person(9, "Hélène"), bob, SearchChip.Person(7, null)))
        val relinked = entry.relinkPeople(known = setOf(2L, 3L), named = mapOf(3L to "Helene", 2L to "Bob"))
        assertEquals(listOf(SearchChip.Person(3, "Helene"), bob, SearchChip.Person(7, null)), relinked.chips)
        val ambiguous = entry.relinkPeople(known = setOf(2L), named = mapOf(3L to "Hélène", 4L to "helene", 2L to "Bob"))
        assertEquals(entry.chips, ambiguous.chips)
        // Déjà dans la recherche : pas de doublon.
        val duplicate = SearchFilters(chips = listOf(SearchChip.Person(9, "Bob"), bob)).relinkPeople(setOf(2L), mapOf(2L to "Bob"))
        assertEquals(listOf(bob), duplicate.chips)
    }

    @Test
    fun `périodes proposées, années puis mois récents ayant des médias`() {
        val buckets = listOf(
            DateBucket(ms(2024, 3, 10), 4),
            DateBucket(ms(2024, 1, 2), 2),
            DateBucket(ms(2023, 7, 14), 9),
            DateBucket(ms(2021, 12, 31), 0),
        )
        val choices = PeriodChoices.from(buckets, zone, recentMonths = 2)
        assertEquals(listOf(2024, 2023), choices.years)
        assertEquals(listOf(YearMonth.of(2024, 3), YearMonth.of(2024, 1)), choices.months)
    }
}
