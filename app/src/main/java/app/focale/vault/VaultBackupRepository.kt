package app.focale.vault

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.runtime.Immutable
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.database.VaultAlbumEntity
import app.focale.core.database.VaultDao
import app.focale.core.database.VaultItemEntity
import app.focale.core.di.IoDispatcher
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Avancement d'une sauvegarde ou d'une restauration. */
@Immutable
data class BackupProgress(
    val stage: Stage,
    val done: Int = 0,
    val total: Int = 0,
    val bytesDone: Long = 0,
    val bytesTotal: Long = 0,
) {
    enum class Stage {
        /** Dérivation de la clé et, à la restauration, vérification du mot de passe. */
        PREPARING,
        TRANSFERRING,
        /** Restauration : ajout au coffre des éléments vérifiés, qui ne s'interrompt pas. */
        FINISHING,
    }

    val fraction: Float
        get() = when {
            bytesTotal > 0 -> (bytesDone.toDouble() / bytesTotal).toFloat().coerceIn(0f, 1f)
            total > 0 -> done.toFloat() / total
            else -> 0f
        }
}

sealed interface ExportResult {
    /** Archive complète : [saved] éléments ; [unreadable] fichiers du coffre illisibles écartés. */
    data class Success(val saved: Int, val unreadable: Int) : ExportResult

    /** [partialFileLeft] : le fichier inachevé n'a pas pu être supprimé. */
    data class Cancelled(val partialFileLeft: Boolean) : ExportResult
    data class Failed(val partialFileLeft: Boolean) : ExportResult
}

sealed interface RestoreResult {
    /** [added] éléments ajoutés ; [duplicates] déjà présents et [unreadable] incomplets, ignorés. */
    data class Success(val added: Int, val duplicates: Int, val unreadable: Int) : RestoreResult
    data object WrongPassword : RestoreResult
    data object Cancelled : RestoreResult
    data class Failed(val reason: Reason) : RestoreResult {
        enum class Reason { NOT_AN_ARCHIVE, UNSUPPORTED, CORRUPTED, NO_SPACE, UNREADABLE }
    }
}

/**
 * Sauvegarde du coffre dans une archive protégée par un mot de passe ([VaultArchive]), et
 * restauration. Tout se fait en flux : aucun média n'est chargé en mémoire ni écrit en
 * clair sur le disque.
 *
 * La restauration est tout ou rien : chaque élément est rechiffré avec la clé de l'appareil
 * dans un dossier d'attente, et n'entre dans le coffre qu'une fois l'archive authentifiée
 * jusqu'à sa fin. Mot de passe faux, archive tronquée ou modifiée, annulation ou erreur :
 * rien n'est importé. Les doublons (même contenu, à l'octet près) sont ignorés. Les albums
 * sont recréés avec leurs éléments, ou rejoints s'il en existe un de même nom et même origine.
 */
@Singleton
class VaultBackupRepository internal constructor(
    private val resolver: ContentResolver,
    private val dao: VaultDao,
    private val crypto: VaultCrypto,
    private val storage: VaultStorage,
    private val prefs: VaultBackupPrefs,
    private val io: CoroutineDispatcher,
    /** Itérations de la dérivation du mot de passe ; réduites dans les tests. */
    private val iterations: Int,
) {

    @Inject
    constructor(
        resolver: ContentResolver,
        dao: VaultDao,
        crypto: VaultCrypto,
        storage: VaultStorage,
        prefs: VaultBackupPrefs,
        @IoDispatcher io: CoroutineDispatcher,
    ) : this(resolver, dao, crypto, storage, prefs, io, VaultArchive.DEFAULT_ITERATIONS)

    /**
     * Une opération à la fois : une restauration abandonnée pendant une lecture bloquante
     * finit de nettoyer le dossier d'attente avant que la suivante ne s'en serve.
     */
    private val mutex = Mutex()

    /**
     * Sauvegarde tout le coffre dans le document [target], créé par le sélecteur système ;
     * le document est supprimé si la sauvegarde n'aboutit pas. [password] est effacé.
     *
     * @param cancelled consulté entre deux morceaux : vrai pour annuler.
     */
    suspend fun export(
        target: Uri,
        password: CharArray,
        cancelled: () -> Boolean = { false },
        onProgress: (BackupProgress) -> Unit = {},
    ): ExportResult = mutex.withLock { exportLocked(target, password, cancelled, onProgress) }

    private suspend fun exportLocked(
        target: Uri,
        password: CharArray,
        cancelled: () -> Boolean,
        onProgress: (BackupProgress) -> Unit,
    ): ExportResult = withContext(io) {
        val result = try {
            val items = dao.observeAll().first()
            val albums = dao.albums()
            onProgress(BackupProgress(BackupProgress.Stage.PREPARING, total = items.size))
            val progress = Progress(items.size, items.sumOf { it.size }, cancelled, coroutineContext.job, onProgress)
            var unreadable = 0
            openOutput(target).use { output ->
                VaultArchive.write(output, password, manifestOf(items, albums), iterations, progress::onBytes) { writer ->
                    progress.start()
                    for (item in items) {
                        progress.countingMedia = true
                        if (!writer.blob { crypto.openDecrypting(storage.file(item.fileName)) }) {
                            // Identifiant seulement : le nom d'un élément du coffre n'a rien à faire dans les journaux.
                            Log.w(TAG, "Élément illisible, non sauvegardé : ${item.id}")
                            unreadable++
                        }
                        progress.countingMedia = false
                        writer.blob { item.thumbFileName?.let { crypto.openDecrypting(storage.file(it)) } }
                        progress.itemDone()
                    }
                }
            }
            ExportResult.Success(saved = items.size - unreadable, unreadable = unreadable)
        } catch (error: CancelledByUser) {
            return@withContext ExportResult.Cancelled(partialFileLeft = !delete(target))
        } catch (error: CancellationException) {
            delete(target)
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Sauvegarde impossible", error)
            return@withContext ExportResult.Failed(partialFileLeft = !delete(target))
        } finally {
            password.fill('\u0000')
        }
        // Archive complète : noter sa date ne doit plus pouvoir la compromettre.
        withContext(NonCancellable) {
            runCatching { prefs.setLastBackupAt(System.currentTimeMillis()) }
                .onFailure { Log.w(TAG, "Date de sauvegarde non enregistrée", it) }
        }
        result
    }

    /** Retire un document créé pour une sauvegarde qui n'aura pas lieu. */
    suspend fun discard(target: Uri) {
        withContext(io) { delete(target) }
    }

    /**
     * Restaure l'archive [source] dans le coffre ; [password] est effacé. Les éléments ne
     * sont ajoutés qu'une fois toute l'archive vérifiée. Un coffre vide restauré en entier
     * prend la date de l'archive comme dernière sauvegarde.
     *
     * @param cancelled consulté entre deux morceaux : vrai pour annuler.
     * @param onProgress [BackupProgress.Stage.TRANSFERRING] annonce un mot de passe accepté.
     */
    suspend fun restore(
        source: Uri,
        password: CharArray,
        cancelled: () -> Boolean = { false },
        onProgress: (BackupProgress) -> Unit = {},
    ): RestoreResult = mutex.withLock { restoreLocked(source, password, cancelled, onProgress) }

    private suspend fun restoreLocked(
        source: Uri,
        password: CharArray,
        cancelled: () -> Boolean,
        onProgress: (BackupProgress) -> Unit,
    ): RestoreResult = withContext(io) {
        storage.clearStaging()
        try {
            onProgress(BackupProgress(BackupProgress.Stage.PREPARING))
            val input = resolver.openInputStream(source) ?: throw FileNotFoundException("Lecture impossible : $source")
            input.buffered(BUFFER).use { stream ->
                restoreFrom(stream, password, Progress.Factory(cancelled, coroutineContext.job, onProgress))
            }
        } catch (error: VaultArchiveException) {
            when (error.reason) {
                VaultArchiveException.Reason.WRONG_PASSWORD -> RestoreResult.WrongPassword
                VaultArchiveException.Reason.NOT_AN_ARCHIVE -> RestoreResult.Failed(RestoreResult.Failed.Reason.NOT_AN_ARCHIVE)
                VaultArchiveException.Reason.UNSUPPORTED -> RestoreResult.Failed(RestoreResult.Failed.Reason.UNSUPPORTED)
                VaultArchiveException.Reason.CORRUPTED -> {
                    Log.w(TAG, "Archive refusée", error)
                    RestoreResult.Failed(RestoreResult.Failed.Reason.CORRUPTED)
                }
            }
        } catch (error: CancelledByUser) {
            RestoreResult.Cancelled
        } catch (error: NoSpaceLeft) {
            RestoreResult.Failed(RestoreResult.Failed.Reason.NO_SPACE)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Restauration impossible", error)
            RestoreResult.Failed(RestoreResult.Failed.Reason.UNREADABLE)
        } finally {
            password.fill('\u0000')
            // Rien à effacer après un ajout réussi ; sinon, restes d'une restauration refusée.
            storage.clearStaging()
        }
    }

    private suspend fun restoreFrom(input: InputStream, password: CharArray, progresses: Progress.Factory): RestoreResult {
        val reader = VaultArchive.open(input, password)
        password.fill('\u0000')
        val manifest = reader.manifest
        val progress = progresses.create(manifest.items.size, manifest.totalBytes)
        reader.onBytes = progress::onBytes
        progress.start()
        val existing = dao.observeAll().first()
        val known = KnownContent(existing, progress)
        if (!storage.hasRoomFor(known.newBytes(manifest.items))) throw NoSpaceLeft()

        val staged = ArrayList<Staged.Added>(manifest.items.size)
        var duplicates = 0
        var unreadable = 0
        for (entry in manifest.items) {
            when (val result = stage(entry, reader, known, progress)) {
                is Staged.Added -> staged += result
                Staged.Duplicate -> duplicates++
                Staged.Unreadable -> unreadable++
            }
            progress.itemDone()
        }
        // Fin authentifiée : sans elle, une archive tronquée passerait pour complète.
        reader.finish()
        progress.finishing()
        commit(staged, manifest)
        // Coffre vide restauré en entier (nouveau téléphone) : son contenu est sauvegardé,
        // à la date de l'archive ; le rappel « aucune sauvegarde » serait trompeur.
        if (existing.isEmpty() && staged.isNotEmpty() && unreadable == 0) {
            withContext(NonCancellable) {
                runCatching {
                    val last = prefs.lastBackupAt.first()
                    if (last == null || last < manifest.createdAt) prefs.setLastBackupAt(manifest.createdAt)
                }.onFailure { Log.w(TAG, "Date de sauvegarde non enregistrée", it) }
            }
        }
        return RestoreResult.Success(added = staged.size, duplicates = duplicates, unreadable = unreadable)
    }

    private sealed interface Staged {
        /** [album] : album de l'élément dans l'archive ([ArchiveAlbum.id]). */
        data class Added(val entity: VaultItemEntity, val album: Long?) : Staged
        data object Duplicate : Staged
        data object Unreadable : Staged
    }

    /** Rechiffre dans le dossier d'attente l'élément suivant de l'archive. */
    private fun stage(entry: ArchiveEntry, reader: VaultArchiveReader, known: KnownContent, progress: Progress): Staged {
        if (!storage.hasRoomFor(entry.size)) throw NoSpaceLeft()
        val base = UUID.randomUUID().toString()
        val mediaFile = storage.stagingFile(base + VaultStorage.MEDIA_SUFFIX)
        val digest = MessageDigest.getInstance("SHA-256")
        val media = reader.nextBlob()
        progress.countingMedia = true
        val size = crypto.encrypt(DigestInputStream(media, digest), mediaFile)
        progress.countingMedia = false
        // Toujours lue ensuite, même ignorée : le blob suivant est la miniature.
        val thumbnail = reader.nextBlob()
        if (!media.complete || size <= 0) {
            mediaFile.delete()
            return Staged.Unreadable
        }
        val hash = digest.digest()
        if (known.contains(size, hash)) {
            mediaFile.delete()
            return Staged.Duplicate
        }
        known.add(size, hash)
        val thumbFile = storage.stagingFile(base + VaultStorage.THUMB_SUFFIX)
        val hasThumbnail = entry.hasThumbnail && crypto.encrypt(thumbnail, thumbFile) > 0 && thumbnail.complete
        if (!hasThumbnail) thumbFile.delete()
        return Staged.Added(
            VaultItemEntity(
                displayName = entry.name,
                mimeType = entry.mimeType,
                mediaType = if (entry.video) MediaQueryBuilder.MEDIA_TYPE_VIDEO else MediaQueryBuilder.MEDIA_TYPE_IMAGE,
                originalPath = entry.originalPath,
                dateTaken = entry.dateTaken,
                size = size,
                width = entry.width,
                height = entry.height,
                duration = entry.durationMs,
                fileName = mediaFile.name,
                thumbFileName = thumbFile.name.takeIf { hasThumbnail },
                addedAt = entry.addedAt,
            ),
            album = entry.album,
        )
    }

    /**
     * Ajoute au coffre les éléments vérifiés : fichiers déplacés depuis le dossier d'attente
     * (même volume, simple renommage), puis entrées et albums insérés en une seule transaction.
     */
    private suspend fun commit(staged: List<Staged.Added>, manifest: ArchiveManifest) = withContext(NonCancellable) {
        val moved = ArrayList<File>()
        try {
            for (added in staged) {
                for (name in listOfNotNull(added.entity.fileName, added.entity.thumbFileName)) {
                    val target = storage.file(name)
                    if (!storage.stagingFile(name).renameTo(target)) throw IOException("Déplacement impossible : $name")
                    moved += target
                }
            }
            val groups = groupsOf(staged, manifest)
            if (groups.isNotEmpty()) dao.insertRestored(groups)
        } catch (error: Throwable) {
            moved.forEach(File::delete)
            throw error
        }
    }

    /**
     * Éléments ajoutés, par album. Un album de l'archive n'est recréé que s'il reçoit un élément,
     * ou s'il était vide dans le coffre sauvegardé ; un élément dont l'album manque reste hors album.
     */
    private fun groupsOf(staged: List<Staged.Added>, manifest: ArchiveManifest): Map<VaultAlbumEntity?, List<VaultItemEntity>> {
        val albums = manifest.albums.associate { it.id to it.toEntity() }
        val used = manifest.items.mapNotNullTo(HashSet()) { it.album }
        val groups = LinkedHashMap<VaultAlbumEntity?, MutableList<VaultItemEntity>>()
        manifest.albums.filter { it.id !in used }.forEach { groups[albums.getValue(it.id)] = ArrayList() }
        for (added in staged) groups.getOrPut(added.album?.let(albums::get)) { ArrayList() } += added.entity
        return groups
    }

    private fun ArchiveAlbum.toEntity() = VaultAlbumEntity(
        id = id,
        name = name,
        sourceBucketId = sourceBucketId,
        sourcePath = sourcePath,
        createdAt = createdAt,
        lastImportAt = lastImportAt,
    )

    /**
     * Contenus déjà présents, par empreinte SHA-256 du média en clair. Un élément du coffre
     * n'est déchiffré pour en calculer l'empreinte que s'il a la taille d'un élément
     * restauré : la plupart ne sont jamais relus.
     */
    private inner class KnownContent(existing: List<VaultItemEntity>, private val progress: Progress) {
        private val unhashed = existing.groupByTo(HashMap<Long, MutableList<VaultItemEntity>>()) { it.size }
        private val hashes = HashMap<Long, MutableList<ByteArray>>()

        /** Place à prévoir : éléments dont aucun contenu présent n'a la taille. */
        fun newBytes(entries: List<ArchiveEntry>): Long = entries.filter { it.size !in unhashed }.sumOf { it.size }

        fun contains(size: Long, hash: ByteArray): Boolean {
            unhashed.remove(size)?.forEach { entity -> fingerprint(entity)?.let { add(size, it) } }
            return hashes[size]?.any { MessageDigest.isEqual(it, hash) } == true
        }

        fun add(size: Long, hash: ByteArray) {
            hashes.getOrPut(size) { ArrayList() } += hash
        }

        private fun fingerprint(entity: VaultItemEntity): ByteArray? = try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(BUFFER)
            crypto.openDecrypting(storage.file(entity.fileName)).use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    progress.checkpoint()
                }
            }
            digest.digest()
        } catch (error: IOException) {
            Log.w(TAG, "Empreinte impossible : ${entity.id}", error)
            null
        } catch (error: GeneralSecurityException) {
            Log.w(TAG, "Empreinte impossible : ${entity.id}", error)
            null
        }
    }

    /** Flux d'écriture du document, tronqué (« wt ») quand le fournisseur le permet. */
    private fun openOutput(target: Uri): OutputStream =
        runCatching { resolver.openOutputStream(target, "wt") }.getOrNull()
            ?: resolver.openOutputStream(target, "w")
            ?: throw FileNotFoundException("Écriture impossible : $target")

    /** Supprime un fichier de sauvegarde inachevé ; faux s'il est resté en place. */
    private fun delete(target: Uri): Boolean = try {
        if (target.scheme == ContentResolver.SCHEME_FILE) {
            val file = File(requireNotNull(target.path))
            !file.exists() || file.delete()
        } else {
            DocumentsContract.deleteDocument(resolver, target)
        }
    } catch (error: Exception) {
        Log.w(TAG, "Fichier inachevé non supprimé : $target", error)
        false
    }

    private fun manifestOf(items: List<VaultItemEntity>, albums: List<VaultAlbumEntity>) = ArchiveManifest(
        createdAt = System.currentTimeMillis(),
        albums = albums.map { album ->
            ArchiveAlbum(
                id = album.id,
                name = album.name,
                createdAt = album.createdAt,
                sourceBucketId = album.sourceBucketId,
                sourcePath = album.sourcePath,
                lastImportAt = album.lastImportAt,
            )
        },
        items = items.map { item ->
            ArchiveEntry(
                name = item.displayName,
                mimeType = item.mimeType,
                video = item.mediaType == MediaQueryBuilder.MEDIA_TYPE_VIDEO,
                originalPath = item.originalPath,
                dateTaken = item.dateTaken,
                addedAt = item.addedAt,
                size = item.size,
                width = item.width,
                height = item.height,
                durationMs = item.duration,
                hasThumbnail = item.thumbFileName != null,
                album = item.albumId,
            )
        },
    )

    /**
     * Points d'annulation et avancement, publié au plus toutes les [UPDATE_INTERVAL_NS]
     * (un appel par morceau de 256 Kio serait inutilement fréquent).
     */
    private class Progress(
        private val total: Int,
        private val bytesTotal: Long,
        private val cancelled: () -> Boolean,
        private val job: Job,
        private val onProgress: (BackupProgress) -> Unit,
    ) {
        /** Seuls les médias comptent : la taille des miniatures n'est pas connue d'avance. */
        var countingMedia = false
        private var done = 0
        private var bytes = 0L
        private var publishedAt = 0L

        fun checkpoint() {
            if (cancelled()) throw CancelledByUser()
            job.ensureActive()
        }

        fun onBytes(count: Int) {
            checkpoint()
            if (countingMedia) bytes += count
            publish(force = false)
        }

        fun start() = publish(force = true)

        fun itemDone() {
            done++
            publish(force = done == total)
        }

        fun finishing() {
            onProgress(BackupProgress(BackupProgress.Stage.FINISHING, done, total, bytesTotal, bytesTotal))
        }

        private fun publish(force: Boolean) {
            val now = System.nanoTime()
            if (!force && now - publishedAt < UPDATE_INTERVAL_NS) return
            publishedAt = now
            onProgress(BackupProgress(BackupProgress.Stage.TRANSFERRING, done, total, bytes, bytesTotal))
        }

        /** Totaux connus seulement une fois le manifeste lu. */
        class Factory(
            private val cancelled: () -> Boolean,
            private val job: Job,
            private val onProgress: (BackupProgress) -> Unit,
        ) {
            fun create(total: Int, bytesTotal: Long) = Progress(total, bytesTotal, cancelled, job, onProgress)
        }
    }

    private companion object {
        const val TAG = "VaultBackup"
        const val BUFFER = 256 * 1024
        const val UPDATE_INTERVAL_NS = 100_000_000L
    }
}

/** Annulation demandée par l'utilisateur ; distincte de celle de la coroutine. */
private class CancelledByUser : RuntimeException()

private class NoSpaceLeft : RuntimeException()
