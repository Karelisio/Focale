package app.focale.screenshots

import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.focale.R
import app.focale.core.settings.AppSettings
import app.focale.core.settings.ThemeMode
import app.focale.panorama.PanoramaKind
import app.focale.ui.actions.SelectionActionsBar
import app.focale.ui.theme.FocaleTheme
import app.focale.ui.viewer.PainterImageSource
import app.focale.ui.viewer.PanoramaButton
import app.focale.ui.viewer.PanoramaChrome
import app.focale.ui.viewer.PanoramaStrip
import app.focale.ui.viewer.ViewerChromeMetrics
import app.focale.ui.viewer.ViewerToolbar
import app.focale.ui.viewer.fakePanorama
import app.focale.ui.viewer.fakePanoramaPainter
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import me.saket.telephoto.zoomable.rememberZoomableImageState
import me.saket.telephoto.zoomable.rememberZoomableState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Captures de référence des photos panoramiques et de l'impression, en français (couleurs
 * Material You de repli, sans couleurs dynamiques) : bouton « 360° » et « Vue panoramique » de
 * la visionneuse, vue panoramique ouverte (« Bouger le téléphone » actif), menu de la sélection
 * avec « Imprimer ». La vue 360° elle-même (OpenGL) n'est pas rendue par Robolectric. Régénérer
 * avec `./gradlew recordRoborazziStandardDebug` quand le changement est voulu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "fr-w400dp-h800dp-xxhdpi")
class PanoramaScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    /** Photo 360° (2:1) dans la visionneuse : bouton « 360° » au-dessus de la barre d'outils. */
    @Test
    fun bouton360() = capture(dark = true) { ViewerFrame(PanoramaKind.SPHERE, ratio = 2f) }

    /** Panorama de 6:1 dans la visionneuse : bouton « Vue panoramique ». */
    @Test
    fun boutonVuePanoramique() = capture(dark = true) { ViewerFrame(PanoramaKind.FLAT, ratio = 6f) }

    /** Vue panoramique ouverte : l'image remplit la hauteur, centrée ; capteur suivi. */
    @Test
    fun vuePanoramique() = capture(dark = true) {
        PanoramaChrome(
            title = "Vue panoramique",
            chromeVisible = true,
            motionAvailable = true,
            motion = true,
            onMotionChange = {},
            onReset = {},
            onClose = {},
        ) {
            PanoramaStrip(
                image = remember { PainterImageSource(fakePanoramaPainter()) },
                contentDescription = null,
                state = rememberZoomableImageState(rememberZoomableState()),
                onClick = {},
            )
        }
    }

    /** Menu « Plus » de la sélection ouvert, avec « Imprimer » : capturé avec tout l'écran (fenêtre à part). */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun menuImprimer() {
        show(dark = false) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface),
            ) {
                SelectionActionsBar(
                    visible = true,
                    selectionCount = 4,
                    onShare = {},
                    onFavorite = {},
                    onAddToAlbum = {},
                    onTrash = {},
                    onCopy = {},
                    onMove = {},
                    onRename = {},
                    onMoveToVault = {},
                    onSelectAll = {},
                    onPrint = {},
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
        val more = ApplicationProvider.getApplicationContext<Context>().getString(R.string.action_more)
        compose.onNode(hasContentDescription(more)).performClick()
        compose.waitForIdle()
        captureScreenRoboImage(roborazziOptions = options)
    }

    /** Visionneuse : photo de proportions [ratio] ajustée à l'écran, bouton de [kind] et barre d'outils. */
    @Composable
    private fun ViewerFrame(kind: PanoramaKind, ratio: Float) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLowest),
        ) {
            val image = remember(ratio) { BitmapPainter(fakePanorama(width = (300 * ratio).toInt(), height = 300)) }
            Image(
                painter = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center),
            )
            PanoramaButton(
                kind = kind,
                onClick = {},
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = ViewerChromeMetrics.ToolbarHeight + ViewerChromeMetrics.ScreenOffset),
            )
            Box(Modifier.align(Alignment.BottomCenter)) {
                ViewerToolbar(
                    isFavorite = false,
                    onShare = {},
                    onToggleFavorite = {},
                    onTrash = {},
                    onEdit = {},
                    menu = {},
                )
            }
        }
    }

    private fun capture(dark: Boolean, content: @Composable () -> Unit) {
        show(dark, content)
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    private fun show(dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            FocaleTheme(AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false)) { content() }
        }
    }
}
