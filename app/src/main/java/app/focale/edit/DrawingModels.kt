package app.focale.edit

import androidx.compose.runtime.Immutable
import kotlin.math.max
import kotlin.math.min

/** Outil de dessin ; la gomme supprime les traits touchés et n'en crée aucun. */
enum class DrawTool {
    PEN, HIGHLIGHTER, LINE, ARROW, RECTANGLE, ELLIPSE, ERASER;

    /** Tracé à main levée ; les formes, elles, vont du point de départ au point d'arrivée du geste. */
    val isFreehand: Boolean get() = this == PEN || this == HIGHLIGHTER
}

/**
 * Trait de dessin, dans le repère de [EditState.crop] : coordonnées normalisées (0..1) de
 * l'image entière après rotation et miroir, avant recadrage. Le trait reste ainsi attaché
 * au contenu de la photo : un recadrage ultérieur le découpe sans le déplacer, une
 * rotation ou un miroir le transforment avec l'image, comme le cadre de recadrage.
 *
 * @param points abscisses et ordonnées alternées (x0, y0, x1, y1…), jamais modifiées
 *   après construction ; pour une forme, départ et arrivée du geste.
 * @param width épaisseur en fraction du petit côté de l'image : le trait garde les mêmes
 *   proportions à toute résolution, dans l'aperçu comme à l'export.
 * @param color couleur ARGB opaque (le surligneur est rendu en transparence).
 */
@Immutable
class DrawingStroke(val tool: DrawTool, val color: Int, val width: Float, val points: FloatArray) {

    init {
        require(tool != DrawTool.ERASER) { "La gomme ne crée pas de trait" }
        require(points.size >= 2 && points.size % 2 == 0) { "Coordonnées incomplètes" }
    }

    val pointCount: Int get() = points.size / 2

    fun x(index: Int): Float = points[2 * index]

    fun y(index: Int): Float = points[2 * index + 1]

    /** Boîte englobante des points, calculée à la demande (gomme). */
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

    /** Quart de tour horaire du repère, comme [CropMath.rotateClockwise]. */
    fun rotateClockwise(): DrawingStroke {
        val rotated = FloatArray(points.size)
        for (i in points.indices step 2) {
            rotated[i] = 1f - points[i + 1]
            rotated[i + 1] = points[i]
        }
        return DrawingStroke(tool, color, width, rotated)
    }

    /** Miroir horizontal du repère, comme [CropMath.flipHorizontal]. */
    fun flipHorizontal(): DrawingStroke {
        val flipped = points.copyOf()
        for (i in flipped.indices step 2) flipped[i] = 1f - flipped[i]
        return DrawingStroke(tool, color, width, flipped)
    }

    // Égalité de contenu (le tableau seul serait comparé par référence).
    override fun equals(other: Any?): Boolean = this === other || other is DrawingStroke &&
        tool == other.tool && color == other.color && width == other.width && points.contentEquals(other.points)

    override fun hashCode(): Int = ((tool.hashCode() * 31 + color) * 31 + width.hashCode()) * 31 + points.contentHashCode()
}

/**
 * Position (px) de l'image pivotée entière dans un canevas : l'aperçu n'en montre qu'une
 * zone quand la photo est recadrée. Convertit le doigt en coordonnées normalisées des
 * traits, et ces coordonnées en pixels du canevas.
 */
@Immutable
data class ImageFrame(val left: Float, val top: Float, val width: Float, val height: Float) {

    /** Unité des épaisseurs ([DrawingStroke.width]) : petit côté de l'image. */
    val reference: Float get() = min(width, height)

    fun normalizedX(x: Float): Float = (x - left) / width

    fun normalizedY(y: Float): Float = (y - top) / height

    fun screenX(x: Float): Float = left + x * width

    fun screenY(y: Float): Float = top + y * height

    companion object {
        /**
         * Cadre de l'aperçu : la zone [region] d'une image [imageWidth] × [imageHeight] px est
         * agrandie au maximum et centrée dans un conteneur [containerWidth] × [containerHeight].
         */
        fun fit(containerWidth: Float, containerHeight: Float, imageWidth: Int, imageHeight: Int, region: NormRect): ImageFrame {
            val regionWidth = region.width * imageWidth
            val regionHeight = region.height * imageHeight
            val scale = min(containerWidth / regionWidth, containerHeight / regionHeight)
            val width = imageWidth * scale
            val height = imageHeight * scale
            return ImageFrame(
                left = (containerWidth - regionWidth * scale) / 2f - region.left * width,
                top = (containerHeight - regionHeight * scale) / 2f - region.top * height,
                width = width,
                height = height,
            )
        }
    }
}
