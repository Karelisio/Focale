package app.focale.edit

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import androidx.core.graphics.withClip
import kotlin.math.min

/**
 * Zone calculée : grille des moyennes de ses blocs (« Pixels ») ou grille lissée (« Flou »), et
 * sa place dans le cadre où elle a été calculée ([frameWidth] × [frameHeight] px, orientation
 * [quarterTurns] / [flipHorizontal]) : coin de la grille en ([left], [top]), cellule de [bitmap]
 * de [cellWidth] × [cellHeight] px.
 */
class BlurPatch(
    val region: BlurRegion,
    val bitmap: Bitmap,
    val left: Float,
    val top: Float,
    val cellWidth: Float,
    val cellHeight: Float,
    val frameWidth: Int,
    val frameHeight: Int,
    val quarterTurns: Int,
    val flipHorizontal: Boolean,
)

/**
 * Rendu des zones floutées sur un [Canvas] Android, partagé par l'aperçu et l'export : le canevas
 * est en pixels d'un cadre `width` × `height` couvrant l'image pivotée entière, avant
 * redressement (l'appelant applique la rotation de la photo).
 *
 * La source n'est lue que dans la zone ([BlurMath.blockAverages]) et la grille obtenue ne compte
 * que quelques blocs : le shader l'étire sur la forme, sans aucune copie de l'image entière.
 */
object BlurRenderer {

    /** Blocs de [region] calculés sur [source], tournée de [quarterTurns] puis retournée si [flip] ; null hors de l'image. */
    fun patch(source: Bitmap, quarterTurns: Int, flip: Boolean, region: BlurRegion): BlurPatch? {
        val q = Math.floorMod(quarterTurns, 4)
        val width = if (q % 2 == 0) source.width else source.height
        val height = if (q % 2 == 0) source.height else source.width
        val grid = BlurMath.grid(region, width, height) ?: return null
        val averages = BlurMath.blockAverages(grid, source.width, source.height, q, flip) { top, rows, left, stripWidth, buffer ->
            source.getPixels(buffer, 0, stripWidth, left, top, stripWidth, rows)
        }
        val smooth = region.style == BlurStyle.BLUR
        val factor = if (smooth) BlurMath.SMOOTHING else 1
        val colors = if (smooth) BlurMath.smooth(averages, grid.columns, grid.rows) else averages
        val bitmap = Bitmap.createBitmap(colors, grid.columns * factor, grid.rows * factor, Bitmap.Config.ARGB_8888)
        return BlurPatch(
            region = region,
            bitmap = bitmap,
            left = grid.left.toFloat(),
            top = grid.top.toFloat(),
            cellWidth = grid.cellWidth / factor,
            cellHeight = grid.cellHeight / factor,
            frameWidth = width,
            frameHeight = height,
            quarterTurns = q,
            flipHorizontal = flip,
        )
    }

    /**
     * Remplace les pixels de [region] par ceux de [patch] (éventuellement calculé pour une
     * position antérieure de la zone, qu'il suit alors), filtre de couleur [colorFilter] compris.
     * Ni bord lissé ni fondu : un pixel dont le centre tombe dans la zone est entièrement
     * remplacé, opacité comprise ; les autres restent intacts.
     */
    fun draw(canvas: Canvas, region: BlurRegion, patch: BlurPatch, width: Float, height: Float, colorFilter: ColorFilter? = null) {
        val scaleX = width / patch.frameWidth
        val scaleY = height / patch.frameHeight
        val offsetX = (region.area.anchorX - patch.region.area.anchorX) * width
        val offsetY = (region.area.anchorY - patch.region.area.anchorY) * height
        val shader = BitmapShader(patch.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(
                Matrix().apply {
                    setScale(patch.cellWidth * scaleX, patch.cellHeight * scaleY)
                    postTranslate(patch.left * scaleX + offsetX, patch.top * scaleY + offsetY)
                },
            )
        }
        val paint = Paint().apply {
            this.shader = shader
            isAntiAlias = false
            // Blocs nets pour la mosaïque, interpolés pour le flou.
            isFilterBitmap = region.style == BlurStyle.BLUR
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
            this.colorFilter = colorFilter
        }
        val area = region.area
        if (area is BlurArea.Brush) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = area.width * min(width, height)
            paint.strokeCap = Paint.Cap.ROUND
            paint.strokeJoin = Paint.Join.ROUND
        }
        // Limitée à la photo : une zone au bord ne déborde pas dans les coins vides d'une photo
        // redressée (aperçu du recadrage).
        canvas.withClip(0f, 0f, width, height) { drawPath(path(area, width, height), paint) }
    }

    /**
     * Contour de la zone (px du cadre) : rectangle ou ellipse tournés, ou axe du pinceau (à tracer
     * de son épaisseur).
     */
    fun path(area: BlurArea, width: Float, height: Float): Path {
        val reference = min(width, height)
        val path = Path()
        when (area) {
            is BlurArea.Box -> {
                val cx = area.x * width
                val cy = area.y * height
                val a = area.halfWidth * reference
                val b = area.halfHeight * reference
                if (area.shape == BlurShape.ELLIPSE) {
                    path.addOval(cx - a, cy - b, cx + a, cy + b, Path.Direction.CW)
                } else {
                    path.addRect(cx - a, cy - b, cx + a, cy + b, Path.Direction.CW)
                }
                if (area.angle != 0f) path.transform(Matrix().apply { setRotate(area.angle, cx, cy) })
            }
            is BlurArea.Brush -> {
                val x0 = area.x(0) * width
                val y0 = area.y(0) * height
                path.moveTo(x0, y0)
                // Simple appui : un segment infime pour que le bout rond du pinceau marque un disque.
                if (area.pointCount == 1) path.lineTo(x0 + 0.01f, y0)
                for (i in 1 until area.pointCount) path.lineTo(area.x(i) * width, area.y(i) * height)
            }
        }
        return path
    }
}
