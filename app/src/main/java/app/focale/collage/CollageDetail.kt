package app.focale.collage

import androidx.compose.runtime.Immutable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Zone d'une photo à décoder plus finement que son aperçu, pour une case agrandie : [region], en
 * fraction de l'image affichée (orientation appliquée, de 0 à 1), décodée à [width] × [height] px
 * au plus.
 */
@Immutable
data class DetailRequest(val region: CellRect, val width: Int, val height: Int) {
    val pixels: Long get() = width.toLong() * height
}

/**
 * Détails des cases agrandies du collage (logique pure). À fort agrandissement, l'aperçu d'une photo
 * (1 280 px au plus) est étiré à l'écran : la partie visible de la case, plus une marge pour qu'un
 * petit glisser reste net, est alors décodée à la résolution de l'écran ([request]). Mémoire bornée :
 * [BUDGET_PIXELS] pour toutes les cases, partagés selon leur surface ([budget]) ; jamais plus de pixels
 * que l'écran n'en montre, ni que la photo n'en a (le décodage s'arrête à sa résolution).
 */
object CollageDetail {
    /** Agrandissement de l'aperçu (pixels de l'écran par pixel de l'aperçu) au-delà duquel la case est détaillée. */
    const val MAGNIFICATION = 1.25f

    /** Marge autour de la partie visible, en fraction de sa largeur et de sa hauteur, de chaque côté. */
    const val MARGIN = 0.25f

    /** Pixels de détail pour toutes les cases à la fois : 16 Mo, autant qu'une bande de l'export. */
    const val BUDGET_PIXELS = CollageRenderer.BAND_PIXELS

    /** Délai après le dernier geste avant de décoder (le geste en cours ne décode rien). */
    const val DELAY_MS = 300L

    /** Part du budget d'une case de [cellWidth] × [cellHeight] dans un collage de [width] × [height] (px de l'écran). */
    fun budget(cellWidth: Float, cellHeight: Float, width: Float, height: Float): Long {
        if (width <= 0f || height <= 0f) return 0
        return (BUDGET_PIXELS * (cellWidth * cellHeight / (width * height)).coerceIn(0f, 1f)).toLong()
    }

    /**
     * Zone à décoder pour la case de [cellWidth] × [cellHeight] px de l'écran, cadrée par [frame],
     * dont l'aperçu mesure [previewWidth] × [previewHeight] px ; null si l'aperçu suffit (agrandi au
     * plus [MAGNIFICATION] fois) ou si [maxPixels] ne permet rien. La taille demandée est celle de la
     * zone à l'écran, réduite au besoin à [maxPixels] pixels, proportions gardées.
     */
    fun request(
        frame: CellFrame,
        cellWidth: Float,
        cellHeight: Float,
        previewWidth: Int,
        previewHeight: Int,
        maxPixels: Long,
    ): DetailRequest? {
        if (cellWidth <= 0f || cellHeight <= 0f || previewWidth <= 0 || previewHeight <= 0 || maxPixels <= 0) return null
        val imageWidth = previewWidth.toFloat()
        val imageHeight = previewHeight.toFloat()
        val visible = CellFraming.visible(frame, cellWidth, cellHeight, imageWidth, imageHeight)
        val scale = cellWidth / visible.width
        if (scale <= MAGNIFICATION) return null
        val marginX = visible.width * MARGIN
        val marginY = visible.height * MARGIN
        val left = max(0f, visible.left - marginX)
        val top = max(0f, visible.top - marginY)
        val right = min(imageWidth, visible.right + marginX)
        val bottom = min(imageHeight, visible.bottom + marginY)
        // Pixels de l'écran que couvrirait la zone, ramenés au budget de la case.
        val screenWidth = (right - left) * scale
        val screenHeight = (bottom - top) * scale
        val fit = min(1f, sqrt(maxPixels / (screenWidth * screenHeight)))
        var width = (screenWidth * fit).roundToInt()
        var height = (screenHeight * fit).roundToInt()
        // Arrondi au-delà du budget : par défaut.
        if (width.toLong() * height > maxPixels) {
            width = (screenWidth * fit).toInt()
            height = (screenHeight * fit).toInt()
        }
        if (width < 1 || height < 1) return null
        return DetailRequest(CellRect(left / imageWidth, top / imageHeight, right / imageWidth, bottom / imageHeight), width, height)
    }

    /** Taille du détail de [request] pour une photo affichée de [displayWidth] × [displayHeight] px : jamais plus fine que la photo. */
    fun size(request: DetailRequest, displayWidth: Int, displayHeight: Int): Pair<Int, Int> {
        val native = request.region.width * displayWidth
        val fit = min(1f, native / request.width)
        return max(1, (request.width * fit).roundToInt()) to max(1, (request.height * fit).roundToInt())
    }
}
