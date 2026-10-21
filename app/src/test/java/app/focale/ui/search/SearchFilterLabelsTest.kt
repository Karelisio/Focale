package app.focale.ui.search

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.focale.core.model.MediaFilter
import app.focale.data.SearchChip
import app.focale.data.SearchFilters
import app.focale.people.Person
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Libellés des pastilles et des recherches mémorisées, en anglais et en français. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SearchFilterLabelsTest {

    private val resources get() = ApplicationProvider.getApplicationContext<Context>().resources
    private val lea = Person(id = 1, name = "Léa", hidden = false, photoCount = 40, cover = null)
    private val unnamed = Person(id = 2, name = null, hidden = false, photoCount = 12, cover = null)

    private fun period(period: SearchChip.Period) = SearchFilterLabels.period(resources, period)
    private fun days(first: LocalDate, last: LocalDate) = period(SearchChip.Period.days(first, last))

    @Test
    fun `périodes en anglais`() {
        assertEquals("2023", period(SearchChip.Period.year(2023)))
        assertEquals("July 2022", period(SearchChip.Period.month(YearMonth.of(2022, 7))))
        assertEquals("Jul 14, 2021", days(LocalDate.of(2021, 7, 14), LocalDate.of(2021, 7, 14)))
        assertEquals("Aug 3 – Aug 12, 2024", days(LocalDate.of(2024, 8, 3), LocalDate.of(2024, 8, 12)))
        assertEquals("Dec 28, 2023 – Jan 3, 2024", days(LocalDate.of(2023, 12, 28), LocalDate.of(2024, 1, 3)))
        assertEquals("July, every year", period(SearchChip.Period.everyYear(7)))
    }

    @Test
    @Config(qualifiers = "fr")
    fun `périodes en français`() {
        assertEquals("2023", period(SearchChip.Period.year(2023)))
        assertEquals("Juillet 2022", period(SearchChip.Period.month(YearMonth.of(2022, 7))))
        assertEquals("14 juil. 2021", days(LocalDate.of(2021, 7, 14), LocalDate.of(2021, 7, 14)))
        assertEquals("3 août – 12 août 2024", days(LocalDate.of(2024, 8, 3), LocalDate.of(2024, 8, 12)))
        assertEquals("Juillet, toutes les années", period(SearchChip.Period.everyYear(7)))
    }

    @Test
    @Config(qualifiers = "fr")
    fun `pastilles et recherche mémorisée, lisibles`() {
        val people = mapOf(lea.id to lea, unnamed.id to unnamed)
        val filters = SearchFilters(
            text = "plage",
            chips = listOf(
                SearchChip.Person(1, "Lea"),
                SearchChip.Person(2, null),
                SearchChip.Person(9, "Tom"),
                SearchChip.Place("Lyon", "FR"),
                SearchChip.Period.year(2023),
                SearchChip.Type(MediaFilter.VIDEOS),
            ),
        )
        // Nom actuel, « Personne sans nom », ou nom mémorisé d'une personne disparue.
        assertEquals("plage · Léa · Personne sans nom · Tom · Lyon · 2023 · Vidéos", SearchFilterLabels.entry(resources, filters, people))
    }
}
