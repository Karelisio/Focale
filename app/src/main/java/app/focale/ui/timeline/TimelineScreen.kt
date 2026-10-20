package app.focale.ui.timeline

import android.os.Build
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import app.focale.R
import app.focale.core.mediastore.SyncState
import app.focale.core.model.MediaFilter
import app.focale.core.permissions.MediaAccess
import app.focale.data.MemoryTitles
import app.focale.ui.components.EmptyState
import app.focale.ui.components.LoadingState
import app.focale.ui.components.MediaFilterChips
import app.focale.ui.components.MediaGridScaffold
import app.focale.ui.components.MediaPermissionScreen
import app.focale.ui.components.PartialAccessBanner
import app.focale.ui.components.SortSheet
import app.focale.ui.components.rememberMediaPermissionLauncher
import app.focale.ui.navigation.LocalTabsNavController
import app.focale.ui.navigation.openCollection

/** Onglet Photos : timeline de toute la bibliothèque. */
@Composable
fun TimelineScreen(viewModel: TimelineViewModel = hiltViewModel()) {
    val access by viewModel.access.collectAsStateWithLifecycle()
    if (access == MediaAccess.DENIED) {
        MediaPermissionScreen(onAccessChanged = viewModel::refreshAccess)
    } else {
        TimelineContent(viewModel, partialAccess = access == MediaAccess.PARTIAL)
    }
}

@Composable
private fun TimelineContent(viewModel: TimelineViewModel, partialAccess: Boolean) {
    val items = viewModel.items.collectAsLazyPagingItems()
    val columns by viewModel.columns.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val count by viewModel.count.collectAsStateWithLifecycle()
    val histogram by viewModel.histogram.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val memories by viewModel.memories.collectAsStateWithLifecycle()
    val tabsNav = LocalTabsNavController.current
    val resources = LocalResources.current
    val showMemories = memories.isNotEmpty() && filter == MediaFilter.ALL
    val manageSelection = rememberMediaPermissionLauncher { viewModel.refreshAccess() }
    var showSort by rememberSaveable { mutableStateOf(false) }

    if (showSort) {
        SortSheet(
            sort = sort,
            onSortChange = viewModel::setSort,
            filter = filter,
            onFilterChange = viewModel::setFilter,
            onDismiss = { showSort = false },
        )
    }

    val leadingCount = 1 + (if (partialAccess) 1 else 0) + (if (showMemories) 1 else 0)
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
        leadingItemCount = leadingCount,
        topBar = { scrollBehavior ->
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.tab_photos)) },
                subtitle = if (count > 0) {
                    { Text(pluralStringResource(R.plurals.media_count, count, count)) }
                } else {
                    null
                },
                actions = {
                    IconButton(onClick = { showSort = true }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Tune, contentDescription = stringResource(R.string.sort_title))
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        leadingContent = {
            if (partialAccess) {
                item(key = "partial", span = { GridItemSpan(maxLineSpan) }) {
                    PartialAccessBanner(onManage = manageSelection)
                }
            }
            if (showMemories) {
                item(key = "memories", span = { GridItemSpan(maxLineSpan) }) {
                    MemoriesCarousel(
                        memories = memories,
                        onOpen = { memory -> tabsNav.openCollection(MemoryTitles.title(resources, memory.kind), memory.query) },
                    )
                }
            }
            item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
                MediaFilterChips(selected = filter, onSelect = viewModel::setFilter)
            }
        },
        emptyContent = {
            when {
                syncState is SyncState.Running -> LoadingState(message = stringResource(R.string.indexing_message))
                count == 0 && filter == MediaFilter.ALL -> EmptyState(
                    icon = Icons.Rounded.PhotoLibrary,
                    title = stringResource(R.string.timeline_empty_title),
                    message = stringResource(
                        if (partialAccess && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            R.string.timeline_empty_partial
                        } else {
                            R.string.timeline_empty_message
                        },
                    ),
                )
                count == 0 -> EmptyState(
                    icon = Icons.Rounded.PhotoLibrary,
                    title = stringResource(R.string.filter_empty_title),
                )
            }
        },
    )
}
