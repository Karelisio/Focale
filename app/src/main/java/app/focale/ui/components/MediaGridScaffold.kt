package app.focale.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import app.focale.core.model.DateBucket
import app.focale.core.model.GridItem
import app.focale.core.model.MediaQuery
import app.focale.core.util.Formatters
import app.focale.ui.actions.ExtraAction
import app.focale.ui.actions.MediaActions
import app.focale.ui.actions.MediaActionsHost
import app.focale.ui.actions.SelectionActionsBar
import app.focale.ui.navigation.LocalMainChrome
import app.focale.ui.navigation.LocalRootNavController
import app.focale.ui.navigation.openViewer

/**
 * Échafaudage commun des écrans de grille : barre supérieure (remplacée en mode
 * sélection), grille paginée, défilement rapide, barre d'actions flottante,
 * snackbar et dialogues d'actions.
 */
@Composable
fun MediaGridScaffold(
    items: LazyPagingItems<GridItem>,
    query: MediaQuery,
    columns: Int,
    selection: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    onClearSelection: () -> Unit,
    onSelectAll: () -> Unit,
    actions: MediaActions,
    topBar: @Composable (TopAppBarScrollBehavior) -> Unit,
    modifier: Modifier = Modifier,
    onColumnsChange: ((Int) -> Unit)? = null,
    histogram: List<DateBucket> = emptyList(),
    gridIndexOf: (suspend (Long) -> Int?)? = null,
    leadingItemCount: Int = 0,
    leadingContent: (LazyGridScope.() -> Unit)? = null,
    extraSelectionActions: List<ExtraAction> = emptyList(),
    gridState: LazyGridState = rememberLazyGridState(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    onAlbumMovedToVault: (() -> Unit)? = null,
    emptyContent: @Composable () -> Unit = {},
    /**
     * Défilement imbriqué d'une barre supérieure qui ne se replie pas comme une barre
     * d'application (barre de recherche) ; par défaut, celui transmis à [topBar].
     */
    nestedScrollConnection: NestedScrollConnection? = null,
) {
    val context = LocalContext.current
    val rootNav = LocalRootNavController.current
    val chrome = LocalMainChrome.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val selecting = selection.isNotEmpty()

    BackHandler(enabled = selecting, onBack = onClearSelection)
    LaunchedEffect(selecting) { chrome.navigationHidden = selecting }
    DisposableEffect(Unit) { onDispose { chrome.navigationHidden = false } }

    // Au retour de la visionneuse, ramène la grille sur le dernier média consulté.
    LaunchedEffect(chrome.lastViewedMediaId) {
        val id = chrome.lastViewedMediaId ?: return@LaunchedEffect
        chrome.lastViewedMediaId = null
        val visible = gridState.layoutInfo.visibleItemsInfo.any { it.key == "media-$id" }
        if (!visible && gridIndexOf != null) {
            gridIndexOf(id)?.let { gridState.scrollToItem(it + leadingItemCount) }
        }
    }

    MediaActionsHost(
        actions = actions,
        snackbarHostState = snackbarHostState,
        onActionDone = onClearSelection,
        onAlbumMoved = onAlbumMovedToVault,
    )

    Scaffold(
        modifier = modifier.nestedScroll(nestedScrollConnection ?: scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selecting) {
                SelectionTopBar(count = selection.size, onClear = onClearSelection)
            } else {
                topBar(scrollBehavior)
            }
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        Box(Modifier.fillMaxSize()) {
            MediaGrid(
                items = items,
                columns = columns,
                selection = selection,
                onSelectionChange = onSelectionChange,
                onOpen = { rootNav.openViewer(query, it) },
                onColumnsChange = onColumnsChange,
                state = gridState,
                contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection),
                    end = padding.calculateEndPadding(layoutDirection),
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + if (selecting) 104.dp else 16.dp,
                ),
                leadingItemCount = leadingItemCount,
                leadingContent = leadingContent,
            )
            if (histogram.isNotEmpty()) {
                FastScroller(
                    state = gridState,
                    buckets = histogram,
                    label = { Formatters.monthYear(context, it.periodStart) },
                    topPadding = padding.calculateTopPadding(),
                    bottomPadding = padding.calculateBottomPadding(),
                    modifier = Modifier.align(Alignment.TopEnd),
                )
            }
            val loaded = items.loadState.refresh is LoadState.NotLoading
            if (loaded && items.itemCount == 0) emptyContent()

            SelectionActionsBar(
                visible = selecting,
                selectionCount = selection.size,
                onShare = { actions.share(selection) },
                onFavorite = { actions.toggleFavorite(selection) },
                onAddToAlbum = { actions.requestAddToAlbum(selection) },
                onTrash = { actions.trash(selection) },
                onCopy = { actions.requestCopy(selection) },
                onMove = { actions.requestMove(selection) },
                onRename = { selection.firstOrNull()?.let(actions::requestRename) },
                onMoveToVault = { actions.requestMoveToVault(selection) },
                onSelectAll = onSelectAll,
                extraActions = extraSelectionActions,
                onCollage = { actions.createCollage(selection) },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
