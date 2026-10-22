package app.focale.screenshots

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.focale.backup.AutoBackupError
import app.focale.backup.AutoBackupFrequency
import app.focale.backup.AutoBackupSettings
import app.focale.backup.DataBackupSummary
import app.focale.core.settings.AppSettings
import app.focale.core.settings.ThemeMode
import app.focale.ui.settings.DataBackupActions
import app.focale.ui.settings.DataBackupSection
import app.focale.ui.settings.RestoreSummaryCard
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
 * Captures de référence de la sauvegarde des données : section des réglages, sauvegarde
 * automatique active (français, thème clair) ou en échec (anglais, thème sombre) ; résumé d'une
 * sauvegarde vérifiée avant sa restauration, avec des personnes à remplacer (français, thème
 * clair) ou sans (anglais, thème sombre). Le résumé est reproduit sans sa fenêtre, sur le voile
 * des dialogues. Régénérer avec `./gradlew recordRoborazziStandardDebug` quand le changement est voulu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class DataBackupScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private val enabled = AutoBackupSettings(
        frequency = AutoBackupFrequency.DAILY,
        folder = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ASauvegardes"),
        folderName = "Sauvegardes",
        lastBackupAt = BACKED_UP_AT,
    )

    private val summary = DataBackupSummary(
        createdAt = BACKED_UP_AT,
        appVersion = "1.9.0",
        favorites = 1_284,
        albums = 12,
        people = 37,
        hiddenFolders = 3,
        media = 20_431,
        faces = 18_762,
        searches = 25,
        existingPeople = 4,
        records = 60_000,
    )

    @Test
    @Config(qualifiers = "fr-w400dp-h800dp-xxhdpi")
    fun sectionSauvegardeDesDonneesClair() = section(dark = false, settings = enabled)

    @Test
    fun sectionSauvegardeDesDonneesEchecSombre() = section(
        dark = true,
        settings = enabled.copy(frequency = AutoBackupFrequency.WEEKLY, error = AutoBackupError.FOLDER_UNAVAILABLE, errorAt = FAILED_AT),
    )

    @Test
    @Config(qualifiers = "fr-w400dp-h800dp-xxhdpi")
    fun resumeRestaurationClair() = summary(dark = false, summary = summary)

    @Test
    fun resumeRestaurationSansPersonneSombre() = summary(dark = true, summary = summary.copy(existingPeople = 0, hiddenFolders = 1))

    private fun section(dark: Boolean, settings: AutoBackupSettings) {
        compose.setContent {
            FocaleTheme(AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false)) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(vertical = 8.dp)) {
                    DataBackupSection(settings, DataBackupActions(onBackup = {}, onRestore = {}, onAutoBackup = {}))
                }
            }
        }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    private fun summary(dark: Boolean, summary: DataBackupSummary) {
        compose.setContent {
            FocaleTheme(AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.32f))
                        .padding(24.dp),
                ) {
                    RestoreSummaryCard(summary, onConfirm = {}, onDismiss = {})
                }
            }
        }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    private companion object {
        /** Midi UTC : même jour affiché quel que soit le fuseau de la machine de test. */
        const val BACKED_UP_AT = 1_792_497_600_000L
        const val FAILED_AT = 1_792_584_000_000L
    }
}
