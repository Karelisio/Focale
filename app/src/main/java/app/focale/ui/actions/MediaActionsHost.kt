package app.focale.ui.actions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focale.R
import app.focale.core.image.thumbnail
import app.focale.core.model.Album
import app.focale.core.model.FolderTarget
import app.focale.core.model.MediaItem
import coil3.compose.AsyncImage

/**
 * Affiche les dialogues et messages de [MediaActions] : choix d'album ou de dossier,
 * renommage, partage, snackbar avec « Annuler ».
 */
@Composable
fun MediaActionsHost(
    actions: MediaActions,
    snackbarHostState: SnackbarHostState,
    onActionDone: () -> Unit = {},
) {
    val context = LocalContext.current
    val dialog by actions.dialog.collectAsStateWithLifecycle()
    val currentOnDone by rememberUpdatedState(onActionDone)
    val undoLabel = stringResource(R.string.action_undo)

    LaunchedEffect(actions) {
        actions.events.collect { event ->
            when (event) {
                is ActionEvent.Share -> context.startActivity(event.intent)
                is ActionEvent.Done -> currentOnDone()
                is ActionEvent.Message -> {
                    val result = snackbarHostState.showSnackbar(
                        message = event.text.resolve(context),
                        actionLabel = if (event.undo != null) undoLabel else null,
                        withDismissAction = event.undo == null,
                        duration = if (event.undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) event.undo?.invoke()
                }
            }
        }
    }

    when (val current = dialog) {
        is ActionDialog.AddToAlbum -> AlbumPickerSheet(
            count = current.ids.size,
            albums = actions.userAlbums,
            onPick = { actions.addToAlbum(current.ids, it) },
            onCreate = { actions.createAlbumAndAdd(it, current.ids) },
            onDismiss = actions::dismissDialog,
        )
        is ActionDialog.CopyTo -> FolderPickerSheet(
            title = pluralStringResource(R.plurals.title_copy_to, current.ids.size, current.ids.size),
            folders = actions.folders,
            onPick = { actions.copyTo(current.ids, it) },
            onDismiss = actions::dismissDialog,
        )
        is ActionDialog.MoveTo -> FolderPickerSheet(
            title = pluralStringResource(R.plurals.title_move_to, current.ids.size, current.ids.size),
            folders = actions.folders,
            onPick = { actions.moveTo(current.ids, it) },
            onDismiss = actions::dismissDialog,
        )
        is ActionDialog.Rename -> RenameDialog(
            item = current.item,
            onConfirm = { actions.rename(current.item, it) },
            onDismiss = actions::dismissDialog,
        )
        null -> Unit
    }
}

@Composable
private fun AlbumPickerSheet(
    count: Int,
    albums: kotlinx.coroutines.flow.Flow<List<Album.User>>,
    onPick: (Album.User) -> Unit,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val list by albums.collectAsStateWithLifecycle(initialValue = emptyList())
    var creating by rememberSaveable { mutableStateOf(false) }
    if (creating) {
        NameDialog(
            title = stringResource(R.string.album_new),
            label = stringResource(R.string.album_name),
            confirm = stringResource(R.string.action_create),
            onConfirm = onCreate,
            onDismiss = { creating = false },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = pluralStringResource(R.plurals.title_add_to_album, count, count),
            style = MaterialTheme.typography.titleLargeEmphasized,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        val total = list.size + 1
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            item {
                SegmentedListItem(
                    onClick = { creating = true },
                    shapes = ListItemDefaults.segmentedShapes(0, total),
                    leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null) },
                ) { Text(stringResource(R.string.album_new)) }
            }
            itemsIndexed(list, key = { _, album -> album.id }) { index, album ->
                SegmentedListItem(
                    onClick = { onPick(album) },
                    shapes = ListItemDefaults.segmentedShapes(index + 1, total),
                    leadingContent = { AlbumThumb(album.cover) },
                    supportingContent = { Text(pluralStringResource(R.plurals.media_count, album.count, album.count)) },
                ) { Text(album.name) }
            }
        }
    }
}

@Composable
private fun FolderPickerSheet(
    title: String,
    folders: kotlinx.coroutines.flow.Flow<List<Album.Folder>>,
    onPick: (FolderTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val list by folders.collectAsStateWithLifecycle(initialValue = emptyList())
    var creating by rememberSaveable { mutableStateOf(false) }
    if (creating) {
        NameDialog(
            title = stringResource(R.string.folder_new),
            label = stringResource(R.string.folder_name),
            confirm = stringResource(R.string.action_create),
            onConfirm = { name -> onPick(FolderTarget(name.trim(), "Pictures/${name.trim()}/")) },
            onDismiss = { creating = false },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLargeEmphasized,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        val total = list.size + 1
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            item {
                SegmentedListItem(
                    onClick = { creating = true },
                    shapes = ListItemDefaults.segmentedShapes(0, total),
                    leadingContent = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                ) { Text(stringResource(R.string.folder_new)) }
            }
            itemsIndexed(list, key = { _, folder -> folder.bucketId }) { index, folder ->
                SegmentedListItem(
                    onClick = { onPick(FolderTarget(folder.name, folder.relativePath)) },
                    shapes = ListItemDefaults.segmentedShapes(index + 1, total),
                    leadingContent = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                    supportingContent = { Text(folder.relativePath.trimEnd('/')) },
                ) { Text(folder.name) }
            }
        }
    }
}

@Composable
private fun AlbumThumb(cover: MediaItem?) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            AsyncImage(
                model = cover.thumbnail(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(48.dp),
            )
        } else {
            Icon(Icons.Outlined.PhotoAlbum, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

@Composable
private fun RenameDialog(item: MediaItem, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    NameDialog(
        title = stringResource(R.string.action_rename),
        label = stringResource(R.string.rename_label),
        confirm = stringResource(R.string.action_rename),
        initial = item.displayName.substringBeforeLast('.'),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

/** Dialogue de saisie d'un nom (album, dossier, renommage). */
@Composable
fun NameDialog(
    title: String,
    label: String,
    confirm: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    initial: String = "",
) {
    var value by remember { mutableStateOf(TextFieldValue(initial, selection = TextRange(0, initial.length))) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .focusRequester(focusRequester),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(value.text)
                    onDismiss()
                },
                enabled = value.text.isNotBlank(),
            ) { Text(confirm) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
