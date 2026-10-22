package app.focale.edit

import androidx.compose.runtime.Immutable
import kotlin.math.max
import kotlin.math.min

/** Forme d'une zone floutée. */
enum class BlurShape { RECTANGLE, ELLIPSE, BRUSH }

/**
 * Rendu d'une zone : flou fort ou mosaïque (« Pixels »). Tous deux ne gardent que la moyenne de
 * gros blocs ([BlurMath]) : aucun détail plus fin qu'un bloc ne survit, ni rien à « déflouter ».
 */
enum class BlurStyle { BLUR, PIXELATE }

/**
 * Étendue d'une zone floutée, attachée à la photo : coordonnées normalisées du cadre de l'image
 * pivotée et retournée, mais *avant* redressement. Contrairement aux traits et aux textes, la
 * zone pivote avec la photo quand on la redresse : ce qu'elle cache reste caché à tout angle.
 * Les longueurs sont en fraction du petit côté de l'image, comme l'épaisseur d'un trait.
 */
sealed interface BlurArea {
    val shape: BlurShape

    /** Quart de tour horaire du repère, comme [CropMath.rotateClockwise]. */
    fun rotateClockwise(): BlurArea

    /** Miroir horizontal du repère, comme [CropMath.flipHorizontal]. */
    fun flipHorizontal(): BlurArea

    /** Déplacement de ([dx], [dy]) en coordonnées normalisées. */
    fun translate(dx: Float, dy: Float): BlurArea

    /** Point de référence de la zone (centre, ou premier point du pinceau), pour suivre un déplacement. */
    val anchorX: Float
    val anchorY: Float

    /**
     * Rectangle ou ellipse de centre ([x], [y]), de demi-largeur [halfWidth] et de
     * demi-hauteur [halfHeight], incliné de [angle] degrés (sens horaire) dans la photo.
     */
    @Immutable
    data class Box(
        override val shape: BlurShape,
        val x: Float,
        val y: Float,
        val halfWidth: Float,
        val halfHeight: Float,
        val angle: Float = 0f,
    ) : BlurArea {
        init {
            require(shape != BlurShape.BRUSH) { "Un pinceau n'est pas un cadre" }
        }

        override val anchorX: Float get() = x
        override val anchorY: Float get() = y

        // Un rectangle tourné d'un quart de tour est le même rectangle, côtés échangés.
        override fun rotateClockwise(): BlurArea = copy(x = 1f - y, y = x, halfWidth = halfHeight, halfHeight = halfWidth)

        override fun flipHorizontal(): BlurArea = copy(x = 1f - x, angle = TextMath.normalizeAngle(-angle))

        override fun translate(dx: Float, dy: Float): BlurArea = copy(x = x + dx, y = y + dy)
    }

    /**
     * Tracé au pinceau : [points] (x0, y0, x1, y1…, jamais modifiés après construction),
     * épaisseur [width].
     */
    @Immutable
    class Brush(val points: FloatArray, val width: Float) : BlurArea {
        init {
            require(points.size >= 2 && points.size % 2 == 0) { "Coordonnées incomplètes" }
        }

        override val shape: BlurShape get() = BlurShape.BRUSH
        override val anchorX: Float get() = points[0]
        override val anchorY: Float get() = points[1]

        val pointCount: Int get() = points.size / 2

        fun x(index: Int): Float = points[2 * index]

        fun y(index: Int): Float = points[2 * index + 1]

        /** Boîte englobante des points (épaisseur non comprise). */
        val bounds: NormRect by lazy {
            var left = Float.MAX_VALUE
            var top = Float.MAX_VALUE
            var right = -Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            for (i in points.indices step 2) {
                left = min(left, points[i])
                right = max(right, points[i])
                top = min(top, points[i + 1])
                bottom = max(bottom, points[i + 1])
            }
            NormRect(left, top, right, bottom)
        }

        override fun rotateClockwise(): BlurArea {
            val rotated = FloatArray(points.size)
            for (i in points.indices step 2) {
                rotated[i] = 1f - points[i + 1]
                rotated[i + 1] = points[i]
            }
            return Brush(rotated, width)
        }

        override fun flipHorizontal(): BlurArea {
            val flipped = points.copyOf()
            for (i in flipped.indices step 2) flipped[i] = 1f - flipped[i]
            return Brush(flipped, width)
        }

        override fun translate(dx: Float, dy: Float): BlurArea {
            val moved = points.copyOf()
            for (i in moved.indices step 2) {
                moved[i] += dx
                moved[i + 1] += dy
            }
            return Brush(moved, width)
        }

        // Égalité de contenu (le tableau seul serait comparé par référence).
        override fun equals(other: Any?): Boolean = this === other || other is Brush && width == other.width && points.contentEquals(other.points)

        override fun hashCode(): Int = width.hashCode() * 31 + points.contentHashCode()
    }
}

/**
 * Zone floutée d'une édition.
 *
 * @param id identité stable (sélection, déplacement), unique dans une édition.
 * @param intensity de 0 (blocs les plus fins permis) à 1 (blocs les plus gros) : voir [BlurMath.blocksAcross].
 */
@Immutable
data class BlurRegion(val id: Long, val area: BlurArea, val style: BlurStyle, val intensity: Float) {
    fun rotateClockwise(): BlurRegion = copy(area = area.rotateClockwise())

    fun flipHorizontal(): BlurRegion = copy(area = area.flipHorizontal())

    fun translate(dx: Float, dy: Float): BlurRegion = copy(area = area.translate(dx, dy))
}
