package app.focale.backup

import android.net.Uri
import androidx.compose.runtime.Immutable
import java.io.Closeable

// Résultats de la sauvegarde des données (DataBackupRepository) : résumé d'une sauvegarde vérifiée,
// issues de la sauvegarde, de la vérification et de la restauration, bilan.

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
)

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

/** Bilan d'une restauration. */
@Immutable
data class DataRestoreReport(
    /** Médias désignés par la sauvegarde, et ceux retrouvés sur ce téléphone. */
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
    /** Réglages remplacés par ceux de la sauvegarde ; faux s'ils n'ont pas pu l'être. */
    val settings: Boolean,
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
