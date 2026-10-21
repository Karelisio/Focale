package app.focale.ui.vault

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.SecureFlagPolicy
import app.focale.R
import app.focale.ui.actions.RenameAlbumDialog
import app.focale.ui.components.MenuOption
import app.focale.ui.components.OptionsSheet
import app.focale.ui.components.OptionsSheetHeader
import app.focale.vault.VaultAlbum

/**
 * Options d'un album du coffre, communes au menu de l'album ouvert et à la feuille de l'appui long
 * sur sa tuile : le renommer, le sortir du coffre (s'il a des éléments), le supprimer.
 */
@Composable
internal fun vaultAlbumOptions(album: VaultAlbum, onRename: () -> Unit, onRestore: () -> Unit, onDelete: () -> Unit): List<MenuOption> =
    listOf(
        MenuOption(stringResource(R.string.album_rename), Icons.Outlined.DriveFileRenameOutline, onClick = onRename),
        MenuOption(
            label = stringResource(R.string.vault_album_restore_action),
            icon = Icons.Outlined.LockOpen,
            enabled = album.count > 0,
            onClick = onRestore,
        ),
        MenuOption(stringResource(R.string.album_delete_title), Icons.Outlined.DeleteOutline, onClick = onDelete),
    )

/**
 * Options de l'album du coffre [album], ouvertes par un appui long sur sa tuile : feuille protégée
 * des captures où figurent sa couverture, son nom et son nombre d'éléments, puis le dialogue de
 * l'option choisie, le même que dans l'album ouvert. [onDismiss] : tout est refermé.
 */
@Composable
internal fun VaultAlbumOptions(
    album: VaultAlbum,
    onRename: (String) -> Unit,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Dialogue de l'option choisie, qui prend la place de la feuille.
    var dialog by rememberSaveable { mutableStateOf<VaultAlbumDialog?>(null) }
    when (dialog) {
        null -> OptionsSheet(
            header = { VaultAlbumOptionsHeader(album) },
            options = vaultAlbumOptions(
                album = album,
                onRename = { dialog = VaultAlbumDialog.RENAME },
                onRestore = { dialog = VaultAlbumDialog.RESTORE },
                onDelete = { dialog = VaultAlbumDialog.DELETE },
            ),
            // Refermée sur une option, la feuille laisse la place à son dialogue.
            onDismiss = { if (dialog == null) onDismiss() },
            securePolicy = SecureFlagPolicy.SecureOn,
        )
        VaultAlbumDialog.RENAME -> RenameAlbumDialog(name = album.name, onConfirm = onRename, onDismiss = onDismiss)
        VaultAlbumDialog.RESTORE -> VaultAlbumRestoreDialog(name = album.name, onConfirm = onRestore, onDismiss = onDismiss)
        VaultAlbumDialog.DELETE -> VaultAlbumDeleteDialog(name = album.name, onConfirm = onDelete, onDismiss = onDismiss)
    }
}

private enum class VaultAlbumDialog { RENAME, RESTORE, DELETE }

/** En-tête de la feuille : couverture (miniature chiffrée), nom et nombre d'éléments de l'album. */
@Composable
internal fun VaultAlbumOptionsHeader(album: VaultAlbum) {
    OptionsSheetHeader(
        title = album.name,
        subtitle = pluralStringResource(R.plurals.media_count, album.count, album.count),
        leading = { VaultAlbumThumb(album, size = 56.dp) },
    )
}

/** Confirmation de la sortie de l'album [name] du coffre : ses éléments retournent dans la galerie. */
@Composable
internal fun VaultAlbumRestoreDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.LockOpen, contentDescription = null) },
        title = { Text(stringResource(R.string.vault_album_restore_title, name)) },
        text = { Text(stringResource(R.string.vault_album_restore_message)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(stringResource(R.string.vault_album_restore_confirm)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Confirmation de la suppression de l'album [name] : ses éléments restent dans le coffre. */
@Composable
internal fun VaultAlbumDeleteDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
        title = { Text(stringResource(R.string.vault_album_delete_title, name)) },
        text = { Text(stringResource(R.string.vault_album_delete_message)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
