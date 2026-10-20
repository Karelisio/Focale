package app.focale.screenshots

import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.focale.core.settings.AppSettings
import app.focale.core.settings.ThemeMode
import app.focale.people.FaceBox
import app.focale.people.FaceThumbnail
import app.focale.people.Person
import app.focale.people.ReviewFace
import app.focale.ui.people.FaceReviewContent
import app.focale.ui.theme.FocaleTheme
import coil3.ColorImage
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Captures de référence de la revue des visages d'une personne, avec une sélection, en
 * thème clair et sombre (couleurs Focale). Les visages sont remplacés par des aplats de
 * couleur. Régénérer avec `./gradlew recordRoborazziStandardDebug` quand le changement est voulu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class FaceReviewScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private val person = Person(id = 1, name = "Alice", hidden = false, photoCount = 214, cover = null)

    /** Les moins ressemblants d'abord, comme la revue. */
    private val faces = List(14) { index ->
        val id = index + 1L
        ReviewFace(
            face = FaceThumbnail(id, id, Uri.parse("content://media/external/images/media/$id"), 0, FaceBox(0.3f, 0.2f, 0.6f, 0.6f)),
            similarity = 0.2f + index * 0.05f,
        )
    }

    /** Teintes de peau et de cheveux en aplat, une par visage. */
    private val tones = listOf(0xFFE2B79C, 0xFF9C6B52, 0xFFC99272, 0xFF5E3D2E, 0xFFF0CDB4, 0xFFB98264, 0xFF7A4E3A)

    private val preview = AsyncImagePreviewHandler { request ->
        val id = (request.data as FaceThumbnail).faceId
        ColorImage(tones[(id % tones.size).toInt()].toInt())
    }

    @Test
    fun revueVisagesClair() = capture(dark = false, selection = emptySet())

    @Test
    fun revueVisagesSelectionClair() = capture(dark = false)

    @Test
    fun revueVisagesSelectionSombre() = capture(dark = true)

    private fun capture(dark: Boolean, selection: Set<Long> = setOf(1L, 2L, 6L)) {
        compose.setContent {
            FocaleTheme(AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false)) {
                CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides preview) {
                    FaceReviewContent(
                        person = person,
                        faces = faces,
                        selection = selection,
                        onSelectionChange = {},
                        onOpen = {},
                        onSelectAll = {},
                        onRemove = {},
                        onMove = {},
                        onBack = {},
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }
}
