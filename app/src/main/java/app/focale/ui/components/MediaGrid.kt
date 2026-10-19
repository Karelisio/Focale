package app.focale.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.toIntRect
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import app.focale.R
import app.focale.core.image.ThumbnailSizes
import app.focale.core.image.thumbnail
import app.focale.core.model.GridItem
import app.focale.core.model.GroupLevel
import app.focale.core.model.MediaItem
import app.focale.core.settings.AppSettings
import app.focale.core.util.Formatters
import app.focale.ui.theme.thumbnailCornerRadius
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.size.Precision
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Espacement entre miniatures. */
private val GridSpacing = 3.dp

/**
 * Grille paginée de médias, partagée par la timeline, les albums, les favoris et la
 * recherche.
 *
 * - pincement à deux doigts : 2 à 7 colonnes ([onColumnsChange]) ;
 * - appui long puis glissement : sélection d'une plage, avec défilement automatique
 *   près des bords et retour haptique ;
 * - en-têtes de période sur toute la largeur.
 *
 * @param leadingItemCount nombre d'éléments ajoutés par [leadingContent] avant les médias.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaGrid(
    items: LazyPagingItems<GridItem>,
    columns: Int,
    selection: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    onOpen: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    onColumnsChange: ((Int) -> Unit)? = null,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(),
    leadingItemCount: Int = 0,
    leadingContent: (LazyGridScope.() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val leftPaddingPx = with(density) { contentPadding.calculateLeftPadding(layoutDirection).roundToPx() }
    val selectionMode = selection.isNotEmpty()
    val currentSelection by rememberUpdatedState(selection)
    val autoScrollSpeed = remember { mutableFloatStateOf(0f) }
    val selectLabel = stringResource(R.string.action_select)

    // Défilement automatique pendant une sélection glissée près d'un bord.
    LaunchedEffect(autoScrollSpeed.floatValue) {
        if (autoScrollSpeed.floatValue != 0f) {
            while (isActive) {
                state.scrollBy(autoScrollSpeed.floatValue)
                delay(10)
            }
        }
    }

    BoxWithConstraints(modifier) {
        val cellPx = with(density) { (maxWidth / columns).roundToPx() }
        val bucket = ThumbnailSizes.bucketFor(cellPx)
        LaunchedEffect(bucket) { ThumbnailSizes.lastGridBucket = bucket }
        val corner = thumbnailCornerRadius(columns)

        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = state,
            contentPadding = contentPadding,
            horizontalArrangement = Arrangement.spacedBy(GridSpacing),
            verticalArrangement = Arrangement.spacedBy(GridSpacing),
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (onColumnsChange != null) {
                        Modifier.pinchToChangeColumns(columns, haptics, onColumnsChange)
                    } else {
                        Modifier
                    },
                )
                .dragToSelect(
                    state = state,
                    items = items,
                    leadingItemCount = leadingItemCount,
                    leftPaddingPx = leftPaddingPx,
                    haptics = haptics,
                    selection = { currentSelection },
                    onSelectionChange = onSelectionChange,
                    autoScrollThreshold = with(density) { 56.dp.toPx() },
                    onAutoScroll = { autoScrollSpeed.floatValue = it },
                ),
        ) {
            leadingContent?.invoke(this)
            items(
                count = items.itemCount,
                key = items.itemKey { it.key },
                contentType = items.itemContentType { if (it is GridItem.Header) "header" else "media" },
                span = { index ->
                    if (items.peek(index) is GridItem.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1)
                },
            ) { index ->
                when (val entry = items[index]) {
                    is GridItem.Header -> GridHeader(entry, Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null))
                    is GridItem.Media -> {
                        val item = entry.item
                        val selected = item.id in selection
                        MediaCell(
                            item = item,
                            thumbnailBucket = bucket,
                            corner = corner,
                            selected = selected,
                            selectionMode = selectionMode,
                            modifier = Modifier
                                .animateItem(fadeInSpec = null, fadeOutSpec = null)
                                // L'appui long tactile est géré par la sélection glissée de la
                                // grille ; onLongClick sert aussi aux services d'accessibilité.
                                .combinedClickable(
                                    onLongClickLabel = selectLabel,
                                    onLongClick = { onSelectionChange(currentSelection + item.id) },
                                    onClick = {
                                        if (selectionMode) {
                                            haptics.performHapticFeedback(
                                                if (selected) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                                            )
                                            onSelectionChange(if (selected) selection - item.id else selection + item.id)
                                        } else {
                                            onOpen(item)
                                        }
                                    },
                                ),
                        )
                    }
                    null -> PlaceholderCell(corner)
                }
            }
        }
    }
}

/** Miniature d'un média, avec badges et animation de sélection. */
@Composable
fun MediaCell(
    item: MediaItem,
    thumbnailBucket: Int,
    corner: Dp,
    selected: Boolean,
    selectionMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scale by animateFloatAsState(
        targetValue = if (selected) 0.84f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "cellScale",
    )
    val radius by animateDpAsState(
        targetValue = if (selected) corner + 14.dp else corner,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "cellCorner",
    )
    val request = remember(item.id, item.dateModified, thumbnailBucket) {
        ImageRequest.Builder(context)
            .data(item.thumbnail())
            .size(thumbnailBucket)
            .precision(Precision.INEXACT)
            .memoryCacheKey(ThumbnailSizes.memoryKey(item.id, item.dateModified, thumbnailBucket))
            .build()
    }
    Box(modifier.aspectRatio(1f)) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(radius))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            AsyncImage(
                model = request,
                contentDescription = item.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .sharedMedia(item.id),
            )
            when {
                item.isVideo -> MediaBadge(
                    text = Formatters.duration(item.durationMs),
                    showPlayIcon = true,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
                item.isGif -> MediaBadge(
                    text = stringResource(R.string.badge_gif),
                    showPlayIcon = false,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
                else -> MotionPhotoBadge(item, Modifier.align(Alignment.BottomEnd))
            }
        }
        if (selectionMode) {
            SelectionIndicator(
                selected = selected,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
            )
        }
    }
}

@Composable
private fun MediaBadge(text: String, showPlayIcon: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .padding(5.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showPlayIcon) {
            Icon(
                imageVector = Icons.Rounded.PlayArrow,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(12.dp),
            )
        }
        Text(
            text = text,
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun PlaceholderCell(corner: Dp) {
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(corner))
            .background(MaterialTheme.colorScheme.surfaceContainer),
    )
}

/** En-tête de période : Display pour les années, Headline pour les mois, Title pour les jours. */
@Composable
fun GridHeader(header: GridItem.Header, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val label = remember(header.periodStart, header.level) {
        Formatters.periodLabel(context, header.periodStart, header.level)
    }
    val typography = MaterialTheme.typography
    val style = when (header.level) {
        GroupLevel.DAY -> typography.titleLargeEmphasized
        GroupLevel.MONTH -> typography.headlineSmallEmphasized
        GroupLevel.YEAR -> typography.displaySmallEmphasized
    }
    Text(
        text = label,
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 20.dp, bottom = 8.dp),
    )
}

/**
 * Pincement à deux doigts : écarter = moins de colonnes, pincer = plus de colonnes.
 * Les gestes à un doigt ne sont pas consommés et restent au défilement de la grille.
 */
private fun Modifier.pinchToChangeColumns(
    columns: Int,
    haptics: HapticFeedback,
    onColumnsChange: (Int) -> Unit,
): Modifier = pointerInput(columns) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        var accumulated = 1f
        var current = columns
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.count { it.pressed }
            if (pressed >= 2 && event.calculateCentroidSize() > 0f) {
                accumulated *= event.calculateZoom()
                val next = when {
                    accumulated > ZOOM_STEP -> current - 1
                    accumulated < 1f / ZOOM_STEP -> current + 1
                    else -> current
                }.coerceIn(AppSettings.MIN_COLUMNS, AppSettings.MAX_COLUMNS)
                if (next != current) {
                    current = next
                    accumulated = 1f
                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    onColumnsChange(next)
                }
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
    }
}

private const val ZOOM_STEP = 1.28f

/**
 * Sélection par appui long puis glissement : la plage entre le premier élément touché
 * et l'élément sous le doigt est ajoutée à la sélection initiale.
 *
 * Les positions des cases (`LazyGridItemInfo.offset`) excluent les marges internes de la
 * grille : le point touché est ramené dans ce repère, sinon la sélection serait décalée
 * de la hauteur de la barre supérieure (une rangée plus bas que le doigt).
 */
private fun Modifier.dragToSelect(
    state: LazyGridState,
    items: LazyPagingItems<GridItem>,
    leadingItemCount: Int,
    leftPaddingPx: Int,
    haptics: HapticFeedback,
    selection: () -> Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    autoScrollThreshold: Float,
    onAutoScroll: (Float) -> Unit,
): Modifier = pointerInput(items, leadingItemCount, leftPaddingPx) {
    var initialIndex: Int? = null
    var currentIndex: Int? = null
    var baseSelection: Set<Long> = emptySet()

    fun indexAt(position: Offset): Int? {
        val layoutInfo = state.layoutInfo
        val point = position.round() - IntOffset(leftPaddingPx, layoutInfo.beforeContentPadding)
        return layoutInfo.visibleItemsInfo.firstOrNull { info ->
            info.size.toIntRect().contains(point - info.offset)
        }?.index?.minus(leadingItemCount)?.takeIf { it >= 0 && it < items.itemCount }
    }

    fun mediaIdAt(index: Int): Long? = (items.peek(index) as? GridItem.Media)?.item?.id

    detectDragGesturesAfterLongPress(
        onDragStart = { offset ->
            val index = indexAt(offset) ?: return@detectDragGesturesAfterLongPress
            val id = mediaIdAt(index) ?: return@detectDragGesturesAfterLongPress
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            baseSelection = selection()
            initialIndex = index
            currentIndex = index
            onSelectionChange(baseSelection + id)
        },
        onDragEnd = {
            initialIndex = null
            onAutoScroll(0f)
        },
        onDragCancel = {
            initialIndex = null
            onAutoScroll(0f)
        },
        onDrag = { change, _ ->
            val start = initialIndex ?: return@detectDragGesturesAfterLongPress
            val viewportHeight = state.layoutInfo.viewportSize.height
            val y = change.position.y
            onAutoScroll(
                when {
                    y > viewportHeight - autoScrollThreshold -> (y - (viewportHeight - autoScrollThreshold)) / 3f
                    y < autoScrollThreshold -> -(autoScrollThreshold - y) / 3f
                    else -> 0f
                },
            )
            val index = indexAt(change.position) ?: return@detectDragGesturesAfterLongPress
            if (index != currentIndex) {
                currentIndex = index
                val range = if (index >= start) start..index else index..start
                val ids = range.mapNotNull(::mediaIdAt)
                haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                onSelectionChange(baseSelection + ids)
            }
        },
    )
}
