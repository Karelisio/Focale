package app.focale.print

import app.focale.core.model.MediaItem
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Rectangle d'une page, en points PostScript (1/72 de pouce). */
data class PrintRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * Page d'une photo : taille de la page (points), cadre de la photo, et taille de décodage
 * (pixels) qui suffit à la résolution de l'imprimante.
 */
data class PrintPage(val width: Int, val height: Int, val photo: PrintRect, val decodeWidth: Int, val decodeHeight: Int) {
    val landscape: Boolean get() = width > height
}

/**
 * Mise en page d'une photo par page : page à l'italienne pour une photo plus large que haute,
 * à la française sinon (dimensions affichées, orientation EXIF appliquée) ; photo agrandie ou
 * réduite pour tenir dans la page moins les marges, sans être rognée, et centrée. Les marges
 * minimales de l'imprimante, données pour le papier dans un sens, valent de tous côtés : la
 * page peut être tournée. Décodage à la résolution de l'imprimante, au plus [MAX_DPI] (au-delà,
 * rien ne se voit sur une photo), sans dépasser la taille de la photo ni [MAX_DECODE_PIXELS].
 */
object PrintLayout {

    const val POINTS_PER_INCH = 72
    const val MILS_PER_INCH = 1000
    const val MAX_DPI = 300
    const val MAX_DECODE_PIXELS = 24_000_000L

    /**
     * @param mediaWidthMils largeur du papier (millièmes de pouce), dans un sens ou dans l'autre.
     * @param marginMils plus grande des marges minimales de l'imprimante.
     * @param dpi résolution de l'imprimante ; 0 ou moins si elle est inconnue.
     */
    fun page(photoWidth: Int, photoHeight: Int, mediaWidthMils: Int, mediaHeightMils: Int, marginMils: Int, dpi: Int): PrintPage {
        val landscape = photoWidth > photoHeight
        val short = minOf(mediaWidthMils, mediaHeightMils).coerceAtLeast(1)
        val long = maxOf(mediaWidthMils, mediaHeightMils).coerceAtLeast(1)
        val width = points(if (landscape) long else short)
        val height = points(if (landscape) short else long)
        val margin = (marginMils.coerceAtLeast(0).toLong() * POINTS_PER_INCH / MILS_PER_INCH).toInt().coerceAtMost(min(width, height) / 4)
        val availableWidth = (width - 2 * margin).coerceAtLeast(1).toFloat()
        val availableHeight = (height - 2 * margin).coerceAtLeast(1).toFloat()
        val safeWidth = photoWidth.coerceAtLeast(1)
        val safeHeight = photoHeight.coerceAtLeast(1)
        val scale = min(availableWidth / safeWidth, availableHeight / safeHeight)
        val photoW = safeWidth * scale
        val photoH = safeHeight * scale
        val left = (width - photoW) / 2
        val top = (height - photoH) / 2
        val (decodeWidth, decodeHeight) = decodeSize(photoW, photoH, safeWidth, safeHeight, dpi)
        return PrintPage(width, height, PrintRect(left, top, left + photoW, top + photoH), decodeWidth, decodeHeight)
    }

    /** Pixels utiles pour un cadre de [frameWidth] × [frameHeight] points à [dpi]. */
    internal fun decodeSize(frameWidth: Float, frameHeight: Float, photoWidth: Int, photoHeight: Int, dpi: Int): Pair<Int, Int> {
        val effectiveDpi = if (dpi in 1..MAX_DPI) dpi else MAX_DPI
        var width = min(frameWidth / POINTS_PER_INCH * effectiveDpi, photoWidth.toFloat())
        var height = min(frameHeight / POINTS_PER_INCH * effectiveDpi, photoHeight.toFloat())
        val pixels = width.toDouble() * height
        if (pixels > MAX_DECODE_PIXELS) {
            val shrink = sqrt(MAX_DECODE_PIXELS / pixels).toFloat()
            width *= shrink
            height *= shrink
        }
        return width.roundToInt().coerceAtLeast(1) to height.roundToInt().coerceAtLeast(1)
    }

    private fun points(mils: Int): Int = (mils.toLong() * POINTS_PER_INCH / MILS_PER_INCH).toInt().coerceAtLeast(1)
}

/** Photos à imprimer, une page chacune : les vidéos sont ignorées. */
object PrintSelection {
    fun photos(items: List<MediaItem>): List<MediaItem> = items.filter { !it.isVideo }
}

/** Pages demandées par le service d'impression, en index de photos. */
internal object PrintPages {

    /** Index (triés, sans doublon) des [count] pages couverts par [ranges] (bornes incluses). */
    fun indices(ranges: List<IntRange>, count: Int): List<Int> =
        ranges.flatMap { range -> (range.first.coerceAtLeast(0)..range.last.coerceAtMost(count - 1)) }.distinct().sorted()

    /** Plages consécutives de [indices] (triés). */
    fun ranges(indices: List<Int>): List<IntRange> {
        val ranges = ArrayList<IntRange>()
        var start = -1
        var previous = -1
        for (index in indices) {
            if (start >= 0 && index == previous + 1) {
                previous = index
                continue
            }
            if (start >= 0) ranges += start..previous
            start = index
            previous = index
        }
        if (start >= 0) ranges += start..previous
        return ranges
    }
}
