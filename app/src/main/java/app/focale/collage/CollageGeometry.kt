package app.focale.collage

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Géométrie d'un collage : cases en pixels pour une taille donnée, espacement et arrondi. Même
 * calcul pour l'aperçu (pixels de l'écran, flottants) et pour l'export (pixels entiers).
 *
 * Chaque case normalisée de la disposition est placée dans le collage réduit d'un
 * demi-espacement sur chaque bord, puis réduite à son tour d'un demi-espacement : deux cases
 * voisines sont séparées par exactement un espacement, et les cases du bord en sont à un
 * espacement. Agrandies d'un demi-espacement, les cases pavent donc exactement ce collage réduit.
 */
object CollageGeometry {

    /** Espacement en pixels : [spacing] est une fraction du petit côté. */
    fun gap(spacing: Float, width: Float, height: Float): Float = spacing * min(width, height)

    /** Espacement de l'export, au pixel près : il est alors exactement le même partout. */
    fun pixelGap(spacing: Float, width: Int, height: Int): Int = (spacing * min(width, height)).roundToInt()

    /** Rayon des coins en pixels ([corners] : fraction du petit côté), au plus la moitié du petit côté de [cell]. */
    fun radius(corners: Float, width: Float, height: Float, cell: CellRect): Float =
        min(corners * min(width, height), min(cell.width, cell.height) / 2f).coerceAtLeast(0f)

    /** Cases de [layout] dans un collage de [width] × [height], à [gap] l'une de l'autre et des bords. */
    fun cells(layout: CollageLayout, width: Float, height: Float, gap: Float): List<CellRect> {
        val innerWidth = width - gap
        val innerHeight = height - gap
        return layout.cells.map { cell ->
            CellRect(
                left = gap + cell.left * innerWidth,
                top = gap + cell.top * innerHeight,
                right = cell.right * innerWidth,
                bottom = cell.bottom * innerHeight,
            )
        }
    }

    /**
     * Cases de l'export, bords au pixel près. Un bord partagé est arrondi une seule fois (même
     * fraction, même arrondi), puis décalé de l'espacement entier : l'écart entre deux cases vaut
     * exactement [gap] pixels, sans case élargie ou rétrécie d'un pixel par endroits.
     */
    fun pixelCells(layout: CollageLayout, width: Int, height: Int, gap: Int): List<CellRect> {
        val innerWidth = (width - gap).toFloat()
        val innerHeight = (height - gap).toFloat()
        return layout.cells.map { cell ->
            CellRect(
                left = (gap + (cell.left * innerWidth).roundToInt()).toFloat(),
                top = (gap + (cell.top * innerHeight).roundToInt()).toFloat(),
                right = (cell.right * innerWidth).roundToInt().toFloat(),
                bottom = (cell.bottom * innerHeight).roundToInt().toFloat(),
            )
        }
    }

    /** Case sous le point ([x], [y]), ou null (espacement, hors du collage). */
    fun cellAt(cells: List<CellRect>, x: Float, y: Float): Int? = cells.indexOfFirst { it.contains(x, y) }.takeIf { it >= 0 }
}
