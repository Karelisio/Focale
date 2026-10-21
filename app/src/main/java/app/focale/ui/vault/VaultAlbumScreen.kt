package app.focale.ui.vault

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focale.R
import app.focale.ui.actions.MediaActionsHost
import app.focale.ui.actions.NameDialog
import app.focale.ui.components.EmptyState
import app.focale.ui.components.SelectionTopBar
import app.focale.vault.VaultAlbum
import app.focale.vault.VaultItem
import kotlinx.coroutines.launch

/**
 * Album du coffre : grille, sélection, visionneuse, et pour un album-dossier les nouveaux
 * médias de son dossier d'origine à ajouter d'un geste. Captures d'écran interdites ; le
 * verrouillage ramène à l'écran du code.
 */
@Composable
fun VaultAlbumScreen(
    onBack: () -> Unit,
    onOpen: (VaultItem) -> Unit,
    onAdd: () -> Unit,
    viewModel: VaultAlbumViewModel = hiltViewModel(),
) {
    SecureWindow()
    val context = LocalContext.current
    val unlocked by viewModel.unlocked.collectAsStateWithLifecycle()
    LaunchedEffect(unlocked) { if (!unlocked) onBack() }
    val album by viewModel.album.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val newMedia by viewModel.newMedia.collectAsStateWithLifecycle()
    val otherAlbums by viewModel.otherAlbums.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteAlbum by rememberSaveable { mutableStateOf(false) }
    var confirmRestoreAlbum by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteItems by rememberSaveable { mutableStateOf(false) }
    var choosingAlbum by rememberSaveable { mutableStateOf(false) }

    MediaActionsHost(actions = viewModel.actions, snackbarHostState = snackbarHostState)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is VaultAlbumEvent.Message -> launch { snackbarHostState.showSnackbar(event.text.resolve(context)) }
                is VaultAlbumEvent.Closed -> {
                    event.text?.let { Toast.makeText(context, it.resolve(context), Toast.LENGTH_SHORT).show() }
                    onBack()
                }
            }
        }
    }

    val current = album
    if (current == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
        return
    }
    if (renaming) {
        NameDialog(
            title = stringResource(R.string.album_rename),
            label = stringResource(R.string.album_name),
            confirm = stringResource(R.string.action_rename),
            initial = current.name,
            onConfirm = viewModel::rename,
            onDismiss = { renaming = false },
        )
    }
    if (confirmDeleteAlbum) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAlbum = false },
            icon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
            title = { Text(stringResource(R.string.vault_album_delete_title, current.name)) },
            text = { Text(stringResource(R.string.vault_album_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDeleteAlbum = false
                    viewModel.deleteAlbum()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteAlbum = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmRestoreAlbum) {
        AlertDialog(
            onDismissRequest = { confirmRestoreAlbum = false },
            icon = { Icon(Icons.Outlined.LockOpen, contentDescription = null) },
            title = { Text(stringResource(R.string.vault_album_restore_title, current.name)) },
            text = { Text(stringResource(R.string.vault_album_restore_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestoreAlbum = false
                    viewModel.restoreAlbum()
                }) { Text(stringResource(R.string.vault_album_restore_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmRestoreAlbum = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmDeleteItems) {
        VaultDeleteDialog(
            count = selection.size,
            onConfirm = {
                confirmDeleteItems = false
                viewModel.deleteSelection()
            },
            onDismiss = { confirmDeleteItems = false },
        )
    }
    if (choosingAlbum && selection.isNotEmpty()) {
        VaultAlbumPickerSheet(
            count = selection.size,
            albums = otherAlbums,
            showNone = true,
            onPick = {
                choosingAlbum = false
                viewModel.moveSelection(it)
            },
            onDismiss = { choosingAlbum = false },
        )
    }

    VaultAlbumContent(
        album = current,
        items = items,
        newMedia = newMedia,
        selection = selection,
        busy = busy,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onLock = viewModel::lock,
        onAdd = onAdd,
        onAddNewMedia = viewModel::addNewMedia,
        onOpen = onOpen,
        onToggle = viewModel::toggle,
        onSelectAll = viewModel::selectAll,
        onClearSelection = viewModel::clearSelection,
        onRename = { renaming = true },
        onRestoreAlbum = { confirmRestoreAlbum = true },
        onDeleteAlbum = { confirmDeleteAlbum = true },
        onRestoreSelection = viewModel::restoreSelection,
        onMoveSelection = { choosingAlbum = true },
        onDeleteSelection = { confirmDeleteItems = true },
    )
}

/** Contenu de l'album, sans état propre (aperçus et captures de référence). */
@Composable
internal fun VaultAlbumContent(
    album: VaultAlbum,
    items: List<VaultItem>?,
    newMedia: NewMedia?,
    selection: Set<Long>,
    busy: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onAdd: () -> Unit,
    onAddNewMedia: () -> Unit,
    onOpen: (VaultItem) -> Unit,
    onToggle: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRename: () -> Unit,
    onRestoreAlbum: () -> Unit,
    onDeleteAlbum: () -> Unit,
    onRestoreSelection: () -> Unit,
    onMoveSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
) {
    val selecting = selection.isNotEmpty()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyGridState()
    val fabExpanded by remember { derivedStateOf { gridState.firstVisibleItemIndex == 0 } }
    BackHandler(enabled = selecting, onBack = onClearSelection)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selecting) {
                SelectionTopBar(count = selection.size, onClear = onClearSelection) {
                    IconButton(onClick = onSelectAll, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.action_select_all))
                    }
                }
            } else {
                LargeFlexibleTopAppBar(
                    title = { Text(album.name) },
                    subtitle = { Text(pluralStringResource(R.plurals.vault_count, album.count, album.count)) },
                    navigationIcon = {
                        IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = onLock, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.action_lock))
                        }
                        VaultAlbumMenu(
                            canRestore = album.count > 0,
                            onRename = onRename,
                            onRestore = onRestoreAlbum,
                            onDelete = onDeleteAlbum,
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        floatingActionButton = {
            if (!selecting) {
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.vault_add)) },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    expanded = fabExpanded,
                    onClick = onAdd,
                )
            }
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                state = gridState,
                columns = GridCells.Adaptive(minSize = 110.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
                contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 104.dp,
                ),
            ) {
                newMedia?.let { media ->
                    item(key = "new", span = { GridItemSpan(maxLineSpan) }, contentType = "new") {
                        NewMediaBanner(media, enabled = !busy, onAdd = onAddNewMedia)
                    }
                }
                if (busy) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                    }
                }
                if (items?.isEmpty() == true) {
                    item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                        EmptyState(
                            icon = Icons.Outlined.PhotoAlbum,
                            title = stringResource(R.string.album_empty_title),
                            message = stringResource(R.string.vault_album_empty_message),
                            shape = MaterialShapes.Gem,
                        )
                    }
                }
                vaultItems(items.orEmpty(), selection, onToggle = onToggle, onOpen = onOpen)
            }
            if (selecting) {
                VaultSelectionToolbar(
                    busy = busy,
                    onRestore = onRestoreSelection,
                    onMoveToAlbum = onMoveSelection,
                    onDelete = onDeleteSelection,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@Composable
private fun VaultAlbumMenu(canRestore: Boolean, onRename: () -> Unit, onRestore: () -> Unit, onDelete: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.album_rename)) },
                leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                onClick = {
                    open = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vault_album_restore_action)) },
                leadingIcon = { Icon(Icons.Outlined.LockOpen, contentDescription = null) },
                enabled = canRestore,
                onClick = {
                    open = false
                    onRestore()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.album_delete_title)) },
                leadingIcon = { Icon(Icons.Outlined.DeleteOutline, contentDescription = null) },
                onClick = {
                    open = false
                    onDelete()
                },
            )
        }
    }
}
