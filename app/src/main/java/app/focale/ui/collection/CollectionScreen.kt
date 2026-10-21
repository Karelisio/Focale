package app.focale.ui.collection

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import app.focale.R
import app.focale.ui.actions.DeleteAlbumDialog
import app.focale.ui.actions.ExtraAction
import app.focale.ui.actions.RenameAlbumDialog
import app.focale.ui.actions.albumOptions
import app.focale.ui.components.EmptyState
import app.focale.ui.components.MediaGridScaffold
import app.focale.ui.components.OptionsMenu
import app.focale.ui.components.SortSheet
import app.focale.ui.navigation.CollectionKind
import app.focale.ui.navigation.LocalRootNavController
import app.focale.ui.navigation.openSlideshow
import app.focale.ui.theme.PhotoTheme

/** Grille d'un album (dossier ou virtuel), des favoris ou d'une catégorie. */
@Composable
fun CollectionScreen(onBack: () -> Unit, viewModel: CollectionViewModel = hiltViewModel()) {
    val seed by viewModel.seedColor.collectAsStateWithLifecycle()
    // « Couleur depuis la photo » : l'album adopte la palette de sa couverture.
    PhotoTheme(seed = seed) {
        CollectionContent(viewModel, onBack)
    }
}

@Composable
private fun CollectionContent(viewModel: CollectionViewModel, onBack: () -> Unit) {
    val rootNav = LocalRootNavController.current
    val items = viewModel.items.collectAsLazyPagingItems()
    val title by viewModel.title.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val columns by viewModel.columns.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val count by viewModel.count.collectAsStateWithLifecycle()
    val histogram by viewModel.histogram.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val isUserAlbum = viewModel.kind == CollectionKind.USER_ALBUM
    val album by viewModel.album.collectAsStateWithLifecycle()
    val folderHidden by viewModel.folderHidden.collectAsStateWithLifecycle()

    var showSort by rememberSaveable { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    if (showSort) {
        SortSheet(
            sort = sort,
            onSortChange = viewModel::setSort,
            filter = filter,
            onFilterChange = viewModel::setFilter,
            onDismiss = { showSort = false },
        )
    }
    // Dialogues des options de l'album, les mêmes que depuis l'appui long sur sa carte.
    if (renaming) {
        RenameAlbumDialog(name = title, onConfirm = viewModel::renameAlbum, onDismiss = { renaming = false })
    }
    if (confirmDelete) {
        DeleteAlbumDialog(onConfirm = { viewModel.deleteAlbum(onBack) }, onDismiss = { confirmDelete = false })
    }

    val extraActions = if (isUserAlbum) {
        buildList {
            add(ExtraAction(stringResource(R.string.action_remove_from_album), Icons.Outlined.RemoveCircleOutline, viewModel::removeSelectionFromAlbum))
            if (selection.size == 1) {
                add(ExtraAction(stringResource(R.string.action_set_cover), Icons.Outlined.Image, viewModel::setSelectionAsCover))
            }
        }
    } else {
        emptyList()
    }

    MediaGridScaffold(
        items = items,
        query = query,
        columns = columns,
        selection = selection,
        onSelectionChange = viewModel::setSelection,
        onClearSelection = viewModel::clearSelection,
        onSelectAll = viewModel::selectAll,
        actions = viewModel.actions,
        onColumnsChange = viewModel::setColumns,
        histogram = if (sort.isChronological) histogram else emptyList(),
        gridIndexOf = viewModel::gridIndexOf,
        extraSelectionActions = extraActions,
        topBar = { scrollBehavior ->
            LargeFlexibleTopAppBar(
                title = { Text(title) },
                subtitle = if (count >= 0) {
                    { Text(pluralStringResource(R.plurals.media_count, count, count)) }
                } else {
                    null
                },
                navigationIcon = {
                    IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (count > 0) {
                        IconButton(onClick = { rootNav.openSlideshow(query) }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Outlined.Slideshow, contentDescription = stringResource(R.string.action_slideshow))
                        }
                    }
                    IconButton(onClick = { showSort = true }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Tune, contentDescription = stringResource(R.string.sort_title))
                    }
                    album?.let { current ->
                        OptionsMenu(
                            albumOptions(
                                album = current,
                                count = count,
                                hidden = folderHidden == true,
                                onRename = { renaming = true },
                                onHiddenChange = viewModel::setFolderHidden,
                                onMoveToVault = viewModel::requestMoveToVault,
                                onDelete = { confirmDelete = true },
                            ),
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        // Album passé en entier dans le coffre : vide (dossier) ou supprimé (album de l'application).
        onAlbumMovedToVault = onBack,
        emptyContent = {
            EmptyState(
                icon = Icons.Outlined.PhotoAlbum,
                title = stringResource(if (isUserAlbum) R.string.album_empty_title else R.string.collection_empty_title),
                message = if (isUserAlbum) stringResource(R.string.album_empty_message) else null,
            )
        },
    )
}
