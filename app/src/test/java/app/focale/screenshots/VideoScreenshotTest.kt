package app.focale.screenshots

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.focale.core.settings.AppSettings
import app.focale.core.settings.ThemeMode
import app.focale.ui.theme.FocaleTheme
import app.focale.ui.theme.ViewerChromeTheme
import app.focale.ui.videoeditor.VideoEditorLayout
import app.focale.ui.viewer.MotionPhotoChip
import app.focale.ui.viewer.SeekDirection
import app.focale.ui.viewer.SeekFeedback
import app.focale.ui.viewer.VideoControlsLayout
import app.focale.ui.viewer.VideoSeekFeedback
import app.focale.ui.viewer.ViewerToolbar
import app.focale.video.TrimRange
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import androidx.compose.ui.graphics.Canvas as BitmapCanvas

/**
 * Captures de référence de la lecture et du montage vidéo, en portrait et en paysage
 * (couleurs Material You de repli, sans couleurs dynamiques), dont le cumul des sauts par
 * double appui. Régénérer avec `./gradlew recordRoborazziStandardDebug` quand le changement
 * est voulu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class VideoScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    @Test
    fun commandesVideoPortrait() = captureViewer(playing = false, muted = false)

    @Test
    @Config(qualifiers = "w800dp-h400dp-xxhdpi")
    fun commandesVideoPaysage() = captureViewer(playing = true, muted = true)

    /** Deuxième appui d'une série à droite, commandes affichées : entre la barre supérieure et la progression. */
    @Test
    fun sautVideoPortrait() = captureViewer(playing = true, muted = false, seek = SeekFeedback(SeekDirection.FORWARD, seconds = 20))

    /** Troisième appui à gauche, en paysage et en français : l'ovale s'aplatit entre la barre supérieure et la progression. */
    @Test
    @Config(qualifiers = "fr-w800dp-h400dp-xxhdpi")
    fun sautVideoPaysage() = captureViewer(playing = true, muted = true, seek = SeekFeedback(SeekDirection.BACKWARD, seconds = 30))

    @Test
    fun montageVideoPortrait() = captureEditor()

    @Test
    @Config(qualifiers = "w800dp-h400dp-xxhdpi")
    fun montageVideoPaysage() = captureEditor()

    @Test
    @Config(qualifiers = "w400dp-h120dp-xxhdpi")
    fun pucePhotoAnimee() = capture {
        ViewerChromeTheme {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(16.dp)) {
                    MotionPhotoChip(playing = false, onClick = {})
                    MotionPhotoChip(playing = true, onClick = {})
                }
            }
        }
    }

    /** Visionneuse : image de la vidéo, cumul des sauts ([seek]), commandes et barre d'outils flottante dessous. */
    private fun captureViewer(playing: Boolean, muted: Boolean, seek: SeekFeedback? = null) = capture {
        // Comme la visionneuse : la progression prend la largeur mesurée de la barre d'outils.
        var toolbarWidth by remember { mutableStateOf(Dp.Unspecified) }
        val density = LocalDensity.current
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            FakeVideoFrame()
            if (seek != null) ViewerChromeTheme { VideoSeekFeedback(seek, controlsVisible = true) }
            ViewerChromeTheme {
                VideoControlsLayout(
                    playing = playing,
                    positionMs = 2_901_000,
                    durationMs = 6_443_000,
                    muted = muted,
                    onPlayPause = {},
                    onSeek = {},
                    onToggleMute = {},
                    barWidth = toolbarWidth,
                )
            }
            Box(Modifier.align(Alignment.BottomCenter)) {
                ViewerToolbar(
                    modifier = Modifier.onSizeChanged { toolbarWidth = with(density) { it.width.toDp() } },
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

    private fun captureEditor() = capture {
        val range = TrimRange(durationMs = 14_600).withStart(2_300).withEnd(11_800)
        VideoEditorLayout(
            range = range,
            frames = frames,
            position = { 5_200 },
            playing = false,
            muted = true,
            hasAudio = true,
            exportProgress = null,
            canSave = true,
            onClose = {},
            onSave = {},
            onTogglePlay = {},
            onMutedChange = {},
            onTrimStart = {},
            onTrim = { _, time -> time },
            onTrimEnd = {},
            onCancelExport = {},
            preview = { modifier -> Box(modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { FakeVideoFrame() } },
        )
    }

    private fun capture(content: @Composable () -> Unit) {
        compose.setContent {
            FocaleTheme(AppSettings(themeMode = ThemeMode.DARK, dynamicColor = false)) { content() }
        }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    /**
     * Image 16:9 inscrite dans l'espace disponible, comme une vidéo affichée en entier : un
     * paysage en aplats (ciel, soleil, collines), léger à stocker en PNG.
     */
    @Composable
    private fun FakeVideoFrame() {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val ratio = 16f / 9f
            val width = if (maxWidth / maxHeight > ratio) maxHeight * ratio else maxWidth
            Canvas(Modifier.size(width, width / ratio)) {
                drawRect(Color(0xFF7FA3B8))
                drawCircle(Color(0xFFF2D37C), radius = size.height * 0.12f, center = Offset(size.width * 0.72f, size.height * 0.3f))
                drawRect(Color(0xFF6E8F5A), topLeft = Offset(0f, size.height * 0.62f), size = Size(size.width, size.height * 0.38f))
                drawRect(Color(0xFF4F6B45), topLeft = Offset(0f, size.height * 0.8f), size = Size(size.width, size.height * 0.2f))
            }
        }
    }

    private val frames: List<ImageBitmap> by lazy { fakeFrames() }

    private fun fakeFrames(): List<ImageBitmap> = List(10) { index ->
        ImageBitmap(160, 90).also { bitmap ->
            BitmapCanvas(bitmap).drawRect(
                left = 0f,
                top = 0f,
                right = 160f,
                bottom = 90f,
                paint = Paint().apply { color = lerp(Color(0xFF263845), Color(0xFFC9925E), index / 9f) },
            )
        }
    }
}
