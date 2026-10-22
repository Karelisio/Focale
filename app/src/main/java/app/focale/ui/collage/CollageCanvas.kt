package app.focale.ui.collage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.collage.CellFrame
import app.focale.collage.CellFraming
import app.focale.collage.CellRect
import app.focale.collage.CollageGeometry
import app.focale.collage.CollageState
import app.focale.core.model.MediaItem
import app.focale.core.util.Formatters
import kotlin.math.min
import kotlin.math.roundToInt

/** Photo emmenée vers une autre case : case de départ, doigt, case visée. */
@Immutable
private data class SwapDrag(val from: Int, val position: Offset, val target: Int?)

private enum class Gesture { TAP, FRAME, SWAP }

/**
 * Aperçu du collage, à sa proportion : fond, cases, photos cadrées et coins arrondis, comme
 * l'export ([app.focale.collage.CollageRenderer]). Dans une case, un glisser cadre la photo et un
 * pincement l'agrandit autour des doigts ; un appui long puis un glisser emmène la photo sur une
 * autre case, avec qui elle s'échange au relâchement. Chaque case est un élément TalkBack, lu avec
 * sa photo, dont les actions échangent la photo avec la case voisine, l'agrandissent, la réduisent
 * ou la recentrent.
 */
@Composable
internal fun CollageCanvas(
    state: CollageState,
    items: Map<Long, MediaItem>,
    previews: Map<Long, ImageBitmap>,
    onFrameStart: () -> Unit,
    onFrame: (cell: Int, pan: Offset, zoom: Float, focus: Offset, cellSize: Size, imageSize: Size) -> Unit,
    onSwap: (from: Int, to: Int) -> Unit,
    onZoomStep: (cell: Int, factor: Float, cellSize: Size, imageSize: Size) -> Unit,
    onResetFrame: (cell: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val placeholder = MaterialTheme.colorScheme.surfaceContainerHighest
    val accent = MaterialTheme.colorScheme.primary
    var swap by remember { mutableStateOf<SwapDrag?>(null) }
    BoxWithConstraints(modifier) {
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val gap = CollageGeometry.gap(state.spacing, width, height)
        val cells = remember(state.layout, width, height, gap) { CollageGeometry.cells(state.layout, width, height, gap) }
        val currentCells by rememberUpdatedState(cells)
        val currentState by rememberUpdatedState(state)
        val currentPreviews by rememberUpdatedState(previews)
        val currentOnFrameStart by rememberUpdatedState(onFrameStart)
        val currentOnFrame by rememberUpdatedState(onFrame)
        val currentOnSwap by rememberUpdatedState(onSwap)
        val ring = with(density) { 3.dp.toPx() }
        val ghostSide = with(density) { GHOST_SIDE.toPx() }
        val ghostCorner = with(density) { 12.dp.toPx() }

        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = CollageGeometry.cellAt(currentCells, down.position.x, down.position.y) ?: return@awaitEachGesture
                        down.consume()
                        // Rien ne bouge avant le délai de l'appui long : c'est un échange.
                        val gesture = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            awaitGesture(down, viewConfiguration.touchSlop)
                        } ?: Gesture.SWAP
                        when (gesture) {
                            Gesture.TAP -> Unit
                            Gesture.FRAME -> {
                                val photo = currentState.photos[start]
                                val image = currentPreviews[photo]
                                if (image != null) currentOnFrameStart()
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.none { it.pressed }) break
                                    val cell = currentCells.getOrNull(start)
                                    if (image != null && cell != null) {
                                        val pan = event.calculatePan()
                                        val zoom = event.calculateZoom()
                                        if (pan != Offset.Zero || zoom != 1f) {
                                            val focus = event.calculateCentroid(useCurrent = true) - Offset(cell.left, cell.top)
                                            currentOnFrame(
                                                start,
                                                pan,
                                                zoom,
                                                focus,
                                                Size(cell.width, cell.height),
                                                Size(image.width.toFloat(), image.height.toFloat()),
                                            )
                                        }
                                    }
                                    event.changes.forEach(PointerInputChange::consume)
                                }
                            }
                            Gesture.SWAP -> {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                var drag = SwapDrag(start, down.position, target = null)
                                swap = drag
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        val target = CollageGeometry.cellAt(currentCells, change.position.x, change.position.y)
                                            ?.takeIf { it != start }
                                        if (target != null && target != drag.target) haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                        drag = drag.copy(position = change.position, target = target)
                                        swap = drag
                                        event.changes.forEach(PointerInputChange::consume)
                                        if (!change.pressed) break
                                    }
                                } finally {
                                    swap = null
                                }
                                drag.target?.let { currentOnSwap(start, it) }
                            }
                        }
                    }
                },
        ) {
            drawRect(Color(state.backgroundColor))
            val dragging = swap
            cells.forEachIndexed { index, cell ->
                val photo = state.photos[index]
                val radius = CollageGeometry.radius(state.corners, width, height, cell)
                val alpha = if (dragging?.from == index) DRAGGED_ALPHA else 1f
                drawPhoto(previews[photo], state.frame(photo), cell, radius, placeholder, alpha)
                if (dragging?.target == index) {
                    val inset = ring / 2f
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(cell.left + inset, cell.top + inset),
                        size = Size(cell.width - ring, cell.height - ring),
                        cornerRadius = CornerRadius((radius - inset).coerceAtLeast(0f)),
                        style = Stroke(ring),
                    )
                }
            }
            // Vignette de la photo emmenée, sous le doigt, aux proportions de sa case.
            if (dragging != null) {
                val cell = cells.getOrNull(dragging.from)
                if (cell != null) {
                    val fit = min(ghostSide / cell.width, ghostSide / cell.height)
                    val ghostWidth = cell.width * fit
                    val ghostHeight = cell.height * fit
                    val ghost = CellRect(
                        dragging.position.x - ghostWidth / 2f,
                        dragging.position.y - ghostHeight / 2f,
                        dragging.position.x + ghostWidth / 2f,
                        dragging.position.y + ghostHeight / 2f,
                    )
                    val photo = state.photos[dragging.from]
                    drawPhoto(previews[photo], state.frame(photo), ghost, ghostCorner, placeholder, GHOST_ALPHA)
                    drawRoundRect(
                        color = accent,
                        topLeft = Offset(ghost.left, ghost.top),
                        size = Size(ghost.width, ghost.height),
                        cornerRadius = CornerRadius(ghostCorner),
                        style = Stroke(ring / 1.5f),
                    )
                }
            }
        }
        CellNodes(state, cells, items, previews, onSwap, onZoomStep, onResetFrame)
    }
}

/**
 * Décide du geste : doigt levé avant tout mouvement (appui), mouvement au-delà du seuil ou second
 * doigt (cadrage). Appelé sous le délai de l'appui long, qui tranche pour l'échange.
 */
private suspend fun AwaitPointerEventScope.awaitGesture(down: PointerInputChange, slop: Float): Gesture {
    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.filter { it.pressed }
        when {
            pressed.isEmpty() -> return Gesture.TAP
            pressed.size > 1 -> return Gesture.FRAME
            (pressed.first().position - down.position).getDistance() > slop -> return Gesture.FRAME
        }
    }
}

/** Photo [image] cadrée par [frame] dans [cell], coins arrondis de [radius] ; un aplat tant qu'elle n'est pas chargée. */
private fun DrawScope.drawPhoto(image: ImageBitmap?, frame: CellFrame, cell: CellRect, radius: Float, placeholder: Color, alpha: Float) {
    val content: DrawScope.() -> Unit = {
        if (image == null) {
            drawRect(placeholder, topLeft = Offset(cell.left, cell.top), size = Size(cell.width, cell.height), alpha = alpha)
        } else {
            val visible = CellFraming.visible(frame, cell.width, cell.height, image.width.toFloat(), image.height.toFloat())
            val scale = cell.width / visible.width
            withTransform({
                translate(cell.left, cell.top)
                scale(scale, scale, pivot = Offset.Zero)
                translate(-visible.left, -visible.top)
            }) {
                drawImage(image, alpha = alpha)
            }
        }
    }
    if (radius > 0f) {
        val path = Path().apply { addRoundRect(RoundRect(cell.left, cell.top, cell.right, cell.bottom, CornerRadius(radius))) }
        clipPath(path, block = content)
    } else {
        clipRect(cell.left, cell.top, cell.right, cell.bottom, block = content)
    }
}

/**
 * Un élément accessible par case, à sa place, lu avec sa photo (nom et date). Sans geste propre
 * (le collage les capte tous), il porte en actions l'échange avec la case précédente ou suivante,
 * l'agrandissement, la réduction et le recentrage de la photo.
 */
@Composable
private fun CellNodes(
    state: CollageState,
    cells: List<CellRect>,
    items: Map<Long, MediaItem>,
    previews: Map<Long, ImageBitmap>,
    onSwap: (from: Int, to: Int) -> Unit,
    onZoomStep: (cell: Int, factor: Float, cellSize: Size, imageSize: Size) -> Unit,
    onResetFrame: (cell: Int) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val swapPrevious = stringResource(R.string.collage_action_swap_previous)
    val swapNext = stringResource(R.string.collage_action_swap_next)
    val zoomIn = stringResource(R.string.collage_action_zoom_in)
    val zoomOut = stringResource(R.string.collage_action_zoom_out)
    val reset = stringResource(R.string.collage_action_reset_frame)
    cells.forEachIndexed { index, cell ->
        val photo = state.photos[index]
        val item = items[photo]
        val name = item?.let { "${it.displayName}, ${Formatters.dayMonthYear(context, it.dateTaken)}" }.orEmpty()
        val description = stringResource(R.string.collage_cell_description, index + 1, cells.size, name)
        val frame = state.frame(photo)
        val image = previews[photo]
        val cellSize = Size(cell.width, cell.height)
        Box(
            Modifier
                .offset { IntOffset(cell.left.roundToInt(), cell.top.roundToInt()) }
                .size(with(density) { cell.width.toDp() }, with(density) { cell.height.toDp() })
                .testTag("$CELL_TAG$index")
                .semantics {
                    contentDescription = description
                    customActions = buildList {
                        if (index > 0) {
                            add(CustomAccessibilityAction(swapPrevious) {
                                onSwap(index, index - 1)
                                true
                            })
                        }
                        if (index < cells.lastIndex) {
                            add(CustomAccessibilityAction(swapNext) {
                                onSwap(index, index + 1)
                                true
                            })
                        }
                        if (image != null) {
                            val imageSize = Size(image.width.toFloat(), image.height.toFloat())
                            if (frame.zoom < CellFraming.MAX_ZOOM) {
                                add(CustomAccessibilityAction(zoomIn) {
                                    onZoomStep(index, ZOOM_STEP, cellSize, imageSize)
                                    true
                                })
                            }
                            if (frame.zoom > 1f) {
                                add(CustomAccessibilityAction(zoomOut) {
                                    onZoomStep(index, 1f / ZOOM_STEP, cellSize, imageSize)
                                    true
                                })
                            }
                        }
                        if (frame != CellFrame.Default) {
                            add(CustomAccessibilityAction(reset) {
                                onResetFrame(index)
                                true
                            })
                        }
                    }
                },
        )
    }
}

/** Préfixe des étiquettes de test des cases (« collage-cell-0 »…). */
internal const val CELL_TAG = "collage-cell-"

/** Agrandissement d'une action TalkBack. */
private const val ZOOM_STEP = 1.5f

/** Grand côté de la vignette emmenée sous le doigt. */
private val GHOST_SIDE = 112.dp
private const val GHOST_ALPHA = 0.9f

/** Case de départ d'un échange, estompée pendant le glisser. */
private const val DRAGGED_ALPHA = 0.35f
