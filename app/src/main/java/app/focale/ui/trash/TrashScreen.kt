package app.focale.ui.trash

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
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.SelectAll
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
import app.focale.data.TrashEntry
import app.focale.ui.components.EmptyState
import app.focale.ui.components.LoadingState
import app.focale.ui.components.SelectionIndicator
import app.focale.ui.components.SelectionTopBar
import coil3.compose.AsyncImage
import java.util.concurrent.TimeUnit

/** Corbeille : restauration ou suppression définitive (30 jours de rétention). */
@Composable
fun TrashScreen(onBack: () -> Unit, viewModel: TrashViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    var confirm by rememberSaveable { mutableStateOf<ConfirmKind?>(null) }
    val selecting = selection.isNotEmpty()

    BackHandler(enabled = selecting, onBack = viewModel::clearSelection)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { snackbarHostState.showSnackbar(it.resolve(context)) }
    }

    confirm?.let { kind ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            icon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null) },
            title = {
                Text(
                    if (kind == ConfirmKind.EMPTY) {
                        stringResource(R.string.trash_empty_confirm)
                    } else {
                        pluralStringResource(R.plurals.trash_delete_confirm, selection.size, selection.size)
                    },
                )
            },
            text = { Text(stringResource(R.string.trash_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    if (kind == ConfirmKind.EMPTY) viewModel.emptyTrash() else viewModel.deleteSelection()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selecting) {
                SelectionTopBar(count = selection.size, onClear = viewModel::clearSelection) {
                    IconButton(onClick = viewModel::selectAll, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.action_select_all))
                    }
                }
            } else {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.collection_trash)) },
                    subtitle = { Text(stringResource(R.string.trash_subtitle)) },
                    navigationIcon = {
                        IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        if (!entries.isNullOrEmpty()) {
                            TextButton(onClick = { confirm = ConfirmKind.EMPTY }) {
                                Text(stringResource(R.string.trash_empty_action))
                            }
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        Box(Modifier.fillMaxSize()) {
            val list = entries
            when {
                list == null -> LoadingState()
                list.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.DeleteOutline,
                    title = stringResource(R.string.trash_empty_title),
                    message = stringResource(R.string.trash_empty_message),
                    shape = MaterialShapes.Pill,
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
                    items(list, key = { it.id }) { entry ->
                        TrashCell(
                            entry = entry,
                            selected = entry.id in selection,
                            selectionMode = selecting,
                            onToggle = {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                viewModel.toggle(entry.id)
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
                    TextButton(onClick = viewModel::restoreSelection) {
                        Icon(Icons.Outlined.RestoreFromTrash, contentDescription = null)
                        Text(stringResource(R.string.action_restore), modifier = Modifier.padding(start = 8.dp))
                    }
                    TextButton(onClick = { confirm = ConfirmKind.SELECTION }) {
                        Icon(Icons.Outlined.DeleteForever, contentDescription = null)
                        Text(stringResource(R.string.action_delete), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

private enum class ConfirmKind { SELECTION, EMPTY }

@Composable
private fun TrashCell(entry: TrashEntry, selected: Boolean, selectionMode: Boolean, onToggle: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 0.86f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "trashScale",
    )
    val daysLeft = TimeUnit.MILLISECONDS.toDays((entry.expiresAt - System.currentTimeMillis()).coerceAtLeast(0)).toInt()
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
        ) {
            AsyncImage(
                model = entry.thumbnail,
                contentDescription = entry.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
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
