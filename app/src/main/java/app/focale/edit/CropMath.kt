package app.focale.edit

/** Poignée de recadrage saisie par l'utilisateur. */
enum class CropHandle { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, LEFT, TOP, RIGHT, BOTTOM, MOVE }

/**
 * Géométrie du recadrage en coordonnées normalisées (pure, testable).
 *
 * Un format en pixels (ex. 16:9) correspond, en coordonnées normalisées, au rapport
 * `ratio × hauteur / largeur` de l'image (après rotation).
 */
object CropMath {

    const val MIN_SIZE = 0.08f

    /** Rapport normalisé (largeur / hauteur) correspondant à [aspect] pour une image w × h. */
    fun normalizedRatio(aspect: CropAspect, imageWidth: Int, imageHeight: Int, originalRatio: Float): Float? {
        val pixelRatio = when (aspect) {
            CropAspect.FREE -> return null
            CropAspect.ORIGINAL -> originalRatio
            else -> aspect.ratio!!
        }
        return pixelRatio * imageHeight / imageWidth
    }

    /** Plus grand rectangle centré de rapport normalisé [ratio] tenant dans l'image. */
    fun fit(ratio: Float?): NormRect {
        if (ratio == null) return NormRect.Full
        return if (ratio >= 1f) {
            val h = 1f / ratio
            NormRect(0f, (1f - h) / 2f, 1f, (1f + h) / 2f)
        } else {
            val w = ratio
            NormRect((1f - w) / 2f, 0f, (1f + w) / 2f, 1f)
        }
    }

    /** Rotation d'un quart de tour horaire du repère de l'image. */
    fun rotateClockwise(rect: NormRect): NormRect = NormRect(
        left = 1f - rect.bottom,
        top = rect.left,
        right = 1f - rect.top,
        bottom = rect.right,
    )

    fun flipHorizontal(rect: NormRect): NormRect = NormRect(1f - rect.right, rect.top, 1f - rect.left, rect.bottom)

    /**
     * Déplace une poignée de (dx, dy) (coordonnées normalisées) en respectant les bords,
     * une taille minimale et, si [ratio] n'est pas null, le format imposé.
     */
    fun drag(rect: NormRect, handle: CropHandle, dx: Float, dy: Float, ratio: Float?): NormRect {
        if (handle == CropHandle.MOVE) {
            val moveX = dx.coerceIn(-rect.left, 1f - rect.right)
            val moveY = dy.coerceIn(-rect.top, 1f - rect.bottom)
            return NormRect(rect.left + moveX, rect.top + moveY, rect.right + moveX, rect.bottom + moveY)
        }
        var l = rect.left
        var t = rect.top
        var r = rect.right
        var b = rect.bottom
        when (handle) {
            CropHandle.TOP_LEFT -> { l += dx; t += dy }
            CropHandle.TOP_RIGHT -> { r += dx; t += dy }
            CropHandle.BOTTOM_LEFT -> { l += dx; b += dy }
            CropHandle.BOTTOM_RIGHT -> { r += dx; b += dy }
            CropHandle.LEFT -> l += dx
            CropHandle.RIGHT -> r += dx
            CropHandle.TOP -> t += dy
            CropHandle.BOTTOM -> b += dy
            CropHandle.MOVE -> Unit
        }
        l = l.coerceIn(0f, rect.right - MIN_SIZE)
        r = r.coerceIn(rect.left + MIN_SIZE, 1f)
        t = t.coerceIn(0f, rect.bottom - MIN_SIZE)
        b = b.coerceIn(rect.top + MIN_SIZE, 1f)
        if (ratio == null) return NormRect(l, t, r, b)
        return constrain(NormRect(l, t, r, b), handle, ratio)
    }

    /** Ajuste le rectangle au format [ratio], en gardant fixe le coin opposé à la poignée. */
    private fun constrain(rect: NormRect, handle: CropHandle, ratio: Float): NormRect {
        var width = rect.width
        var height = rect.height
        // La dimension pilotée par le geste détermine l'autre.
        when (handle) {
            CropHandle.LEFT, CropHandle.RIGHT -> height = width / ratio
            CropHandle.TOP, CropHandle.BOTTOM -> width = height * ratio
            else -> if (width / height > ratio) width = height * ratio else height = width / ratio
        }
        val anchorRight = handle == CropHandle.TOP_LEFT || handle == CropHandle.BOTTOM_LEFT || handle == CropHandle.LEFT
        val anchorBottom = handle == CropHandle.TOP_LEFT || handle == CropHandle.TOP_RIGHT || handle == CropHandle.TOP
        val maxWidth = if (anchorRight) rect.right else 1f - rect.left
        val maxHeight = if (anchorBottom) rect.bottom else 1f - rect.top
        if (width > maxWidth) {
            width = maxWidth
            height = width / ratio
        }
        if (height > maxHeight) {
            height = maxHeight
            width = height * ratio
        }
        val left = if (anchorRight) rect.right - width else rect.left
        val top = if (anchorBottom) rect.bottom - height else rect.top
        return NormRect(left, top, left + width, top + height)
    }
}
