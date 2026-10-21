package app.focale.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.focale.edit.CropHandle
import app.focale.edit.NormRect
import app.focale.edit.StraightenMath
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Cadre de recadrage : assombrit l'extérieur, affiche la grille des tiers (une grille fine
 * pendant un redressement, pour aligner un horizon) et des poignées ; coins, bords et
 * intérieur se manipulent au doigt, sans jamais quitter l'image redressée.
 *
 * @param imageRect zone (px) où l'image complète est affichée.
 * @param angle redressement de l'image, en degrés.
 * @param imageSize dimensions de l'image pivotée (seul leur rapport compte).
 * @param straightening redressement en cours : grille fine.
 */
@Composable
fun CropOverlay(
    imageRect: Rect,
    crop: NormRect,
    ratio: Float?,
    angle: Float,
    imageSize: Size,
    straightening: Boolean,
    accent: Color,
    onGestureStart: () -> Unit,
    onCropChange: (NormRect) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val touchRadius = with(LocalDensity.current) { 36.dp.toPx() }
    val currentCrop by rememberUpdatedState(crop)
    val currentRatio by rememberUpdatedState(ratio)
    val currentImage by rememberUpdatedState(imageRect)
    val currentAngle by rememberUpdatedState(angle)
    val currentSize by rememberUpdatedState(imageSize)

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var handle: CropHandle? = null
                detectDragGestures(
                    onDragStart = { position ->
                        handle = hitTest(position, toPx(currentCrop, currentImage), touchRadius)
                        if (handle != null) {
                            onGestureStart()
                            haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                        }
                    },
                    onDragEnd = { handle = null },
                    onDragCancel = { handle = null },
                    onDrag = { change, amount ->
                        val active = handle ?: return@detectDragGestures
                        change.consume()
                        val image = currentImage
                        onCropChange(
                            StraightenMath.drag(
                                currentCrop,
                                active,
                                amount.x / image.width,
                                amount.y / image.height,
                                currentRatio,
                                currentAngle,
                                currentSize.width,
                                currentSize.height,
                            ),
                        )
                    },
                )
            },
    ) {
        val rect = toPx(crop, imageRect)
        // Extérieur assombri.
        val shade = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRect(rect)
        }
        drawPath(shade, Color.Black.copy(alpha = 0.55f))
        if (straightening) grid(rect, GRID_CELL.toPx(), Color.White.copy(alpha = 0.35f)) else grid(rect, columns = 3, rows = 3)
        drawRoundRect(
            color = Color.White,
            topLeft = rect.topLeft,
            size = Size(rect.width, rect.height),
            cornerRadius = CornerRadius(4.dp.toPx()),
            style = Stroke(width = 1.5.dp.toPx()),
        )
        // Poignées d'angle épaisses, à la couleur d'accent.
        val arm = 22.dp.toPx()
        val stroke = 4.dp.toPx()
        listOf(
            rect.topLeft to Offset(1f, 1f),
            rect.topRight to Offset(-1f, 1f),
            rect.bottomLeft to Offset(1f, -1f),
            rect.bottomRight to Offset(-1f, -1f),
        ).forEach { (corner, direction) ->
            drawLine(accent, corner, corner + Offset(arm * direction.x, 0f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(accent, corner, corner + Offset(0f, arm * direction.y), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

/** Côté approximatif d'une case de la grille fine. */
private val GRID_CELL = 28.dp

/** Grille fine : des cases à peu près carrées, de [cell] px de côté. */
private fun DrawScope.grid(rect: Rect, cell: Float, color: Color) = grid(
    rect,
    columns = (rect.width / cell).roundToInt().coerceAtLeast(3),
    rows = (rect.height / cell).roundToInt().coerceAtLeast(3),
    color = color,
)

private fun DrawScope.grid(rect: Rect, columns: Int, rows: Int, color: Color = Color.White.copy(alpha = 0.45f)) {
    val width = 1.dp.toPx()
    for (i in 1 until columns) {
        val x = rect.left + rect.width * i / columns
        drawLine(color, Offset(x, rect.top), Offset(x, rect.bottom), strokeWidth = width)
    }
    for (i in 1 until rows) {
        val y = rect.top + rect.height * i / rows
        drawLine(color, Offset(rect.left, y), Offset(rect.right, y), strokeWidth = width)
    }
}

private fun toPx(crop: NormRect, image: Rect): Rect = Rect(
    left = image.left + crop.left * image.width,
    top = image.top + crop.top * image.height,
    right = image.left + crop.right * image.width,
    bottom = image.top + crop.bottom * image.height,
)

/** Poignée la plus proche du point touché, ou déplacement si le point est à l'intérieur. */
private fun hitTest(point: Offset, rect: Rect, radius: Float): CropHandle? {
    val corners = listOf(
        CropHandle.TOP_LEFT to rect.topLeft,
        CropHandle.TOP_RIGHT to rect.topRight,
        CropHandle.BOTTOM_LEFT to rect.bottomLeft,
        CropHandle.BOTTOM_RIGHT to rect.bottomRight,
    )
    corners.minByOrNull { (_, c) -> hypot(c.x - point.x, c.y - point.y) }
        ?.takeIf { (_, c) -> hypot(c.x - point.x, c.y - point.y) <= radius }
        ?.let { return it.first }
    val withinX = point.x in rect.left..rect.right
    val withinY = point.y in rect.top..rect.bottom
    return when {
        withinY && abs(point.x - rect.left) <= radius -> CropHandle.LEFT
        withinY && abs(point.x - rect.right) <= radius -> CropHandle.RIGHT
        withinX && abs(point.y - rect.top) <= radius -> CropHandle.TOP
        withinX && abs(point.y - rect.bottom) <= radius -> CropHandle.BOTTOM
        rect.contains(point) -> CropHandle.MOVE
        else -> null
    }
}
