package app.focale.screenshots

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.focale.backup.AutoBackupError
import app.focale.backup.AutoBackupFrequency
import app.focale.backup.AutoBackupSettings
import app.focale.core.settings.AppSettings
import app.focale.core.settings.ThemeMode
import app.focale.ui.theme.FocaleTheme
import app.focale.ui.vault.VaultAutoBackupSettingsContent
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Captures de référence du réglage « Sauvegarde automatique » du coffre : actif avec la date de
 * sa dernière sauvegarde, en français et thème clair ; en échec (dossier inaccessible), en anglais
 * et thème sombre ; désactivé, en français et thème sombre (couleurs Focale). La feuille est
 * reproduite sans sa fenêtre : poignée, forme et contenu. Régénérer avec
 * `./gradlew recordRoborazziStandardDebug` quand le changement est voulu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class VaultAutoBackupScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private val enabled = AutoBackupSettings(
        frequency = AutoBackupFrequency.DAILY,
        folder = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ASauvegardes"),
        folderName = "Sauvegardes",
        lastBackupAt = BACKED_UP_AT,
    )

    @Test
    @Config(qualifiers = "fr-w400dp-h800dp-xxhdpi")
    fun sauvegardeAutomatiqueClair() = capture(dark = false, settings = enabled)

    @Test
    fun sauvegardeAutomatiqueEchecSombre() = capture(
        dark = true,
        settings = enabled.copy(
            frequency = AutoBackupFrequency.WEEKLY,
            error = AutoBackupError.FOLDER_UNAVAILABLE,
            errorAt = FAILED_AT,
        ),
    )

    @Test
    @Config(qualifiers = "fr-w400dp-h800dp-xxhdpi")
    fun sauvegardeAutomatiqueDesactiveeSombre() = capture(dark = true, settings = AutoBackupSettings())

    private fun capture(dark: Boolean, settings: AutoBackupSettings) {
        compose.setContent {
            FocaleTheme(AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false)) {
                Surface(
                    color = BottomSheetDefaults.ContainerColor,
                    shape = BottomSheetDefaults.ExpandedShape,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BottomSheetDefaults.DragHandle()
                        VaultAutoBackupSettingsContent(
                            settings = settings,
                            busy = false,
                            notice = null,
                            onFrequencyChange = {},
                            onChangeFolder = {},
                            onChangePassword = {},
                            modifier = Modifier.padding(bottom = 16.dp),
                        )
                    }
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
