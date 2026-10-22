package app.focale.screenshots

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.focale.collage.CollageAspect
import app.focale.collage.CollageBackground
import app.focale.collage.CollageState
import app.focale.core.settings.AppSettings
import app.focale.core.settings.ThemeMode
import app.focale.ui.collage.CollageContent
import app.focale.ui.collage.CollageEditor
import app.focale.ui.collage.CollageTestPhotos
import app.focale.ui.collage.CollageTool
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
 * Captures de référence de l'écran de collage avec 2, 4 et 9 photos (aplats), chacune avec un
 * panneau : dispositions (2), bordures avec espacement et coins arrondis (4), fond (9, fond noir),
 * l'application en thème clair (en français) puis sombre (en anglais). Couleurs Focale sans
 * couleurs dynamiques. Régénérer avec `./gradlew recordRoborazziStandardDebug` quand le
 * changement est voulu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class CollageScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    @Test
    @Config(qualifiers = "fr-w400dp-h800dp-xxhdpi")
    fun deuxPhotosClair() = captureTwo(ThemeMode.LIGHT)

    @Test
    fun deuxPhotosSombre() = captureTwo(ThemeMode.DARK)

    @Test
    @Config(qualifiers = "fr-w400dp-h800dp-xxhdpi")
    fun quatrePhotosClair() = captureFour(ThemeMode.LIGHT)

    @Test
    fun quatrePhotosSombre() = captureFour(ThemeMode.DARK)

    @Test
    @Config(qualifiers = "fr-w400dp-h800dp-xxhdpi")
    fun neufPhotosClair() = captureNine(ThemeMode.LIGHT)

    @Test
    fun neufPhotosSombre() = captureNine(ThemeMode.DARK)

    /** Deux photos côte à côte, la seconde cadrée sur sa gauche ; panneau des dispositions. */
    private fun captureTwo(theme: ThemeMode) {
        val editor = CollageEditor(listOf(1L, 2L))
        editor.beginGesture()
        editor.frame(1, panX = 40f, panY = 0f, zoom = 1f, focusX = 0f, focusY = 0f, 170f, 340f, 400f, 300f)
        capture(theme, editor, CollageTool.LAYOUT)
    }

    /** Quatre photos, une grande en haut, en 4:5 ; espacement et coins arrondis ; panneau des bordures. */
    private fun captureFour(theme: ThemeMode) {
        val editor = CollageEditor(listOf(1L, 2L, 3L, 4L))
        editor.selectLayout("4-large-top")
        editor.setAspect(CollageAspect.PORTRAIT)
        editor.beginGesture()
        editor.setSpacing(0.03f)
        editor.setCorners(0.05f)
        capture(theme, editor, CollageTool.BORDERS)
    }

    /** Neuf photos, grande au centre agrandie, fond noir ; panneau du fond. */
    private fun captureNine(theme: ThemeMode) {
        val editor = CollageEditor((1L..9L).toList())
        editor.selectLayout("9-large-center")
        editor.setBackground(CollageBackground.BLACK, CollageState.BLACK)
        editor.beginGesture()
        editor.setSpacing(0.02f)
        editor.zoomStep(4, 1.5f, 200f, 200f, 400f, 300f)
        capture(theme, editor, CollageTool.BACKGROUND)
    }

    private fun capture(theme: ThemeMode, editor: CollageEditor, tool: CollageTool) {
        val ids = editor.current.photos
        compose.setContent {
            FocaleTheme(AppSettings(themeMode = theme, dynamicColor = false)) {
                // Comme CollageScreen : surface de l'écran, puis son contenu.
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) {
                    CollageContent(
                        editor = editor,
                        items = ids.associateWith { CollageTestPhotos.item(it) },
                        previews = ids.associateWith { CollageTestPhotos.scene(it.toInt()) },
                        saving = false,
                        onClose = {},
                        onSave = {},
                        initialTool = tool,
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
