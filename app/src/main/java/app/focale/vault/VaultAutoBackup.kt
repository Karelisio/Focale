package app.focale.vault

import android.content.ContentResolver
import android.net.Uri
import androidx.annotation.StringRes
import androidx.work.WorkManager
import app.focale.R
import app.focale.backup.ArchiveFormat
import app.focale.backup.ArchiveKeys
import app.focale.backup.AutoBackupEngine
import app.focale.backup.AutoBackupError
import app.focale.backup.AutoBackupExport
import app.focale.backup.AutoBackupFiles
import app.focale.backup.AutoBackupScheduler
import app.focale.backup.WorkAutoBackupSchedule
import app.focale.core.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Sauvegardes automatiques du coffre dans un dossier choisi par l'utilisateur, à la fréquence
 * choisie ([VaultAutoBackupWorker]) : réglage, clés et fichiers propres au coffre, logique commune
 * à toutes les sauvegardes automatiques ([AutoBackupEngine]).
 *
 * Le coffre n'a pas à être déverrouillé : ses fichiers se lisent avec la clé de l'appareil, comme
 * pour l'import. Chaque sauvegarde est un nouveau document, `focale-coffre-auto-AAAA-MM-JJ.fvault`,
 * écrit en flux par la logique de la sauvegarde manuelle ([VaultBackupRepository.export]) ; elle
 * est sautée si ni le contenu du coffre ([VaultSnapshot.fingerprint]) ni le mot de passe n'ont
 * changé depuis la dernière, et un coffre vide n'est pas sauvegardé.
 */
@Singleton
class VaultAutoBackup internal constructor(
    resolver: ContentResolver,
    private val backups: VaultBackupRepository,
    prefs: VaultAutoBackupPrefs,
    keyStore: VaultAutoBackupKeys,
    notifications: VaultNotifications,
    scheduler: AutoBackupScheduler,
    io: CoroutineDispatcher,
    /** Itérations de la dérivation du mot de passe ; réduites dans les tests. */
    iterations: Int,
    clock: () -> Long,
) : AutoBackupEngine<VaultSnapshot>(
    resolver, prefs, keyStore, notifications, scheduler, FILES, ArchiveFormat.VAULT, io, iterations, clock, TAG,
) {
    @Inject
    constructor(
        resolver: ContentResolver,
        backups: VaultBackupRepository,
        prefs: VaultAutoBackupPrefs,
        keyStore: VaultAutoBackupKeys,
        notifications: VaultNotifications,
        workManager: dagger.Lazy<WorkManager>,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(
        resolver, backups, prefs, keyStore, notifications,
        WorkAutoBackupSchedule(workManager, PERIODIC, NOW, VaultAutoBackupWorker::class.java), io,
        VaultArchive.DEFAULT_ITERATIONS, System::currentTimeMillis,
    )

    /** Coffre vide : les sauvegardes précédentes restent les plus récentes. */
    override suspend fun snapshot(): VaultSnapshot? = backups.snapshot().takeUnless { it.isEmpty }

    override suspend fun fingerprint(snapshot: VaultSnapshot): String = snapshot.fingerprint

    override suspend fun export(target: Uri, keys: ArchiveKeys, snapshot: VaultSnapshot, cancelled: () -> Boolean): AutoBackupExport =
        when (val result = backups.export(target, keys, snapshot, cancelled)) {
            is ExportResult.Success -> AutoBackupExport.Saved(result.saved)
            is ExportResult.Cancelled -> AutoBackupExport.Cancelled
            is ExportResult.Failed -> AutoBackupExport.Failed(result.noSpace)
        }

    companion object {
        /** Sauvegardes automatiques gardées dans le dossier. */
        const val KEEP = AutoBackupEngine.KEEP
        private const val TAG = "VaultAutoBackup"
        private const val PERIODIC = "vault-auto-backup"
        private const val NOW = "vault-auto-backup-now"

        /**
         * `focale-coffre-auto-AAAA-MM-JJ.fvault` : seuls les fichiers ainsi nommés (ou renommés par
         * le fournisseur, « … (1).fvault ») sont remplacés.
         */
        private val FILES = AutoBackupFiles(prefix = "focale-coffre-auto-", extension = ".fvault")
    }
}

/** Explication d'un échec de la sauvegarde automatique du coffre, qui renvoie à son menu. */
@get:StringRes
internal val AutoBackupError.vaultMessage: Int
    get() = when (this) {
        AutoBackupError.FOLDER_UNAVAILABLE -> R.string.vault_auto_backup_error_folder
        AutoBackupError.NO_SPACE -> R.string.auto_backup_error_space
        AutoBackupError.WRITE_FAILED -> R.string.auto_backup_error_write
        AutoBackupError.KEYS_UNAVAILABLE -> R.string.vault_auto_backup_error_keys
    }
