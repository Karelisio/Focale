package app.focale.screenshots

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.core.settings.AppSettings
import app.focale.core.settings.ThemeMode
import app.focale.ui.components.OptionsSheetContent
import app.focale.ui.theme.FocaleTheme
import app.focale.ui.vault.NewMedia
import app.focale.ui.vault.VaultAlbumContent
import app.focale.ui.vault.VaultAlbumOptionsHeader
import app.focale.ui.vault.VaultAlbumsShelf
import app.focale.ui.vault.VaultSectionHeader
import app.focale.ui.vault.VaultTrashContent
import app.focale.ui.vault.vaultAlbumOptions
import app.focale.ui.vault.vaultItems
import app.focale.vault.VaultAlbum
import app.focale.vault.VaultImage
import app.focale.vault.VaultItem
import app.focale.vault.VaultTrashEntry
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
 * Captures de référence des albums du coffre : section « Albums » au-dessus de « Tous les
 * éléments », album-dossier avec la bannière de ses nouvelles photos, feuille de l'appui long sur
 * une tuile (reproduite sans sa fenêtre ; album vide : « Sortir du coffre » grisé) et corbeille ; en
 * français et en thème clair, en anglais et en thème sombre (couleurs Focale). Les miniatures
 * chiffrées sont remplacées par des dégradés servis par l'aperçu de Coil. Régénérer avec
 * `./gradlew recordRoborazziStandardDebug` quand le changement est voulu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h800dp-xxhdpi")
class VaultAlbumsScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val options = RoborazziOptions(compareOptions = RoborazziOptions.CompareOptions(changeThreshold = 0.002f))

    private val albums = listOf(
        VaultAlbum(id = 1, name = "WhatsApp Images", count = 342, coverThumbFileName = "c1.thumb", sourcePath = "Pictures/WhatsApp Images/", createdAt = 4),
        VaultAlbum(id = 2, name = "Vacances 2025", count = 57, coverThumbFileName = "c2.thumb", sourcePath = null, createdAt = 3),
        VaultAlbum(id = 3, name = "Papiers", count = 0, coverThumbFileName = null, sourcePath = null, createdAt = 2),
        VaultAlbum(id = 4, name = "Plage", count = 18, coverThumbFileName = "c3.thumb", sourcePath = null, createdAt = 1),
    )

    private val items = List(15) { index ->
        val id = index + 1L
        VaultItem(
            id = id,
            displayName = "IMG-$id.jpg",
            mimeType = if (id == 2L) "video/mp4" else "image/jpeg",
            isVideo = id == 2L,
            originalPath = "Pictures/WhatsApp Images/",
            dateTaken = 1_760_000_000_000 - id * 86_400_000,
            size = 1,
            durationMs = if (id == 2L) 83_000 else 0,
            fileName = "$id.bin",
            thumbFileName = "i${id % PALETTES.size}.thumb",
            addedAt = 1_760_000_000_000,
        )
    }

    @Test
    @Config(qualifiers = "fr-w400dp-h800dp-xxhdpi")
    fun albumsDuCoffreClair() = capture(dark = false) { AlbumsAndItems() }

    @Test
    fun albumsDuCoffreSombre() = capture(dark = true) { AlbumsAndItems() }

    @Test
    @Config(qualifiers = "fr-w400dp-h800dp-xxhdpi")
    fun albumAvecNouvellesPhotosClair() = capture(dark = false) { AlbumWithNewPhotos() }

    @Test
    fun albumAvecNouvellesPhotosSombre() = capture(dark = true) { AlbumWithNewPhotos() }

    @Test
    @Config(qualifiers = "fr-w400dp-h800dp-xxhdpi")
    fun optionsAlbumDuCoffreClair() = capture(dark = false) { TileOptions(albums.first()) }

    @Test
    fun optionsAlbumVideDuCoffreSombre() = capture(dark = true) { TileOptions(albums[2]) }

    @Test
    @Config(qualifiers = "fr-w400dp-h800dp-xxhdpi")
    fun corbeilleDuCoffreClair() = capture(dark = false) { Trash() }

    @Test
    fun corbeilleDuCoffreSombre() = capture(dark = true) { Trash() }

    /** Corbeille du coffre : éléments supprimés il y a 1 à 25 jours, avec les jours qui leur restent. */
    @Composable
    private fun Trash() {
        VaultTrashContent(
            entries = items.take(7).mapIndexed { index, item -> VaultTrashEntry(item, deletedAt = NOW - (index * 4 + 1) * DAY) },
            selection = emptySet(),
            busy = false,
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onLock = {},
            onToggle = {},
            onSelectAll = {},
            onClearSelection = {},
            onRestoreSelection = {},
            onDeleteSelection = {},
            onEmpty = {},
            now = NOW,
        )
    }

    /** Feuille des options de [album] (appui long sur sa tuile), sans sa fenêtre. */
    @Composable
    private fun TileOptions(album: VaultAlbum) {
        Surface(
            color = BottomSheetDefaults.ContainerColor,
            shape = BottomSheetDefaults.ExpandedShape,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BottomSheetDefaults.DragHandle()
                OptionsSheetContent(
                    header = { VaultAlbumOptionsHeader(album) },
                    options = vaultAlbumOptions(album, onRename = {}, onRestore = {}, onDelete = {}),
                    onPick = {},
                    modifier = Modifier.padding(bottom = 16.dp),
                )
            }
        }
    }

    /** Haut de l'écran du coffre, sous la barre : albums puis le début de « Tous les éléments ». */
    @Composable
    private fun AlbumsAndItems() {
        Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 110.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    VaultAlbumsShelf(albums, onOpen = {}, onRename = { _, _ -> }, onRestore = {}, onDelete = {})
                }
                item(span = { GridItemSpan(maxLineSpan) }) { VaultSectionHeader(stringResource(R.string.vault_all_items)) }
                vaultItems(items, selection = emptySet(), onToggle = {}, onOpen = {})
            }
        }
    }

    @Composable
    private fun AlbumWithNewPhotos() {
        VaultAlbumContent(
            album = albums.first().copy(count = items.size),
            items = items,
            newMedia = NewMedia(photos = 5, videos = 0, folderName = "WhatsApp Images"),
            selection = emptySet(),
            busy = false,
            snackbarHostState = remember { SnackbarHostState() },
            onBack = {},
            onLock = {},
            onSort = {},
            onAdd = {},
            onAddNewMedia = {},
            onOpen = {},
            onToggle = {},
            onSelectAll = {},
            onClearSelection = {},
            onRename = {},
            onRestoreAlbum = {},
            onDeleteAlbum = {},
            onRestoreSelection = {},
            onMoveSelection = {},
            onDeleteSelection = {},
        )
    }

    private val thumbnails = AsyncImagePreviewHandler { request ->
        val name = (request.data as VaultImage).fileName
        val (top, bottom) = COVERS[name] ?: PALETTES[name.removePrefix("i").substringBefore('.').toInt()]
        gradient(top, bottom)
    }

    private fun capture(dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            FocaleTheme(AppSettings(themeMode = if (dark) ThemeMode.DARK else ThemeMode.LIGHT, dynamicColor = false)) {
                CompositionLocalProvider(LocalInspectionMode provides true, LocalAsyncImagePreviewHandler provides thumbnails) {
                    content()
                }
            }
        }
        compose.onRoot().captureRoboImage(roborazziOptions = options)
    }

    /** Miniature dessinée : dégradé vertical et disque clair. */
    private fun gradient(top: Long, bottom: Long): Image {
        val size = SIZE.toFloat()
        val bitmap = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, 0f, size, top.toInt(), bottom.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, size, size, paint)
        paint.shader = null
        paint.color = 0x55FFFFFF
        canvas.drawCircle(size * 0.3f, size * 0.35f, size * 0.12f, paint)
        return bitmap.asImage()
    }

    private companion object {
        const val SIZE = 256
        const val NOW = 1_760_000_000_000L
        const val DAY = 86_400_000L

        /** Couvertures des albums : conversation au soleil, mer, sable. */
        val COVERS = mapOf(
            "c1.thumb" to (0xFFF6C177 to 0xFF3E7C59),
            "c2.thumb" to (0xFF7FB2E5 to 0xFF1D3F6E),
            "c3.thumb" to (0xFFF3E1C0 to 0xFFC08A4E),
        )

        /** Teintes des éléments de la grille. */
        val PALETTES = listOf(
            0xFF8EC5FC to 0xFF5B6AB0,
            0xFFFFD3A5 to 0xFFFD6585,
            0xFFC2E59C to 0xFF64B3F4,
            0xFFE0C3FC to 0xFF8EC5FC,
            0xFFF6D365 to 0xFFFDA085,
            0xFF96E6A1 to 0xFF3E8E6E,
            0xFFD4FC79 to 0xFF4FA36C,
        )
    }
}
