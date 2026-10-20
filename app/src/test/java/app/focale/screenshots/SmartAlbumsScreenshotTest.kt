package app.focale.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.focale.core.image.MediaThumbnail
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaType
import app.focale.core.settings.AppSettings
import app.focale.core.settings.ThemeMode
import app.focale.data.SmartAlbum
import app.focale.data.SmartAlbumKind
import app.focale.data.SmartAlbums
import app.focale.ui.albums.SmartAlbumsShelf
import app.focale.ui.theme.FocaleTheme
import coil3.Image
import coil3.asImage
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
 * Captures de référence de la section « Albums automatiques » de l'onglet Albums : en français
 * et en thème clair, en anglais et en thème sombre (couleurs Focale, sans couleurs dynamiques).
 * Les couvertures sont dessinées et servies par l'aperçu de Coil, faute de miniatures MediaStore.
 * Régénérer avec `./gradlew recordRoborazziStandardDebug` quand le changement est voulu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class SmartAlbumsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    @Test
    @Config(qualifiers = "fr-w400dp-h800dp-xxhdpi")
    fun albumsAutomatiquesClair() = capture(
        dark = false,
        albums = listOf(
            album(SmartAlbumKind.DOCUMENTS, 37, cover = 1),
            album(SmartAlbumKind.RECEIPTS, 12, cover = 2),
            album(SmartAlbumKind.ANIMALS, 1_284, cover = 3),
            album(SmartAlbumKind.LANDSCAPES, 2_341, cover = 4),
        ),
    )

    /** La deuxième tuile, sans couverture, montre l'icône de sa catégorie. */
    @Test
    fun albumsAutomatiquesSombre() = capture(
        dark = true,
        albums = listOf(
            album(SmartAlbumKind.ANIMALS, 1_284, cover = 3),
            album(SmartAlbumKind.FOOD, 3, cover = null),
            album(SmartAlbumKind.LANDSCAPES, 2_341, cover = 4),
            album(SmartAlbumKind.CELEBRATIONS, 58, cover = 5),
        ),
    )

    /** Dégradés évoquant chaque sujet : feuille sur un bureau, ticket, pelage, ciel au-dessus d'un champ, feu d'artifice. */
    private val palettes = mapOf(
        1L to (0xFFEDE7DA to 0xFF9C7B5B),
        2L to (0xFFF7F5F0 to 0xFF7D8894),
        3L to (0xFFD9A066 to 0xFF6B4423),
        4L to (0xFF7FB2E5 to 0xFF4E7A3A),
        5L to (0xFF1A1446 to 0xFFB3365B),
    )

    private val covers = AsyncImagePreviewHandler { request ->
        val (top, bottom) = palettes.getValue((request.data as MediaThumbnail).id)
        coverImage(top, bottom)
    }

    private fun capture(dark: Boolean, albums: List<SmartAlbum>) {
        compose.setContent {
            FocaleTheme(AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false)) {
                CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides covers) {
                    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) { SmartAlbumsShelf(albums, onOpen = { _, _ -> }) }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    private fun album(kind: SmartAlbumKind, count: Int, cover: Long?) =
        SmartAlbum(kind, SmartAlbums.query(kind), count, cover?.let(::photo))

    private fun photo(id: Long) = MediaItem(
        id = id, uri = Uri.parse("content://media/external/images/media/$id"), type = MediaType.IMAGE,
        mimeType = "image/jpeg", displayName = "IMG_$id.jpg", relativePath = "DCIM/Camera/", bucketId = 1,
        bucketName = "Camera", dateTaken = id, dateModified = id, size = 1, width = 4000, height = 3000,
        orientation = 0, durationMs = 0, isGif = false, isScreenshot = false,
    )

    /** Couverture dessinée : dégradé vertical et disque clair. */
    private fun coverImage(top: Long, bottom: Long): Image {
        val size = COVER_SIZE.toFloat()
        val bitmap = Bitmap.createBitmap(COVER_SIZE, COVER_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, size, top.toInt(), bottom.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size, size, paint)
        paint.shader = null
        paint.color = 0x66FFFFFF
        canvas.drawCircle(size * 0.7f, size * 0.32f, size * 0.14f, paint)
        return bitmap.asImage()
    }

    private companion object {
        const val COVER_SIZE = 256
    }
}
