package app.focale.edit

import androidx.compose.runtime.Immutable
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Géométrie et calcul des zones floutées (pur, testable), en pixels d'un cadre `width` ×
 * `height` couvrant l'image pivotée entière, avant redressement.
 *
 * Vie privée d'abord : une zone est découpée en gros blocs dont seule la moyenne est gardée,
 * puis affichée telle quelle (« Pixels ») ou lissée (« Flou ») ; dans les deux cas, rien de plus
 * fin qu'un bloc ne subsiste, et aucun calcul ne peut le retrouver. La taille des blocs suit
 * celle de la zone : un visage, petit ou grand, ne compte jamais plus de [MAX_BLOCKS] blocs de
 * large, jusqu'à [MIN_BLOCKS] à pleine intensité.
 */
object BlurMath {

    /** Blocs dans le petit côté d'une zone, à l'intensité minimale puis maximale. */
    const val MAX_BLOCKS = 8f
    const val MIN_BLOCKS = 3f

    /** Bloc minimal, en fraction du petit côté de l'image puis en pixels : jamais de mosaïque fine. */
    const val MIN_BLOCK = 0.006f
    const val MIN_BLOCK_PIXELS = 3f

    /** Sur-échantillonnage de la grille lissée du style « Flou ». */
    const val SMOOTHING = 4

    /** Rayon et passes du flou en boîte de la grille sur-échantillonnée (approche d'une gaussienne). */
    private const val SMOOTH_RADIUS = 2
    private const val SMOOTH_PASSES = 2

    /** Agrandissement du cadre d'un visage détecté (cheveux, menton, coins de l'ellipse). */
    const val FACE_MARGIN = 1.4f

    /** Nombre de blocs dans le petit côté d'une zone, de [MAX_BLOCKS] (intensité 0) à [MIN_BLOCKS] (intensité 1). */
    fun blocksAcross(intensity: Float): Float = MAX_BLOCKS + (MIN_BLOCKS - MAX_BLOCKS) * intensity.coerceIn(0f, 1f)

    /** Taille caractéristique de la zone (px) : petit côté de la forme, ou de la boîte du pinceau. */
    fun extent(area: BlurArea, width: Float, height: Float): Float = when (area) {
        is BlurArea.Box -> 2f * min(area.halfWidth, area.halfHeight) * min(width, height)
        is BlurArea.Brush -> bounds(area, width, height).let { min(it[2] - it[0], it[3] - it[1]) }
    }

    /** Côté visé d'un bloc (px), avant ajustement à la grille de la zone. */
    fun blockSize(region: BlurRegion, width: Float, height: Float): Float = maxOf(
        extent(region.area, width, height) / blocksAcross(region.intensity),
        MIN_BLOCK * min(width, height),
        MIN_BLOCK_PIXELS,
    )

    /** Boîte englobante (px) de la zone : gauche, haut, droite, bas. */
    fun bounds(area: BlurArea, width: Float, height: Float): FloatArray {
        val reference = min(width, height)
        return when (area) {
            is BlurArea.Box -> {
                val a = area.halfWidth * reference
                val b = area.halfHeight * reference
                val radians = Math.toRadians(area.angle.toDouble())
                val c = abs(cos(radians)).toFloat()
                val s = abs(sin(radians)).toFloat()
                val (ex, ey) = if (area.shape == BlurShape.ELLIPSE) {
                    sqrt(a * a * c * c + b * b * s * s) to sqrt(a * a * s * s + b * b * c * c)
                } else {
                    a * c + b * s to a * s + b * c
                }
                val cx = area.x * width
                val cy = area.y * height
                floatArrayOf(cx - ex, cy - ey, cx + ex, cy + ey)
            }
            is BlurArea.Brush -> {
                val half = area.width * reference / 2f
                val box = area.bounds
                floatArrayOf(box.left * width - half, box.top * height - half, box.right * width + half, box.bottom * height + half)
            }
        }
    }

    /**
     * Grille des blocs de la zone, sur sa boîte englobante limitée au cadre [width] × [height]
     * (px entiers) : autant de blocs que la boîte en contient de la taille visée, de sorte que
     * tous soient égaux (aucun bloc réduit à quelques pixels au bord). Null si la zone est hors
     * du cadre. Les blocs ne dépendent que des proportions : l'aperçu et l'export ont la même grille.
     */
    fun grid(region: BlurRegion, width: Int, height: Int): BlurGrid? {
        val box = bounds(region.area, width.toFloat(), height.toFloat())
        val left = floor(box[0]).toInt().coerceIn(0, width)
        val top = floor(box[1]).toInt().coerceIn(0, height)
        val right = ceil(box[2]).toInt().coerceIn(0, width)
        val bottom = ceil(box[3]).toInt().coerceIn(0, height)
        if (right <= left || bottom <= top) return null
        val block = blockSize(region, width.toFloat(), height.toFloat())
        val columns = max(1, ((right - left) / block).roundToInt())
        val rows = max(1, ((bottom - top) / block).roundToInt())
        return BlurGrid(left, top, right, bottom, columns, rows)
    }

    /** Vrai si le point (x, y) (px) tombe dans la zone, à [slop] px près. */
    fun contains(area: BlurArea, x: Float, y: Float, width: Float, height: Float, slop: Float = 0f): Boolean {
        val reference = min(width, height)
        return when (area) {
            is BlurArea.Box -> {
                // Point ramené dans le repère de la forme (rotation inverse autour de son centre).
                val dx = x - area.x * width
                val dy = y - area.y * height
                val radians = Math.toRadians(area.angle.toDouble())
                val cos = cos(radians).toFloat()
                val sin = sin(radians).toFloat()
                val localX = dx * cos + dy * sin
                val localY = dy * cos - dx * sin
                val a = area.halfWidth * reference + slop
                val b = area.halfHeight * reference + slop
                if (area.shape == BlurShape.ELLIPSE) {
                    a > 0f && b > 0f && (localX / a) * (localX / a) + (localY / b) * (localY / b) <= 1f
                } else {
                    abs(localX) <= a && abs(localY) <= b
                }
            }
            is BlurArea.Brush -> {
                val reach = area.width * reference / 2f + slop
                var best = Float.MAX_VALUE
                if (area.pointCount == 1) {
                    best = DrawingMath.segmentDistance(x, y, area.x(0) * width, area.y(0) * height, area.x(0) * width, area.y(0) * height)
                }
                for (i in 0 until area.pointCount - 1) {
                    best = min(
                        best,
                        DrawingMath.segmentDistance(x, y, area.x(i) * width, area.y(i) * height, area.x(i + 1) * width, area.y(i + 1) * height),
                    )
                }
                best <= reach
            }
        }
    }

    /**
     * Ellipse couvrant un visage détecté sur la photo d'origine (orientation EXIF appliquée,
     * [face] normalisé), dans le repère des zones d'une édition tournée de [quarterTurns] quarts
     * de tour puis retournée si [flip] ; [width] × [height] : proportions de l'image pivotée.
     */
    fun faceArea(face: NormRect, quarterTurns: Int, flip: Boolean, width: Float, height: Float): BlurArea.Box {
        var rect = face
        repeat(Math.floorMod(quarterTurns, 4)) { rect = CropMath.rotateClockwise(rect) }
        if (flip) rect = CropMath.flipHorizontal(rect)
        val reference = min(width, height)
        return BlurArea.Box(
            shape = BlurShape.ELLIPSE,
            x = (rect.left + rect.right) / 2f,
            y = (rect.top + rect.bottom) / 2f,
            halfWidth = rect.width * width / 2f * FACE_MARGIN / reference,
            halfHeight = rect.height * height / 2f * FACE_MARGIN / reference,
        )
    }

    /** Pixel du cadre (x) d'un pixel (sx, sy) d'une source [sourceWidth] × [sourceHeight] tournée puis retournée. */
    fun frameX(sx: Int, sy: Int, sourceWidth: Int, sourceHeight: Int, quarterTurns: Int, flip: Boolean): Int {
        val q = Math.floorMod(quarterTurns, 4)
        val x = when (q) {
            0 -> sx
            1 -> sourceHeight - 1 - sy
            2 -> sourceWidth - 1 - sx
            else -> sy
        }
        val frameWidth = if (q % 2 == 0) sourceWidth else sourceHeight
        return if (flip) frameWidth - 1 - x else x
    }

    /** Pixel du cadre (y) d'un pixel (sx, sy) de la source, comme [frameX]. */
    fun frameY(sx: Int, sy: Int, sourceWidth: Int, sourceHeight: Int, quarterTurns: Int): Int = when (Math.floorMod(quarterTurns, 4)) {
        0 -> sy
        1 -> sx
        2 -> sourceHeight - 1 - sy
        else -> sourceWidth - 1 - sx
    }

    /** Pixel de la source (x, y) d'un pixel (fx, fy) du cadre : inverse de [frameX] et [frameY]. */
    fun sourcePixel(fx: Int, fy: Int, sourceWidth: Int, sourceHeight: Int, quarterTurns: Int, flip: Boolean): Pair<Int, Int> {
        val q = Math.floorMod(quarterTurns, 4)
        val frameWidth = if (q % 2 == 0) sourceWidth else sourceHeight
        val x = if (flip) frameWidth - 1 - fx else fx
        return when (q) {
            0 -> x to fy
            1 -> fy to sourceHeight - 1 - x
            2 -> sourceWidth - 1 - x to sourceHeight - 1 - fy
            else -> sourceWidth - 1 - fy to x
        }
    }

    /**
     * Couleur moyenne (ARGB) de chaque bloc de [grid], ligne après ligne, lue dans une source
     * [sourceWidth] × [sourceHeight] tournée de [quarterTurns] puis retournée si [flip]. La source
     * n'est lue que dans le rectangle de la zone, par bandes de [stripRows] lignes ([read] :
     * première ligne, nombre de lignes, gauche, largeur, tampon à remplir) : une zone couvrant une
     * photo de 50 Mpx ne coûte que ses sommes par bloc et une bande.
     */
    fun blockAverages(
        grid: BlurGrid,
        sourceWidth: Int,
        sourceHeight: Int,
        quarterTurns: Int,
        flip: Boolean,
        stripRows: Int = STRIP_ROWS,
        read: (top: Int, rows: Int, left: Int, width: Int, buffer: IntArray) -> Unit,
    ): IntArray {
        // Rectangle de la source couvert par la grille : coins opposés de la grille ramenés dans la source.
        val (ax, ay) = sourcePixel(grid.left, grid.top, sourceWidth, sourceHeight, quarterTurns, flip)
        val (bx, by) = sourcePixel(grid.right - 1, grid.bottom - 1, sourceWidth, sourceHeight, quarterTurns, flip)
        val left = min(ax, bx)
        val top = min(ay, by)
        val right = max(ax, bx) + 1
        val bottom = max(ay, by) + 1
        val width = right - left
        // Bloc de chaque colonne et de chaque ligne du cadre, calculé une fois.
        val columnOf = IntArray(grid.right - grid.left) { grid.column(grid.left + it) }
        val rowOf = IntArray(grid.bottom - grid.top) { grid.row(grid.top + it) }
        // Le pixel du cadre avance d'un pas fixe quand on avance d'un pixel dans une ligne de la source.
        val stepX = frameX(left + 1, top, sourceWidth, sourceHeight, quarterTurns, flip) - frameX(left, top, sourceWidth, sourceHeight, quarterTurns, flip)
        val stepY = frameY(left + 1, top, sourceWidth, sourceHeight, quarterTurns) - frameY(left, top, sourceWidth, sourceHeight, quarterTurns)
        val cells = grid.columns * grid.rows
        val sums = LongArray(cells * 4)
        val counts = IntArray(cells)
        val buffer = IntArray(width * min(stripRows, bottom - top))
        var stripTop = top
        while (stripTop < bottom) {
            val rows = min(stripRows, bottom - stripTop)
            read(stripTop, rows, left, width, buffer)
            for (r in 0 until rows) {
                val sy = stripTop + r
                var fx = frameX(left, sy, sourceWidth, sourceHeight, quarterTurns, flip) - grid.left
                var fy = frameY(left, sy, sourceWidth, sourceHeight, quarterTurns) - grid.top
                for (c in 0 until width) {
                    val cell = rowOf[fy] * grid.columns + columnOf[fx]
                    val color = buffer[r * width + c]
                    sums[cell * 4] += (color ushr 24).toLong()
                    sums[cell * 4 + 1] += (color shr 16 and 0xFF).toLong()
                    sums[cell * 4 + 2] += (color shr 8 and 0xFF).toLong()
                    sums[cell * 4 + 3] += (color and 0xFF).toLong()
                    counts[cell]++
                    fx += stepX
                    fy += stepY
                }
            }
            stripTop += rows
        }
        return IntArray(cells) { cell ->
            val count = max(1, counts[cell]).toLong()
            fun channel(index: Int) = ((sums[cell * 4 + index] + count / 2) / count).toInt()
            (channel(0) shl 24) or (channel(1) shl 16) or (channel(2) shl 8) or channel(3)
        }
    }

    /**
     * Grille lissée du style « Flou » : [colors] ([columns] × [rows]) agrandie [SMOOTHING] fois par
     * interpolation bilinéaire entre les centres des blocs, puis floutée en boîte. Elle ne dépend
     * que des moyennes des blocs : le flou n'apporte aucun détail que la mosaïque n'aurait pas.
     */
    fun smooth(colors: IntArray, columns: Int, rows: Int): IntArray {
        val width = columns * SMOOTHING
        val height = rows * SMOOTHING
        val channels = Array(4) { FloatArray(width * height) }
        for (y in 0 until height) {
            // Position dans la grille des blocs, centres des cellules alignés.
            val gy = ((y + 0.5f) / SMOOTHING - 0.5f).coerceIn(0f, (rows - 1).toFloat())
            val y0 = floor(gy).toInt()
            val y1 = min(y0 + 1, rows - 1)
            val ty = gy - y0
            for (x in 0 until width) {
                val gx = ((x + 0.5f) / SMOOTHING - 0.5f).coerceIn(0f, (columns - 1).toFloat())
                val x0 = floor(gx).toInt()
                val x1 = min(x0 + 1, columns - 1)
                val tx = gx - x0
                for (k in 0 until 4) {
                    val shift = 24 - 8 * k
                    fun at(cx: Int, cy: Int) = (colors[cy * columns + cx] ushr shift and 0xFF).toFloat()
                    val top = at(x0, y0) + (at(x1, y0) - at(x0, y0)) * tx
                    val bottom = at(x0, y1) + (at(x1, y1) - at(x0, y1)) * tx
                    channels[k][y * width + x] = top + (bottom - top) * ty
                }
            }
        }
        for (channel in channels) {
            repeat(SMOOTH_PASSES) {
                boxBlur(channel, width, height, horizontal = true)
                boxBlur(channel, width, height, horizontal = false)
            }
        }
        return IntArray(width * height) { i ->
            var color = 0
            for (k in 0 until 4) color = color or (channels[k][i].roundToInt().coerceIn(0, 255) shl (24 - 8 * k))
            color
        }
    }

    /** Moyenne glissante sur 2 × [SMOOTH_RADIUS] + 1 cellules, bords prolongés. */
    private fun boxBlur(values: FloatArray, width: Int, height: Int, horizontal: Boolean) {
        val length = if (horizontal) width else height
        val lines = if (horizontal) height else width
        val line = FloatArray(length)
        val window = 2 * SMOOTH_RADIUS + 1
        for (l in 0 until lines) {
            for (i in 0 until length) line[i] = values[if (horizontal) l * width + i else i * width + l]
            var sum = 0f
            for (i in -SMOOTH_RADIUS..SMOOTH_RADIUS) sum += line[i.coerceIn(0, length - 1)]
            for (i in 0 until length) {
                values[if (horizontal) l * width + i else i * width + l] = sum / window
                sum += line[(i + SMOOTH_RADIUS + 1).coerceIn(0, length - 1)] - line[(i - SMOOTH_RADIUS).coerceIn(0, length - 1)]
            }
        }
    }

    /** Lignes de la source lues d'un coup pour calculer les blocs d'une zone. */
    private const val STRIP_ROWS = 64
}

/**
 * Grille des blocs d'une zone, en pixels entiers du cadre : colonnes de [left] (inclus) à
 * [right] (exclu), lignes de [top] à [bottom], découpées en [columns] × [rows] blocs égaux.
 */
@Immutable
data class BlurGrid(val left: Int, val top: Int, val right: Int, val bottom: Int, val columns: Int, val rows: Int) {
    val cellWidth: Float get() = (right - left).toFloat() / columns
    val cellHeight: Float get() = (bottom - top).toFloat() / rows

    /** Colonne du bloc contenant le centre du pixel x. */
    fun column(x: Int): Int = ((x + 0.5f - left) / cellWidth).toInt().coerceIn(0, columns - 1)

    /** Ligne du bloc contenant le centre du pixel y. */
    fun row(y: Int): Int = ((y + 0.5f - top) / cellHeight).toInt().coerceIn(0, rows - 1)
}

/**
 * Photo non redressée posée dans un cadre à l'écran : passage des coordonnées normalisées de la
 * photo (celles des [BlurArea]) aux pixels de l'écran, et retour. Même géométrie que l'affichage :
 * image pivotée entière en [frame], tournée de [angle] degrés (sens horaire) autour du centre du
 * cadre et agrandie de [bleed] ([StraightenMath.bleed]).
 */
@Immutable
data class PhotoPlacement(val frame: ImageFrame, val angle: Float, val bleed: Float) {
    private val cos = cos(Math.toRadians(angle.toDouble())).toFloat()
    private val sin = sin(Math.toRadians(angle.toDouble())).toFloat()
    private val centerX get() = frame.left + frame.width / 2f
    private val centerY get() = frame.top + frame.height / 2f

    /** Px d'écran d'une longueur des zones (fraction du petit côté). */
    fun screenLength(length: Float): Float = length * frame.reference * bleed

    /** Longueur des zones (fraction du petit côté) d'une distance de [pixels] px d'écran. */
    fun photoLength(pixels: Float): Float = pixels / (frame.reference * bleed)

    fun screenX(u: Float, v: Float): Float {
        val dx = (u - 0.5f) * frame.width
        val dy = (v - 0.5f) * frame.height
        return centerX + bleed * (dx * cos - dy * sin)
    }

    fun screenY(u: Float, v: Float): Float {
        val dx = (u - 0.5f) * frame.width
        val dy = (v - 0.5f) * frame.height
        return centerY + bleed * (dx * sin + dy * cos)
    }

    fun photoX(x: Float, y: Float): Float {
        val dx = (x - centerX) / bleed
        val dy = (y - centerY) / bleed
        return 0.5f + (dx * cos + dy * sin) / frame.width
    }

    fun photoY(x: Float, y: Float): Float {
        val dx = (x - centerX) / bleed
        val dy = (y - centerY) / bleed
        return 0.5f + (dy * cos - dx * sin) / frame.height
    }

    /** Déplacement normalisé de la photo pour un glissé de ([dx], [dy]) px d'écran. */
    fun photoDeltaX(dx: Float, dy: Float): Float = (dx * cos + dy * sin) / bleed / frame.width

    fun photoDeltaY(dx: Float, dy: Float): Float = (dy * cos - dx * sin) / bleed / frame.height
}
