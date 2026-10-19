package app.focale.ml

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.core.graphics.createBitmap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Mesures d'image pures (testables sur la JVM) : empreinte perceptuelle et netteté.
 * Les entrées sont des luminances 0..255 en ordre ligne par ligne.
 */
object ImageMetrics {

    /** Taille de l'image de travail pour l'empreinte : 9 × 8 (8 comparaisons par ligne). */
    const val HASH_WIDTH = 9
    const val HASH_HEIGHT = 8

    /**
     * dHash 64 bits : chaque bit indique si un pixel est plus clair que son voisin de
     * droite. Robuste au redimensionnement, à la compression et aux légers réglages.
     */
    fun dHash(gray: IntArray): Long {
        require(gray.size == HASH_WIDTH * HASH_HEIGHT) { "dHash attend une image 9 × 8" }
        var hash = 0L
        var bit = 0
        for (y in 0 until HASH_HEIGHT) {
            for (x in 0 until HASH_WIDTH - 1) {
                val left = gray[y * HASH_WIDTH + x]
                val right = gray[y * HASH_WIDTH + x + 1]
                if (left > right) hash = hash or (1L shl bit)
                bit++
            }
        }
        return hash
    }

    fun hammingDistance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /**
     * Variance du laplacien (noyau 4-voisins) : mesure classique de netteté. Une image
     * floue a peu de contours marqués, donc une variance faible.
     */
    fun laplacianVariance(gray: IntArray, width: Int, height: Int): Double {
        if (width < 3 || height < 3) return 0.0
        var sum = 0.0
        var sumSquares = 0.0
        var count = 0
        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val i = row + x
                val value = (gray[i - width] + gray[i + width] + gray[i - 1] + gray[i + 1] - 4 * gray[i]).toDouble()
                sum += value
                sumSquares += value * value
                count++
            }
        }
        val mean = sum / count
        return sumSquares / count - mean * mean
    }

    /** Luminance perceptuelle (Rec. 601) d'un pixel ARGB. */
    fun luminance(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (0.299 * r + 0.587 * g + 0.114 * b).roundToInt()
    }

    /** Luminances d'un bitmap redimensionné à [width] × [height] (filtrage bilinéaire). */
    fun grayscale(bitmap: Bitmap, width: Int, height: Int): IntArray {
        val scaled = createBitmap(width, height)
        Canvas(scaled).drawBitmap(
            bitmap,
            null,
            android.graphics.Rect(0, 0, width, height),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        scaled.recycle()
        for (i in pixels.indices) pixels[i] = luminance(pixels[i])
        return pixels
    }

    /** Dimensions préservant le rapport d'aspect, côté le plus long = [maxSide]. */
    fun fit(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        val scale = maxSide.toDouble() / max(width, height)
        if (scale >= 1.0) return width to height
        return max(3, (width * scale).roundToInt()) to max(3, (height * scale).roundToInt())
    }
}
