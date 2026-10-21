package app.focale.screenshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.focale.core.model.MediaFilter
import app.focale.core.settings.AppSettings
import app.focale.core.settings.ThemeMode
import app.focale.data.SearchChip
import app.focale.people.Person
import app.focale.ui.search.Relaxation
import app.focale.ui.search.SearchFilterBar
import app.focale.ui.search.SearchFilterLabels
import app.focale.ui.search.SearchNoResults
import app.focale.ui.search.SearchResultsHeader
import app.focale.ui.theme.FocaleTheme
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Captures de référence des pastilles de la recherche : barre d'exploration (pastilles d'ajout),
 * barre d'une recherche (personnes, période, type actifs) avec son nombre de résultats, et recherche
 * sans résultat avec les pastilles à retirer. Français en clair, anglais en sombre (vignettes non
 * chargées : silhouettes). Régénérer avec `./gradlew recordRoborazziStandardDebug` quand le
 * changement est voulu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h360dp-xxhdpi")
class SearchFiltersScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private val lea = Person(id = 1, name = "Léa", hidden = false, photoCount = 248, cover = null)
    private val tom = Person(id = 2, name = "Tom", hidden = false, photoCount = 412, cover = null)
    private val people = mapOf(lea.id to lea, tom.id to tom)
    private val chips = listOf(
        SearchChip.Person(lea.id, lea.name),
        SearchChip.Person(tom.id, tom.name),
        SearchChip.Period.month(YearMonth.of(2023, 7)),
        SearchChip.Type(MediaFilter.VIDEOS),
    )

    @Test
    @Config(qualifiers = "fr-w400dp-h360dp-xxhdpi")
    fun pastillesClair() = capture(dark = false) { Bars() }

    @Test
    fun pastillesSombre() = capture(dark = true) { Bars() }

    @Test
    @Config(qualifiers = "fr-w400dp-h640dp-xxhdpi")
    fun aucunResultatClair() = capture(dark = false) { NoResults() }

    @Composable
    private fun Bars() {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SearchFilterBar(chips = emptyList(), people = people, onRemove = {}, onAdd = {}, onPickType = {})
            Column {
                SearchFilterBar(chips = chips, people = people, onRemove = {}, onAdd = {}, onPickType = {})
                SearchResultsHeader(count = 1_284, searching = false, onClear = {}, onSlideshow = {})
            }
            SearchFilterBar(
                chips = listOf(
                    SearchChip.Place("Saint-Malo", "FR"),
                    SearchChip.Period.days(LocalDate.of(2024, 8, 3), LocalDate.of(2024, 8, 12)),
                ),
                people = people,
                onRemove = {},
                onAdd = {},
                onPickType = {},
            )
        }
    }

    @Composable
    private fun NoResults() {
        val resources = LocalResources.current
        val place = SearchChip.Place("Lyon", "FR")
        val person = SearchChip.Person(tom.id, tom.name)
        Column {
            SearchFilterBar(chips = listOf(person, place), people = people, onRemove = {}, onAdd = {}, onPickType = {})
            SearchResultsHeader(count = 0, searching = false, onClear = {})
            SearchNoResults(
                relaxations = listOf(Relaxation(place, 412), Relaxation(person, 37)),
                label = { relaxation -> relaxation.chip?.let { SearchFilterLabels.chip(resources, it, people) }.orEmpty() },
                onRelax = {},
            )
        }
    }

    private fun capture(dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            FocaleTheme(AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false)) {
                // Surface : couleur de contenu par défaut identique à celle des écrans.
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 16.dp)) { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
