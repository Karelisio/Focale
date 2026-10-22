package app.focale.backup

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.annotation.StringRes
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.byteArrayPreferencesKey
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.focale.R
import app.focale.core.di.DataBackupStore
import app.focale.core.di.IoDispatcher
import com.google.crypto.tink.Aead
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Sauvegardes automatiques des données dans un dossier choisi par l'utilisateur, à la fréquence
 * choisie ([DataAutoBackupWorker]) : réglage, clés et fichiers propres aux données, indépendants
 * de ceux du coffre (l'utilisateur peut n'avoir aucun coffre), logique commune à toutes les
 * sauvegardes automatiques ([AutoBackupEngine]).
 *
 * Chaque sauvegarde est un nouveau document, `focale-auto-AAAA-MM-JJ.focale`, écrit en flux par la
 * logique de la sauvegarde manuelle ([DataBackupRepository.export]) ; elle est sautée si ni le
 * contenu ([DataBackupRepository.fingerprint], tout ce qu'elle écrirait, sa date exceptée) ni le
 * mot de passe n'ont changé depuis la dernière, toujours présente dans le dossier.
 */
@Singleton
class DataAutoBackup internal constructor(
    resolver: ContentResolver,
    private val backups: DataBackupRepository,
    prefs: DataAutoBackupPrefs,
    keyStore: DataAutoBackupKeys,
    notifications: DataBackupNotifications,
    scheduler: AutoBackupScheduler,
    io: CoroutineDispatcher,
    /** Itérations de la dérivation du mot de passe ; réduites dans les tests. */
    iterations: Int,
    clock: () -> Long,
) : AutoBackupEngine<String>(
    resolver, prefs, keyStore, notifications, scheduler, FILES, ArchiveFormat.DATA, io, iterations, clock, TAG,
) {
    @Inject
    constructor(
        resolver: ContentResolver,
        backups: DataBackupRepository,
        prefs: DataAutoBackupPrefs,
        keyStore: DataAutoBackupKeys,
        notifications: DataBackupNotifications,
        workManager: dagger.Lazy<WorkManager>,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(
        resolver, backups, prefs, keyStore, notifications,
        WorkAutoBackupSchedule(workManager, PERIODIC, NOW, DataAutoBackupWorker::class.java), io,
        PasswordArchive.DEFAULT_ITERATIONS, System::currentTimeMillis,
    )

    /** L'instantané est l'empreinte du contenu : il y a toujours quelque chose à sauvegarder (les réglages). */
    override suspend fun snapshot(): String = backups.fingerprint()

    override suspend fun fingerprint(snapshot: String): String = snapshot

    override suspend fun export(target: Uri, keys: ArchiveKeys, snapshot: String, cancelled: () -> Boolean): AutoBackupExport {
        val (result, written) = backups.export(target, keys, cancelled)
        return when (result) {
            // Contenu relu à l'écriture : son empreinte est celle de ce qui a été écrit.
            is DataExportResult.Success -> AutoBackupExport.Saved(result.favorites + result.albums + result.people, written)
            is DataExportResult.Cancelled -> AutoBackupExport.Cancelled
            is DataExportResult.Failed -> AutoBackupExport.Failed(result.noSpace)
        }
    }

    companion object {
        private const val TAG = "DataAutoBackup"
        private const val PERIODIC = "data-auto-backup"
        private const val NOW = "data-auto-backup-now"

        /**
         * `focale-auto-AAAA-MM-JJ.focale` : seuls les fichiers ainsi nommés (ou renommés par le
         * fournisseur, « … (1).focale ») sont remplacés, jamais les sauvegardes manuelles
         * (`focale-AAAA-MM-JJ.focale`).
         */
        internal val FILES = AutoBackupFiles(prefix = "focale-auto-", extension = ".focale")
    }
}

/** Explication d'un échec de la sauvegarde automatique des données, qui renvoie aux réglages. */
@get:StringRes
val AutoBackupError.dataMessage: Int
    get() = when (this) {
        AutoBackupError.FOLDER_UNAVAILABLE -> R.string.data_auto_backup_error_folder
        AutoBackupError.NO_SPACE -> R.string.auto_backup_error_space
        AutoBackupError.WRITE_FAILED -> R.string.auto_backup_error_write
        AutoBackupError.KEYS_UNAVAILABLE -> R.string.data_auto_backup_error_keys
    }

/** Réglage et état des sauvegardes automatiques des données, dans leur propre DataStore. */
@Singleton
class DataAutoBackupPrefs @Inject constructor(@DataBackupStore store: DataStore<Preferences>) : AutoBackupPrefs(store)

/**
 * Clés des sauvegardes automatiques des données ([AutoBackupKeys]), dans leur DataStore, chiffrées
 * par un jeu de clés propre à cet usage, protégé par le Keystore : distinct de celui du coffre.
 */
@Singleton
class DataAutoBackupKeys(
    store: DataStore<Preferences>,
    primitive: () -> Aead,
) : AutoBackupKeys(store, KEYS, ASSOCIATED_DATA, ArchiveFormat.DATA, primitive) {

    @Inject
    constructor(
        @DataBackupStore store: DataStore<Preferences>,
        @ApplicationContext context: Context,
    ) : this(store, { AutoBackupKeys.keystoreBackedAead(context, KEYSET_NAME, PREFS_NAME, MASTER_KEY_URI) })

    private companion object {
        val KEYS = byteArrayPreferencesKey("auto_backup_keys")
        /** Lie le chiffré à son usage : il ne peut pas passer pour une autre donnée. */
        val ASSOCIATED_DATA = "focale-data-auto-backup/keys".toByteArray(Charsets.US_ASCII)
        const val KEYSET_NAME = "data_backup_keyset"
        const val PREFS_NAME = "focale_data_backup_keyset"
        const val MASTER_KEY_URI = "android-keystore://focale_data_backup_key"
    }
}

/**
 * Sauvegarde automatique des données ([DataAutoBackup.run]), confiée à WorkManager
 * ([WorkAutoBackupSchedule]). Son issue est toujours un succès pour WorkManager : un échec est noté
 * dans le réglage et signalé, et l'échéance suivante réessaie.
 */
@HiltWorker
class DataAutoBackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val autoBackup: DataAutoBackup,
    private val notifications: DataBackupNotifications,
) : CoroutineWorker(context, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo = notifications.autoBackupForeground()

    override suspend fun doWork(): Result {
        autoBackup.run(
            cancelled = { isStopped },
            beforeWriting = {
                // Focale à l'écran (première sauvegarde, juste après le réglage) : au premier plan, la
                // tâche n'est plus limitée à 10 minutes. En arrière-plan, Android 12+ le refuse : elle
                // continue comme tâche ordinaire.
                runCatching { setForeground(getForegroundInfo()) }.onFailure { Log.w(TAG, "Service au premier plan refusé", it) }
            },
        )
        return Result.success()
    }

    private companion object {
        const val TAG = "DataAutoBackupWorker"
    }
}
