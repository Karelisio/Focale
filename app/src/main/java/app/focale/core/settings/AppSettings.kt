package app.focale.core.settings

import androidx.compose.runtime.Immutable
import app.focale.core.model.GroupMode

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Réglages utilisateur persistés (DataStore). */
@Immutable
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Surfaces noir pur en thème sombre (écrans OLED). */
    val amoled: Boolean = false,
    /** Couleurs dynamiques du fond d'écran (Android 12+). */
    val dynamicColor: Boolean = true,
    /** La visionneuse et les albums adoptent la palette de la photo affichée. */
    val colorFromPhoto: Boolean = true,
    /** Nombre de colonnes de la grille, de [MIN_COLUMNS] à [MAX_COLUMNS]. */
    val gridColumns: Int = DEFAULT_COLUMNS,
    val groupMode: GroupMode = GroupMode.AUTO,
    val showMemories: Boolean = true,
    val autoplayVideos: Boolean = true,
    val muteVideosByDefault: Boolean = false,
    /** Supprime les métadonnées EXIF/GPS des fichiers partagés. */
    val stripMetadataOnShare: Boolean = false,
    /** Analyse intelligente (étiquettes, doublons, flou, lieux). */
    val analysisEnabled: Boolean = true,
    val analysisOnlyWhileCharging: Boolean = false,
    /** Fond de carte OpenStreetMap en ligne (seul usage du réseau, désactivé par défaut). */
    val onlineMapTiles: Boolean = false,
    /** Durée d'affichage de chaque photo du diaporama. */
    val slideshowIntervalSeconds: Int = 5,
    /** Délai (s) avant reverrouillage du coffre une fois l'application en arrière-plan. */
    val vaultAutoLockSeconds: Int = 0,
    /** Recherche quotidienne d'une nouvelle version sur GitHub (variante standard). */
    val autoCheckUpdates: Boolean = true,
) {
    companion object {
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 7
        const val DEFAULT_COLUMNS = 4
    }
}
