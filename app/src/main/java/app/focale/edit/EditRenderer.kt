package app.focale.edit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withMatrix
import kotlin.math.max
import kotlin.math.roundToInt

/** Rendu d'une [EditState] sur un bitmap (aperçu et export partagent ce code). */
object EditRenderer {

    /**
     * Matrice de la géométrie (quarts de tour horaires puis miroir horizontal) et taille
     * de l'image obtenue.
     */
    fun geometry(width: Int, height: Int, quarterTurns: Int, flip: Boolean): Pair<Matrix, Pair<Int, Int>> {
        val q = Math.floorMod(quarterTurns, 4)
        val (outWidth, outHeight) = if (q % 2 == 0) width to height else height to width
        val matrix = Matrix()
        when (q) {
            1 -> {
                matrix.postRotate(90f)
                matrix.postTranslate(height.toFloat(), 0f)
            }
            2 -> {
                matrix.postRotate(180f)
                matrix.postTranslate(width.toFloat(), height.toFloat())
            }
            3 -> {
                matrix.postRotate(270f)
                matrix.postTranslate(0f, width.toFloat())
            }
        }
        if (flip) {
            matrix.postScale(-1f, 1f)
            matrix.postTranslate(outWidth.toFloat(), 0f)
        }
        return matrix to (outWidth to outHeight)
    }

    /**
     * Applique géométrie, recadrage, couleurs puis dessin ; l'aperçu géométrique se passe du
     * recadrage, des couleurs et du dessin (tracé en direct par l'écran).
     */
    fun render(
        source: Bitmap,
        state: EditState,
        includeCrop: Boolean = true,
        includeColor: Boolean = true,
        includeDrawing: Boolean = true,
    ): Bitmap {
        val (matrix, size) = geometry(source.width, source.height, state.quarterTurns, state.flipHorizontal)
        val (fullWidth, fullHeight) = size
        val crop = if (includeCrop) state.crop else NormRect.Full
        val left = (crop.left * fullWidth).roundToInt().coerceIn(0, fullWidth - 1)
        val top = (crop.top * fullHeight).roundToInt().coerceIn(0, fullHeight - 1)
        val width = max(1, (crop.width * fullWidth).roundToInt()).coerceAtMost(fullWidth - left)
        val height = max(1, (crop.height * fullHeight).roundToInt()).coerceAtMost(fullHeight - top)

        val output = createBitmap(width, height)
        val canvas = Canvas(output)
        canvas.translate(-left.toFloat(), -top.toFloat())
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (includeColor) {
            val values = state.colorMatrix()
            if (!values.contentEquals(ColorMatrices.identity)) {
                paint.colorFilter = ColorMatrixColorFilter(values)
            }
        }
        canvas.withMatrix(matrix) { drawBitmap(source, 0f, 0f, paint) }
        // Les traits sont exprimés dans le repère pivoté : tracés hors de la matrice de
        // géométrie, mais sous la translation du recadrage, et sans filtre de couleur.
        if (includeDrawing) DrawingRenderer.draw(canvas, state.strokes, fullWidth.toFloat(), fullHeight.toFloat())
        return output
    }
}
