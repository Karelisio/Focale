package app.focale.ui.viewer

import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Mesures partagées par la barre d'outils flottante des visionneuses et les commandes vidéo,
 * pour que les deux s'empilent alignées : centrées, mêmes marges, même largeur maximale.
 */
internal object ViewerChromeMetrics {
    /** Marge entre les barres flottantes et les bords de l'écran (insets système en plus). */
    val ScreenOffset: Dp = FloatingToolbarDefaults.ScreenOffset

    /** Largeur maximale des barres du bas : pas de bandeau d'un bord à l'autre en paysage. */
    val BarMaxWidth: Dp = 560.dp

    /** Hauteur occupée en bas par la barre d'outils flottante, marge comprise. */
    val ToolbarHeight: Dp = FloatingToolbarDefaults.ContainerSize + ScreenOffset

    /** Écart entre la ligne de progression d'une vidéo et la barre d'outils. */
    val ControlsGap: Dp = 4.dp
}
