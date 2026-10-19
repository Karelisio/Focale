package app.focale.ui.cleanup

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import app.focale.core.image.thumbnail
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaQuery
import app.focale.core.util.Formatters
import app.focale.data.DuplicateGroup
import app.focale.ui.actions.MediaActionsHost
import app.focale.ui.components.Choice
import app.focale.ui.components.ConnectedChoiceGroup
import app.focale.ui.components.EmptyState
import app.focale.ui.components.LoadingState
import app.focale.ui.components.SelectionIndicator
import app.focale.ui.navigation.LocalRootNavController
import app.focale.ui.navigation.openViewer
import coil3.compose.AsyncImage

/** Nettoyage : doublons (similarité perceptuelle), photos floues (netteté), captures et grosses vidéos. */
@Composable
fun CleanupScreen(onBack: () -> Unit, viewModel: CleanupViewModel = hiltViewModel()) {
    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val duplicates by viewModel.duplicates.collectAsStateWithLifecycle()
    val blurry by viewModel.blurry.collectAsStateWithLifecycle()
    val screenshots by viewModel.screenshots.collectAsStateWithLifecycle()
    val largeVideos by viewModel.largeVideos.collectAsStateWithLifecycle()
    val threshold by viewModel.threshold.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val rootNav = LocalRootNavController.current

    MediaActionsHost(actions = viewModel.actions, snackbarHostState = snackbarHostState, onActionDone = viewModel::onActionDone)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.cleanup_title)) },
                subtitle = { Text(stringResource(R.string.cleanup_summary)) },
                navigationIcon = {
                    IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (tab == CleanupTab.DUPLICATES) {
                        IconButton(onClick = viewModel::refreshDuplicates, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
                        }
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        Box(Modifier.fillMaxSize()) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 104.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection) + 12.dp,
                    end = padding.calculateEndPadding(layoutDirection) + 12.dp,
                    top = padding.calculateTopPadding(),
                    bottom = padding.calculateBottomPadding() + 112.dp,
                ),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "tabs", span = { GridItemSpan(maxLineSpan) }) {
                    ConnectedChoiceGroup(
                        choices = listOf(
                            Choice(CleanupTab.DUPLICATES, stringResource(R.string.cleanup_duplicates), Icons.Outlined.ContentCopy),
                            Choice(CleanupTab.BLURRY, stringResource(R.string.cleanup_blurry), Icons.Outlined.BlurOn),
                            Choice(CleanupTab.SCREENSHOTS, stringResource(R.string.cleanup_screenshots), Icons.Outlined.Screenshot),
                            Choice(CleanupTab.LARGE_VIDEOS, stringResource(R.string.cleanup_large_videos), Icons.Outlined.VideoFile),
                        ),
                        selected = tab,
                        onSelect = viewModel::setTab,
                        compact = true,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                when (tab) {
                    CleanupTab.DUPLICATES -> {
                        val groups = duplicates
                        when {
                            groups == null -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) {
                                LoadingState(Modifier.padding(top = 48.dp), message = stringResource(R.string.cleanup_searching))
                            }
                            groups.isEmpty() -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                                EmptyState(
                                    icon = Icons.Outlined.CheckCircle,
                                    title = stringResource(R.string.cleanup_no_duplicates),
                                    message = stringResource(R.string.cleanup_analysis_note),
                                    shape = MaterialShapes.Sunny,
                                )
                            }
                            else -> items(groups, key = { "group-${it.keepId}" }, span = { GridItemSpan(maxLineSpan) }) { group ->
                                DuplicateGroupCard(
                                    group = group,
                                    selection = selection,
                                    onToggle = viewModel::toggle,
                                    onOpen = { item ->
                                        rootNav.openViewer(MediaQuery(mediaIds = group.items.map { it.id }), item)
                                    },
                                )
                            }
                        }
                    }
                    CleanupTab.BLURRY -> {
                        item(key = "threshold", span = { GridItemSpan(maxLineSpan) }) {
                            SensitivitySlider(threshold, viewModel::setThreshold)
                        }
                        val photos = blurry
                        when {
                            photos == null -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { LoadingState() }
                            photos.isEmpty() -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                                EmptyState(
                                    icon = Icons.Outlined.CheckCircle,
                                    title = stringResource(R.string.cleanup_no_blurry),
                                    message = stringResource(R.string.cleanup_analysis_note),
                                    shape = MaterialShapes.Sunny,
                                )
                            }
                            else -> items(photos, key = { "blur-${it.id}" }) { item ->
                                SelectableThumb(
                                    item = item,
                                    selected = item.id in selection,
                                    onToggle = { viewModel.toggle(item.id) },
                                    onOpen = { rootNav.openViewer(MediaQuery(mediaIds = photos.map { it.id }), item) },
                                )
                            }
                        }
                    }
                    CleanupTab.SCREENSHOTS, CleanupTab.LARGE_VIDEOS -> {
                        val videos = tab == CleanupTab.LARGE_VIDEOS
                        val list = if (videos) largeVideos else screenshots
                        when {
                            list == null -> item(key = "loading", span = { GridItemSpan(maxLineSpan) }) { LoadingState() }
                            list.isEmpty() -> item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                                EmptyState(
                                    icon = Icons.Outlined.CheckCircle,
                                    title = stringResource(if (videos) R.string.cleanup_no_large_videos else R.string.cleanup_no_screenshots),
                                    shape = MaterialShapes.Sunny,
                                )
                            }
                            else -> {
                                item(key = "summary", span = { GridItemSpan(maxLineSpan) }) {
                                    val total = Formatters.fileSize(LocalContext.current, list.sumOf { it.size })
                                    Text(
                                        text = if (videos) {
                                            pluralStringResource(R.plurals.cleanup_large_videos_summary, list.size, list.size, total)
                                        } else {
                                            pluralStringResource(R.plurals.cleanup_screenshots_summary, list.size, list.size, total)
                                        },
                                        style = MaterialTheme.typography.titleSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                                    )
                                }
                                items(list, key = { "${tab.name}-${it.id}" }) { item ->
                                    SelectableThumb(
                                        item = item,
                                        selected = item.id in selection,
                                        onToggle = { viewModel.toggle(item.id) },
                                        onOpen = { rootNav.openViewer(MediaQuery(mediaIds = list.map { it.id }), item) },
                                        label = if (videos) Formatters.fileSize(LocalContext.current, item.size) else null,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            CleanupBottomBar(
                selectionCount = selection.size,
                onSuggest = viewModel::selectSuggested,
                onClear = viewModel::clearSelection,
                onTrash = viewModel::trashSelection,
                suggestLabel = stringResource(
                    if (tab == CleanupTab.DUPLICATES) R.string.cleanup_select_suggested else R.string.action_select_all,
                ),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun DuplicateGroupCard(
    group: DuplicateGroup,
    selection: Set<Long>,
    onToggle: (Long) -> Unit,
    onOpen: (MediaItem) -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        Column(Modifier.padding(vertical = 14.dp)) {
            Text(
                text = pluralStringResource(R.plurals.cleanup_similar, group.items.size, group.items.size),
                style = MaterialTheme.typography.titleMediumEmphasized,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                items(group.items, key = { it.id }) { item ->
                    Box(Modifier.size(112.dp)) {
                        SelectableThumb(item = item, selected = item.id in selection, onToggle = { onToggle(item.id) }, onOpen = { onOpen(item) })
                        if (item.id == group.keepId) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .align(Alignment.BottomStart)
                                    .padding(6.dp)
                                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Icon(Icons.Outlined.Star, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(12.dp))
                                Text(
                                    stringResource(R.string.cleanup_best),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(start = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Miniature sélectionnable : appui = sélection, appui long = ouverture. */
@Composable
private fun SelectableThumb(
    item: MediaItem,
    selected: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    label: String? = null,
) {
    val haptics = LocalHapticFeedback.current
    val scale by animateFloatAsState(
        targetValue = if (selected) 0.86f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "cleanupScale",
    )
    Box(
        Modifier
            .aspectRatio(1f)
            .combinedClickable(
                onClick = {
                    haptics.performHapticFeedback(if (selected) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                    onToggle()
                },
                onLongClick = onOpen,
            ),
    ) {
        AsyncImage(
            model = item.thumbnail(),
            contentDescription = item.displayName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(if (selected) 22.dp else 12.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        )
        SelectionIndicator(selected = selected, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp))
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.inverseOnSurface,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(6.dp)
                    .background(MaterialTheme.colorScheme.inverseSurface.copy(alpha = 0.8f), RoundedCornerShape(50))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun SensitivitySlider(value: Float, onChange: (Float) -> Unit) {
    val state = rememberSliderState(value = value, trackRange = 10f..120f)
    LaunchedEffect(state.value) { onChange(state.value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        Text(stringResource(R.string.cleanup_sensitivity), style = MaterialTheme.typography.titleSmall)
        Slider(state = state, onValueChange = { state.value = it })
    }
}

@Composable
private fun CleanupBottomBar(
    selectionCount: Int,
    onSuggest: () -> Unit,
    onClear: () -> Unit,
    onTrash: () -> Unit,
    suggestLabel: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shadowElevation = 6.dp,
        modifier = modifier
            .navigationBarsPadding()
            .padding(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (selectionCount == 0) {
                TextButton(onClick = onSuggest) { Text(suggestLabel) }
            } else {
                TextButton(onClick = onClear) { Text(stringResource(R.string.action_clear_selection)) }
                Button(
                    onClick = onTrash,
                    shapes = ButtonDefaults.shapes(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Text(
                        pluralStringResource(R.plurals.cleanup_trash, selectionCount, selectionCount),
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
    }
}
