package app.focale.backup

import android.net.Uri
import androidx.compose.runtime.Immutable
import java.io.Closeable

// Résultats de la sauvegarde des données (DataBackupRepository) : résumé d'une sauvegarde vérifiée,
// issues de la sauvegarde, de la vérification et de la restauration, bilan.

/**
 * Ce qu'une restauration peut reprendre d'une sauvegarde des données, ensemble ou séparément
 * ([DataBackupRepository.restore]). Chaque catégorie s'applique comme dans une restauration
 * complète ; les autres ne sont pas touchées.
 */
enum class DataCategory {
    FAVORITES,
    ALBUMS,

    /** Personnes, leurs visages et les refus de fusion ; passages de la détection des visages. */
    PEOPLE,
    HIDDEN_FOLDERS,
    SETTINGS,
    SEARCH_HISTORY,

    /** Dates de prise de vue retrouvées par Focale pour des médias que MediaStore ne datait pas. */
    ORIGINAL_DATES,
    ;

    /** Désigne des médias, à retrouver sur ce téléphone. */
    val needsMedia: Boolean get() = this == FAVORITES || this == ALBUMS || this == PEOPLE || this == ORIGINAL_DATES

    companion object {
        val ALL: Set<DataCategory> = entries.toSet()
    }
}

/** Résumé d'une sauvegarde des données vérifiée, montré avant de la restaurer. */
@Immutable
data class DataBackupSummary(
    /** Création de la sauvegarde (ms). */
    val createdAt: Long,
    val appVersion: String,
    val favorites: Int,
    val albums: Int,
    /** Personnes de la liste des personnes : nommées, ou sur au moins trois photos. */
    val people: Int,
    val hiddenFolders: Int,
    /** Médias désignés par la sauvegarde (favoris, albums, visages…), à retrouver sur ce téléphone. */
    val media: Int,
    val faces: Int,
    val searches: Int,
    /** Personnes déjà listées sur ce téléphone : celles des photos retrouvées seront remplacées. */
    val existingPeople: Int,
    /** Enregistrements de la sauvegarde (avancement de la restauration). */
    val records: Int,
    /** Dates d'origine ([DataCategory.ORIGINAL_DATES]). */
    val dates: Int = 0,
    /** Réglages présents (toute sauvegarde de Focale en a). */
    val settings: Boolean = true,
) {
    /** Catégories qui ont quelque chose à restaurer, proposées (et cochées d'office) dans le résumé. */
    val categories: Set<DataCategory>
        get() = buildSet {
            if (favorites > 0) add(DataCategory.FAVORITES)
            if (albums > 0) add(DataCategory.ALBUMS)
            if (people > 0 || faces > 0) add(DataCategory.PEOPLE)
            if (hiddenFolders > 0) add(DataCategory.HIDDEN_FOLDERS)
            if (settings) add(DataCategory.SETTINGS)
            if (searches > 0) add(DataCategory.SEARCH_HISTORY)
            if (dates > 0) add(DataCategory.ORIGINAL_DATES)
        }
}

/**
 * Sauvegarde vérifiée de bout en bout ([DataBackupRepository.inspect]) : son résumé, et les clés
 * qui la rouvrent sans redemander le mot de passe, à effacer ([close]) une fois la restauration
 * faite ou abandonnée.
 */
class InspectedBackup internal constructor(
    val source: Uri,
    val summary: DataBackupSummary,
    internal val keys: ArchiveKeys,
) : Closeable {
    override fun close() = keys.close()
}

sealed interface DataExportResult {
    /** Sauvegarde complète ; ce qu'elle contient, pour le message de fin. */
    data class Success(val favorites: Int, val albums: Int, val people: Int, val hiddenFolders: Int) : DataExportResult

    /** [partialFileLeft] : le fichier inachevé n'a pas pu être supprimé. */
    data class Cancelled(val partialFileLeft: Boolean) : DataExportResult

    /** [noSpace] : plus de place à l'emplacement de la sauvegarde. */
    data class Failed(val partialFileLeft: Boolean, val noSpace: Boolean = false) : DataExportResult
}

/** Sauvegarde refusée ou illisible. */
enum class DataBackupFailure {
    /** Pas une sauvegarde de Focale. */
    NOT_AN_ARCHIVE,

    /** Sauvegarde du coffre, à restaurer depuis le coffre. */
    VAULT_BACKUP,

    /** Écrite par une version plus récente de Focale. */
    UNSUPPORTED,

    /** Modifiée, tronquée ou mal formée ; ou changée depuis sa vérification. */
    CORRUPTED,

    /** Plus de place sur l'appareil pour enregistrer ce qu'elle restaure. */
    NO_SPACE,

    /** Fichier illisible (supprimé, fournisseur indisponible…). */
    UNREADABLE,
}

sealed interface DataInspectResult {
    /** Sauvegarde vérifiée, prête à restaurer ; à refermer si elle ne l'est pas. */
    class Ready(val backup: InspectedBackup) : DataInspectResult

    data object WrongPassword : DataInspectResult

    data object Cancelled : DataInspectResult

    data class Failed(val reason: DataBackupFailure) : DataInspectResult
}

/** Bilan d'une restauration, des seules catégories choisies ([categories]). */
@Immutable
data class DataRestoreReport(
    /** Médias de la sauvegarde que désignent les catégories choisies, et ceux retrouvés sur ce téléphone. */
    val media: Int,
    val matched: Int,
    /** Favoris des médias retrouvés (déjà favoris compris). */
    val favorites: Int,
    /** Albums recréés ou complétés. */
    val albums: Int,
    /** Personnes de la liste recréées avec leurs visages. */
    val people: Int,
    val faces: Int,
    /** Dossiers masqués de plus. */
    val hiddenFolders: Int,
    val searches: Int,
    /** Réglages remplacés par ceux de la sauvegarde ; faux s'ils n'ont pas pu l'être, null s'ils n'étaient pas à restaurer. */
    val settings: Boolean?,
    /** Dates d'origine restaurées. */
    val dates: Int = 0,
    val categories: Set<DataCategory> = DataCategory.ALL,
) {
    /** Médias introuvables sur ce téléphone : leurs favoris, albums et visages n'ont pas été restaurés. */
    val missing: Int get() = media - matched
}

sealed interface DataRestoreResult {
    data class Success(val report: DataRestoreReport) : DataRestoreResult

    /** Annulée : rien n'a été modifié. */
    data object Cancelled : DataRestoreResult

    /** Rien n'a été modifié. */
    data class Failed(val reason: DataBackupFailure) : DataRestoreResult
}
