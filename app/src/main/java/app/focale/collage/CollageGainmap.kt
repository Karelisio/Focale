package app.focale.collage

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.withClip
import app.focale.core.image.GainmapParams
import java.nio.ByteBuffer
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * Gainmap d'un collage dont des photos sont Ultra HDR : une seule gainmap pour tout le collage,
 * donc un seul jeu de paramètres. Chaque photo HDR garde son gain, ré-exprimé dans ces
 * paramètres communs ([table]) : bornes qui couvrent celles de toutes les photos et le gain
 * neutre, gamma 1. Les cases SDR, le fond, les espacements et les coins arrondis ont le gain
 * neutre ([neutral]) : l'écran HDR les montre telles quelles.
 *
 * Les décalages (`epsilon`) et les marges d'écran ne se combinent pas exactement : ceux de la
 * première photo HDR sont gardés (libultrahdr écrit les mêmes partout), les marges couvrent
 * celles de toutes les photos.
 */
object CollageGainmap {

    /** Réduction de la gainmap par rapport au collage : 1 024 px pour 4 096, comme les appareils photo. */
    const val SCALE = 4

    /** Paramètres communs aux gainmaps [sources] (null : photo SDR) ; null si aucune n'apporte de gain. */
    fun combine(sources: List<GainmapParams?>): GainmapParams? {
        val hdr = sources.filterNotNull()
        val first = hdr.firstOrNull() ?: return null
        val low = (0..2).map { channel -> min(0f, hdr.minOf { ln(it.ratioMin[channel]) }) }
        val high = (0..2).map { channel -> max(0f, hdr.maxOf { ln(it.ratioMax[channel]) }) }
        if ((0..2).all { high[it] - low[it] < MIN_RANGE }) return null
        return GainmapParams(
            ratioMin = low.map(::exp),
            ratioMax = high.map(::exp),
            gamma = listOf(1f, 1f, 1f),
            epsilonSdr = first.epsilonSdr,
            epsilonHdr = first.epsilonHdr,
            displayRatioHdr = hdr.maxOf { it.displayRatioHdr },
            displayRatioSdr = max(1f, hdr.minOf { it.displayRatioSdr }),
            channels = hdr.maxOf { it.channels },
        )
    }

    /**
     * Pour chaque canal, valeur (0..255) dans [target] de chaque valeur stockée (0..255) d'une
     * gainmap aux paramètres [source] : même gain, autrement écrit. Photo SDR ([source] null) :
     * gain neutre partout.
     */
    fun table(source: GainmapParams?, target: GainmapParams): Array<IntArray> = Array(3) { channel ->
        IntArray(256) { value ->
            val logGain = if (source == null) {
                0f
            } else {
                val low = ln(source.ratioMin[channel])
                val high = ln(source.ratioMax[channel])
                low + (high - low) * (value / 255f).pow(1f / source.gamma[channel])
            }
            encode(logGain, target, channel)
        }
    }

    /** Valeur du gain neutre (1) de chaque canal dans [target]. */
    fun neutral(target: GainmapParams): IntArray = IntArray(3) { encode(0f, target, it) }

    /** Taille de la gainmap d'un collage de [width] × [height] px. */
    fun size(width: Int, height: Int): Pair<Int, Int> = max(1, ceil(width / SCALE.toFloat()).toInt()) to max(1, ceil(height / SCALE.toFloat()).toInt())

    /** Gain de logarithme [logGain] écrit sur 8 bits dans [target] (gamma 1). */
    private fun encode(logGain: Float, target: GainmapParams, channel: Int): Int {
        val low = ln(target.ratioMin[channel])
        val high = ln(target.ratioMax[channel])
        if (high - low < MIN_RANGE) return 0
        return (((logGain - low) / (high - low)).coerceIn(0f, 1f) * 255f).roundToInt()
    }

    private fun exp(value: Float): Float = kotlin.math.exp(value)

    /** Écart de logarithme en deçà duquel une gainmap n'apporte rien. */
    private const val MIN_RANGE = 1e-4f
}

/**
 * Gainmap du collage en cours de rendu ([CollageRenderer.render]) : ARGB, un canal par couleur,
 * au gain neutre au départ ; [CollageRenderer.drawCell] y peint la gainmap de chaque photo HDR,
 * bande par bande, au même cadrage que ses pixels.
 */
class CollageGainLayer(collageWidth: Int, collageHeight: Int, val params: GainmapParams) {
    val width: Int
    val height: Int
    private val scaleX: Float
    private val scaleY: Float
    private val bitmap: Bitmap
    private val canvas: Canvas
    private val neutral = CollageGainmap.neutral(params)
    private val tables = HashMap<GainmapParams, Array<IntArray>>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        val (w, h) = CollageGainmap.size(collageWidth, collageHeight)
        width = w
        height = h
        scaleX = w / collageWidth.toFloat()
        scaleY = h / collageHeight.toFloat()
        bitmap = createBitmap(w, h).apply { eraseColor(argb(neutral[0], neutral[1], neutral[2])) }
        canvas = Canvas(bitmap)
    }

    /**
     * Peint [gain], gainmap (ALPHA_8 ou ARGB) d'une zone décodée de [decodedWidth] ×
     * [decodedHeight] px d'une photo aux paramètres [source] : le bitmap décodé est placé dans le
     * collage par [local], la bande ne couvre que [core] (px du collage, arrondi) de la case [bounds].
     */
    fun draw(gain: Bitmap, source: GainmapParams, decodedWidth: Int, decodedHeight: Int, local: Matrix, core: RectF, bounds: RectF) {
        val table = tables.getOrPut(source) { CollageGainmap.table(source, params) }
        val pixels = IntArray(gain.width * gain.height)
        gain.getPixels(pixels, 0, gain.width, 0, 0, gain.width, gain.height)
        val single = gain.config == Bitmap.Config.ALPHA_8
        for (index in pixels.indices) {
            val pixel = pixels[index]
            pixels[index] = if (single) {
                val value = pixel ushr 24
                argb(table[0][value], table[1][value], table[2][value])
            } else {
                argb(table[0][(pixel shr 16) and 0xFF], table[1][(pixel shr 8) and 0xFF], table[2][pixel and 0xFF])
            }
        }
        val encoded = createBitmap(gain.width, gain.height).apply { setPixels(pixels, 0, gain.width, 0, 0, gain.width, gain.height) }
        try {
            // Pixel de la gainmap → pixel décodé (même zone, autre résolution) → collage → gainmap du collage.
            val matrix = Matrix().apply {
                setScale(decodedWidth / gain.width.toFloat(), decodedHeight / gain.height.toFloat())
                postConcat(local)
                postScale(scaleX, scaleY)
            }
            paint.shader = BitmapShader(encoded, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(matrix) }
            // Arrondi au pixel comme le cœur des bandes : la bande voisine arrondit la même limite.
            val clip = RectF(round(core.left * scaleX), round(core.top * scaleY), round(core.right * scaleX), round(core.bottom * scaleY))
            canvas.withClip(clip) { drawRect(bounds.left * scaleX, bounds.top * scaleY, bounds.right * scaleX, bounds.bottom * scaleY, paint) }
        } finally {
            paint.shader = null
            encoded.recycle()
        }
    }

    /** Gain neutre dans [path] (px du collage) : coins arrondis repeints de la couleur du fond. */
    fun fill(path: Path) {
        val scaled = Path(path).apply { transform(Matrix().apply { setScale(scaleX, scaleY) }) }
        canvas.drawPath(scaled, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb(neutral[0], neutral[1], neutral[2]) })
    }

    /** Valeur (0..255) du canal [channel] au pixel ([x], [y]) de la gainmap. */
    fun value(x: Int, y: Int, channel: Int): Int = (bitmap[x, y] shr (16 - 8 * channel)) and 0xFF

    /** Contenu de la gainmap : ALPHA_8 si toutes les photos HDR ont une gainmap à un canal, sinon une copie ARGB. */
    fun contents(): Bitmap {
        if (params.channels != 1) return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val alpha = createBitmap(width, height, Bitmap.Config.ALPHA_8)
        val buffer = ByteBuffer.allocate(alpha.rowBytes * height)
        val row = IntArray(width)
        for (y in 0 until height) {
            bitmap.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) buffer.put(y * alpha.rowBytes + x, ((row[x] shr 16) and 0xFF).toByte())
        }
        alpha.copyPixelsFromBuffer(buffer)
        return alpha
    }

    fun recycle() = bitmap.recycle()

    private fun argb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}
