package app.focale.edit

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Géométrie des textes en pixels d'un cadre [width] × [height] couvrant l'image pivotée entière
 * (pure, testable). Un texte est composé une fois pour toutes au corps [LAYOUT_SIZE]
 * ([TextRenderer]), puis mis à l'échelle de son corps ([TextItem.size]) et tourné autour de son
 * centre : l'aperçu et l'export ne diffèrent que par l'échelle.
 */
object TextMath {

    /** Corps (px) de la mise en page de référence. */
    const val LAYOUT_SIZE = 100f

    /** Px du cadre par px de la mise en page de référence. */
    fun scale(item: TextItem, width: Float, height: Float): Float = item.size * min(width, height) / LAYOUT_SIZE

    /** Angle ramené dans ]-180, 180]. */
    fun normalizeAngle(angle: Float): Float {
        var a = angle % 360f
        if (a <= -180f) a += 360f
        if (a > 180f) a -= 360f
        // Jamais -0 : deux textes de même position et de même angle restent égaux.
        return if (a == 0f) 0f else a
    }

    /**
     * Vrai si le point (x, y) (px du cadre) tombe dans le bloc du texte, de demi-dimensions
     * [halfWidth] × [halfHeight] px de mise en page (fond compris), à [slop] px du cadre près.
     */
    fun contains(item: TextItem, halfWidth: Float, halfHeight: Float, x: Float, y: Float, width: Float, height: Float, slop: Float): Boolean {
        val scale = scale(item, width, height)
        // Point ramené dans le repère du texte (rotation inverse autour de son centre).
        val dx = x - item.x * width
        val dy = y - item.y * height
        val radians = Math.toRadians(item.rotation.toDouble())
        val cos = cos(radians).toFloat()
        val sin = sin(radians).toFloat()
        val localX = dx * cos + dy * sin
        val localY = dy * cos - dx * sin
        return abs(localX) <= halfWidth * scale + slop && abs(localY) <= halfHeight * scale + slop
    }

    /**
     * Coins (x0, y0 … x3, y3, en px du cadre, sens horaire depuis le haut gauche) du bloc tourné,
     * agrandi de [margin] px du cadre de chaque côté.
     */
    fun corners(item: TextItem, halfWidth: Float, halfHeight: Float, width: Float, height: Float, margin: Float = 0f): FloatArray {
        val scale = scale(item, width, height)
        val a = halfWidth * scale + margin
        val b = halfHeight * scale + margin
        val cx = item.x * width
        val cy = item.y * height
        val radians = Math.toRadians(item.rotation.toDouble())
        val cos = cos(radians).toFloat()
        val sin = sin(radians).toFloat()
        val local = floatArrayOf(-a, -b, a, -b, a, b, -a, b)
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val lx = local[2 * i]
            val ly = local[2 * i + 1]
            out[2 * i] = cx + lx * cos - ly * sin
            out[2 * i + 1] = cy + lx * sin + ly * cos
        }
        return out
    }

    /**
     * Texte déplacé de ([dx], [dy]) (coordonnées normalisées), agrandi de [zoom] et tourné de
     * [rotation] degrés autour de son centre ; corps borné, centre gardé dans [bounds] (le
     * recadrage) pour qu'un texte ne puisse pas disparaître hors de la zone visible.
     */
    fun transform(item: TextItem, dx: Float, dy: Float, zoom: Float, rotation: Float, bounds: NormRect): TextItem = item.copy(
        x = (item.x + dx).coerceIn(bounds.left, bounds.right),
        y = (item.y + dy).coerceIn(bounds.top, bounds.bottom),
        size = (item.size * zoom).coerceIn(TextItem.MIN_SIZE, TextItem.MAX_SIZE),
        rotation = normalizeAngle(item.rotation + rotation),
    )
}
