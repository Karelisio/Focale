package app.focale.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.withTranslation
import app.focale.edit.DrawTool
import app.focale.edit.DrawingMath
import app.focale.edit.DrawingRenderer
import app.focale.edit.DrawingStroke
import app.focale.edit.ImageFrame
import kotlin.math.ceil

/**
 * Couche de dessin posée sur l'aperçu pendant l'outil Dessin. Elle capte et consomme tous
 * les gestes, doigts supplémentaires compris : rien ne peut zoomer ni déplacer l'aperçu.
 * Le trait suit le doigt dès le contact (pas de seuil de glissement), tracé par le même
 * rendu que l'export, puis chaque trait terminé est transmis ; la gomme supprime les
 * traits touchés.
 *
 * @param frame position à l'écran de l'image pivotée entière.
 * @param visible zone affichée (recadrée) de l'image : le dessin y est découpé.
 * @param size épaisseur à l'écran du trait de l'outil courant.
 */
@Composable
fun DrawingOverlay(
    frame: ImageFrame,
    visible: Rect,
    strokes: List<DrawingStroke>,
    tool: DrawTool,
    color: Color,
    size: Dp,
    onStroke: (DrawingStroke) -> Unit,
    onEraseStart: () -> Unit,
    onErase: (DrawingStroke) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val step = with(density) { MIN_STEP.toPx() }
    val minShape = with(density) { MIN_SHAPE.toPx() }
    val eraserRadius = with(density) { ERASER_RADIUS.toPx() }
    val ring = with(density) { 1.5.dp.toPx() }
    val currentFrame by rememberUpdatedState(frame)
    val currentVisible by rememberUpdatedState(visible)
    val currentStrokes by rememberUpdatedState(strokes)
    val currentTool by rememberUpdatedState(tool)
    val currentColor by rememberUpdatedState(color)
    val currentSize by rememberUpdatedState(with(density) { size.toPx() })
    val currentOnStroke by rememberUpdatedState(onStroke)
    val currentOnEraseStart by rememberUpdatedState(onEraseStart)
    val currentOnErase by rememberUpdatedState(onErase)
    // Trait sous le doigt, puis trait terminé tant que l'état de l'éditeur ne l'affiche pas
    // encore : il passe d'une couche à l'autre sans clignoter.
    var live by remember { mutableStateOf<DrawingStroke?>(null) }
    var pending by remember { mutableStateOf<DrawingStroke?>(null) }
    var eraser by remember { mutableStateOf<Offset?>(null) }

    SideEffect {
        val finished = pending
        if (finished != null && strokes.any { it === finished }) pending = null
    }

    Canvas(
        modifier
            .fillMaxSize()
            // Couche propre : suivre le doigt ne redessine ni l'image ni les traits enregistrés.
            .graphicsLayer {}
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val image = currentFrame
                    if (currentTool == DrawTool.ERASER) {
                        val erased = ArrayList<DrawingStroke>()
                        fun eraseAt(position: Offset) {
                            if (!currentVisible.contains(position)) return
                            val candidates = currentStrokes.filter { stroke -> erased.none { it === stroke } }
                            val index = DrawingMath.hitTest(
                                candidates,
                                position.x - image.left,
                                position.y - image.top,
                                image.width,
                                image.height,
                                eraserRadius,
                            )
                            if (index < 0) return
                            if (erased.isEmpty()) currentOnEraseStart()
                            erased += candidates[index]
                            currentOnErase(candidates[index])
                            haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        }
                        var last = down.position
                        try {
                            eraser = last
                            eraseAt(last)
                            track(down.id) { positions ->
                                for (position in positions) {
                                    // Positions intermédiaires : un balayage rapide ne saute pas un trait fin.
                                    val steps = ceil((position - last).getDistance() / eraserRadius).toInt().coerceAtLeast(1)
                                    for (k in 1..steps) eraseAt(lerp(last, position, k / steps.toFloat()))
                                    last = position
                                }
                                eraser = last
                            }
                        } finally {
                            eraser = null
                        }
                    } else {
                        val drawTool = currentTool
                        val argb = currentColor.toArgb()
                        val width = currentSize / image.reference
                        val points = StrokePoints(image)
                        val start = down.position
                        var added = start
                        var finger = start
                        points.add(start)
                        // Le tracé vivant se termine toujours exactement sous le doigt.
                        fun snapshot() = DrawingStroke(drawTool, argb, width, points.toArray(tail = finger.takeIf { it != added }))
                        try {
                            live = snapshot()
                            track(down.id) { positions ->
                                for (position in positions) {
                                    finger = position
                                    // Points trop serrés ignorés : le lissage n'hérite pas du tremblement.
                                    if (drawTool.isFreehand && (position - added).getDistance() >= step) {
                                        points.add(position)
                                        added = position
                                    }
                                }
                                live = snapshot()
                            }
                            val stroke = snapshot()
                            live = null
                            // Une forme sans étendue (simple appui) n'est pas gardée.
                            if (drawTool.isFreehand || (finger - start).getDistance() >= minShape) {
                                pending = stroke
                                currentOnStroke(stroke)
                            }
                        } finally {
                            live = null
                        }
                    }
                }
            },
    ) {
        val finished = pending?.takeIf { stroke -> strokes.none { it === stroke } }
        val current = live
        if (finished != null || current != null) {
            clipRect(visible.left, visible.top, visible.right, visible.bottom) {
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.withTranslation(frame.left, frame.top) {
                        finished?.let { DrawingRenderer.prepare(it, frame.width, frame.height).draw(this) }
                        current?.let { DrawingRenderer.prepare(it, frame.width, frame.height).draw(this) }
                    }
                }
            }
        }
        eraser?.let { center ->
            drawCircle(Color.Black.copy(alpha = 0.4f), radius = eraserRadius, center = center, style = Stroke(ring * 2f))
            drawCircle(Color.White, radius = eraserRadius, center = center, style = Stroke(ring))
        }
    }
}

/** Points du trait en cours, normalisés dans le cadre de l'image au début du geste. */
private class StrokePoints(private val frame: ImageFrame) {
    private var values = FloatArray(64)
    private var count = 0

    fun add(position: Offset) {
        if (count + 2 > values.size) values = values.copyOf(values.size * 2)
        values[count++] = frame.normalizedX(position.x)
        values[count++] = frame.normalizedY(position.y)
    }

    /** Copie des points, suivie de [tail] s'il est donné (position provisoire du doigt). */
    fun toArray(tail: Offset?): FloatArray {
        if (tail == null) return values.copyOf(count)
        val out = values.copyOf(count + 2)
        out[count] = frame.normalizedX(tail.x)
        out[count + 1] = frame.normalizedY(tail.y)
        return out
    }
}

/**
 * Suit le doigt [id] jusqu'à ce qu'il se lève, en consommant tous les changements ;
 * [onMove] reçoit chaque événement avec ses positions intermédiaires, pour un tracé aussi
 * fin que l'échantillonnage de l'écran tactile.
 */
private suspend fun AwaitPointerEventScope.track(id: PointerId, onMove: (List<Offset>) -> Unit) {
    while (true) {
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
        val change = event.changes.firstOrNull { it.id == id } ?: return
        onMove(change.historical.map { it.position } + change.position)
        if (!change.pressed) return
    }
}

/** Écart minimal entre deux points d'un tracé à main levée. */
private val MIN_STEP = 1.5.dp

/** Diagonale minimale d'une forme. */
private val MIN_SHAPE = 4.dp

/** Rayon de la gomme autour du doigt (aussi montré par l'aperçu d'épaisseur). */
internal val ERASER_RADIUS = 14.dp
