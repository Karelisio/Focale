package app.focale.screenshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.focale.core.settings.AppSettings
import app.focale.core.settings.PeopleSort
import app.focale.core.settings.ThemeMode
import app.focale.people.Person
import app.focale.ui.people.MergeSuggestionCard
import app.focale.ui.people.PeopleContent
import app.focale.ui.theme.FocaleTheme
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Captures de référence de la liste des personnes : suggestion « Est-ce la même personne ? »
 * et liste avec une personne épinglée, en thème clair et sombre (vignettes non chargées :
 * silhouettes). Régénérer avec `./gradlew recordRoborazziStandardDebug` quand le changement
 * est voulu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class PeopleListScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private val lea = Person(id = 1, name = "Léa", hidden = false, photoCount = 248, cover = null, pinned = true)
    private val people = listOf(
        lea,
        Person(id = 2, name = null, hidden = false, photoCount = 1_032, cover = null),
        Person(id = 3, name = "Tom", hidden = false, photoCount = 412, cover = null),
        Person(id = 4, name = "Grand-mère Madeleine", hidden = false, photoCount = 87, cover = null),
        Person(id = 5, name = null, hidden = false, photoCount = 12, cover = null),
        Person(id = 6, name = "Inès", hidden = false, photoCount = 3, cover = null),
    )

    @Test
    fun suggestionClair() = capture(dark = false) { Suggestion() }

    @Test
    fun suggestionSombre() = capture(dark = true) { Suggestion() }

    @Test
    fun listeEpingleeClair() = captureScreen(dark = false)

    @Test
    fun listeEpingleeSombre() = captureScreen(dark = true)

    @Composable
    private fun Suggestion() {
        MergeSuggestionCard(target = lea, source = people[4], onMerge = {}, onDifferent = {}, onOpen = {})
    }

    private fun captureScreen(dark: Boolean) {
        compose.setContent {
            Theme(dark) {
                PeopleContent(
                    people = people,
                    suggestion = null,
                    sort = PeopleSort.PHOTOS,
                    showHidden = false,
                    analysisEnabled = true,
                    onBack = {},
                    onOpen = {},
                    onSortChange = {},
                    onShowHiddenChange = {},
                    onPinnedChange = { _, _ -> },
                    onMerge = {},
                    onRefuse = {},
                    onEnableAnalysis = {},
                )
            }
        }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    private fun capture(dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            Theme(dark) {
                // Surface : couleur de contenu par défaut identique à celle des écrans.
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Composable
    private fun Theme(dark: Boolean, content: @Composable () -> Unit) {
        FocaleTheme(AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false), content = content)
    }
}
