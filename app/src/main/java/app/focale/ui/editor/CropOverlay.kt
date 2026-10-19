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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import app.focale.edit.CropHandle
import app.focale.edit.CropMath
import app.focale.edit.NormRect
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Cadre de recadrage : assombrit l'extérieur, affiche la grille des tiers et des
 * poignées ; coins, bords et intérieur se manipulent au doigt.
 *
 * @param imageRect zone (px) où l'image complète est affichée.
 */
@Composable
fun CropOverlay(
    imageRect: Rect,
    crop: NormRect,
    ratio: Float?,
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
                            CropMath.drag(currentCrop, active, amount.x / image.width, amount.y / image.height, currentRatio),
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
        // Grille des tiers.
        val gridColor = Color.White.copy(alpha = 0.45f)
        for (i in 1..2) {
            val x = rect.left + rect.width * i / 3f
            val y = rect.top + rect.height * i / 3f
            drawLine(gridColor, Offset(x, rect.top), Offset(x, rect.bottom), strokeWidth = 1.dp.toPx())
            drawLine(gridColor, Offset(rect.left, y), Offset(rect.right, y), strokeWidth = 1.dp.toPx())
        }
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
