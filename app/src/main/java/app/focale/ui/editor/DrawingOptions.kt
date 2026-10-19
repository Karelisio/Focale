package app.focale.ui.editor

import androidx.compose.runtime.Immutable
import app.focale.edit.DrawTool

/** Couleurs de la palette : trois couleurs Material You du thème, puis des couleurs fixes. */
enum class InkColor { PRIMARY, SECONDARY, TERTIARY, BLACK, WHITE, RED, YELLOW, GREEN, BLUE }

/**
 * Réglages de l'outil Dessin, hors historique (ils ne modifient pas la photo).
 *
 * @param size épaisseur à l'écran (dp) au moment du tracé ; le trait la convertit en
 *   fraction de l'image, ce qui la rend proportionnelle à l'export.
 */
@Immutable
data class DrawingOptions(
    val tool: DrawTool = DrawTool.PEN,
    val ink: InkColor = InkColor.PRIMARY,
    val size: Float = DEFAULT_SIZE,
) {
    /** Épaisseur (dp) du trait de l'outil courant : le surligneur est plus large. */
    val strokeSize: Float get() = if (tool == DrawTool.HIGHLIGHTER) size * HIGHLIGHTER_SCALE else size

    companion object {
        const val MIN_SIZE = 2f
        const val MAX_SIZE = 20f
        const val DEFAULT_SIZE = 6f
        const val HIGHLIGHTER_SCALE = 2.5f
    }
}
