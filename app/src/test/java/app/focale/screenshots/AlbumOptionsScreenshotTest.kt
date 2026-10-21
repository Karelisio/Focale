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
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.focale.core.image.MediaThumbnail
import app.focale.core.model.Album
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaType
import app.focale.core.settings.AppSettings
import app.focale.core.settings.ThemeMode
import app.focale.ui.albums.AlbumOptionsHeader
import app.focale.ui.albums.albumSheetOptions
import app.focale.ui.components.OptionsSheetContent
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
 * Captures de référence de la feuille ouverte par un appui long sur un album de l'onglet Albums :
 * album de l'application, en français et en thème clair ; dossier masqué dans Photos, en anglais
 * et en thème sombre (couleurs Focale). La feuille est reproduite sans sa fenêtre : poignée, forme
 * et contenu ; les couvertures sont dessinées et servies par l'aperçu de Coil. Régénérer avec
 * `./gradlew recordRoborazziStandardDebug` quand le changement est voulu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class AlbumOptionsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    @Test
    @Config(qualifiers = "fr-w400dp-h800dp-xxhdpi")
    fun optionsAlbumClair() = capture(
        dark = false,
        album = Album.User(id = 1, name = "Vacances 2025", count = 57, cover = photo(1)),
        hidden = false,
    )

    /** Dossier masqué : « Afficher dans Photos », ni renommage ni suppression. */
    @Test
    fun optionsDossierMasqueSombre() = capture(
        dark = true,
        album = Album.Folder(bucketId = 2, name = "WhatsApp Images", relativePath = "Pictures/WhatsApp Images/", count = 342, cover = photo(2)),
        hidden = true,
    )

    /** Dégradés des couvertures : ciel au-dessus de la mer, conversation au soleil. */
    private val palettes = mapOf(
        1L to (0xFF7FB2E5 to 0xFF1D3F6E),
        2L to (0xFFF6C177 to 0xFF3E7C59),
    )

    private val covers = AsyncImagePreviewHandler { request ->
        val (top, bottom) = palettes.getValue((request.data as MediaThumbnail).id)
        coverImage(top, bottom)
    }

    private fun capture(dark: Boolean, album: Album, hidden: Boolean) {
        compose.setContent {
            FocaleTheme(AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false)) {
                CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides covers) {
                    Surface(
                        color = BottomSheetDefaults.ContainerColor,
                        shape = BottomSheetDefaults.ExpandedShape,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            BottomSheetDefaults.DragHandle()
                            OptionsSheetContent(
                                header = { AlbumOptionsHeader(album) },
                                options = albumSheetOptions(
                                    album = album,
                                    hidden = hidden,
                                    onSlideshow = {},
                                    onRename = {},
                                    onHiddenChange = {},
                                    onMoveToVault = {},
                                    onDelete = {},
                                ),
                                onPick = {},
                                modifier = Modifier.padding(bottom = 16.dp),
                            )
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

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
