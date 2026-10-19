package app.focale.ui.videoeditor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.video.TrimFormat
import app.focale.video.TrimRange
import kotlin.math.abs
import kotlin.math.roundToLong

private val BarHeight = 64.dp
private val HandleWidth = 18.dp
private val FrameBorder = 4.dp
private val TouchRadius = 32.dp

/**
 * Frise de vignettes de la vidéo. Deux poignées délimitent la plage conservée (l'extérieur est
 * assombri) et le curseur blanc suit la lecture : glisser une poignée coupe le début ou la fin,
 * toucher ou glisser ailleurs déplace la lecture dans la plage. Retour haptique à la prise d'une
 * poignée, à chaque seconde franchie et en butée.
 *
 * @param onTrim reçoit la position demandée et renvoie celle retenue (bornée par la plage).
 */
@Composable
fun TrimBar(
    range: TrimRange,
    position: () -> Long,
    frames: List<ImageBitmap?>,
    enabled: Boolean,
    onTrimStart: (TrimHandle) -> Unit,
    onTrim: (TrimHandle, Long) -> Long,
    onTrimEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val colors = MaterialTheme.colorScheme
    val locale = LocalConfiguration.current.locales[0]
    val currentRange by rememberUpdatedState(range)
    val currentOnTrimStart by rememberUpdatedState(onTrimStart)
    val currentOnTrim by rememberUpdatedState(onTrim)
    val currentOnTrimEnd by rememberUpdatedState(onTrimEnd)

    // Accessibilité : la plage se décrit et ses bornes se déplacent par actions dédiées.
    val description = stringResource(
        R.string.video_editor_trim_description,
        TrimFormat.position(range.startMs, locale),
        TrimFormat.position(range.endMs, locale),
    )
    val step = maxOf(ACCESSIBILITY_STEP_MS, range.durationMs / ACCESSIBILITY_STEPS)
    val nudge = { handle: TrimHandle, delta: Long ->
        val from = if (handle == TrimHandle.START) currentRange.startMs else currentRange.endMs
        currentOnTrimStart(handle)
        currentOnTrim(handle, from + delta)
        currentOnTrimEnd()
        true
    }
    val actions = listOf(
        CustomAccessibilityAction(stringResource(R.string.video_editor_start_earlier)) { nudge(TrimHandle.START, -step) },
        CustomAccessibilityAction(stringResource(R.string.video_editor_start_later)) { nudge(TrimHandle.START, step) },
        CustomAccessibilityAction(stringResource(R.string.video_editor_end_earlier)) { nudge(TrimHandle.END, -step) },
        CustomAccessibilityAction(stringResource(R.string.video_editor_end_later)) { nudge(TrimHandle.END, step) },
    )

    Box(
        modifier
            .fillMaxWidth()
            .height(BarHeight)
            .semantics {
                contentDescription = description
                if (enabled && range.canTrim) customActions = actions
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val handleWidth = HandleWidth.toPx()
                val touchRadius = TouchRadius.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val trim = currentRange
                    val geometry = TrimGeometry(size.width.toFloat(), handleWidth, trim.durationMs)
                    val xStart = geometry.x(trim.startMs)
                    val xEnd = geometry.x(trim.endMs)
                    // La poignée la plus proche du doigt, sinon le curseur de lecture.
                    val toStart = abs(down.position.x - (xStart - handleWidth / 2))
                    val toEnd = abs(down.position.x - (xEnd + handleWidth / 2))
                    val handle = when {
                        !trim.canTrim -> TrimHandle.PLAYHEAD
                        toStart <= touchRadius && toStart <= toEnd -> TrimHandle.START
                        toEnd <= touchRadius -> TrimHandle.END
                        else -> TrimHandle.PLAYHEAD
                    }
                    // Décalage entre le doigt et la poignée, conservé pendant tout le geste.
                    val grab = down.position.x - when (handle) {
                        TrimHandle.START -> xStart
                        TrimHandle.END -> xEnd
                        TrimHandle.PLAYHEAD -> down.position.x
                    }
                    down.consume()
                    currentOnTrimStart(handle)
                    if (handle != TrimHandle.PLAYHEAD) haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                    var last = currentOnTrim(handle, geometry.time(down.position.x - grab))
                    var atLimit = false
                    try {
                        drag(down.id) { change ->
                            change.consume()
                            val requested = geometry.time(change.position.x - grab)
                            val applied = currentOnTrim(handle, requested)
                            if (handle != TrimHandle.PLAYHEAD) {
                                val limited = applied != requested
                                when {
                                    limited && !atLimit -> haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    !limited && applied / 1_000 != last / 1_000 ->
                                        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                                }
                                atLimit = limited
                            }
                            last = applied
                        }
                    } finally {
                        currentOnTrimEnd()
                    }
                }
            },
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(horizontal = HandleWidth, vertical = FrameBorder)
                .clip(RoundedCornerShape(4.dp))
                .background(colors.surfaceContainerHigh),
        ) {
            frames.forEach { frame ->
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (frame != null) {
                        Image(
                            bitmap = frame,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        val accent = if (enabled) colors.primary else colors.outline
        val onAccent = if (enabled) colors.onPrimary else colors.surface
        Canvas(Modifier.fillMaxSize()) {
            val handleWidth = HandleWidth.toPx()
            val border = FrameBorder.toPx()
            val geometry = TrimGeometry(size.width, handleWidth, range.durationMs)
            val xStart = geometry.x(range.startMs)
            val xEnd = geometry.x(range.endMs)
            val stripHeight = size.height - 2 * border

            // Parties coupées, assombries.
            val shade = Color.Black.copy(alpha = 0.62f)
            if (xStart > handleWidth) {
                drawRect(shade, topLeft = Offset(handleWidth, border), size = Size(xStart - handleWidth, stripHeight))
            }
            if (xEnd < size.width - handleWidth) {
                drawRect(shade, topLeft = Offset(xEnd, border), size = Size(size.width - handleWidth - xEnd, stripHeight))
            }

            // Cadre de la plage : poignées pleines à gauche et à droite, bords en haut et en bas.
            val frame = Path().apply {
                fillType = PathFillType.EvenOdd
                addRoundRect(RoundRect(xStart - handleWidth, 0f, xEnd + handleWidth, size.height, CornerRadius(12.dp.toPx())))
                addRoundRect(RoundRect(xStart, border, xEnd, size.height - border, CornerRadius(4.dp.toPx())))
            }
            drawPath(frame, accent)
            val grip = 9.dp.toPx()
            for (x in floatArrayOf(xStart - handleWidth / 2, xEnd + handleWidth / 2)) {
                drawLine(
                    color = onAccent,
                    start = Offset(x, center.y - grip),
                    end = Offset(x, center.y + grip),
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }

            // Curseur de lecture (lu ici : seul le dessin suit la lecture, sans recomposition).
            val playhead = position()
            if (playhead in range.startMs..range.endMs) {
                val x = geometry.x(playhead).coerceIn(xStart, xEnd)
                val top = Offset(x, border / 2)
                val bottom = Offset(x, size.height - border / 2)
                drawLine(Color.Black.copy(alpha = 0.35f), top, bottom, strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
                drawLine(Color.White, top, bottom, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
    }
}

/** Correspondance entre temps et abscisse ; la frise est encadrée par la largeur des poignées. */
private class TrimGeometry(width: Float, private val inset: Float, private val durationMs: Long) {
    private val stripWidth = (width - 2 * inset).coerceAtLeast(1f)

    fun x(timeMs: Long): Float = inset + if (durationMs > 0) stripWidth * timeMs / durationMs else 0f

    /** Temps sous l'abscisse [x], non borné : c'est la plage qui fixe les limites (et signale la butée). */
    fun time(x: Float): Long = if (durationMs > 0) ((x - inset) / stripWidth * durationMs).roundToLong() else 0L
}

private const val ACCESSIBILITY_STEP_MS = 1_000L
private const val ACCESSIBILITY_STEPS = 50
