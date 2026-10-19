package app.focale.ml.text

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Bande horizontale d'une image haute, lue séparément ([top] inclus, [bottom] exclu).
 * Les bandes voisines se chevauchent pour qu'aucune ligne ne soit coupée ; une ligne
 * revient à la bande dont la zone propre ([ownTop]..[ownBottom]) contient son centre.
 */
data class TextBand(val top: Int, val bottom: Int, val ownTop: Int, val ownBottom: Int) {
    val height: Int get() = bottom - top

    /** Vrai si une ligne centrée en [y] (coordonnée de l'image entière) revient à cette bande. */
    fun owns(y: Float): Boolean = y >= ownTop && y < ownBottom
}

/**
 * Cadrage pur (testable sur la JVM) de l'image soumise à la reconnaissance de texte :
 * réduite mais lisible, côté le plus long à 1600 px. Les images très hautes (captures
 * d'écran, pages défilées) gardent leur largeur d'écran et sont lues par bandes : réduites
 * à 1600 px de haut, leur texte deviendrait illisible.
 */
object TextFraming {

    const val MAX_SIDE = 1600

    /** Rapport hauteur / largeur à partir duquel une image est lue par bandes (écrans 18:9 et plus). */
    const val TALL_RATIO = 2.0
    const val TALL_WIDTH = 1200

    /** Plafond de pixels décodés (24 Mo en ARGB) pour les pages défilées très longues. */
    const val MAX_PIXELS = 6_000_000L

    const val BAND_HEIGHT = 1600

    /** Chevauchement des bandes, supérieur à la hauteur d'une ligne de titre. */
    const val BAND_OVERLAP = 160

    /** En dessous, aucune ligne lisible : l'image n'est pas soumise à la reconnaissance. */
    const val MIN_SIDE = 32

    /** Taille de décodage d'une image [width] × [height] (orientation appliquée), jamais agrandie. */
    fun decodeSize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return width to height
        val tall = height >= width * TALL_RATIO
        val scale = min(
            if (tall) TALL_WIDTH.toDouble() / width else MAX_SIDE.toDouble() / max(width, height),
            sqrt(MAX_PIXELS.toDouble() / (width.toLong() * height)),
        )
        if (scale >= 1.0) return width to height
        return max(1, (width * scale).roundToInt()) to max(1, (height * scale).roundToInt())
    }

    /** Bandes de hauteur égale (au plus [BAND_HEIGHT]) couvrant une image de [height] px. */
    fun bands(height: Int): List<TextBand> {
        if (height <= BAND_HEIGHT) return listOf(TextBand(0, height, 0, height))
        val count = ceil((height - BAND_OVERLAP).toDouble() / (BAND_HEIGHT - BAND_OVERLAP)).toInt()
        val size = ceil((height + (count - 1) * BAND_OVERLAP).toDouble() / count).toInt()
        val step = size - BAND_OVERLAP
        // Frontière entre les zones propres de deux bandes : milieu de leur chevauchement.
        val boundaries = (1 until count).map { i -> i * step + BAND_OVERLAP / 2 }
        return (0 until count).map { i ->
            val top = i * step
            TextBand(
                top = top,
                bottom = if (i == count - 1) height else top + size,
                ownTop = if (i == 0) 0 else boundaries[i - 1],
                ownBottom = if (i == count - 1) height else boundaries[i],
            )
        }
    }
}
