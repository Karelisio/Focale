package app.focale.edit

import androidx.compose.runtime.Immutable

/**
 * Fond d'un texte : aucun, encadré (pavé de la couleur du texte, lettres en noir ou en blanc
 * selon le contraste) ou contour (lettres de la couleur, cernées de noir ou de blanc).
 */
enum class TextBackground { NONE, BOX, OUTLINE }

/**
 * Texte posé sur la photo, dans le repère des traits ([DrawingStroke]) : centre normalisé dans
 * le cadre de l'image pivotée entière, avant recadrage. Comme un trait, il reste attaché au
 * contenu : un recadrage le découpe sans le déplacer, une rotation le fait tourner avec l'image,
 * un miroir le déplace sans retourner ses lettres, et le redressement fait pivoter la photo
 * sous lui (un texte posé droit le reste).
 *
 * @param id identité stable (sélection, modification), unique dans une édition.
 * @param text une ou plusieurs lignes, jamais vide.
 * @param size corps des caractères en fraction du petit côté de l'image : le texte garde les
 *   mêmes proportions à toute résolution, dans l'aperçu comme à l'export.
 * @param rotation inclinaison en degrés, sens horaire, de -180 (exclu) à 180.
 * @param color couleur ARGB opaque des lettres, ou du pavé pour [TextBackground.BOX].
 */
@Immutable
data class TextItem(
    val id: Long,
    val text: String,
    val x: Float,
    val y: Float,
    val color: Int,
    val size: Float = DEFAULT_SIZE,
    val rotation: Float = 0f,
    val background: TextBackground = TextBackground.NONE,
    val bold: Boolean = false,
) {
    /** Quart de tour horaire du repère, comme [DrawingStroke.rotateClockwise] : le texte tourne avec l'image. */
    fun rotateClockwise(): TextItem = copy(x = 1f - y, y = x, rotation = TextMath.normalizeAngle(rotation + 90f))

    /**
     * Miroir horizontal du repère : le texte change de côté et son inclinaison s'inverse, mais
     * ses lettres ne sont pas retournées (il se lit toujours de gauche à droite).
     */
    fun flipHorizontal(): TextItem = copy(x = 1f - x, rotation = TextMath.normalizeAngle(-rotation))

    companion object {
        const val MIN_SIZE = 0.02f
        const val MAX_SIZE = 0.4f
        const val DEFAULT_SIZE = 0.07f
    }
}
