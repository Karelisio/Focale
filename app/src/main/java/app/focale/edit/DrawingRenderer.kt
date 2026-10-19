package app.focale.edit

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Tracé des [DrawingStroke] sur un [Canvas] Android, partagé par l'aperçu (canevas natif de
 * Compose) et l'export : le même code produit le même dessin à l'écran et dans le fichier.
 * Le canevas est en pixels d'un cadre [width] × [height] couvrant l'image pivotée entière,
 * origine au coin haut gauche de celle-ci ; épaisseurs et pointes de flèche en dépendent
 * seules, jamais de pixels absolus.
 */
object DrawingRenderer {

    /**
     * Opacité du surligneur. Chaque trait est tracé d'un seul chemin : là où il se recoupe,
     * l'opacité ne se cumule pas.
     */
    const val HIGHLIGHTER_ALPHA = 0.5f

    /** Trait prêt à tracer, que l'aperçu garde en cache d'une image à l'autre. */
    class Prepared(val path: Path, val paint: Paint) {
        fun draw(canvas: Canvas) = canvas.drawPath(path, paint)
    }

    fun draw(canvas: Canvas, strokes: List<DrawingStroke>, width: Float, height: Float) {
        for (stroke in strokes) prepare(stroke, width, height).draw(canvas)
    }

    fun prepare(stroke: DrawingStroke, width: Float, height: Float): Prepared {
        val strokeWidth = DrawingMath.strokeWidth(stroke, width, height)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            this.strokeWidth = strokeWidth
            strokeJoin = Paint.Join.ROUND
            // Bout carré pour le surligneur, à la manière d'une pointe biseautée.
            strokeCap = if (stroke.tool == DrawTool.HIGHLIGHTER) Paint.Cap.SQUARE else Paint.Cap.ROUND
            color = stroke.color
            if (stroke.tool == DrawTool.HIGHLIGHTER) alpha = (HIGHLIGHTER_ALPHA * 255).roundToInt()
        }
        return Prepared(path(stroke, width, height, strokeWidth), paint)
    }

    private fun path(stroke: DrawingStroke, width: Float, height: Float, strokeWidth: Float): Path {
        val path = Path()
        val last = stroke.pointCount - 1
        val x0 = stroke.x(0) * width
        val y0 = stroke.y(0) * height
        val x1 = stroke.x(last) * width
        val y1 = stroke.y(last) * height
        when (stroke.tool) {
            DrawTool.PEN, DrawTool.HIGHLIGHTER -> smooth(path, stroke, width, height, strokeWidth)
            DrawTool.LINE -> {
                path.moveTo(x0, y0)
                path.lineTo(x1, y1)
            }
            DrawTool.ARROW -> {
                path.moveTo(x0, y0)
                path.lineTo(x1, y1)
                DrawingMath.arrowHead(x0, y0, x1, y1, strokeWidth, min(width, height))?.let { head ->
                    path.moveTo(head[0], head[1])
                    path.lineTo(x1, y1)
                    path.lineTo(head[2], head[3])
                }
            }
            DrawTool.RECTANGLE -> path.addRect(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1), Path.Direction.CW)
            DrawTool.ELLIPSE -> path.addOval(min(x0, x1), min(y0, y1), max(x0, x1), max(y0, y1), Path.Direction.CW)
            DrawTool.ERASER -> Unit
        }
        return path
    }

    /**
     * Lissage du tracé à main levée : courbes quadratiques reliant les milieux des points
     * successifs, chaque point servant de point de contrôle. Le tracé part du premier point
     * et finit exactement sous le doigt.
     */
    private fun smooth(path: Path, stroke: DrawingStroke, width: Float, height: Float, strokeWidth: Float) {
        var px = stroke.x(0) * width
        var py = stroke.y(0) * height
        path.moveTo(px, py)
        if (stroke.pointCount == 1) {
            // Simple appui : un segment infime pour que l'extrémité du pinceau marque un point.
            path.lineTo(px + strokeWidth * 0.01f, py)
            return
        }
        for (i in 1 until stroke.pointCount) {
            val x = stroke.x(i) * width
            val y = stroke.y(i) * height
            if (i == 1) path.lineTo((px + x) / 2f, (py + y) / 2f) else path.quadTo(px, py, (px + x) / 2f, (py + y) / 2f)
            px = x
            py = y
        }
        path.lineTo(px, py)
    }
}
