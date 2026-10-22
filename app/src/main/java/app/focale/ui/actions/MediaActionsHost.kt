package app.focale.ui.actions

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.focale.R
import app.focale.core.image.thumbnail
import app.focale.core.model.Album
import app.focale.core.model.FolderTarget
import app.focale.core.model.MediaItem
import app.focale.ui.components.rememberNotificationPermission
import app.focale.ui.navigation.LocalRootNavController
import app.focale.ui.navigation.VaultRoute
import app.focale.ui.navigation.VaultUnlockRoute
import app.focale.ui.navigation.openCollage
import app.focale.vault.VaultAlbum
import app.focale.vault.VaultDestination
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Affiche les dialogues et messages de [MediaActions] : choix d'album ou de dossier,
 * renommage, partage, snackbar avec « Annuler ».
 *
 * @param onAlbumMoved l'album affiché est passé en entier dans le coffre : l'écran se ferme, sur
 *   un message qui lui survit. Sans lui (onglet Albums, rien ne se ferme), message en snackbar.
 */
@Composable
fun MediaActionsHost(
    actions: MediaActions,
    snackbarHostState: SnackbarHostState,
    onActionDone: () -> Unit = {},
    onAlbumMoved: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val rootNav = LocalRootNavController.current
    val dialog by actions.dialog.collectAsStateWithLifecycle()
    val progress by actions.progress.collectAsStateWithLifecycle()
    val currentOnDone by rememberUpdatedState(onActionDone)
    val currentOnAlbumMoved by rememberUpdatedState(onAlbumMoved)
    val undoLabel = stringResource(R.string.action_undo)
    // Un dossier lié au coffre signalera ses nouvelles photos par une notification.
    val requestNotifications = rememberNotificationPermission()

    LaunchedEffect(actions) {
        // Snackbar dans sa propre coroutine : les événements suivants (fin de l'action…) ne
        // doivent pas attendre sa disparition.
        fun showMessage(event: ActionEvent.Message) = launch {
            val result = snackbarHostState.showSnackbar(
                message = event.text.resolve(context),
                actionLabel = if (event.undo != null) undoLabel else null,
                withDismissAction = event.undo == null,
                duration = if (event.undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            if (result == SnackbarResult.ActionPerformed) event.undo?.invoke()
        }
        actions.events.collect { event ->
            when (event) {
                is ActionEvent.Share -> context.startActivity(event.intent)
                is ActionEvent.Done -> currentOnDone()
                is ActionEvent.AlbumMoved -> {
                    val close = currentOnAlbumMoved
                    if (close != null) {
                        // Message qui survit à l'écran fermé, comme à la sortie de l'éditeur.
                        Toast.makeText(context, event.text.resolve(context), Toast.LENGTH_SHORT).show()
                        close()
                    } else {
                        showMessage(ActionEvent.Message(event.text))
                    }
                }
                is ActionEvent.Message -> showMessage(event)
                is ActionEvent.OpenCollage -> rootNav.openCollage(event.ids, event.truncated)
            }
        }
    }

    progress?.let { ProgressDialog(it.label.asString(), onHide = actions::hideProgress.takeIf { _ -> it.background }) }

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
        is ActionDialog.MoveToVault -> if (current.vaultConfigured) {
            val unlocked by actions.vaultUnlocked.collectAsStateWithLifecycle()
            val albums by remember(actions) { actions.vaultAlbums }.collectAsStateWithLifecycle(initialValue = emptyList())
            MoveToVaultDialog(
                count = current.ids.size,
                unlocked = unlocked,
                albums = albums,
                onConfirm = { actions.moveToVault(current.ids, it) },
                // Le dialogue reste ouvert : au retour, coffre déverrouillé, il propose ses albums.
                onUnlock = {
                    actions.unlockForAlbumChoice()
                    rootNav.navigate(VaultUnlockRoute) { launchSingleTop = true }
                },
                onDismiss = actions::dismissDialog,
            )
        } else {
            VaultSetupDialog(onSetUp = { setUpVault(actions, rootNav) }, onDismiss = actions::dismissDialog)
        }
        is ActionDialog.MoveAlbumToVault -> if (current.vaultConfigured) {
            MoveAlbumToVaultDialog(
                name = current.album.name,
                photos = current.photos,
                videos = current.videos,
                onConfirm = {
                    if (current.album is AlbumSource.Folder) requestNotifications()
                    actions.moveAlbumToVault(current.album, current.photos + current.videos)
                },
                onDismiss = actions::dismissDialog,
            )
        } else {
            VaultSetupDialog(onSetUp = { setUpVault(actions, rootNav) }, onDismiss = actions::dismissDialog)
        }
        null -> Unit
    }
}

/**
 * Confirmation du déplacement d'une sélection vers le coffre, et choix de son album. Les noms
 * des albums du coffre ne s'affichent que s'il est déverrouillé, dans une fenêtre protégée des
 * captures ; verrouillé, il reçoit la sélection hors album, ou se déverrouille pour en choisir un.
 */
@Composable
private fun MoveToVaultDialog(
    count: Int,
    unlocked: Boolean,
    albums: List<VaultAlbum>,
    onConfirm: (VaultDestination) -> Unit,
    onUnlock: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Album choisi : null hors album, NEW_VAULT_ALBUM pour un nouvel album.
    var choice by rememberSaveable { mutableStateOf<Long?>(null) }
    var name by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(unlocked) { if (!unlocked) choice = null }
    val destination = when (val chosen = choice.takeIf { unlocked }) {
        null -> VaultDestination.None
        NEW_VAULT_ALBUM -> VaultDestination.NewAlbum(name)
        else -> VaultDestination.Album(chosen)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = if (unlocked) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit),
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text(pluralStringResource(R.plurals.vault_move_title, count, count)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.vault_move_message))
                Text(
                    text = stringResource(R.string.vault_album_choice),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
                )
                if (unlocked) {
                    AlbumOption(stringResource(R.string.vault_album_none), selected = choice == null) { choice = null }
                    albums.forEach { album ->
                        AlbumOption(album.name, selected = choice == album.id) { choice = album.id }
                    }
                    AlbumOption(stringResource(R.string.album_new), selected = choice == NEW_VAULT_ALBUM) { choice = NEW_VAULT_ALBUM }
                    if (choice == NEW_VAULT_ALBUM) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(stringResource(R.string.album_name)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                } else {
                    Text(
                        text = stringResource(R.string.vault_album_locked),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = onUnlock, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(Icons.Outlined.LockOpen, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                        Text(stringResource(R.string.vault_album_unlock), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(destination) },
                enabled = destination !is VaultDestination.NewAlbum || destination.name.isNotBlank(),
            ) { Text(stringResource(R.string.action_move)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val NEW_VAULT_ALBUM = -1L

/** Ligne à choix unique d'un dialogue. */
@Composable
private fun AlbumOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 2.dp),
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Déplacement d'un album entier : ce qui sera chiffré, et ce qu'il advient des originaux. */
@Composable
private fun MoveAlbumToVaultDialog(name: String, photos: Int, videos: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val photoCount = pluralStringResource(R.plurals.vault_album_photos, photos, photos)
    val videoCount = pluralStringResource(R.plurals.vault_album_videos, videos, videos)
    val content = when {
        videos == 0 -> photoCount
        photos == 0 -> videoCount
        else -> stringResource(R.string.vault_album_photos_and_videos, photoCount, videoCount)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text(stringResource(R.string.vault_album_move_title, name)) },
        text = { Text(stringResource(R.string.vault_album_move_message, content)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_move)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private fun setUpVault(actions: MediaActions, rootNav: NavHostController) {
    actions.dismissDialog()
    rootNav.navigate(VaultRoute)
}

/** Coffre sans code : invitation à le configurer avant d'y déplacer quoi que ce soit. */
@Composable
private fun VaultSetupDialog(onSetUp: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
        title = { Text(stringResource(R.string.vault_setup_needed_title)) },
        text = { Text(stringResource(R.string.vault_setup_needed_message)) },
        confirmButton = { TextButton(onClick = onSetUp) { Text(stringResource(R.string.action_set_up)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Progression d'une opération longue, affichée seulement si elle dure : une opération
 * rapide ne fait pas clignoter de dialogue.
 */
@Composable
private fun ProgressDialog(label: String, onHide: (() -> Unit)? = null) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(PROGRESS_DELAY_MS)
        visible = true
    }
    if (!visible) return
    BasicAlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    LoadingIndicator(modifier = Modifier.size(48.dp))
                    Text(label, style = MaterialTheme.typography.bodyLarge)
                }
                // Opération confiée à une tâche de fond : l'écran peut la laisser continuer seule.
                if (onHide != null) {
                    TextButton(onClick = onHide, modifier = Modifier.align(Alignment.End).padding(top = 12.dp)) {
                        Text(stringResource(R.string.vault_import_background))
                    }
                }
            }
        }
    }
}

private const val PROGRESS_DELAY_MS = 350L

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

/** Couverture d'un album en vignette arrondie ; l'icône d'album faute de couverture. */
@Composable
internal fun AlbumThumb(cover: MediaItem?, size: Dp = 48.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            AsyncImage(
                model = cover.thumbnail(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size),
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
