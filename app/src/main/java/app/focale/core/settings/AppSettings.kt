package app.focale.core.settings

import androidx.compose.runtime.Immutable
import app.focale.core.model.GroupMode

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Ordre de la liste des personnes ; les personnes épinglées restent en tête. */
enum class PeopleSort { PHOTOS, NAME, RECENT }

/** Champ de tri des éléments du coffre-fort. */
enum class VaultItemOrder { DATE_TAKEN, DATE_ADDED, NAME, SIZE }

/** Tri des éléments du coffre-fort, commun à toutes ses grilles et à sa visionneuse. */
@Immutable
data class VaultSort(val order: VaultItemOrder = VaultItemOrder.DATE_TAKEN, val ascending: Boolean = false)

/** Champ de tri des albums du coffre-fort. */
enum class VaultAlbumOrder { NAME, DATE, COUNT }

/** Tri des albums du coffre-fort ; par date, celle de leur création. */
@Immutable
data class VaultAlbumSort(val order: VaultAlbumOrder = VaultAlbumOrder.DATE, val ascending: Boolean = false)

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
    /** Dossiers (BUCKET_ID) masqués de la timeline, des souvenirs et du widget. */
    val hiddenFolders: Set<Long> = emptySet(),
    /** Ordre de l'écran Personnes et de la rangée Personnes de l'onglet Albums. */
    val peopleSort: PeopleSort = PeopleSort.PHOTOS,
    val vaultSort: VaultSort = VaultSort(),
    val vaultAlbumSort: VaultAlbumSort = VaultAlbumSort(),
    /** Notification des nouvelles photos des dossiers liés à un album du coffre. */
    val vaultNewMediaAlerts: Boolean = true,
) {
    companion object {
        const val MIN_COLUMNS = 2
        const val MAX_COLUMNS = 7
        const val DEFAULT_COLUMNS = 4
    }
}
