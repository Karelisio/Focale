package app.focale.ml.semantic

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Prétraitement CLIP d'une image : carré central (équivalent de `Resize(224)` +
 * `CenterCrop(224)`), réduction bicubique avec anticrénelage (calcul des coefficients de
 * Pillow), puis normalisation par canal.
 *
 * Réplique exacte de `preprocess()` dans `tools/clip/clip_model.py`, qui a servi à vérifier
 * les modèles exportés : les deux doivent évoluer ensemble.
 */
object ClipPreprocessor {

    /** Côté de l'image d'entrée du modèle. */
    const val SIZE = 224

    private val MEAN = doubleArrayOf(0.48145466, 0.4578275, 0.40821073)
    private val STD = doubleArrayOf(0.26862954, 0.26130258, 0.27577711)

    /**
     * @param pixels couleurs ARGB non prémultipliées (sRGB), ligne par ligne, orientation
     *   déjà appliquée
     * @return tenseur `[3, 224, 224]` (canal, ligne, colonne) aplati
     */
    fun preprocess(pixels: IntArray, width: Int, height: Int): FloatArray {
        require(width > 0 && height > 0 && pixels.size >= width * height) { "Image vide" }
        val side = min(width, height)
        val x0 = (width - side) / 2.0
        val y0 = (height - side) / 2.0
        val columns = Resampling.of(width, x0, x0 + side, SIZE)
        val rows = Resampling.of(height, y0, y0 + side, SIZE)

        // Passe horizontale, limitée aux lignes lues par la passe verticale.
        val firstRow = rows.start.first()
        val lastRow = rows.start.indices.maxOf { rows.start[it] + rows.weights[it].size }
        val horizontal = DoubleArray((lastRow - firstRow) * SIZE * 3)
        for (y in firstRow until lastRow) {
            val line = y * width
            val out = (y - firstRow) * SIZE * 3
            for (x in 0 until SIZE) {
                var r = 0.0
                var g = 0.0
                var b = 0.0
                val start = columns.start[x]
                val w = columns.weights[x]
                for (k in w.indices) {
                    val color = pixels[line + start + k]
                    r += w[k] * (color shr 16 and 0xFF)
                    g += w[k] * (color shr 8 and 0xFF)
                    b += w[k] * (color and 0xFF)
                }
                horizontal[out + x * 3] = clamp(r)
                horizontal[out + x * 3 + 1] = clamp(g)
                horizontal[out + x * 3 + 2] = clamp(b)
            }
        }

        // Passe verticale, puis normalisation en plans de couleur.
        val plane = SIZE * SIZE
        val tensor = FloatArray(plane * 3)
        for (y in 0 until SIZE) {
            val start = rows.start[y] - firstRow
            val w = rows.weights[y]
            for (x in 0 until SIZE) {
                for (channel in 0 until 3) {
                    var sum = 0.0
                    for (k in w.indices) sum += w[k] * horizontal[((start + k) * SIZE + x) * 3 + channel]
                    val value = (clamp(sum) / 255.0 - MEAN[channel]) / STD[channel]
                    tensor[channel * plane + y * SIZE + x] = value.toFloat()
                }
            }
        }
        return tensor
    }

    private fun clamp(value: Double): Double = value.coerceIn(0.0, 255.0)

    /** Coefficients d'une passe : premier pixel source et poids normalisés, par pixel de sortie. */
    internal class Resampling(val start: IntArray, val weights: Array<DoubleArray>) {
        companion object {
            /** Réduction de l'intervalle source `[in0, in1)` de [inSize] pixels vers [outSize] pixels. */
            fun of(inSize: Int, in0: Double, in1: Double, outSize: Int): Resampling {
                val scale = (in1 - in0) / outSize
                // Le support du filtre s'élargit avec la réduction : c'est l'anticrénelage.
                val filterScale = max(scale, 1.0)
                val support = 2.0 * filterScale
                val starts = IntArray(outSize)
                val weights = Array(outSize) { xx ->
                    val center = in0 + (xx + 0.5) * scale
                    val xmin = max((center - support + 0.5).toInt(), 0)
                    val xmax = min((center + support + 0.5).toInt(), inSize)
                    starts[xx] = xmin
                    val w = DoubleArray(max(xmax - xmin, 0)) { x -> bicubic((x + xmin - center + 0.5) / filterScale) }
                    val total = w.sum()
                    if (total != 0.0) for (i in w.indices) w[i] /= total
                    w
                }
                return Resampling(starts, weights)
            }

            /** Noyau bicubique de Pillow (a = -0,5). */
            private fun bicubic(value: Double): Double {
                val a = -0.5
                val x = abs(value)
                return when {
                    x < 1.0 -> ((a + 2.0) * x - (a + 3.0)) * x * x + 1.0
                    x < 2.0 -> (((x - 5.0) * x + 8.0) * x - 4.0) * a
                    else -> 0.0
                }
            }
        }
    }
}
