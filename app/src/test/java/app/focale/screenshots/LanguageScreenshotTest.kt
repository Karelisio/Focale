package app.focale.screenshots

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.focale.core.locale.AppLanguage
import app.focale.core.settings.AppSettings
import app.focale.core.settings.ThemeMode
import app.focale.ui.settings.LanguageSection
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
 * Captures de référence de la section « Langue » des réglages : en anglais et thème clair, langue
 * du système choisie (Android 13+ : le même réglage que celui d'Android), puis en français et
 * thème sombre, français choisi (avant Android 13). Couleurs Focale, sans couleurs dynamiques.
 * Régénérer avec `./gradlew recordRoborazziStandardDebug` quand le changement est voulu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h320dp-xxhdpi")
class LanguageScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    @Test
    fun sectionLangueClair() = capture(dark = false, language = AppLanguage.SYSTEM, sameAsSystemSetting = true)

    @Test
    @Config(qualifiers = "fr-w400dp-h320dp-xxhdpi")
    fun sectionLangueSombre() = capture(dark = true, language = AppLanguage.FRENCH, sameAsSystemSetting = false)

    private fun capture(dark: Boolean, language: AppLanguage, sameAsSystemSetting: Boolean) {
        compose.setContent {
            FocaleTheme(AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false)) {
                // Surface : couleur de contenu par défaut identique à celle des écrans.
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(bottom = 16.dp)) {
                        LanguageSection(language = language, onSelect = {}, sameAsSystemSetting = sameAsSystemSetting)
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
