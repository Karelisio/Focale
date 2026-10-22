package app.focale.collage

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Photo d'un collage telle qu'elle est stockée ([width] × [height] px, avant orientation) et son
 * orientation EXIF. Le rendu n'en décode jamais que des zones, réduites au besoin.
 */
interface CollageSource : Closeable {
    val width: Int
    val height: Int
    val orientation: Int

    /**
     * Zone [region] de l'image stockée, réduite d'un facteur [sampleSize] (puissance de 2) :
     * bitmap d'environ `region / sampleSize` pixels, dont le pixel (0, 0) commence au coin de [region].
     */
    fun decode(region: Rect, sampleSize: Int): Bitmap

    override fun close() = Unit
}

/**
 * Photo déjà en mémoire (repli des formats sans décodeur de zones, tests). [owned] : le bitmap est
 * recyclé à la fermeture.
 */
class BitmapSource(private val bitmap: Bitmap, override val orientation: Int, private val owned: Boolean = true) : CollageSource {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height

    /** Toujours un bitmap neuf, que l'appelant recycle. Comme le décodeur de zones : division entière. */
    override fun decode(region: Rect, sampleSize: Int): Bitmap {
        val width = (region.width() / sampleSize).coerceAtLeast(1)
        val height = (region.height() / sampleSize).coerceAtLeast(1)
        val part = createBitmap(width, height)
        val target = Rect(0, 0, width, height)
        val source = Rect(region.left, region.top, region.left + width * sampleSize, region.top + height * sampleSize)
        Canvas(part).drawBitmap(bitmap, source, target, Paint(Paint.FILTER_BITMAP_FLAG))
        return part
    }

    override fun close() {
        if (owned) bitmap.recycle()
    }
}

/**
 * Rendu du collage : fond, puis chaque case, sa photo cadrée ([CellFraming]) et ses coins
 * arrondis. Chaque photo est décodée à la résolution utile seulement : la zone visible de l'image
 * stockée, réduite par le plus grand facteur qui laisse au moins un pixel de la photo par pixel du
 * collage, et par bandes de [BAND_PIXELS] pixels décodés au plus. Neuf photos de 50 Mpx tiennent
 * ainsi en mémoire : le collage, plus une bande à la fois.
 */
object CollageRenderer {
    /** Grand côté de l'export. */
    const val LONG_SIDE = 4096

    /** Pixels décodés au plus à la fois (16 Mo en ARGB). */
    const val BAND_PIXELS = 4_000_000

    /**
     * Collage de [width] × [height] px. [open] ouvre la photo d'identifiant donné ; elle est
     * refermée une fois sa case dessinée.
     */
    fun render(
        state: CollageState,
        width: Int,
        height: Int,
        open: (Long) -> CollageSource,
        bandPixels: Int = BAND_PIXELS,
    ): Bitmap {
        val output = createBitmap(width, height)
        val canvas = Canvas(output)
        canvas.drawColor(state.backgroundColor)
        val gap = CollageGeometry.pixelGap(state.spacing, width, height)
        val cells = CollageGeometry.pixelCells(state.layout, width, height, gap)
        cells.forEachIndexed { index, cell ->
            val photo = state.photos[index]
            val radius = CollageGeometry.radius(state.corners, width.toFloat(), height.toFloat(), cell)
            open(photo).use { source -> drawCell(canvas, cell, radius, state.backgroundColor, state.frame(photo), source, bandPixels) }
        }
        return output
    }

    /**
     * Aperçu de [source] pour l'écran du collage : photo entière, orientation appliquée, grand côté
     * ramené à [maxSide] au plus. Même lecture que l'export (même orientation, mêmes proportions) :
     * le cadrage réglé sur l'aperçu est celui du collage enregistré.
     */
    fun preview(source: CollageSource, maxSide: Int): Bitmap {
        val sample = sampleSize(maxSide / max(source.width, source.height).toFloat())
        val decoded = source.decode(Rect(0, 0, source.width, source.height), sample)
        val orientation = ImageOrientation(decoded.width, decoded.height, source.orientation)
        val fit = min(1f, maxSide / max(orientation.displayWidth, orientation.displayHeight).toFloat())
        val matrix = Matrix().apply {
            setValues(orientation.toDisplay + floatArrayOf(0f, 0f, 1f))
            postScale(fit, fit)
        }
        if (matrix.isIdentity) return decoded
        val shown = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (shown !== decoded) decoded.recycle()
        return shown
    }

    /**
     * Plus grand facteur de réduction (puissance de 2) qui garde au moins un pixel décodé par pixel
     * du collage, à l'échelle [scale] (pixels du collage par pixel de la photo).
     */
    fun sampleSize(scale: Float): Int {
        var sample = 1
        while (sample * 2 * scale <= 1f) sample *= 2
        return sample
    }

    /**
     * Dessine [source] dans [cell]. La zone stockée sous la case (plus une marge pour le filtrage)
     * est lue par bandes de lignes ; chaque bande ne peint que son cœur, découpé dans le collage :
     * les bandes se raccordent sans couture, chaque pixel étant peint par une seule d'entre elles.
     * Les bornes des zones sont des multiples du facteur de réduction : toutes les bandes tombent
     * sur la même grille de pixels réduits. Les coins arrondis sont ensuite repeints de la couleur
     * [background], hors du rectangle arrondi et en une fois : leur lissage ne dépend pas des bandes.
     */
    fun drawCell(
        canvas: Canvas,
        cell: CellRect,
        radius: Float,
        background: Int,
        frame: CellFrame,
        source: CollageSource,
        bandPixels: Int = BAND_PIXELS,
    ) {
        val orientation = ImageOrientation(source.width, source.height, source.orientation)
        val visible = CellFraming.visible(
            frame,
            cell.width,
            cell.height,
            orientation.displayWidth.toFloat(),
            orientation.displayHeight.toFloat(),
        )
        val scale = cell.width / visible.width
        val sample = sampleSize(scale)
        val margin = MARGIN * sample
        val stored = orientation.toStored(visible)
        val left = alignDown(stored.left - margin, sample).coerceAtLeast(0)
        val top = alignDown(stored.top - margin, sample).coerceAtLeast(0)
        val right = alignUp(stored.right + margin, sample).coerceAtMost(source.width)
        val bottom = alignUp(stored.bottom + margin, sample).coerceAtMost(source.height)
        if (right <= left || bottom <= top) return

        // Image affichée → collage.
        val toCollage = Matrix().apply {
            setValues(orientation.toDisplay + floatArrayOf(0f, 0f, 1f))
            postTranslate(-visible.left, -visible.top)
            postScale(scale, scale)
            postTranslate(cell.left, cell.top)
        }
        val decodedWidth = ceil((right - left) / sample.toFloat()).toInt()
        val bandHeight = max(MIN_BAND_ROWS, bandPixels / max(1, decodedWidth) - 2 * MARGIN) * sample
        val bounds = RectF(cell.left, cell.top, cell.right, cell.bottom)
        // Bords de la case au pixel près : pas de lissage, au raccord des bandes comme au bord.
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val core = RectF()
        var bandTop = top
        while (bandTop < bottom) {
            val bandBottom = min(bottom, bandTop + bandHeight)
            val region = Rect(left, max(top, bandTop - margin), right, min(bottom, bandBottom + margin))
            val bitmap = source.decode(region, sample)
            try {
                // Pixel décodé → image stockée (chaque pixel couvre [sample] pixels stockés) → collage.
                val local = Matrix().apply {
                    setScale(sample.toFloat(), sample.toFloat())
                    postTranslate(region.left.toFloat(), region.top.toFloat())
                    postConcat(toCollage)
                }
                paint.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(local) }
                // Cœur de la bande : ses lignes stockées, de bord à bord, ramenées dans le collage et
                // arrondies au pixel ; la bande voisine arrondit la même limite de la même façon.
                core.set(left.toFloat(), bandTop.toFloat(), right.toFloat(), bandBottom.toFloat())
                toCollage.mapRect(core)
                core.set(round(core.left), round(core.top), round(core.right), round(core.bottom))
                canvas.withClip(core) { drawRect(bounds, paint) }
            } finally {
                paint.shader = null
                bitmap.recycle()
            }
            bandTop = bandBottom
        }
        if (radius > 0f) {
            val corners = Path().apply {
                addRect(bounds, Path.Direction.CW)
                op(Path().apply { addRoundRect(bounds, radius, radius, Path.Direction.CW) }, Path.Op.DIFFERENCE)
            }
            canvas.drawPath(corners, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = background })
        }
    }

    private fun alignDown(value: Float, step: Int): Int = (floor(value / step).toInt()) * step

    private fun alignUp(value: Float, step: Int): Int = (ceil(value / step).toInt()) * step

    /**
     * Marge de chaque zone, en pixels décodés : le filtrage bilinéaire lit les voisins du bord, et
     * le cœur arrondi au pixel peut déborder d'un pixel décodé.
     */
    private const val MARGIN = 3

    /** Lignes décodées au moins par bande (photo très large). */
    private const val MIN_BAND_ROWS = 16
}
