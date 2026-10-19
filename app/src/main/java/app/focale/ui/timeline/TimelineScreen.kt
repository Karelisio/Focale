package app.focale.ui.timeline

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import app.focale.R
import app.focale.core.mediastore.SyncState
import app.focale.core.model.MediaFilter
import app.focale.core.permissions.MediaAccess
import app.focale.core.util.Formatters
import app.focale.ui.components.EmptyState
import app.focale.ui.components.FastScroller
import app.focale.ui.components.LoadingState
import app.focale.ui.components.MediaFilterChips
import app.focale.ui.components.MediaGrid
import app.focale.ui.components.MediaPermissionScreen
import app.focale.ui.components.PartialAccessBanner
import app.focale.ui.components.SelectionTopBar
import app.focale.ui.components.rememberMediaPermissionLauncher
import app.focale.ui.navigation.LocalMainChrome
import app.focale.ui.navigation.LocalRootNavController
import app.focale.ui.navigation.openViewer

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
    val context = LocalContext.current
    val rootNav = LocalRootNavController.current
    val chrome = LocalMainChrome.current
    val items = viewModel.items.collectAsLazyPagingItems()
    val columns by viewModel.columns.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val count by viewModel.count.collectAsStateWithLifecycle()
    val histogram by viewModel.histogram.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val manageSelection = rememberMediaPermissionLauncher { viewModel.refreshAccess() }

    BackHandler(enabled = selection.isNotEmpty()) { viewModel.clearSelection() }
    LaunchedEffect(selection.isNotEmpty()) { chrome.navigationHidden = selection.isNotEmpty() }
    DisposableEffect(Unit) { onDispose { chrome.navigationHidden = false } }

    // Au retour de la visionneuse, ramène la grille sur le dernier média consulté.
    val leadingCount = if (partialAccess) 2 else 1
    LaunchedEffect(chrome.lastViewedMediaId) {
        val id = chrome.lastViewedMediaId ?: return@LaunchedEffect
        chrome.lastViewedMediaId = null
        val visible = gridState.layoutInfo.visibleItemsInfo.any { it.key == "media-$id" }
        if (!visible) viewModel.gridIndexOf(id)?.let { gridState.scrollToItem(it + leadingCount) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            if (selection.isEmpty()) {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.tab_photos)) },
                    subtitle = if (count > 0) {
                        { Text(pluralStringResource(R.plurals.media_count, count, count)) }
                    } else {
                        null
                    },
                    scrollBehavior = scrollBehavior,
                )
            } else {
                SelectionTopBar(count = selection.size, onClear = viewModel::clearSelection)
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            val layoutDirection = LocalLayoutDirection.current
            MediaGrid(
                items = items,
                columns = columns,
                selection = selection,
                onSelectionChange = viewModel::setSelection,
                onOpen = { rootNav.openViewer(query, it) },
                onColumnsChange = viewModel::setColumns,
                state = gridState,
                contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 16.dp,
                ),
                leadingItemCount = leadingCount,
                leadingContent = {
                    if (partialAccess) {
                        item(key = "partial", span = { GridItemSpan(maxLineSpan) }) {
                            PartialAccessBanner(onManage = manageSelection)
                        }
                    }
                    item(key = "filters", span = { GridItemSpan(maxLineSpan) }) {
                        MediaFilterChips(selected = filter, onSelect = viewModel::setFilter)
                    }
                },
            )
            FastScroller(
                state = gridState,
                buckets = histogram,
                label = { Formatters.monthYear(context, it.periodStart) },
                topPadding = padding.calculateTopPadding(),
                bottomPadding = padding.calculateBottomPadding(),
                modifier = Modifier.align(Alignment.TopEnd),
            )

            val refreshDone = items.loadState.refresh is LoadState.NotLoading
            if (items.itemCount == 0 && refreshDone) {
                when {
                    syncState is SyncState.Running -> LoadingState(
                        message = stringResource(R.string.indexing_message),
                        modifier = Modifier.align(Alignment.Center),
                    )
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
            }
        }
    }
}
