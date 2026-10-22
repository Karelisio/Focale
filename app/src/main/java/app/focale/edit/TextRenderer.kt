package app.focale.edit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.text.LineBreaker
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withSave
import app.focale.R
import kotlin.math.ceil
import kotlin.math.max

/**
 * Rendu des [TextItem] sur un [Canvas] Android, partagé par l'aperçu (canevas natif de Compose)
 * et l'export, comme [DrawingRenderer] : le canevas est en pixels d'un cadre `width` × `height`
 * couvrant l'image pivotée entière. Chaque texte est composé au corps [TextMath.LAYOUT_SIZE]
 * (lignes séparées par l'utilisateur seulement, centrées), puis mis à l'échelle : aperçu et
 * fichier ont exactement la même mise en page, à toute résolution.
 */
object TextRenderer {

    /** Marges du bloc autour des lignes, en corps : le pavé de [TextBackground.BOX], la zone de saisie au doigt. */
    private const val PADDING_X = 0.3f
    private const val PADDING_Y = 0.12f

    /** Rayon des coins du pavé, en corps. */
    private const val CORNER = 0.25f

    /** Épaisseur du contour, en corps (la moitié déborde des lettres). */
    private const val OUTLINE = 0.14f

    /** Axes de Google Sans Flex : graisse moyenne ou extra-grasse, forme arrondie des titres. */
    private const val REGULAR = "'wght' 500, 'ROND' 100"
    private const val BOLD = "'wght' 800, 'ROND' 100"

    /** Luminance au-delà de laquelle les lettres d'un pavé (ou le contour) passent au noir. */
    private const val LIGHT = 0.5

    /**
     * Police des textes : Google Sans Flex (police de l'application), ou celle du système si
     * elle n'a pas pu être chargée.
     */
    class Faces(val typeface: Typeface?) {

        fun paint(bold: Boolean): TextPaint {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
            paint.textSize = TextMath.LAYOUT_SIZE
            if (typeface != null) {
                paint.typeface = typeface
                paint.setFontVariationSettings(if (bold) BOLD else REGULAR)
            } else {
                paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            }
            return paint
        }

        companion object {
            val System = Faces(null)
        }
    }

    @Volatile
    private var appFaces: Faces? = null

    /** Police de l'application, chargée une fois. */
    fun faces(context: Context): Faces = appFaces ?: Faces(
        runCatching { context.applicationContext.resources.getFont(R.font.google_sans_flex) }.getOrNull(),
    ).also { appFaces = it }

    /**
     * Texte composé, indépendant de sa position, de son corps et de son angle : l'aperçu le garde
     * en cache pendant qu'on le déplace, l'agrandit ou le fait tourner.
     *
     * @property halfWidth demi-largeur du bloc (marges comprises), en px de mise en page.
     * @property halfHeight demi-hauteur du bloc, en px de mise en page.
     */
    class Prepared internal constructor(
        private val fill: StaticLayout,
        private val outline: StaticLayout?,
        private val box: Paint?,
        val halfWidth: Float,
        val halfHeight: Float,
    ) {
        /** Tracé dans le repère du texte : origine au centre du bloc, px de mise en page. */
        internal fun drawLocal(canvas: Canvas) {
            box?.let { canvas.drawRoundRect(-halfWidth, -halfHeight, halfWidth, halfHeight, CORNER * TextMath.LAYOUT_SIZE, CORNER * TextMath.LAYOUT_SIZE, it) }
            canvas.withSave {
                translate(-fill.width / 2f, -fill.height / 2f)
                outline?.draw(this)
                fill.draw(this)
            }
        }
    }

    /** Ce dont dépend la mise en page d'un texte : deux textes de même clé partagent leur [Prepared]. */
    data class Key(val text: String, val color: Int, val background: TextBackground, val bold: Boolean)

    fun key(item: TextItem): Key = Key(item.text, item.color, item.background, item.bold)

    fun prepare(item: TextItem, faces: Faces): Prepared {
        val contrast = if (ColorUtils.calculateLuminance(item.color) > LIGHT) Color.BLACK else Color.WHITE
        val fillPaint = faces.paint(item.bold).apply {
            color = if (item.background == TextBackground.BOX) contrast else item.color
        }
        // Largeur de la plus longue ligne : aucune coupure automatique, seules les lignes saisies comptent.
        val width = ceil(Layout.getDesiredWidth(item.text, fillPaint)).toInt() + 1
        val fill = layout(item.text, fillPaint, width)
        val outline = if (item.background == TextBackground.OUTLINE) {
            val strokePaint = faces.paint(item.bold).apply {
                style = Paint.Style.STROKE
                strokeWidth = OUTLINE * TextMath.LAYOUT_SIZE
                strokeJoin = Paint.Join.ROUND
                color = contrast
            }
            layout(item.text, strokePaint, width)
        } else {
            null
        }
        val box = if (item.background == TextBackground.BOX) Paint(Paint.ANTI_ALIAS_FLAG).apply { color = item.color } else null
        // Largeur visible des lignes : getLineWidth compterait le saut de ligne final.
        var lineWidth = 0f
        for (line in 0 until fill.lineCount) lineWidth = max(lineWidth, fill.getLineMax(line))
        return Prepared(
            fill = fill,
            outline = outline,
            box = box,
            halfWidth = lineWidth / 2f + PADDING_X * TextMath.LAYOUT_SIZE,
            halfHeight = fill.height / 2f + PADDING_Y * TextMath.LAYOUT_SIZE,
        )
    }

    /** Trace le texte [item], composé dans [prepared], sur un canevas en px d'un cadre [width] × [height]. */
    fun draw(canvas: Canvas, prepared: Prepared, item: TextItem, width: Float, height: Float) {
        val scale = TextMath.scale(item, width, height)
        canvas.withSave {
            translate(item.x * width, item.y * height)
            rotate(item.rotation)
            scale(scale, scale)
            prepared.drawLocal(this)
        }
    }

    fun draw(canvas: Canvas, texts: List<TextItem>, faces: Faces, width: Float, height: Float) {
        for (item in texts) draw(canvas, prepare(item, faces), item, width, height)
    }

    private fun layout(text: String, paint: TextPaint, width: Int): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .setBreakStrategy(LineBreaker.BREAK_STRATEGY_SIMPLE)
            .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            .build()
}
