package app.focale.edit

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Géométrie des traits en pixels d'un cadre [width] × [height] couvrant l'image pivotée
 * entière (pure, testable). Les grandeurs dérivées (pointe de flèche) sont proportionnelles
 * au cadre : l'aperçu et l'export ne diffèrent que par l'échelle.
 */
object DrawingMath {

    /** Longueur de la pointe de flèche : en épaisseurs de trait, plus une part du petit côté. */
    private const val ARROW_HEAD_WIDTHS = 3f
    private const val ARROW_HEAD_REFERENCE = 0.012f
    private const val ARROW_ANGLE = (PI / 6).toFloat()

    /** Segments de l'ellipse approchée par la gomme. */
    private const val ELLIPSE_SEGMENTS = 64

    fun strokeWidth(stroke: DrawingStroke, width: Float, height: Float): Float = stroke.width * min(width, height)

    /**
     * Extrémités (x1, y1, x2, y2) des deux branches de la pointe plantée en ([tipX], [tipY]),
     * ouvertes de 30° de part et d'autre du trait ; null pour une flèche de longueur nulle.
     * La pointe ne dépasse pas la moitié de la flèche.
     */
    fun arrowHead(startX: Float, startY: Float, tipX: Float, tipY: Float, strokeWidth: Float, reference: Float): FloatArray? {
        val dx = startX - tipX
        val dy = startY - tipY
        val length = hypot(dx, dy)
        if (length == 0f) return null
        val head = min(strokeWidth * ARROW_HEAD_WIDTHS + reference * ARROW_HEAD_REFERENCE, length / 2f)
        // Direction de la pointe vers le départ, tournée de +30° puis de -30°.
        val ux = dx / length
        val uy = dy / length
        val cos = cos(ARROW_ANGLE)
        val sin = sin(ARROW_ANGLE)
        return floatArrayOf(
            tipX + head * (ux * cos - uy * sin), tipY + head * (ux * sin + uy * cos),
            tipX + head * (ux * cos + uy * sin), tipY + head * (uy * cos - ux * sin),
        )
    }

    /** Distance (px) entre (x, y) et l'axe du trait, épaisseur non comprise. */
    fun distance(stroke: DrawingStroke, x: Float, y: Float, width: Float, height: Float): Float {
        val last = stroke.pointCount - 1
        val x0 = stroke.x(0) * width
        val y0 = stroke.y(0) * height
        val x1 = stroke.x(last) * width
        val y1 = stroke.y(last) * height
        return when (stroke.tool) {
            DrawTool.PEN, DrawTool.HIGHLIGHTER -> {
                var best = hypot(x - x0, y - y0)
                for (i in 0 until last) {
                    best = min(
                        best,
                        segmentDistance(x, y, stroke.x(i) * width, stroke.y(i) * height, stroke.x(i + 1) * width, stroke.y(i + 1) * height),
                    )
                }
                best
            }
            DrawTool.LINE -> segmentDistance(x, y, x0, y0, x1, y1)
            DrawTool.ARROW -> {
                var best = segmentDistance(x, y, x0, y0, x1, y1)
                arrowHead(x0, y0, x1, y1, strokeWidth(stroke, width, height), min(width, height))?.let { head ->
                    best = min(best, segmentDistance(x, y, x1, y1, head[0], head[1]))
                    best = min(best, segmentDistance(x, y, x1, y1, head[2], head[3]))
                }
                best
            }
            DrawTool.RECTANGLE -> {
                val left = min(x0, x1)
                val top = min(y0, y1)
                val right = max(x0, x1)
                val bottom = max(y0, y1)
                minOf(
                    segmentDistance(x, y, left, top, right, top),
                    segmentDistance(x, y, right, top, right, bottom),
                    segmentDistance(x, y, right, bottom, left, bottom),
                    segmentDistance(x, y, left, bottom, left, top),
                )
            }
            DrawTool.ELLIPSE -> {
                val cx = (x0 + x1) / 2f
                val cy = (y0 + y1) / 2f
                val rx = abs(x1 - x0) / 2f
                val ry = abs(y1 - y0) / 2f
                var best = Float.MAX_VALUE
                var px = cx + rx
                var py = cy
                for (k in 1..ELLIPSE_SEGMENTS) {
                    val angle = 2.0 * PI * k / ELLIPSE_SEGMENTS
                    val qx = cx + rx * cos(angle).toFloat()
                    val qy = cy + ry * sin(angle).toFloat()
                    best = min(best, segmentDistance(x, y, px, py, qx, qy))
                    px = qx
                    py = qy
                }
                best
            }
            DrawTool.ERASER -> Float.MAX_VALUE
        }
    }

    /**
     * Indice du trait le plus récent touché en (x, y), à [slop] px près au-delà de sa
     * demi-épaisseur ; -1 si aucun. Les formes ne se touchent que par leur contour.
     */
    fun hitTest(strokes: List<DrawingStroke>, x: Float, y: Float, width: Float, height: Float, slop: Float): Int {
        for (index in strokes.indices.reversed()) {
            val stroke = strokes[index]
            val reach = strokeWidth(stroke, width, height) / 2f + slop
            // Un tracé à main levée peut compter des centaines de points : sa boîte englobante
            // écarte d'abord les traits éloignés du doigt.
            if (stroke.tool.isFreehand && !near(stroke, x, y, width, height, reach)) continue
            if (distance(stroke, x, y, width, height) <= reach) return index
        }
        return -1
    }

    /** Distance du point (px, py) au segment de (ax, ay) à (bx, by). */
    fun segmentDistance(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        val t = if (lengthSquared == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / lengthSquared).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun near(stroke: DrawingStroke, x: Float, y: Float, width: Float, height: Float, reach: Float): Boolean {
        val bounds = stroke.bounds
        return x >= bounds.left * width - reach && x <= bounds.right * width + reach &&
            y >= bounds.top * height - reach && y <= bounds.bottom * height + reach
    }
}
