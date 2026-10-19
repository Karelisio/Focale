package app.focale.people

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Cadre d'un visage en fraction de l'image (orientation appliquée), de 0 à 1. */
data class FaceBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    /** Intersection sur union : 1 pour deux cadres identiques, 0 s'ils sont disjoints. */
    fun iou(other: FaceBox): Float {
        val w = min(right, other.right) - max(left, other.left)
        val h = min(bottom, other.bottom) - max(top, other.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        val union = width * height + other.width * other.height - inter
        return if (union <= 0f) 0f else inter / union
    }
}

/** Rectangle entier en pixels, bornes droite et basse exclues. */
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

object FaceGeometry {

    /**
     * Associe les visages d'une nouvelle analyse d'une photo à ceux de la précédente, par
     * recouvrement des cadres (les plus recouvrants d'abord) : un visage retrouvé garde son
     * identifiant, sa personne et son rattachement manuel. Renvoie, pour chaque cadre de
     * [detected], l'index du cadre de [previous] retenu ou -1.
     */
    fun matchPrevious(previous: List<FaceBox>, detected: List<FaceBox>, minIou: Float = MATCH_IOU): IntArray {
        val result = IntArray(detected.size) { -1 }
        val pairs = ArrayList<Triple<Int, Int, Float>>()
        for (i in detected.indices) {
            for (j in previous.indices) {
                val overlap = detected[i].iou(previous[j])
                if (overlap >= minIou) pairs += Triple(i, j, overlap)
            }
        }
        pairs.sortByDescending { it.third }
        val used = BooleanArray(previous.size)
        for ((i, j) in pairs) {
            if (result[i] != -1 || used[j]) continue
            result[i] = j
            used[j] = true
        }
        return result
    }

    /**
     * Carré centré sur le visage, agrandi de [margin] (cheveux, menton), dans une image de
     * [width] × [height] pixels : réduit à l'image si besoin, puis décalé pour y tenir.
     */
    fun squareCrop(box: FaceBox, width: Int, height: Int, margin: Float = AVATAR_MARGIN): PixelRect {
        val faceSide = max(box.width * width, box.height * height)
        val side = (faceSide * margin).roundToInt().coerceIn(1, min(width, height).coerceAtLeast(1))
        val centerX = (box.left + box.right) / 2 * width
        val centerY = (box.top + box.bottom) / 2 * height
        val left = (centerX - side / 2f).roundToInt().coerceIn(0, (width - side).coerceAtLeast(0))
        val top = (centerY - side / 2f).roundToInt().coerceIn(0, (height - side).coerceAtLeast(0))
        return PixelRect(left, top, left + side, top + side)
    }

    /** Recouvrement minimal pour considérer qu'un visage est le même d'une analyse à l'autre. */
    const val MATCH_IOU = 0.4f

    /** Marge autour du visage dans les vignettes rondes. */
    const val AVATAR_MARGIN = 1.6f
}
