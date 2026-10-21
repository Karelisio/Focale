package app.focale.screenshots

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.focale.core.settings.AppSettings
import app.focale.core.settings.ThemeMode
import app.focale.data.PhotoRotator
import app.focale.ui.theme.FocaleTheme
import app.focale.ui.theme.ViewerChromeTheme
import app.focale.ui.viewer.RotationBar
import app.focale.ui.viewer.ViewerChromeMetrics
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Captures de référence de la barre « Enregistrer la rotation », sous une photo tournée d'un
 * quart de tour, en thème clair et sombre (couleurs Focale, sans couleurs dynamiques). En
 * sombre, la barre porte l'habillage de la visionneuse. Régénérer avec
 * `./gradlew recordRoborazziStandardDebug` quand le changement est voulu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h480dp-xxhdpi")
class RotationScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    @Test
    fun barreRotationClair() = capture(dark = false)

    @Test
    fun barreRotationSombre() = capture(dark = true)

    private fun capture(dark: Boolean) {
        compose.setContent {
            FocaleTheme(AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false)) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                ) {
                    FakePhoto(
                        Modifier
                            .align(Alignment.Center)
                            .graphicsLayer { rotationZ = 90f },
                    )
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        if (dark) ViewerChromeTheme { Bar() } else Bar()
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    @Composable
    private fun Bar() {
        RotationBar(
            options = PhotoRotator.Options(lossless = true, canReplace = true),
            saving = false,
            onSave = {},
            onCancel = {},
            modifier = Modifier
                .navigationBarsPadding()
                .padding(bottom = ViewerChromeMetrics.ScreenOffset),
        )
    }

    /** Paysage 4:3 en aplats (ciel, soleil, collines), léger à stocker en PNG. */
    @Composable
    private fun FakePhoto(modifier: Modifier) {
        Canvas(modifier.size(288.dp, 216.dp)) {
            drawRect(Color(0xFF7FA3B8))
            drawCircle(Color(0xFFF2D37C), radius = size.height * 0.12f, center = Offset(size.width * 0.72f, size.height * 0.3f))
            drawRect(Color(0xFF6E8F5A), topLeft = Offset(0f, size.height * 0.62f), size = Size(size.width, size.height * 0.38f))
            drawRect(Color(0xFF4F6B45), topLeft = Offset(0f, size.height * 0.8f), size = Size(size.width, size.height * 0.2f))
        }
    }
}
