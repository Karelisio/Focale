package app.focale.screenshots

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.core.settings.AppSettings
import app.focale.core.settings.ThemeMode
import app.focale.data.ExternalMedia
import app.focale.ui.settings.DefaultAppAction
import app.focale.ui.settings.DefaultAppEntry
import app.focale.ui.settings.SettingsGroup
import app.focale.ui.theme.FocaleTheme
import app.focale.ui.theme.PhotoTheme
import app.focale.ui.viewer.ExternalViewerToolbar
import app.focale.ui.viewer.ExternalViewerTopBar
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Captures de référence de l'entrée « Application par défaut pour les photos » dans ses trois états
 * (Focale, aucune application, une autre application), en anglais et thème clair puis en français et
 * thème sombre (couleurs Focale, sans couleurs dynamiques), et de l'habillage de la visionneuse d'un
 * fichier reçu d'une autre application. Régénérer avec `./gradlew recordRoborazziStandardDebug`
 * quand le changement est voulu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class DefaultAppScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private val states = listOf(
        DefaultAppAction.Done,
        DefaultAppAction.OpenPhoto,
        DefaultAppAction.ClearDefaults("com.simplemobiletools.gallery.pro", "Simple Gallery"),
    )

    @Test
    fun reglageApplicationParDefautClair() = captureSettings(dark = false)

    @Test
    @Config(qualifiers = "fr-w400dp-h800dp-xxhdpi")
    fun reglageApplicationParDefautSombre() = captureSettings(dark = true)

    /** Fichier d'une messagerie, hors index : nom et taille, partage et « Ouvrir avec » seulement. */
    @Test
    @Config(qualifiers = "fr-w400dp-h480dp-xxhdpi")
    fun visionneuseFichierExterne() {
        val media = ExternalMedia(
            uri = Uri.parse("content://com.whatsapp.provider.media/item/12"),
            mimeType = "image/jpeg",
            displayName = "IMG-20261018-WA0012.jpg",
            size = 2_457_600,
        )
        compose.setContent {
            FocaleTheme(AppSettings(themeMode = ThemeMode.LIGHT, dynamicColor = false)) {
                PhotoTheme(seed = null, forceDark = true) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                    ) {
                        FakePhoto(Modifier.align(Alignment.Center))
                        Box(Modifier.align(Alignment.TopCenter)) { ExternalViewerTopBar(media = media, onBack = {}) }
                        Box(Modifier.align(Alignment.BottomCenter)) { ExternalViewerToolbar(onShare = {}, onOpenWith = {}) }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    private fun captureSettings(dark: Boolean) {
        compose.setContent {
            FocaleTheme(AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false)) {
                // Surface : couleur de contenu par défaut identique à celle des écrans.
                Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(bottom = 16.dp)) {
                        states.forEach { action ->
                            SettingsGroup(stringResource(R.string.settings_files)) {
                                item { shapes ->
                                    DefaultAppEntry(action = action, shapes = shapes, onClick = if (action == DefaultAppAction.Done) null else ({}))
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    /** Paysage 4:3 en aplats (ciel, soleil, collines), léger à stocker en PNG. */
    @Composable
    private fun FakePhoto(modifier: Modifier) {
        Canvas(modifier.size(320.dp, 240.dp)) {
            drawRect(Color(0xFF7FA3B8))
            drawCircle(Color(0xFFF2D37C), radius = size.height * 0.12f, center = Offset(size.width * 0.72f, size.height * 0.3f))
            drawRect(Color(0xFF6E8F5A), topLeft = Offset(0f, size.height * 0.62f), size = Size(size.width, size.height * 0.38f))
            drawRect(Color(0xFF4F6B45), topLeft = Offset(0f, size.height * 0.8f), size = Size(size.width, size.height * 0.2f))
        }
    }
}
