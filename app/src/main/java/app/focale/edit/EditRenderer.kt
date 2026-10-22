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
     * Applique géométrie (quarts de tour, miroir, redressement), recadrage, couleurs, zones
     * floutées puis dessin et textes ; l'aperçu géométrique se passe du recadrage, des couleurs
     * et du dessin (tracé en direct par l'écran).
     *
     * @param faces police des textes ([TextRenderer.faces] pour celle de l'application).
     */
    fun render(
        source: Bitmap,
        state: EditState,
        includeCrop: Boolean = true,
        includeColor: Boolean = true,
        includeDrawing: Boolean = true,
        faces: TextRenderer.Faces = TextRenderer.Faces.System,
    ): Bitmap {
        val (matrix, size) = geometry(source.width, source.height, state.quarterTurns, state.flipHorizontal)
        val (fullWidth, fullHeight) = size
        // Redressement seul, autour du centre du cadre : la photo et ses zones floutées le suivent.
        val straighten = Matrix()
        if (state.straighten != 0f) {
            // Image très légèrement agrandie : aucun pixel à demi couvert dans les coins du
            // recadrage ([StraightenMath.bleed]).
            val bleed = StraightenMath.bleed(fullWidth.toFloat(), fullHeight.toFloat())
            for (target in listOf(matrix, straighten)) {
                target.postRotate(state.straighten, fullWidth / 2f, fullHeight / 2f)
                target.postScale(bleed, bleed, fullWidth / 2f, fullHeight / 2f)
            }
        }
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
        // Zones floutées : calculées zone par zone sur la source en pleine résolution, puis
        // posées dans le repère pivoté avant redressement, avec le filtre de couleur de la photo.
        if (state.blurs.isNotEmpty()) {
            canvas.withMatrix(straighten) {
                for (region in state.blurs) {
                    val patch = BlurRenderer.patch(source, state.quarterTurns, state.flipHorizontal, region) ?: continue
                    BlurRenderer.draw(this, region, patch, fullWidth.toFloat(), fullHeight.toFloat(), paint.colorFilter)
                    patch.bitmap.recycle()
                }
            }
        }
        // Les traits et les textes sont exprimés dans le repère pivoté : tracés hors de la
        // matrice de géométrie, mais sous la translation du recadrage, et sans filtre de couleur.
        if (includeDrawing) {
            DrawingRenderer.draw(canvas, state.strokes, fullWidth.toFloat(), fullHeight.toFloat())
            TextRenderer.draw(canvas, state.texts, faces, fullWidth.toFloat(), fullHeight.toFloat())
        }
        return output
    }
}
