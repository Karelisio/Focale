package app.focale.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.focale.core.model.DateBucket
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter

/**
 * Barre de défilement rapide avec bulle de date.
 *
 * La date affichée provient d'un histogramme des périodes ([buckets]) calculé en SQL :
 * elle est exacte même pour les pages pas encore chargées.
 */
@Composable
fun FastScroller(
    state: LazyGridState,
    buckets: List<DateBucket>,
    label: (DateBucket) -> String,
    modifier: Modifier = Modifier,
    topPadding: Dp = 0.dp,
    bottomPadding: Dp = 0.dp,
) {
    val totalItems by remember { derivedStateOf { state.layoutInfo.totalItemsCount } }
    val totalMedia = remember(buckets) { buckets.sumOf { it.count } }
    if (totalItems < MIN_ITEMS || totalMedia == 0) return

    val haptics = LocalHapticFeedback.current
    var visible by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    var dragTarget by remember { mutableIntStateOf(-1) }

    // Un seul défilement à la fois, toujours vers la dernière position demandée.
    LaunchedEffect(state) {
        snapshotFlow { dragTarget }.filter { it >= 0 }.collectLatest { state.scrollToItem(it) }
    }

    // Visible pendant le défilement, masquée après un court délai d'inactivité.
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress || dragging }.collectLatest { active ->
            if (active) {
                visible = true
            } else {
                delay(HIDE_DELAY_MS)
                visible = false
            }
        }
    }

    val scrollProgress by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val visibleCount = info.visibleItemsInfo.size
            val scrollable = (info.totalItemsCount - visibleCount).coerceAtLeast(1)
            (state.firstVisibleItemIndex.toFloat() / scrollable).coerceIn(0f, 1f)
        }
    }
    val progress = if (dragging) dragProgress else scrollProgress

    val bucketIndex = remember(buckets, totalMedia, progress) {
        val target = (progress * totalMedia).toInt()
        var cumulative = 0
        buckets.indexOfFirst { cumulative += it.count; cumulative > target }.let { if (it < 0) buckets.lastIndex else it }
    }
    LaunchedEffect(bucketIndex, dragging) {
        if (dragging) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    BoxWithConstraints(modifier.fillMaxHeight().padding(top = topPadding, bottom = bottomPadding)) {
        val density = LocalDensity.current
        val thumbHeight = 52.dp
        val trackPx = with(density) { (maxHeight - thumbHeight).toPx() }.coerceAtLeast(1f)
        val currentTrackPx by rememberUpdatedState(trackPx)
        val currentScrollProgress by rememberUpdatedState(scrollProgress)
        val thumbOffsetPx = (progress * trackPx).roundToInt()

        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.offset { IntOffset(0, thumbOffsetPx) },
            ) {
                AnimatedVisibility(
                    visible = dragging,
                    enter = scaleIn(transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)) + fadeIn(),
                    exit = scaleOut(transformOrigin = androidx.compose.ui.graphics.TransformOrigin(1f, 0.5f)) + fadeOut(),
                ) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp, topEnd = 28.dp, bottomEnd = 6.dp),
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shadowElevation = 3.dp,
                        modifier = Modifier.padding(end = 12.dp),
                    ) {
                        Text(
                            text = buckets.getOrNull(bucketIndex)?.let(label).orEmpty(),
                            style = MaterialTheme.typography.titleMediumEmphasized,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                }
                val thumbWidth by animateDpAsState(
                    targetValue = if (dragging) 36.dp else 28.dp,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                    label = "thumbWidth",
                )
                Surface(
                    shape = RoundedCornerShape(topStart = 26.dp, bottomStart = 26.dp),
                    color = if (dragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = if (dragging) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                    shadowElevation = 2.dp,
                    modifier = Modifier
                        .width(thumbWidth)
                        .height(thumbHeight)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures(
                                onDragStart = {
                                    dragging = true
                                    dragProgress = currentScrollProgress
                                    haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                },
                                onDragEnd = { dragging = false },
                                onDragCancel = { dragging = false },
                                onVerticalDrag = { change, delta ->
                                    change.consume()
                                    dragProgress = (dragProgress + delta / currentTrackPx).coerceIn(0f, 1f)
                                    dragTarget = (dragProgress * (state.layoutInfo.totalItemsCount - 1))
                                        .roundToInt()
                                        .coerceAtLeast(0)
                                },
                            )
                        },
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.UnfoldMore, contentDescription = null, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

private const val MIN_ITEMS = 60
private const val HIDE_DELAY_MS = 1400L
