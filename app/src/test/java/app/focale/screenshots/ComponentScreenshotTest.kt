package app.focale.screenshots

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.focale.core.model.Album
import app.focale.core.settings.AppSettings
import app.focale.core.settings.ThemeMode
import app.focale.ui.albums.AlbumCard
import app.focale.ui.components.Choice
import app.focale.ui.components.ConnectedChoiceGroup
import app.focale.ui.settings.SettingsAction
import app.focale.ui.settings.SettingsCustom
import app.focale.ui.settings.SettingsGroup
import app.focale.ui.settings.SettingsSwitch
import app.focale.ui.theme.FocaleTheme
import app.focale.ui.update.UpdateBanner
import app.focale.ui.whatsnew.ReleaseNotesBlocks
import app.focale.update.AppUpdate
import app.focale.update.UpdateError
import app.focale.update.UpdateState
import app.focale.whatsnew.Changelog
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Captures de référence de composants partagés, en thème clair et sombre (couleurs
 * Focale, sans couleurs dynamiques). Une différence fait échouer la CI : régénérer avec
 * `./gradlew recordRoborazziStandardDebug` quand le changement est voulu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class ComponentScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    /** Tolérance minime : l'anticrénelage peut varier d'un pixel selon la machine. */
    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private val update = AppUpdate(
        version = "1.2.0",
        apkUrl = "https://github.com/Karelisio/Focale/releases/download/v1.2.0/app-standard-arm64-v8a-release.apk",
        apkSize = 25_162_099,
        sha256 = null,
        pageUrl = "https://github.com/Karelisio/Focale/releases/tag/v1.2.0",
        notes = "## Nouveautés\n\n- Dessins dans l'éditeur",
    )

    @Test
    fun banniereMiseAJourClair() = capture(dark = false) { UpdateBanners() }

    @Test
    fun banniereMiseAJourSombre() = capture(dark = true) { UpdateBanners() }

    @Test
    fun reglagesClair() = capture(dark = false) { SettingsSample() }

    @Test
    fun reglagesSombre() = capture(dark = true) { SettingsSample() }

    @Test
    fun ongletsCompacts() = capture(dark = false) {
        val choices = listOf(
            Choice(0, "Doublons", Icons.Outlined.ContentCopy),
            Choice(1, "Floues", Icons.Outlined.BlurOn),
            Choice(2, "Captures", Icons.Outlined.Screenshot),
            Choice(3, "Grosses vidéos", Icons.Outlined.VideoFile),
        )
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ConnectedChoiceGroup(choices = choices, selected = 0, onSelect = {}, compact = true)
            ConnectedChoiceGroup(choices = choices, selected = 3, onSelect = {}, compact = true)
        }
    }

    @Test
    fun albumMasqueDansPhotos() = capture(dark = true) {
        Column(Modifier.width(180.dp)) {
            AlbumCard(
                album = Album.Folder(bucketId = 1, name = "WhatsApp Images", relativePath = "Pictures/WhatsApp/", count = 128, cover = null),
                hiddenFromTimeline = true,
                onClick = {},
            )
        }
    }

    @Test
    fun nouveautes() = capture(dark = false) {
        val release = Changelog.parse(
            """
            ## 1.2.0

            - Dessins dans l'éditeur : stylo, surligneur, formes
            - Sauvegarde chiffrée du coffre, à restaurer sur un autre
              téléphone

            Merci d'utiliser Focale !
            """.trimIndent(),
        ).single()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Version ${release.version}", style = MaterialTheme.typography.titleLargeEmphasized)
            ReleaseNotesBlocks(release.blocks)
        }
    }

    @Composable
    private fun UpdateBanners() {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            UpdateBanner(state = UpdateState.Available(update), onCheck = {}, onInstall = {})
            UpdateBanner(state = UpdateState.Downloading(update, 0.42f), onCheck = {}, onInstall = {})
            UpdateBanner(state = UpdateState.Failed(UpdateError.INSTALL, update), onCheck = {}, onInstall = {})
        }
    }

    @Composable
    private fun SettingsSample() {
        SettingsGroup("Grille") {
            item { shapes ->
                SettingsSwitch(
                    title = "Noir pur",
                    subtitle = "Surfaces noires en thème sombre",
                    icon = Icons.Outlined.Contrast,
                    checked = true,
                    onCheckedChange = {},
                    shapes = shapes,
                )
            }
            item { shapes ->
                SettingsAction(
                    title = "Dossiers masqués",
                    subtitle = "2 dossiers masqués dans Photos",
                    icon = Icons.Outlined.VisibilityOff,
                    onClick = {},
                    shapes = shapes,
                )
            }
            item { shapes ->
                SettingsCustom(title = "Intervalle", shapes = shapes) {
                    ConnectedChoiceGroup(
                        choices = listOf(Choice(3, "3 s"), Choice(5, "5 s"), Choice(10, "10 s")),
                        selected = 5,
                        onSelect = {},
                    )
                }
            }
        }
    }

    private fun capture(dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            FocaleTheme(AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false)) {
                // Surface : couleur de contenu par défaut identique à celle des écrans.
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
