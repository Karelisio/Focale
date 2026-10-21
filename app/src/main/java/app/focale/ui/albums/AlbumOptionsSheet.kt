package app.focale.ui.albums

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.core.model.Album
import app.focale.ui.actions.AlbumThumb
import app.focale.ui.actions.DeleteAlbumDialog
import app.focale.ui.actions.RenameAlbumDialog
import app.focale.ui.actions.albumOptions
import app.focale.ui.actions.source
import app.focale.ui.components.MenuOption
import app.focale.ui.components.OptionsSheet
import app.focale.ui.components.OptionsSheetHeader

/**
 * Options de [album], ouvertes par un appui long sur sa carte : feuille où figurent sa couverture,
 * son nom et son nombre d'éléments, puis le dialogue de l'option choisie (renommage, confirmation
 * de suppression), le même que dans l'album ouvert. [onDismiss] : tout est refermé.
 */
@Composable
internal fun AlbumOptions(
    album: Album,
    hidden: Boolean,
    onSlideshow: () -> Unit,
    onRename: (String) -> Unit,
    onHiddenChange: (Boolean) -> Unit,
    onMoveToVault: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Dialogue de l'option choisie, qui prend la place de la feuille.
    var dialog by rememberSaveable { mutableStateOf<AlbumDialog?>(null) }
    when (dialog) {
        null -> OptionsSheet(
            header = { AlbumOptionsHeader(album) },
            options = albumSheetOptions(
                album = album,
                hidden = hidden,
                onSlideshow = onSlideshow,
                onRename = { dialog = AlbumDialog.RENAME },
                onHiddenChange = onHiddenChange,
                onMoveToVault = onMoveToVault,
                onDelete = { dialog = AlbumDialog.DELETE },
            ),
            // Refermée sur une option à dialogue, la feuille laisse la place à celui-ci.
            onDismiss = { if (dialog == null) onDismiss() },
        )
        AlbumDialog.RENAME -> RenameAlbumDialog(name = album.name, onConfirm = onRename, onDismiss = onDismiss)
        AlbumDialog.DELETE -> DeleteAlbumDialog(onConfirm = onDelete, onDismiss = onDismiss)
    }
}

private enum class AlbumDialog { RENAME, DELETE }

/** En-tête de la feuille : couverture, nom et nombre d'éléments de l'album. */
@Composable
internal fun AlbumOptionsHeader(album: Album) {
    OptionsSheetHeader(
        title = album.name,
        subtitle = pluralStringResource(R.plurals.media_count, album.count, album.count),
        leading = { AlbumThumb(album.cover, size = 56.dp) },
    )
}

/**
 * Options de la feuille : le diaporama (album non vide), que l'album ouvert a dans sa barre,
 * puis celles de son menu, dans le même ordre et aux mêmes conditions.
 */
@Composable
internal fun albumSheetOptions(
    album: Album,
    hidden: Boolean,
    onSlideshow: () -> Unit,
    onRename: () -> Unit,
    onHiddenChange: (Boolean) -> Unit,
    onMoveToVault: () -> Unit,
    onDelete: () -> Unit,
): List<MenuOption> = buildList {
    if (album.count > 0) add(MenuOption(stringResource(R.string.action_slideshow), Icons.Outlined.Slideshow, onClick = onSlideshow))
    addAll(albumOptions(album.source, album.count, hidden, onRename, onHiddenChange, onMoveToVault, onDelete))
}
