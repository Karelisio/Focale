package app.focale.data

import android.app.Application
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MemoryTitlesTest {

    private val resources: Resources get() = ApplicationProvider.getApplicationContext<Application>().resources

    private val june = LocalDate.of(2023, 6, 10)

    private fun trip(place: String?, country: String?, first: LocalDate = june, last: LocalDate = june.plusDays(7)) =
        MemoryKind.Trip(yearsAgo = 3, place = place, country = country, firstDay = first, lastDay = last)

    private fun withPerson(yearsAgo: Int, name: String?) = MemoryKind.WithPerson(yearsAgo, personId = 1, name = name, face = null)

    @Test
    @Config(qualifiers = "fr")
    fun `titres en français, au singulier et au pluriel`() {
        assertEquals("Il y a 1 an", MemoryTitles.title(resources, MemoryKind.YearsAgo(1)))
        assertEquals("Il y a 1 an avec Alice", MemoryTitles.title(resources, withPerson(1, "Alice")))
        assertEquals("Il y a 2 ans avec Alice", MemoryTitles.title(resources, withPerson(2, "Alice")))
        assertEquals("Il y a 3 ans, ensemble", MemoryTitles.title(resources, withPerson(3, null)))
        assertEquals("Lisbonne · juin 2023", MemoryTitles.title(resources, trip("Lisbonne", "PT")))
        // Sur sa carte, un voyage tient sur deux lignes ; les autres gardent leur titre.
        assertEquals("Lisbonne\njuin 2023", MemoryTitles.cardTitle(resources, trip("Lisbonne", "PT")))
        assertEquals("Il y a 2 ans avec Alice", MemoryTitles.cardTitle(resources, withPerson(2, "Alice")))
    }

    @Test
    @Config(qualifiers = "fr")
    fun `voyage sans ville dominante titré par le pays, sinon voyage`() {
        assertEquals("Portugal · juin 2023", MemoryTitles.title(resources, trip(null, "PT")))
        assertEquals("Italie · juin 2023", MemoryTitles.title(resources, trip(null, "IT")))
        assertEquals("Voyage · juin 2023", MemoryTitles.title(resources, trip(null, null)))
    }

    @Test
    fun `titres en anglais`() {
        assertEquals("1 year ago with Alice", MemoryTitles.title(resources, withPerson(1, "Alice")))
        assertEquals("2 years ago with Alice", MemoryTitles.title(resources, withPerson(2, "Alice")))
        assertEquals("1 year ago, together", MemoryTitles.title(resources, withPerson(1, null)))
        assertEquals("Italy · June 2023", MemoryTitles.title(resources, trip(null, "IT")))
        assertEquals("Trip · June 2023", MemoryTitles.title(resources, trip(null, null)))
    }

    @Test
    fun `un séjour à cheval sur deux mois ou deux années`() {
        val french = Locale.FRENCH
        assertEquals("juin 2023", MemoryTitles.months(june, june.plusDays(20), french))
        assertEquals("juin – juillet 2023", MemoryTitles.months(june, LocalDate.of(2023, 7, 2), french))
        assertEquals("décembre 2022 – janvier 2023", MemoryTitles.months(LocalDate.of(2022, 12, 28), LocalDate.of(2023, 1, 3), french))
        assertEquals("June – July 2023", MemoryTitles.months(june, LocalDate.of(2023, 7, 2), Locale.ENGLISH))
    }
}
