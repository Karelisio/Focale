package app.focale.collage

import kotlin.math.roundToInt

/** Rectangle en pixels : case d'un collage, ou partie visible d'une photo. */
data class CellRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** Bord gauche et haut inclus, droit et bas exclus : un point n'appartient qu'à une case. */
    fun contains(x: Float, y: Float): Boolean = x >= left && x < right && y >= top && y < bottom
}

/** Proportion du collage, largeur : hauteur. */
enum class CollageAspect(val width: Int, val height: Int) {
    SQUARE(1, 1),
    PORTRAIT(4, 5),
    PHOTO(3, 4),
    STORY(9, 16),
    LANDSCAPE(16, 9),
    ;

    val ratio: Float get() = width.toFloat() / height

    /** Dimensions en pixels, [longSide] sur le grand côté (l'autre arrondi au pixel). */
    fun size(longSide: Int): Pair<Int, Int> = if (width >= height) {
        longSide to (longSide.toDouble() * height / width).roundToInt()
    } else {
        (longSide.toDouble() * width / height).roundToInt() to longSide
    }
}

/**
 * Cadrage d'une photo dans sa case : agrandissement depuis « remplir la case » (1 à
 * [CellFraming.MAX_ZOOM]) et centre de la partie visible, en fraction de la photo. Normalisé,
 * il garde son sens quelle que soit la taille de la case : aperçu, export, autre disposition.
 */
data class CellFrame(val zoom: Float = 1f, val centerX: Float = 0.5f, val centerY: Float = 0.5f) {
    companion object {
        val Default = CellFrame()
    }
}

/** Fond du collage : blanc, noir ou une couleur du thème Material You. */
enum class CollageBackground { WHITE, BLACK, PRIMARY, PRIMARY_SOFT, SECONDARY, SECONDARY_SOFT, TERTIARY, TERTIARY_SOFT }

/**
 * Collage en cours. [photos] : la photo de chaque case de la disposition, dans l'ordre de ses
 * cases. Espacement et arrondi sont des fractions du petit côté du collage : l'aperçu et l'export
 * ont les mêmes proportions. Le cadrage suit la photo ([frames]), même quand elle change de case.
 * [backgroundColor] est la couleur résolue du fond (ARGB), celle qu'on voit et qu'on exporte.
 */
data class CollageState(
    val layoutId: String,
    val photos: List<Long>,
    val aspect: CollageAspect = CollageAspect.SQUARE,
    val spacing: Float = DEFAULT_SPACING,
    val corners: Float = 0f,
    val background: CollageBackground = CollageBackground.WHITE,
    val backgroundColor: Int = WHITE,
    val frames: Map<Long, CellFrame> = emptyMap(),
) {
    /** Disposition choisie ; la première pour ce nombre de photos si l'identifiant est inconnu. */
    val layout: CollageLayout
        get() = CollageLayouts.byId(layoutId)?.takeIf { it.cells.size == photos.size } ?: CollageLayouts.forCount(photos.size).first()

    fun frame(photo: Long): CellFrame = frames[photo] ?: CellFrame.Default

    /** Photos des cases [first] et [second] échangées, chacune avec son cadrage. */
    fun swapped(first: Int, second: Int): CollageState {
        if (first == second || first !in photos.indices || second !in photos.indices) return this
        val list = photos.toMutableList()
        list[first] = photos[second]
        list[second] = photos[first]
        return copy(photos = list)
    }

    companion object {
        /** Espacement maximal : 5 % du petit côté. */
        const val MAX_SPACING = 0.05f

        /** Arrondi maximal : 8 % du petit côté (et jamais plus de la moitié d'une case). */
        const val MAX_CORNERS = 0.08f
        const val DEFAULT_SPACING = 0.015f
        const val WHITE = -0x1
        const val BLACK = -0x1000000

        /** Première disposition proposée pour ces photos, fond blanc. */
        fun initial(photos: List<Long>): CollageState = CollageState(CollageLayouts.forCount(photos.size).first().id, photos)
    }
}
