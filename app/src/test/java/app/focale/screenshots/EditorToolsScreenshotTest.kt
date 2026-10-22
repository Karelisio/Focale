package app.focale.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.focale.core.settings.AppSettings
import app.focale.core.settings.ThemeMode
import app.focale.edit.BlurArea
import app.focale.edit.BlurRegion
import app.focale.edit.BlurRenderer
import app.focale.edit.BlurShape
import app.focale.edit.BlurStyle
import app.focale.edit.EditState
import app.focale.edit.TextBackground
import app.focale.edit.TextItem
import app.focale.ui.editor.BlurOptions
import app.focale.ui.editor.BlurOverlay
import app.focale.ui.editor.BlurPanel
import app.focale.ui.editor.DrawingOptions
import app.focale.ui.editor.EditorCanvas
import app.focale.ui.editor.EditorPreview
import app.focale.ui.editor.EditorTool
import app.focale.ui.editor.EditorToolChoices
import app.focale.ui.editor.EditorTopBar
import app.focale.ui.editor.EditorViewport
import app.focale.ui.editor.InkColor
import app.focale.ui.editor.TextOptions
import app.focale.ui.editor.TextOverlay
import app.focale.ui.editor.TextPanel
import app.focale.ui.editor.rememberTextLayoutCache
import app.focale.ui.theme.FocaleTheme
import app.focale.ui.theme.PhotoTheme
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Captures de référence de l'éditeur avec les outils Texte (un texte encadré, sélectionné) et
 * Flouter (une zone en mosaïque sur un visage, sélectionnée), l'application en thème clair (en
 * français) puis sombre (en anglais). L'éditeur reste sombre dans les deux cas ([PhotoTheme],
 * `forceDark`) : la capture claire vérifie qu'aucune surface claire n'y entre. Couleurs Focale
 * sans couleurs dynamiques. Régénérer avec `./gradlew recordRoborazziStandardDebug` quand le
 * changement est voulu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class EditorToolsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    @Test
    @Config(qualifiers = "fr-w400dp-h800dp-xxhdpi")
    fun texteClair() = captureText(ThemeMode.LIGHT)

    @Test
    fun texteSombre() = captureText(ThemeMode.DARK)

    @Test
    @Config(qualifiers = "fr-w400dp-h800dp-xxhdpi")
    fun flouPixelsClair() = captureBlur(ThemeMode.LIGHT)

    @Test
    fun flouPixelsSombre() = captureBlur(ThemeMode.DARK)

    private fun captureText(theme: ThemeMode) {
        val photo = portrait()
        val text = TextItem(
            id = 1,
            text = "Lisbonne\n2026",
            x = 0.24f,
            y = 0.25f,
            color = YELLOW,
            size = 0.085f,
            rotation = -6f,
            background = TextBackground.BOX,
            bold = true,
        )
        val state = EditState(texts = listOf(text))
        capture(theme, photo, state, EditorTool.TEXT, overlay = { viewport ->
            TextOverlay(
                viewport = viewport,
                texts = state.texts,
                layouts = rememberTextLayoutCache(),
                selected = text.id,
                onAdd = { _, _ -> },
                onEdit = {},
                onSelect = {},
                onGestureStart = {},
                onTransform = { _, _, _, _, _ -> },
                onDelete = {},
            )
        }) {
            TextPanel(
                options = TextOptions(ink = InkColor.YELLOW, size = text.size, background = text.background, bold = text.bold),
                ink = InkColor.YELLOW,
                hasSelection = true,
                onAdd = {},
                onInk = {},
                onBackground = {},
                onBold = {},
                onSizeStart = {},
                onSize = {},
                onDelete = {},
            )
        }
    }

    private fun captureBlur(theme: ThemeMode) {
        val photo = portrait()
        // Mosaïque sur le visage (cercle de 50 px de rayon en (300, 170)), un peu plus grande que lui.
        val zone = BlurRegion(1, BlurArea.Box(BlurShape.RECTANGLE, 0.5f, 170f / 400f, 70f / 400f, 82f / 400f), BlurStyle.PIXELATE, 0.5f)
        val state = EditState(blurs = listOf(zone))
        val patch = BlurRenderer.patch(photo.asAndroidBitmap(), quarterTurns = 0, flip = false, region = zone)!!
        capture(theme, photo, state, EditorTool.BLUR, blurPatches = mapOf(zone.id to patch), overlay = { viewport ->
            BlurOverlay(
                viewport = viewport,
                regions = state.blurs,
                selected = zone.id,
                shape = BlurShape.RECTANGLE,
                brushSize = BlurOptions.DEFAULT_BRUSH.dp,
                accent = MaterialTheme.colorScheme.primary,
                onAdd = {},
                onSelect = {},
                onMoveStart = {},
                onMove = { _, _, _ -> },
                onDelete = {},
            )
        }) {
            BlurPanel(
                options = BlurOptions(style = BlurStyle.PIXELATE),
                hasSelection = true,
                detectingFaces = false,
                onShape = {},
                onStyle = {},
                onIntensityStart = {},
                onIntensity = {},
                onBrushSize = {},
                onFaces = {},
                onDelete = {},
            )
        }
    }

    private fun capture(
        theme: ThemeMode,
        photo: ImageBitmap,
        state: EditState,
        tool: EditorTool,
        blurPatches: Map<Long, app.focale.edit.BlurPatch> = emptyMap(),
        overlay: @Composable (EditorViewport) -> Unit,
        panel: @Composable () -> Unit,
    ) {
        compose.setContent {
            FocaleTheme(AppSettings(themeMode = theme, dynamicColor = false)) {
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
                                    tool = tool,
                                    showOriginal = false,
                                    ratio = null,
                                    drawing = DrawingOptions(),
                                    ink = Color.White,
                                    straightening = false,
                                    onGestureStart = {},
                                    onCropChange = {},
                                    onStraightenStart = {},
                                    onStraighten = {},
                                    onStraightenEnd = {},
                                    onStroke = {},
                                    onErase = {},
                                    onCompare = {},
                                    blurPatches = blurPatches,
                                    overlay = overlay,
                                )
                            }
                            panel()
                            EditorToolChoices(tool = tool, onSelect = {})
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    /**
     * Photo 3:2 en aplats : ciel, soleil, collines et une silhouette dont le visage (cercle de
     * 50 px de rayon en (300, 170), yeux et sourire) est à flouter.
     */
    private fun portrait(): ImageBitmap = ImageBitmap(600, 400).also { bitmap ->
        val canvas = Canvas(bitmap)
        val paint = Paint()
        paint.color = Color(0xFF8DB3CC)
        canvas.drawRect(0f, 0f, 600f, 400f, paint)
        paint.color = Color(0xFFF2D37C)
        canvas.drawCircle(Offset(500f, 80f), 34f, paint)
        paint.color = Color(0xFF6E8F5A)
        canvas.drawPath(
            Path().apply {
                moveTo(-10f, 300f)
                cubicTo(150f, 250f, 400f, 330f, 610f, 270f)
                lineTo(610f, 410f)
                lineTo(-10f, 410f)
                close()
            },
            paint,
        )
        // Épaules et visage.
        paint.color = Color(0xFF3F51B5)
        canvas.drawRoundRect(200f, 240f, 400f, 420f, 90f, 90f, paint)
        paint.color = Color(0xFFE0AC8A)
        canvas.drawCircle(Offset(300f, 170f), 50f, paint)
        paint.color = Color(0xFF4E342E)
        canvas.drawCircle(Offset(282f, 160f), 6f, paint)
        canvas.drawCircle(Offset(318f, 160f), 6f, paint)
        canvas.drawRect(284f, 190f, 316f, 196f, paint)
        canvas.drawPath(
            Path().apply {
                moveTo(250f, 150f)
                cubicTo(255f, 100f, 345f, 100f, 350f, 150f)
                cubicTo(330f, 125f, 270f, 125f, 250f, 150f)
                close()
            },
            paint,
        )
    }

    private companion object {
        const val YELLOW = 0xFFFDD835.toInt()
    }
}
