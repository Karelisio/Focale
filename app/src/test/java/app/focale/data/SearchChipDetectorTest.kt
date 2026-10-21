package app.focale.data

import app.focale.core.model.MediaFilter
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pastilles repérées dans la saisie (mêmes règles que l'analyse du texte) et texte restant. */
class SearchChipDetectorTest {

    private val zone = ZoneId.of("Europe/Paris")
    private val today = LocalDate.of(2026, 10, 21)
    private val people = mapOf(1L to "Alice Martin", 2L to "Bob", 3L to "Avril")
    private val places = listOf(place("Paris"), place("Saint-Malo"), place("Parisot"), place("Venice", "IT"))

    private fun place(name: String, country: String = "FR") = PlaceSuggestion(name, country, count = 3, cover = null)
    private fun detect(text: String) = SearchChipDetector.detect(text, people, places, today, zone)

    @Test
    fun `période, type, personne et lieux sont repérés dans la saisie`() {
        assertEquals(
            listOf(
                ChipSuggestion(SearchChip.Period.month(YearMonth.of(2022, 7)), listOf("juillet", "2022")),
                ChipSuggestion(SearchChip.Type(MediaFilter.VIDEOS), listOf("vidéos")),
                ChipSuggestion(SearchChip.Person(1, "Alice Martin"), listOf("alice")),
                ChipSuggestion(SearchChip.Place("Paris", "FR"), listOf("paris")),
                ChipSuggestion(SearchChip.Place("Parisot", "FR"), listOf("paris")),
            ),
            detect("alice plage juillet 2022 vidéos paris"),
        )
    }

    @Test
    fun `jour, année, mois de toutes les années et aujourd'hui`() {
        assertEquals(SearchChip.Period.days(LocalDate.of(2021, 7, 14), LocalDate.of(2021, 7, 14)), detect("14/07/2021").single().chip)
        assertEquals(SearchChip.Period.year(2023), detect("2023").single().chip)
        assertEquals(SearchChip.Period.everyYear(8), detect("août").single().chip)
        assertEquals(SearchChip.Period.days(today, today), detect("aujourd'hui").single().chip)
    }

    @Test
    fun `un prénom l'emporte sur le mois homonyme et un nom complet ne donne qu'une pastille`() {
        assertEquals(listOf(ChipSuggestion(SearchChip.Person(3, "Avril"), listOf("avril"))), detect("avril"))
        assertEquals(listOf(ChipSuggestion(SearchChip.Person(1, "Alice Martin"), listOf("Alice", "Martin"))), detect("Alice Martin"))
    }

    @Test
    fun `un lieu se trouve par le début de ses mots`() {
        assertEquals(listOf(ChipSuggestion(SearchChip.Place("Saint-Malo", "FR"), listOf("saint", "malo"))), detect("saint malo"))
        // « nice » ne trouve pas « Venice ».
        assertTrue(detect("nice").isEmpty())
    }

    @Test
    fun `mots vides et favoris ne donnent pas de pastille`() {
        assertTrue(detect("").isEmpty())
        assertTrue(detect("de la").isEmpty())
        assertTrue(detect("favoris").isEmpty())
    }

    @Test
    fun `reprendre une suggestion retire ses mots du texte`() {
        val text = "photos de Alice Martin à la plage"
        val alice = detect(text).first { it.chip is SearchChip.Person }
        assertEquals("photos de à la plage", SearchChipDetector.remainingText(text, alice, people))
        // Il ne reste que des mots vides : plus rien à chercher.
        val period = detect("en juillet 2022").single()
        assertEquals("", SearchChipDetector.remainingText("en juillet 2022", period, people))
    }
}
