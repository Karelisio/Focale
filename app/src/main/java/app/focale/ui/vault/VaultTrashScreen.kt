package app.focale.ui.vault

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focale.R
import app.focale.ui.components.EmptyState
import app.focale.ui.components.SelectionIndicator
import app.focale.ui.components.SelectionTopBar
import app.focale.vault.VaultTrashEntry
import coil3.compose.AsyncImage
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

/**
 * Corbeille du coffre : éléments supprimés, chiffrés, avec les jours qui leur restent ; les
 * remettre dans le coffre ou les effacer définitivement. Captures d'écran interdites ; le
 * verrouillage referme l'écran.
 */
@Composable
fun VaultTrashScreen(onBack: () -> Unit, viewModel: VaultTrashViewModel = hiltViewModel()) {
    SecureWindow()
    val context = LocalContext.current
    val unlocked by viewModel.unlocked.collectAsStateWithLifecycle()
    LaunchedEffect(unlocked) { if (!unlocked) onBack() }
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var confirm by rememberSaveable { mutableStateOf<TrashConfirm?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { launch { snackbarHostState.showSnackbar(it.resolve(context)) } }
    }
    confirm?.let { kind ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null) },
            title = {
                Text(
                    if (kind == TrashConfirm.EMPTY) {
                        stringResource(R.string.trash_empty_confirm)
                    } else {
                        pluralStringResource(R.plurals.trash_delete_confirm, selection.size, selection.size)
                    },
                )
            },
            text = { Text(stringResource(R.string.vault_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    if (kind == TrashConfirm.EMPTY) viewModel.emptyTrash() else viewModel.deleteSelection()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    VaultTrashContent(
        entries = entries,
        selection = selection,
        busy = busy,
        snackbarHostState = snackbarHostState,
        onBack = onBack,
        onLock = viewModel::lock,
        onToggle = viewModel::toggle,
        onSelectAll = viewModel::selectAll,
        onClearSelection = viewModel::clearSelection,
        onRestoreSelection = viewModel::restoreSelection,
        onDeleteSelection = { confirm = TrashConfirm.SELECTION },
        onEmpty = { confirm = TrashConfirm.EMPTY },
    )
}

private enum class TrashConfirm { SELECTION, EMPTY }

/** Contenu de la corbeille, sans état propre (captures de référence). */
@Composable
internal fun VaultTrashContent(
    entries: List<VaultTrashEntry>?,
    selection: Set<Long>,
    busy: Boolean,
    snackbarHostState: SnackbarHostState,
    onBack: () -> Unit,
    onLock: () -> Unit,
    onToggle: (Long) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onRestoreSelection: () -> Unit,
    onDeleteSelection: () -> Unit,
    onEmpty: () -> Unit,
    now: Long = System.currentTimeMillis(),
) {
    val haptics = LocalHapticFeedback.current
    val selecting = selection.isNotEmpty()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
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
                    title = { Text(stringResource(R.string.vault_trash_title)) },
                    subtitle = { Text(stringResource(R.string.vault_trash_subtitle)) },
                    navigationIcon = {
                        IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        if (!entries.isNullOrEmpty()) {
                            TextButton(onClick = onEmpty, enabled = !busy) { Text(stringResource(R.string.trash_empty_action)) }
                        }
                        IconButton(onClick = onLock, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.action_lock))
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        Box(Modifier.fillMaxSize()) {
            when {
                entries == null -> Unit
                entries.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.DeleteOutline,
                    title = stringResource(R.string.vault_trash_empty_title),
                    message = stringResource(R.string.vault_trash_empty_message),
                    shape = MaterialShapes.Pill,
                    modifier = Modifier.padding(padding),
                )
                else -> LazyVerticalGrid(
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
                    if (busy) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                        }
                    }
                    items(entries, key = { it.item.id }) { entry ->
                        TrashCell(
                            entry = entry,
                            daysLeft = TimeUnit.MILLISECONDS.toDays((entry.expiresAt - now).coerceAtLeast(0)).toInt(),
                            selected = entry.item.id in selection,
                            selectionMode = selecting,
                            onToggle = {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                onToggle(entry.item.id)
                            },
                        )
                    }
                }
            }
            if (selecting) {
                HorizontalFloatingToolbar(
                    expanded = true,
                    colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp),
                ) {
                    TextButton(onClick = onRestoreSelection, enabled = !busy) {
                        Icon(Icons.Outlined.RestoreFromTrash, contentDescription = null)
                        Text(stringResource(R.string.vault_trash_restore), modifier = Modifier.padding(start = 8.dp))
                    }
                    TextButton(onClick = onDeleteSelection, enabled = !busy) {
                        Icon(Icons.Outlined.DeleteForever, contentDescription = null)
                        Text(stringResource(R.string.action_delete), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

/** Case d'un élément de la corbeille : miniature chiffrée et jours restants ; un appui sélectionne. */
@Composable
private fun TrashCell(entry: VaultTrashEntry, daysLeft: Int, selected: Boolean, selectionMode: Boolean, onToggle: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 0.86f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "vaultTrashScale",
    )
    Box(
        Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onToggle, onLongClick = onToggle),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(if (selected) 24.dp else 10.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = entry.item.thumbFileName
            if (thumb != null) {
                AsyncImage(
                    model = rememberVaultThumbnail(thumb),
                    contentDescription = entry.item.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Rounded.Image, contentDescription = entry.item.displayName, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                text = pluralStringResource(R.plurals.trash_days_left, daysLeft, daysLeft),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        if (selectionMode) {
            SelectionIndicator(selected = selected, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
        }
    }
}
