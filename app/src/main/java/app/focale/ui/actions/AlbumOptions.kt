package app.focale.ui.actions

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.focale.R
import app.focale.ui.components.MenuOption

/**
 * Options d'un album entier de la galerie, communes au menu de l'album ouvert et à la feuille de
 * l'appui long sur sa carte : renommer un album de l'application, ou masquer un dossier dans Photos
 * (l'y afficher s'il l'est) ; le déplacer dans le coffre s'il a des médias ([count]) ; supprimer un
 * album de l'application, en dernier.
 */
@Composable
fun albumOptions(
    album: AlbumSource,
    count: Int,
    hidden: Boolean,
    onRename: () -> Unit,
    onHiddenChange: (Boolean) -> Unit,
    onMoveToVault: () -> Unit,
    onDelete: () -> Unit,
): List<MenuOption> = buildList {
    when (album) {
        is AlbumSource.User -> add(
            MenuOption(stringResource(R.string.album_rename), Icons.Outlined.DriveFileRenameOutline, onClick = onRename),
        )
        is AlbumSource.Folder -> add(
            if (hidden) {
                MenuOption(stringResource(R.string.folder_show_in_timeline), Icons.Outlined.Visibility) { onHiddenChange(false) }
            } else {
                MenuOption(stringResource(R.string.folder_hide_from_timeline), Icons.Outlined.VisibilityOff) { onHiddenChange(true) }
            },
        )
    }
    if (count > 0) add(MenuOption(stringResource(R.string.vault_album_move_action), Icons.Outlined.Lock, onClick = onMoveToVault))
    if (album is AlbumSource.User) {
        add(MenuOption(stringResource(R.string.album_delete_title), Icons.Outlined.DeleteOutline, onClick = onDelete))
    }
}

/** Renommage d'un album, de la galerie ou du coffre. */
@Composable
fun RenameAlbumDialog(name: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    NameDialog(
        title = stringResource(R.string.album_rename),
        label = stringResource(R.string.album_name),
        confirm = stringResource(R.string.action_rename),
        initial = name,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Confirmation de la suppression d'un album de l'application : ses photos et vidéos restent sur l'appareil. */
@Composable
fun DeleteAlbumDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.album_delete_title)) },
        text = { Text(stringResource(R.string.album_delete_message)) },
        confirmButton = {
            TextButton(onClick = {
                onDismiss()
                onConfirm()
            }) { Text(stringResource(R.string.action_delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
