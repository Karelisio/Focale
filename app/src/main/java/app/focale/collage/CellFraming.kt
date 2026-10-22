package app.focale.collage

import kotlin.math.max

/**
 * Cadrage d'une photo dans sa case ([CellFrame]) : la photo remplit d'abord la case (comme un
 * recadrage centré), puis s'agrandit et se déplace sans jamais laisser de vide dans la case.
 * Logique pure, commune à l'aperçu (gestes) et à l'export : la case est en pixels quelconques
 * (écran ou collage), la photo en pixels de l'image affichée (orientation appliquée).
 */
object CellFraming {
    const val MAX_ZOOM = 4f

    /** Pixels de la case par pixel de la photo. */
    fun scale(frame: CellFrame, cellWidth: Float, cellHeight: Float, imageWidth: Float, imageHeight: Float): Float =
        max(cellWidth / imageWidth, cellHeight / imageHeight) * frame.zoom.coerceIn(1f, MAX_ZOOM)

    /** Cadrage ramené dans ses bornes : agrandissement de 1 à [MAX_ZOOM], partie visible dans la photo. */
    fun clamp(frame: CellFrame, cellWidth: Float, cellHeight: Float, imageWidth: Float, imageHeight: Float): CellFrame {
        val zoom = frame.zoom.coerceIn(1f, MAX_ZOOM)
        val scale = max(cellWidth / imageWidth, cellHeight / imageHeight) * zoom
        // Demi-largeur et demi-hauteur visibles, en fraction de la photo (au plus 1/2).
        val halfWidth = cellWidth / scale / imageWidth / 2f
        val halfHeight = cellHeight / scale / imageHeight / 2f
        return CellFrame(
            zoom = zoom,
            centerX = if (halfWidth >= 0.5f) 0.5f else frame.centerX.coerceIn(halfWidth, 1f - halfWidth),
            centerY = if (halfHeight >= 0.5f) 0.5f else frame.centerY.coerceIn(halfHeight, 1f - halfHeight),
        )
    }

    /** Partie de la photo visible dans la case, en pixels de la photo. */
    fun visible(frame: CellFrame, cellWidth: Float, cellHeight: Float, imageWidth: Float, imageHeight: Float): CellRect {
        val clamped = clamp(frame, cellWidth, cellHeight, imageWidth, imageHeight)
        val scale = scale(clamped, cellWidth, cellHeight, imageWidth, imageHeight)
        val width = cellWidth / scale
        val height = cellHeight / scale
        val x = clamped.centerX * imageWidth
        val y = clamped.centerY * imageHeight
        return CellRect(x - width / 2f, y - height / 2f, x + width / 2f, y + height / 2f)
    }

    /** Glisser de ([dx], [dy]) pixels de la case : la photo suit le doigt, jusqu'à son bord. */
    fun pan(frame: CellFrame, dx: Float, dy: Float, cellWidth: Float, cellHeight: Float, imageWidth: Float, imageHeight: Float): CellFrame {
        val current = clamp(frame, cellWidth, cellHeight, imageWidth, imageHeight)
        val scale = scale(current, cellWidth, cellHeight, imageWidth, imageHeight)
        val moved = current.copy(
            centerX = current.centerX - dx / scale / imageWidth,
            centerY = current.centerY - dy / scale / imageHeight,
        )
        return clamp(moved, cellWidth, cellHeight, imageWidth, imageHeight)
    }

    /**
     * Agrandissement de [factor] autour du point ([focusX], [focusY]), en pixels de la case depuis
     * son coin supérieur gauche : le point de la photo sous les doigts y reste (sauf au bord de la
     * photo, où le cadrage s'arrête).
     */
    fun zoom(
        frame: CellFrame,
        factor: Float,
        focusX: Float,
        focusY: Float,
        cellWidth: Float,
        cellHeight: Float,
        imageWidth: Float,
        imageHeight: Float,
    ): CellFrame {
        val current = clamp(frame, cellWidth, cellHeight, imageWidth, imageHeight)
        val scale = scale(current, cellWidth, cellHeight, imageWidth, imageHeight)
        val zoom = (current.zoom * factor).coerceIn(1f, MAX_ZOOM)
        val newScale = scale * zoom / current.zoom
        // Point de la photo sous les doigts, en pixels de la photo.
        val pointX = current.centerX * imageWidth + (focusX - cellWidth / 2f) / scale
        val pointY = current.centerY * imageHeight + (focusY - cellHeight / 2f) / scale
        val zoomed = CellFrame(
            zoom = zoom,
            centerX = (pointX - (focusX - cellWidth / 2f) / newScale) / imageWidth,
            centerY = (pointY - (focusY - cellHeight / 2f) / newScale) / imageHeight,
        )
        return clamp(zoomed, cellWidth, cellHeight, imageWidth, imageHeight)
    }
}
