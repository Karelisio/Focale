package app.focale.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import app.focale.core.settings.AppSettings
import app.focale.core.settings.ThemeMode
import app.focale.edit.CropAspect
import app.focale.edit.CropMath
import app.focale.edit.EditState
import app.focale.edit.StraightenMath
import app.focale.ui.editor.CropPanel
import app.focale.ui.editor.DrawingOptions
import app.focale.ui.editor.EditorCanvas
import app.focale.ui.editor.EditorPreview
import app.focale.ui.editor.EditorTool
import app.focale.ui.editor.EditorToolChoices
import app.focale.ui.editor.EditorTopBar
import app.focale.ui.theme.FocaleTheme
import app.focale.ui.theme.PhotoTheme
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import kotlin.math.tan
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Captures de référence de l'éditeur en mode recadrage sur une photo dont l'horizon penche de
 * 6,5° : pendant le redressement (grille fine, en anglais), puis redressée au format carré (en
 * français). Thème sombre de l'éditeur, couleurs Focale sans couleurs dynamiques. Régénérer
 * avec `./gradlew recordRoborazziStandardDebug` quand le changement est voulu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class StraightenScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    @Test
    fun redressementEnCours() = capture(CropAspect.FREE, straightening = true)

    @Test
    @Config(qualifiers = "fr-w400dp-h800dp-xxhdpi")
    fun redressementCarre() = capture(CropAspect.SQUARE, straightening = false)

    private fun capture(aspect: CropAspect, straightening: Boolean) {
        val photo = tiltedPhoto()
        val width = photo.width.toFloat()
        val height = photo.height.toFloat()
        val ratio = CropMath.normalizedRatio(aspect, photo.width, photo.height, width / height)
        val state = EditState(
            straighten = TILT,
            aspect = aspect,
            crop = StraightenMath.fit(CropMath.fit(ratio), TILT, width, height),
        )
        compose.setContent {
            FocaleTheme(AppSettings(themeMode = ThemeMode.DARK, dynamicColor = false)) {
                PhotoTheme(seed = null, forceDark = true) {
                    // Comme EditorScreen : surface de l'éditeur, puis sa disposition.
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
                        Column(Modifier.fillMaxSize()) {
                            EditorTopBar(
                                canUndo = true,
                                canRedo = false,
                                canSave = true,
                                canReplace = true,
                                onClose = {},
                                onUndo = {},
                                onRedo = {},
                                onSave = {},
                                changes = state.changeCount,
                            )
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center,
                            ) {
                                EditorCanvas(
                                    preview = EditorPreview(photo, quarterTurns = 0, flipHorizontal = false),
                                    state = state,
                                    tool = EditorTool.CROP,
                                    showOriginal = false,
                                    ratio = ratio,
                                    drawing = DrawingOptions(),
                                    ink = Color.White,
                                    straightening = straightening,
                                    onGestureStart = {},
                                    onCropChange = {},
                                    onStraightenStart = {},
                                    onStraighten = {},
                                    onStraightenEnd = {},
                                    onStroke = {},
                                    onErase = {},
                                    onCompare = {},
                                )
                            }
                            CropPanel(
                                state = state,
                                onRotate = {},
                                onFlip = {},
                                onAspect = {},
                                onStraightenStart = {},
                                onStraighten = {},
                                onStraightenEnd = {},
                                onStraightenReset = {},
                            )
                            EditorToolChoices(tool = EditorTool.CROP, onSelect = {})
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    /**
     * Paysage 3:2 en aplats (ciel, soleil, deux bandes de collines) dont l'horizon monte vers la
     * droite de [TILT] : un redressement horaire du même angle le remet droit.
     */
    private fun tiltedPhoto(): ImageBitmap = ImageBitmap(600, 400).also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = Color(0xFF7FA3B8)
        canvas.drawRect(0f, 0f, 600f, 400f, paint)
        paint.color = Color(0xFFF2D37C)
        canvas.drawCircle(Offset(420f, 110f), 36f, paint)
        // Pente de l'horizon, autour du milieu de la photo.
        val rise = 310f * tan(Math.toRadians(TILT.toDouble())).toFloat()
        for ((level, color) in listOf(230f to Color(0xFF6E8F5A), 300f to Color(0xFF4F6B45))) {
            paint.color = color
            val band = Path().apply {
                moveTo(-10f, level + rise)
                lineTo(610f, level - rise)
                lineTo(610f, 410f)
                lineTo(-10f, 410f)
                close()
            }
            canvas.drawPath(band, paint)
        }
    }

    private companion object {
        const val TILT = 6.5f
    }
}
