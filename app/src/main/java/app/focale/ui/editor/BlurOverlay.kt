package app.focale.ui.editor

import android.graphics.Matrix
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.edit.BlurArea
import app.focale.edit.BlurMath
import app.focale.edit.BlurRegion
import app.focale.edit.BlurRenderer
import app.focale.edit.BlurShape
import app.focale.edit.PhotoPlacement
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Couche de l'outil Flouter posée sur l'aperçu. Elle capte et consomme tous les gestes : un
 * glisser trace une nouvelle zone (rectangle ou ellipse du point de départ au doigt, ou trait de
 * pinceau) ; avec le rectangle et l'ellipse, un glisser parti de la zone sélectionnée la déplace,
 * alors que le pinceau peint toujours, même par-dessus une zone. Un appui long sur une zone (retour
 * haptique, comme pour emmener une photo d'un collage) la sélectionne, puis le glisser qui suit la
 * déplace, avec tous les outils ; un appui sélectionne la zone touchée (ou aucune). Les zones sont
 * exprimées dans le repère de la photo avant
 * redressement ([BlurArea]) : un rectangle tracé à l'écran sur une photo redressée reste droit à
 * l'écran, puis pivote avec elle. Chaque zone est aussi un élément accessible (sélection,
 * suppression).
 *
 * @param brushSize épaisseur à l'écran du pinceau.
 */
@Composable
fun BlurOverlay(
    viewport: EditorViewport,
    regions: List<BlurRegion>,
    selected: Long?,
    shape: BlurShape,
    brushSize: Dp,
    accent: Color,
    onAdd: (BlurArea) -> Unit,
    onSelect: (Long?) -> Unit,
    onMoveStart: (Long) -> Unit,
    onMove: (id: Long, dx: Float, dy: Float) -> Unit,
    onDelete: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val slop = with(density) { TOUCH_MARGIN.toPx() }
    val step = with(density) { MIN_STEP.toPx() }
    val minShape = with(density) { MIN_SHAPE.toPx() }
    val line = with(density) { 1.dp.toPx() }
    val dash = with(density) { 5.dp.toPx() }
    val currentViewport by rememberUpdatedState(viewport)
    val currentRegions by rememberUpdatedState(regions)
    val currentSelected by rememberUpdatedState(selected)
    val currentShape by rememberUpdatedState(shape)
    val currentBrush by rememberUpdatedState(with(density) { brushSize.toPx() })
    val currentOnAdd by rememberUpdatedState(onAdd)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentOnMoveStart by rememberUpdatedState(onMoveStart)
    val currentOnMove by rememberUpdatedState(onMove)
    // Zone en cours de tracé, en px de l'écran : cadre du départ au doigt, ou points du pinceau.
    var liveBox by remember { mutableStateOf<Rect?>(null) }
    var liveBrush by remember { mutableStateOf<List<Offset>>(emptyList()) }

    Box(modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        val placement = currentViewport.photo
                        val start = down.position
                        val brush = currentShape == BlurShape.BRUSH
                        val under = hitTest(placement, currentRegions, start, slop)
                        // Rectangle et ellipse : un glisser parti de la zone sélectionnée la déplace. Le
                        // pinceau, lui, peint toujours ; seul l'appui long emmène une zone, avec tous les outils.
                        val selectedUnder = currentSelected?.takeIf { id ->
                            currentRegions.firstOrNull { it.id == id }?.let { contains(placement, it.area, start, slop) } == true
                        }
                        // Sur une zone, le doigt resté immobile jusqu'au délai de l'appui long l'emmène ;
                        // sinon, le premier mouvement au-delà du seuil (ou le relâchement) reste à traiter.
                        var pending: PointerInputChange? = null
                        val held = under != null && withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            awaitSlopOrUp(down, viewConfiguration.touchSlop).also { pending = it }
                        } == null
                        val moving = if (held) under else selectedUnder?.takeIf { !brush }
                        if (held) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentOnSelect(under)
                        }
                        val brushing = moving == null && brush
                        val brushPoints = ArrayList<Offset>()
                        if (brushing) {
                            brushPoints += start
                            liveBrush = brushPoints.toList()
                        }
                        var last = start
                        var finger = start
                        var dragging = false
                        try {
                            while (true) {
                                val change = pending ?: awaitPointerEvent().let { event ->
                                    event.changes.forEach { it.consume() }
                                    event.changes.firstOrNull { it.id == down.id }
                                } ?: break
                                pending = null
                                finger = change.position
                                if (!dragging && (finger - start).getDistance() > viewConfiguration.touchSlop) {
                                    dragging = true
                                    // Une seule étape d'historique pour tout le déplacement.
                                    moving?.let(currentOnMoveStart)
                                }
                                when {
                                    moving != null && dragging -> {
                                        val delta = finger - last
                                        currentOnMove(moving, placement.photoDeltaX(delta.x, delta.y), placement.photoDeltaY(delta.x, delta.y))
                                        last = finger
                                    }
                                    brushing -> {
                                        // Points trop serrés ignorés, comme pour le stylo.
                                        if ((finger - brushPoints.last()).getDistance() >= step) brushPoints += finger
                                        liveBrush = brushPoints + finger
                                    }
                                    moving == null && dragging -> liveBox = Rect(start, finger)
                                }
                                if (!change.pressed) break
                            }
                        } finally {
                            liveBox = null
                            liveBrush = emptyList()
                        }
                        val extent = finger - start
                        when {
                            // Sélectionnée à l'appui long, déplacée si le doigt a bougé ensuite.
                            held -> Unit
                            !dragging -> currentOnSelect(under)
                            moving != null -> Unit
                            brushing -> {
                                if (brushPoints.last() != finger) brushPoints += finger
                                val points = FloatArray(brushPoints.size * 2)
                                brushPoints.forEachIndexed { index, point ->
                                    points[2 * index] = placement.photoX(point.x, point.y)
                                    points[2 * index + 1] = placement.photoY(point.x, point.y)
                                }
                                currentOnAdd(BlurArea.Brush(points, placement.photoLength(currentBrush)))
                            }
                            abs(extent.x) >= minShape && abs(extent.y) >= minShape -> {
                                // Cadre droit à l'écran : incliné dans la photo de l'opposé du redressement.
                                val center = (start + finger) / 2f
                                currentOnAdd(
                                    BlurArea.Box(
                                        shape = currentShape,
                                        x = placement.photoX(center.x, center.y),
                                        y = placement.photoY(center.x, center.y),
                                        halfWidth = placement.photoLength(abs(extent.x) / 2f),
                                        halfHeight = placement.photoLength(abs(extent.y) / 2f),
                                        angle = -placement.angle,
                                    ),
                                )
                            }
                        }
                    }
                },
        ) {
            val placement = viewport.photo
            val visible = viewport.visible
            clipRect(visible.left, visible.top, visible.right, visible.bottom) {
                for (region in regions) {
                    val isSelected = region.id == selected
                    val area = region.area
                    if (area is BlurArea.Brush) {
                        // Trait de pinceau sélectionné : voile de la couleur d'accent sur son tracé.
                        if (isSelected) {
                            drawPath(
                                screenPath(placement, area),
                                accent.copy(alpha = 0.45f),
                                style = Stroke(placement.screenLength(area.width), cap = StrokeCap.Round, join = StrokeJoin.Round),
                            )
                        }
                        continue
                    }
                    val path = screenPath(placement, area)
                    if (isSelected) {
                        drawPath(path, Color.Black.copy(alpha = 0.4f), style = Stroke(line * 4f))
                        drawPath(path, accent, style = Stroke(line * 2f))
                    } else {
                        drawPath(path, Color.Black.copy(alpha = 0.3f), style = Stroke(line * 2f))
                        drawPath(path, Color.White.copy(alpha = 0.8f), style = Stroke(line, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash))))
                    }
                }
                liveBox?.let { box ->
                    val rect = Rect(minOf(box.left, box.right), minOf(box.top, box.bottom), maxOf(box.left, box.right), maxOf(box.top, box.bottom))
                    live(rect, currentShape == BlurShape.ELLIPSE, line)
                }
                if (liveBrush.size >= 1) {
                    val path = Path().apply {
                        moveTo(liveBrush[0].x, liveBrush[0].y)
                        if (liveBrush.size == 1) lineTo(liveBrush[0].x + 0.01f, liveBrush[0].y)
                        for (point in liveBrush.drop(1)) lineTo(point.x, point.y)
                    }
                    drawPath(path, Color.White.copy(alpha = 0.35f), style = Stroke(currentBrush, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
        BlurNodes(viewport, regions, selected, onSelect, onDelete)
    }
}

/**
 * Attend, sous le délai de l'appui long, que le doigt [down] franchisse le seuil [slop] ou se lève :
 * rend ce mouvement (ou ce relâchement), à traiter par le geste ; le doigt disparu, son appui.
 */
private suspend fun AwaitPointerEventScope.awaitSlopOrUp(down: PointerInputChange, slop: Float): PointerInputChange {
    while (true) {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
        val change = event.changes.firstOrNull { it.id == down.id } ?: return down
        if (!change.pressed || (change.position - down.position).getDistance() > slop) return change
    }
}

/** Cadre en cours de tracé : voile clair et contour. */
private fun DrawScope.live(rect: Rect, ellipse: Boolean, line: Float) {
    val topLeft = rect.topLeft
    val size = Size(rect.width, rect.height)
    if (ellipse) {
        drawOval(Color.White.copy(alpha = 0.25f), topLeft, size)
        drawOval(Color.White, topLeft, size, style = Stroke(line * 1.5f))
    } else {
        drawRect(Color.White.copy(alpha = 0.25f), topLeft, size)
        drawRect(Color.White, topLeft, size, style = Stroke(line * 1.5f))
    }
}

/** Contour de la zone (ou axe du pinceau) en px de l'écran, avec la rotation de la photo. */
private fun screenPath(placement: PhotoPlacement, area: BlurArea): Path {
    val frame = placement.frame
    val path = BlurRenderer.path(area, frame.width, frame.height)
    path.transform(screenMatrix(placement))
    return path.asComposePath()
}

/** Du repère de la photo (px d'un cadre de la taille de [PhotoPlacement.frame]) aux px de l'écran. */
private fun screenMatrix(placement: PhotoPlacement): Matrix {
    val frame = placement.frame
    val centerX = frame.left + frame.width / 2f
    val centerY = frame.top + frame.height / 2f
    return Matrix().apply {
        setTranslate(frame.left, frame.top)
        postRotate(placement.angle, centerX, centerY)
        postScale(placement.bleed, placement.bleed, centerX, centerY)
    }
}

/** Vrai si le point (px de l'écran) tombe dans la zone, à [slop] px près. */
private fun contains(placement: PhotoPlacement, area: BlurArea, point: Offset, slop: Float): Boolean {
    val frame = placement.frame
    val x = placement.photoX(point.x, point.y) * frame.width
    val y = placement.photoY(point.x, point.y) * frame.height
    return BlurMath.contains(area, x, y, frame.width, frame.height, slop / placement.bleed)
}

/** Zone la plus récente sous le point, ou null. */
private fun hitTest(placement: PhotoPlacement, regions: List<BlurRegion>, point: Offset, slop: Float): Long? =
    regions.lastOrNull { contains(placement, it.area, point, slop) }?.id

/** Un élément accessible par zone, à l'emplacement de sa boîte à l'écran, sans geste propre. */
@Composable
private fun BlurNodes(
    viewport: EditorViewport,
    regions: List<BlurRegion>,
    selected: Long?,
    onSelect: (Long?) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val density = LocalDensity.current
    val selectLabel = stringResource(R.string.blur_action_select)
    val deleteLabel = stringResource(R.string.blur_action_delete)
    val shapes = mapOf(
        BlurShape.RECTANGLE to stringResource(R.string.blur_rectangle),
        BlurShape.ELLIPSE to stringResource(R.string.blur_ellipse),
        BlurShape.BRUSH to stringResource(R.string.blur_brush),
    )
    val placement = viewport.photo
    val visible = viewport.visible
    val matrix = remember(placement) { screenMatrix(placement) }
    val bounds = RectF()
    regions.forEachIndexed { index, region ->
        val area = region.area
        val path = BlurRenderer.path(area, placement.frame.width, placement.frame.height)
        if (area is BlurArea.Brush) {
            // Boîte du tracé, épaisseur comprise.
            val half = area.width * placement.frame.reference / 2f
            path.computeBounds(bounds, true)
            bounds.inset(-half, -half)
            path.reset()
            path.addRect(bounds, android.graphics.Path.Direction.CW)
        }
        path.transform(matrix)
        path.computeBounds(bounds, true)
        val left = bounds.left.coerceIn(visible.left, visible.right)
        val right = bounds.right.coerceIn(visible.left, visible.right)
        val top = bounds.top.coerceIn(visible.top, visible.bottom)
        val bottom = bounds.bottom.coerceIn(visible.top, visible.bottom)
        if (right <= left || bottom <= top) return@forEachIndexed
        val description = stringResource(R.string.blur_zone_description, index + 1, shapes.getValue(area.shape))
        val id = region.id
        Box(
            Modifier
                .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
                .size(with(density) { (right - left).toDp() }, with(density) { (bottom - top).toDp() })
                .semantics {
                    contentDescription = description
                    role = Role.Button
                    this.selected = id == selected
                    onClick(label = selectLabel) {
                        onSelect(id)
                        true
                    }
                    customActions = listOf(
                        CustomAccessibilityAction(deleteLabel) {
                            onDelete(id)
                            true
                        },
                    )
                }
                .onFocusChanged { if (it.isFocused) onSelect(id) }
                .onKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && (event.key == Key.Delete || event.key == Key.Backspace)) {
                        onDelete(id)
                        true
                    } else {
                        false
                    }
                }
                .focusable(),
        )
    }
}

/** Marge de saisie au doigt autour d'une zone. */
private val TOUCH_MARGIN = 6.dp

/** Écart minimal entre deux points d'un trait de pinceau. */
private val MIN_STEP = 2.dp

/** Côtés minimaux d'un rectangle ou d'une ellipse. */
private val MIN_SHAPE = 12.dp
