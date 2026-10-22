package app.focale.collage

import app.focale.edit.ExifOrientation
import kotlin.math.max
import kotlin.math.min

/**
 * Passage de l'image stockée ([storedWidth] × [storedHeight] px) à l'image affichée, selon son
 * orientation EXIF : quarts de tour horaires, puis miroir horizontal ([ExifOrientation]). Le
 * collage cadre l'image affichée ; le décodeur de zones ne lit que l'image stockée.
 */
class ImageOrientation(val storedWidth: Int, val storedHeight: Int, orientation: Int) {
    private val turns = ExifOrientation.quarterTurns(orientation)
    private val mirrored = ExifOrientation.mirrored(orientation)

    val displayWidth: Int get() = if (turns % 2 == 0) storedWidth else storedHeight
    val displayHeight: Int get() = if (turns % 2 == 0) storedHeight else storedWidth

    /**
     * Transformation affine stocké → affiché, (a, b, c, d, e, f) : u = a·x + b·y + c et
     * v = d·x + e·y + f, dans l'ordre d'une matrice Android (`setValues`, sans la dernière ligne).
     */
    val toDisplay: FloatArray
        get() {
            val w = storedWidth.toFloat()
            val h = storedHeight.toFloat()
            val turned = when (turns) {
                1 -> floatArrayOf(0f, -1f, h, 1f, 0f, 0f)
                2 -> floatArrayOf(-1f, 0f, w, 0f, -1f, h)
                3 -> floatArrayOf(0f, 1f, 0f, -1f, 0f, w)
                else -> floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
            }
            if (!mirrored) return turned
            // Miroir de l'image tournée : u devient largeur affichée − u.
            val width = displayWidth.toFloat()
            return floatArrayOf(-turned[0], -turned[1], width - turned[2], turned[3], turned[4], turned[5])
        }

    /** Point affiché ([u], [v]) → point stocké. */
    fun toStored(u: Float, v: Float): Pair<Float, Float> {
        val w = storedWidth.toFloat()
        val h = storedHeight.toFloat()
        val x = if (mirrored) displayWidth - u else u
        return when (turns) {
            1 -> v to h - x
            2 -> w - x to h - v
            3 -> w - v to x
            else -> x to v
        }
    }

    /** Rectangle affiché → rectangle stocké (quarts de tour et miroir gardent les rectangles droits). */
    fun toStored(rect: CellRect): CellRect {
        val (x1, y1) = toStored(rect.left, rect.top)
        val (x2, y2) = toStored(rect.right, rect.bottom)
        return CellRect(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2))
    }

    /** Rectangle stocké → rectangle affiché. */
    fun toDisplay(rect: CellRect): CellRect {
        val m = toDisplay
        val u1 = m[0] * rect.left + m[1] * rect.top + m[2]
        val v1 = m[3] * rect.left + m[4] * rect.top + m[5]
        val u2 = m[0] * rect.right + m[1] * rect.bottom + m[2]
        val v2 = m[3] * rect.right + m[4] * rect.bottom + m[5]
        return CellRect(min(u1, u2), min(v1, v2), max(u1, u2), max(v1, v2))
    }
}
