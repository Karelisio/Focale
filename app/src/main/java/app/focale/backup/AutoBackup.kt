package app.focale.backup

import android.content.ContentResolver
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.work.ForegroundInfo
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Fréquence des sauvegardes automatiques. */
enum class AutoBackupFrequency(internal val days: Long) {
    OFF(0),
    DAILY(1),
    WEEKLY(7),
}

/** Cause du dernier échec d'une sauvegarde automatique ; chaque sauvegarde l'explique à sa façon. */
enum class AutoBackupError {
    /** Dossier supprimé, support retiré ou autorisation révoquée : à choisir de nouveau. */
    FOLDER_UNAVAILABLE,

    /** Plus assez de place dans le dossier. */
    NO_SPACE,

    /** Écriture interrompue par une autre erreur ; nouvel essai à l'échéance suivante. */
    WRITE_FAILED,

    /** Clés des sauvegardes illisibles (Keystore réinitialisé…) : mot de passe à saisir de nouveau. */
    KEYS_UNAVAILABLE,
}

/** Réglage et état des sauvegardes automatiques, tels que leur écran les montre. */
@Immutable
data class AutoBackupSettings(
    val frequency: AutoBackupFrequency = AutoBackupFrequency.OFF,
    /** Arbre de documents choisi par le sélecteur système. */
    val folder: Uri? = null,
    val folderName: String? = null,
    /** Dernière sauvegarde automatique écrite dans ce dossier (ms). */
    val lastBackupAt: Long? = null,
    val error: AutoBackupError? = null,
    val errorAt: Long? = null,
) {
    val enabled: Boolean get() = frequency != AutoBackupFrequency.OFF && folder != null
}

/** Issue d'une sauvegarde automatique. */
sealed interface AutoBackupResult {
    data class Saved(val items: Int) : AutoBackupResult

    data class Skipped(val reason: Reason) : AutoBackupResult {
        enum class Reason {
            DISABLED,
            /** Rien à sauvegarder : les sauvegardes précédentes restent les plus récentes. */
            EMPTY,
            /** Rien n'a changé depuis la dernière sauvegarde, toujours dans le dossier. */
            UNCHANGED,
        }
    }

    data class Failed(val error: AutoBackupError) : AutoBackupResult

    /** Interrompue (tâche arrêtée par le système) : le fichier inachevé est retiré, elle reprendra. */
    data object Interrupted : AutoBackupResult
}

/** Écriture d'une sauvegarde automatique par la sauvegarde concernée ([AutoBackupEngine.export]). */
sealed interface AutoBackupExport {
    /**
     * [items] éléments sauvegardés ; [fingerprint] : empreinte du contenu effectivement écrit, quand
     * il a pu changer depuis l'instantané (contenu relu pendant l'écriture), sinon null.
     */
    data class Saved(val items: Int, val fingerprint: String? = null) : AutoBackupExport

    /** Interrompue ([AutoBackupEngine.run], `cancelled`) : le document inachevé est supprimé. */
    data object Cancelled : AutoBackupExport

    /** [noSpace] : plus de place dans le dossier. */
    data class Failed(val noSpace: Boolean) : AutoBackupExport
}

/** Notifications d'une sauvegarde automatique : exécution au premier plan, échec et son retrait. */
interface AutoBackupNotifier {
    /** Sauvegarde en cours, quand elle peut passer au premier plan : discrète, sans détail. */
    fun autoBackupForeground(): ForegroundInfo

    /** Échec : sa cause seulement, sans rien du contenu sauvegardé. */
    fun autoBackupFailed(error: AutoBackupError)

    fun cancelAutoBackupFailed()
}

/**
 * Sauvegardes automatiques dans un dossier choisi par l'utilisateur, à la fréquence choisie
 * (tâche WorkManager propre à chaque sauvegarde, voir [AutoBackupScheduler]) : celles du coffre et
 * celles des données, chacune avec son réglage, ses clés et ses fichiers.
 *
 * Le mot de passe n'est pas conservé : il sert une fois à tirer les clés des archives
 * ([PasswordArchive.deriveKeys]), gardées chiffrées par une clé du Keystore ([AutoBackupKeys]). Les
 * archives, au format habituel, se restaurent avec le mot de passe comme une sauvegarde manuelle.
 *
 * Chaque sauvegarde est un nouveau document, nommé d'après le jour ([AutoBackupFiles]), écrit en
 * flux par la logique de la sauvegarde manuelle ([export]) et supprimé si l'écriture échoue.
 * Seules les [KEEP] plus récentes sont gardées ; les autres fichiers du dossier ne sont jamais
 * touchés. Une sauvegarde est sautée si ni le contenu ([fingerprint]) ni le mot de passe n'ont
 * changé depuis la dernière, toujours présente dans le dossier.
 *
 * @param S instantané du contenu à sauvegarder ([snapshot]).
 */
abstract class AutoBackupEngine<S : Any> internal constructor(
    private val resolver: ContentResolver,
    private val prefs: AutoBackupPrefs,
    private val keyStore: AutoBackupKeys,
    private val notifier: AutoBackupNotifier,
    private val scheduler: AutoBackupScheduler,
    private val files: AutoBackupFiles,
    private val format: ArchiveFormat,
    private val io: CoroutineDispatcher,
    /** Itérations de la dérivation du mot de passe ; réduites dans les tests. */
    private val iterations: Int,
    private val clock: () -> Long,
    /** Étiquette des journaux. */
    private val tag: String,
) {
    /** Une sauvegarde à la fois (tâche périodique et sauvegarde demandée après un changement). */
    private val running = Mutex()

    val settings: Flow<AutoBackupSettings> get() = prefs.settings

    /** Contenu à sauvegarder, figé ; null s'il n'y a rien à sauvegarder. */
    protected abstract suspend fun snapshot(): S?

    /** Empreinte de [snapshot] : identique d'une sauvegarde à l'autre tant que le contenu ne change pas. */
    protected abstract suspend fun fingerprint(snapshot: S): String

    /**
     * Écrit [snapshot] dans le document [target] avec les clés [keys], effacées ensuite ; le
     * document est supprimé si l'écriture n'aboutit pas.
     *
     * @param cancelled consulté pendant l'écriture : vrai pour l'interrompre (tâche arrêtée).
     */
    protected abstract suspend fun export(target: Uri, keys: ArchiveKeys, snapshot: S, cancelled: () -> Boolean): AutoBackupExport

    /**
     * Tire du mot de passe les clés des sauvegardes (dérivation lente, PBKDF2) ; [password] est
     * effacé aussitôt après, et les clés le sont si l'appelant n'en veut plus (annulation).
     */
    suspend fun deriveKeys(password: CharArray): ArchiveKeys {
        val keys = withContext(io + NonCancellable) {
            try {
                PasswordArchive.deriveKeys(format, password, iterations)
            } finally {
                password.fill('\u0000')
            }
        }
        try {
            currentCoroutineContext().ensureActive()
        } catch (error: CancellationException) {
            keys.close()
            throw error
        }
        return keys
    }

    /**
     * Active les sauvegardes automatiques dans [folder] avec les clés [keys], effacées ensuite ;
     * la première a lieu dès que possible.
     *
     * @return null si elles sont actives ; [AutoBackupError.FOLDER_UNAVAILABLE] si le dossier ne peut
     *   pas recevoir les sauvegardes, [AutoBackupError.KEYS_UNAVAILABLE] si les clés n'ont pas pu
     *   être enregistrées.
     */
    suspend fun enable(folder: Uri, keys: ArchiveKeys, frequency: AutoBackupFrequency): AutoBackupError? = withContext(io) {
        keys.use {
            require(frequency != AutoBackupFrequency.OFF)
            val previous = prefs.state().settings.folder
            if (!takeFolder(folder, current = previous)) return@withContext AutoBackupError.FOLDER_UNAVAILABLE
            if (!store(keys)) {
                if (folder != previous) releasePermission(folder)
                return@withContext AutoBackupError.KEYS_UNAVAILABLE
            }
            prefs.enable(folder, BackupFolder(resolver, folder).displayName(), frequency)
            previous?.takeIf { it != folder }?.let(::releasePermission)
            notifier.cancelAutoBackupFailed()
            scheduler.schedule(frequency, restart = true)
            null
        }
    }

    /** Nouvelle fréquence ; [AutoBackupFrequency.OFF] désactive tout ([disable]). */
    suspend fun setFrequency(frequency: AutoBackupFrequency) {
        if (frequency == AutoBackupFrequency.OFF) return disable()
        val settings = prefs.state().settings
        if (!settings.enabled || settings.frequency == frequency) return
        prefs.setFrequency(frequency)
        scheduler.schedule(frequency, restart = false)
    }

    /**
     * Nouveau dossier, ou le même choisi de nouveau (autorisation perdue) : la sauvegarde
     * suivante y est écrite dès que possible.
     *
     * @return faux si le dossier ne peut pas recevoir les sauvegardes.
     */
    suspend fun changeFolder(folder: Uri): Boolean = withContext(io) {
        val settings = prefs.state().settings
        if (!settings.enabled) return@withContext false
        if (!takeFolder(folder, current = settings.folder)) return@withContext false
        prefs.changeFolder(folder, BackupFolder(resolver, folder).displayName())
        settings.folder?.takeIf { it != folder }?.let(::releasePermission)
        notifier.cancelAutoBackupFailed()
        scheduler.runSoon()
        true
    }

    /**
     * Nouveau mot de passe, par ses clés [keys] (effacées) : la sauvegarde suivante les emploie,
     * dès que possible (l'empreinte d'une sauvegarde couvre ses clés, elle n'est pas sautée).
     *
     * @return faux si les clés n'ont pas pu être enregistrées.
     */
    suspend fun changePassword(keys: ArchiveKeys): Boolean = withContext(io) {
        keys.use {
            if (!prefs.state().settings.enabled) return@withContext false
            if (!store(keys)) return@withContext false
            notifier.cancelAutoBackupFailed()
            scheduler.runSoon()
            true
        }
    }

    /** Arrête les sauvegardes et oublie dossier et clés ; les sauvegardes déjà écrites restent. */
    suspend fun disable() {
        withContext(io) {
            val folder = prefs.state().settings.folder
            scheduler.cancel()
            keyStore.clear()
            prefs.clear()
            folder?.let(::releasePermission)
            notifier.cancelAutoBackupFailed()
        }
    }

    /**
     * Sauvegarde le contenu s'il a changé depuis la dernière sauvegarde automatique, puis ne garde
     * que les [KEEP] plus récentes. Un échec est noté dans le réglage et signalé par une
     * notification, sans rien du contenu sauvegardé.
     *
     * @param cancelled consulté pendant l'écriture : vrai pour l'interrompre (tâche arrêtée).
     * @param beforeWriting appelé quand une archive va être écrite (passage au premier plan).
     */
    suspend fun run(cancelled: () -> Boolean = { false }, beforeWriting: suspend () -> Unit = {}): AutoBackupResult =
        running.withLock { withContext(io) { runLocked(cancelled, beforeWriting) } }

    private suspend fun runLocked(cancelled: () -> Boolean, beforeWriting: suspend () -> Unit): AutoBackupResult {
        val state = prefs.state()
        val tree = state.settings.folder
        if (!state.settings.enabled || tree == null) return AutoBackupResult.Skipped(AutoBackupResult.Skipped.Reason.DISABLED)
        val keys = try {
            keyStore.load()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Keystore indisponible, clé maîtresse invalidée : le mot de passe doit être saisi de nouveau.
            Log.w(tag, "Clés des sauvegardes automatiques illisibles", error)
            null
        } ?: return fail(tree, AutoBackupError.KEYS_UNAVAILABLE)
        keys.use {
            val folder = BackupFolder(resolver, tree)
            val existing = try {
                if (!folder.permitted()) throw SecurityException("Autorisation du dossier retirée")
                folder.list()
            } catch (error: Exception) {
                Log.w(tag, "Dossier des sauvegardes automatiques inaccessible", error)
                return fail(tree, AutoBackupError.FOLDER_UNAVAILABLE)
            }
            val snapshot = snapshot() ?: return skip(tree, AutoBackupResult.Skipped.Reason.EMPTY)
            // Le sel, public, reste lisible une fois les clés effacées par l'écriture.
            val salt = keys.salt
            val fingerprint = combined(fingerprint(snapshot), salt)
            if (fingerprint == state.fingerprint && existing.any { it.id == state.lastDocument }) {
                return skip(tree, AutoBackupResult.Skipped.Reason.UNCHANGED)
            }
            beforeWriting()
            val target = try {
                folder.create(files.nameOf(today()))
            } catch (error: Exception) {
                Log.w(tag, "Sauvegarde automatique impossible à créer", error)
                return fail(tree, AutoBackupError.FOLDER_UNAVAILABLE)
            }
            return when (val result = export(target, keys, snapshot, cancelled)) {
                is AutoBackupExport.Saved -> {
                    val document = BackupFolder.documentId(target)
                    val written = result.fingerprint?.let { combined(it, salt) } ?: fingerprint
                    prefs.recordSuccess(tree, clock(), written, document)
                    notifier.cancelAutoBackupFailed()
                    rotate(folder, keep = document)
                    AutoBackupResult.Saved(result.items)
                }
                AutoBackupExport.Cancelled -> AutoBackupResult.Interrupted
                is AutoBackupExport.Failed -> fail(tree, if (result.noSpace) AutoBackupError.NO_SPACE else AutoBackupError.WRITE_FAILED)
            }
        }
    }

    /**
     * Ne garde que les [KEEP] sauvegardes automatiques les plus récentes, dont [keep] qui vient
     * d'être écrite ; les fichiers aux autres noms ne sont jamais touchés.
     */
    private fun rotate(folder: BackupFolder, keep: String) {
        val stale = try {
            folder.list()
                .mapNotNull { entry -> files.parse(entry.name)?.let { entry to it } }
                .sortedWith(
                    compareByDescending<Pair<BackupFolder.Entry, AutoBackupFiles.Name>> { it.first.id == keep }
                        .thenByDescending { it.second.day }
                        .thenByDescending { it.first.lastModified }
                        .thenByDescending { it.second.copy },
                )
                .drop(KEEP)
        } catch (error: Exception) {
            Log.w(tag, "Anciennes sauvegardes automatiques non relues", error)
            return
        }
        for ((entry, _) in stale) {
            runCatching { folder.delete(entry.id) }
                .onFailure { Log.w(tag, "Ancienne sauvegarde automatique non supprimée", it) }
        }
    }

    private suspend fun fail(folder: Uri, error: AutoBackupError): AutoBackupResult {
        // Réglage changé entre-temps (désactivée, autre dossier) : l'échec ne le concerne plus.
        if (prefs.recordError(folder, error, clock())) notifier.autoBackupFailed(error)
        return AutoBackupResult.Failed(error)
    }

    private suspend fun skip(folder: Uri, reason: AutoBackupResult.Skipped.Reason): AutoBackupResult {
        prefs.clearError(folder)
        notifier.cancelAutoBackupFailed()
        return AutoBackupResult.Skipped(reason)
    }

    private fun today(): LocalDate = Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()).toLocalDate()

    /**
     * Empreinte d'une sauvegarde : celle du contenu et les clés employées, par leur sel (public,
     * dans l'en-tête de l'archive). Un nouveau mot de passe la change : une sauvegarde en cours avec
     * l'ancien n'empêche pas la suivante.
     */
    private fun combined(content: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(content.toByteArray(Charsets.UTF_8))
        digest.update(salt)
        return Base64.getEncoder().encodeToString(digest.digest())
    }

    private suspend fun store(keys: ArchiveKeys): Boolean = try {
        keyStore.save(keys)
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        // Keystore indisponible ou clé maîtresse invalidée.
        Log.w(tag, "Clés des sauvegardes automatiques non enregistrées", error)
        false
    }

    /**
     * Garde l'autorisation sur le dossier [folder], s'il peut recevoir des documents ; sinon, elle
     * est rendue, sauf s'il s'agit du dossier [current].
     */
    private fun takeFolder(folder: Uri, current: Uri?): Boolean {
        if (!BackupFolder.isTree(folder)) return false
        try {
            resolver.takePersistableUriPermission(folder, BackupFolder.PERMISSIONS)
        } catch (error: SecurityException) {
            Log.w(tag, "Autorisation du dossier refusée", error)
            return false
        }
        if (BackupFolder(resolver, folder).canCreate()) return true
        Log.w(tag, "Dossier en lecture seule ou illisible")
        if (folder != current) releasePermission(folder)
        return false
    }

    private fun releasePermission(folder: Uri) {
        runCatching { resolver.releasePersistableUriPermission(folder, BackupFolder.PERMISSIONS) }
            .onFailure { Log.w(tag, "Autorisation du dossier non rendue", it) }
    }

    companion object {
        /** Sauvegardes automatiques gardées dans le dossier. */
        const val KEEP = 3
    }
}

/**
 * Noms des sauvegardes automatiques, `<prefix>AAAA-MM-JJ<extension>` : seuls les fichiers ainsi
 * nommés (ou renommés par le fournisseur, « … (1).ext ») sont remplacés.
 */
internal class AutoBackupFiles(private val prefix: String, private val extension: String) {

    /** Jour de la sauvegarde (AAAA-MM-JJ, qui se trie comme une date) et numéro de copie du même nom. */
    class Name(val day: String, val copy: Int)

    private val pattern = Regex(Regex.escape(prefix) + """(\d{4}-\d{2}-\d{2})(?: ?\((\d{1,4})\))?""" + Regex.escape(extension))

    fun nameOf(day: LocalDate): String = "$prefix$day$extension"

    fun parse(name: String): Name? = pattern.matchEntire(name)?.let { match ->
        Name(day = match.groupValues[1], copy = match.groupValues[2].toIntOrNull() ?: 0)
    }
}
