package app.focale.data

import android.app.PendingIntent
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.annotation.RequiresApi
import app.focale.core.database.FavoriteDao
import app.focale.core.database.FavoriteEntity
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.database.TrashDao
import app.focale.core.database.TrashItemEntity
import app.focale.core.di.IoDispatcher
import app.focale.core.mediastore.ConsentRequester
import app.focale.core.mediastore.MediaSyncManager
import app.focale.core.model.FolderTarget
import app.focale.core.model.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.channels.FileChannel
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Résultat d'une opération sur des médias. */
sealed interface OperationResult {
    data class Success(val count: Int) : OperationResult
    data class Partial(val succeeded: Int, val failed: Int) : OperationResult
    data object Cancelled : OperationResult
    data class Failure(val error: Throwable? = null) : OperationResult
}

/**
 * Opérations sur les fichiers : corbeille, suppression, copie, déplacement, renommage.
 *
 * Toute modification d'un média appartenant à une autre application exige le
 * consentement de l'utilisateur, obtenu via [ConsentRequester] :
 * - Android 11+ : requêtes groupées MediaStore (`createTrashRequest`,
 *   `createDeleteRequest`, `createWriteRequest`) ; aucune confirmation si l'utilisateur a
 *   accordé l'accès spécial « Gestion des médias » ;
 * - Android 10 : `RecoverableSecurityException`, élément par élément.
 */
@Singleton
class MediaOperations @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: ContentResolver,
    private val consent: ConsentRequester,
    private val syncManager: MediaSyncManager,
    private val trashDao: TrashDao,
    private val favoriteDao: FavoriteDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /** Corbeille système MediaStore disponible (Android 11+). */
    val hasSystemTrash: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    private val legacyTrashDir: File by lazy { File(context.filesDir, "trash").apply { mkdirs() } }

    // ── Corbeille ──────────────────────────────────────────────────────────────────

    suspend fun trash(items: List<MediaItem>): OperationResult = withContext(io) {
        if (items.isEmpty()) return@withContext OperationResult.Success(0)
        val result = if (hasSystemTrash) {
            batchRequest(items) { uris -> MediaStore.createTrashRequest(resolver, uris, true) }
        } else {
            trashLegacy(items)
        }
        syncManager.sync()
        result
    }

    /** Restaure des médias de la corbeille système (Android 11+). */
    suspend fun restore(items: List<MediaItem>): OperationResult = withContext(io) {
        if (!hasSystemTrash || items.isEmpty()) return@withContext OperationResult.Success(0)
        val result = batchRequest(items) { uris -> MediaStore.createTrashRequest(resolver, uris, false) }
        syncManager.sync()
        result
    }

    /** Suppression définitive (avec confirmation système). */
    suspend fun deleteForever(items: List<MediaItem>): OperationResult = withContext(io) {
        if (items.isEmpty()) return@withContext OperationResult.Success(0)
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            batchRequest(items) { uris -> MediaStore.createDeleteRequest(resolver, uris) }
        } else {
            sequential(items) { deleteWithRecovery(it.uri) }
        }
        syncManager.sync()
        result
    }

    // ── Corbeille applicative (Android 10) ─────────────────────────────────────────

    private suspend fun trashLegacy(items: List<MediaItem>): OperationResult {
        val favorites = favoriteDao.favoritesAmong(items.map { it.id }).toSet()
        return sequential(items) { item ->
            val extension = item.displayName.substringAfterLast('.', "").takeIf { it.isNotBlank() }
                ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(item.mimeType).orEmpty()
            val file = File(legacyTrashDir, UUID.randomUUID().toString() + if (extension.isEmpty()) "" else ".$extension")
            resolver.openInputStream(item.uri)?.use { input ->
                file.outputStream().use { input.copyTo(it, COPY_BUFFER) }
            } ?: return@sequential false
            val entryId = trashDao.insert(
                TrashItemEntity(
                    displayName = item.displayName,
                    relativePath = item.relativePath,
                    mimeType = item.mimeType,
                    mediaType = if (item.isVideo) MediaQueryBuilder.MEDIA_TYPE_VIDEO else MediaQueryBuilder.MEDIA_TYPE_IMAGE,
                    dateTaken = item.dateTaken,
                    size = item.size,
                    width = item.width,
                    height = item.height,
                    duration = item.durationMs,
                    fileName = file.name,
                    wasFavorite = item.id in favorites,
                    trashedAt = System.currentTimeMillis(),
                ),
            )
            val deleted = deleteWithRecovery(item.uri)
            if (!deleted) {
                file.delete()
                trashDao.delete(listOf(entryId))
            }
            deleted
        }
    }

    fun legacyTrashFile(entry: TrashItemEntity): File = File(legacyTrashDir, entry.fileName)

    suspend fun restoreLegacy(entries: List<TrashItemEntity>): OperationResult = withContext(io) {
        var restored = 0
        for (entry in entries) {
            val file = legacyTrashFile(entry)
            val uri = runCatching {
                insertAndWrite(
                    displayName = entry.displayName,
                    mimeType = entry.mimeType,
                    relativePath = entry.relativePath,
                    dateTaken = entry.dateTaken,
                    isVideo = entry.mediaType == MediaQueryBuilder.MEDIA_TYPE_VIDEO,
                ) { output -> file.inputStream().use { it.copyTo(output, COPY_BUFFER) } }
            }.onFailure { Log.w(TAG, "Restauration impossible : ${entry.displayName}", it) }.getOrNull()
                ?: continue
            if (entry.wasFavorite) {
                favoriteDao.insert(listOf(FavoriteEntity(ContentUris.parseId(uri), System.currentTimeMillis())))
            }
            file.delete()
            trashDao.delete(listOf(entry.id))
            restored++
        }
        syncManager.sync()
        if (restored == entries.size) OperationResult.Success(restored) else OperationResult.Partial(restored, entries.size - restored)
    }

    suspend fun deleteLegacy(entries: List<TrashItemEntity>): OperationResult = withContext(io) {
        entries.forEach { legacyTrashFile(it).delete() }
        entries.map { it.id }.chunked(MediaRepository.MAX_SQL_ARGS).forEach { trashDao.delete(it) }
        OperationResult.Success(entries.size)
    }

    /** Supprime définitivement les éléments de la corbeille applicative de plus de 30 jours. */
    suspend fun purgeLegacyTrash(): Int = withContext(io) {
        val expired = trashDao.expired(System.currentTimeMillis() - TRASH_RETENTION_MS)
        deleteLegacy(expired)
        expired.size
    }

    // ── Copier / déplacer / renommer ───────────────────────────────────────────────

    suspend fun copyTo(items: List<MediaItem>, target: FolderTarget): OperationResult = withContext(io) {
        val result = sequential(items, stopOnRefusal = false) { item ->
            insertAndWrite(
                displayName = item.displayName,
                mimeType = item.mimeType,
                relativePath = target.relativePath,
                dateTaken = item.dateTaken,
                isVideo = item.isVideo,
            ) { output ->
                resolver.openInputStream(item.uri)?.use { it.copyTo(output, COPY_BUFFER) }
                    ?: error("Lecture impossible : ${item.uri}")
            }
            true
        }
        syncManager.sync()
        result
    }

    suspend fun moveTo(items: List<MediaItem>, target: FolderTarget): OperationResult =
        update(items) { ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath) } }

    /** Renomme un média en conservant son extension. */
    suspend fun rename(item: MediaItem, newBaseName: String): OperationResult {
        val extension = item.displayName.substringAfterLast('.', "")
        val clean = newBaseName.trim().replace(Regex("""[\\/:*?"<>|]"""), "_")
        if (clean.isEmpty()) return OperationResult.Failure()
        val name = if (extension.isEmpty()) clean else "$clean.$extension"
        return update(listOf(item)) { ContentValues().apply { put(MediaStore.MediaColumns.DISPLAY_NAME, name) } }
    }

    /**
     * Remplace le contenu d'un média existant (édition « remplacer l'original »). L'original est
     * d'abord copié à part : une écriture qui échoue en cours de route le remet en place au lieu
     * de laisser un fichier tronqué.
     */
    suspend fun overwrite(item: MediaItem, write: (java.io.OutputStream) -> Unit): OperationResult = writeWithConsent(item) {
        val backup = java.io.File.createTempFile("original", null)
        try {
            resolver.openInputStream(item.uri)?.use { input -> backup.outputStream().use { input.copyTo(it) } }
                ?: error("Lecture impossible")
            try {
                resolver.openOutputStream(item.uri, "wt")?.use(write) ?: error("Écriture impossible")
            } catch (error: Exception) {
                runCatching { resolver.openOutputStream(item.uri, "wt")?.use { out -> backup.inputStream().use { it.copyTo(out) } } }
                    .onFailure { Log.e(TAG, "Original non rétabli après un échec d'écriture", it) }
                throw error
            }
        } finally {
            backup.delete()
        }
    }

    /**
     * Modifie un média sur place, sans le tronquer (quelques octets à une position donnée),
     * après le même consentement que [overwrite].
     */
    suspend fun patch(item: MediaItem, edit: (FileChannel) -> Unit): OperationResult = writeWithConsent(item) {
        val descriptor = resolver.openFileDescriptor(item.uri, "rw") ?: error("Écriture impossible")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { edit(it.channel) }
    }

    private suspend fun writeWithConsent(item: MediaItem, write: () -> Unit): OperationResult = withContext(io) {
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            consent.request(MediaStore.createWriteRequest(resolver, listOf(item.uri)).intentSender)
        } else {
            ensureLegacyWriteAccess(item.uri)
        }
        if (!granted) return@withContext OperationResult.Cancelled
        val result = runCatching {
            write()
            OperationResult.Success(1)
        }.getOrElse { OperationResult.Failure(it) }
        syncManager.sync()
        result
    }

    private suspend fun update(items: List<MediaItem>, values: (MediaItem) -> ContentValues): OperationResult =
        withContext(io) {
            if (items.isEmpty()) return@withContext OperationResult.Success(0)
            val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                var granted = true
                for (chunk in items.chunked(REQUEST_CHUNK)) {
                    if (!consent.request(MediaStore.createWriteRequest(resolver, chunk.map { it.uri }).intentSender)) {
                        granted = false
                        break
                    }
                }
                if (!granted) {
                    OperationResult.Cancelled
                } else {
                    sequential(items, stopOnRefusal = false) { resolver.update(it.uri, values(it), null, null) > 0 }
                }
            } else {
                sequential(items) { item -> updateWithRecovery(item.uri, values(item)) }
            }
            syncManager.sync()
            result
        }

    // ── Outils ─────────────────────────────────────────────────────────────────────

    /**
     * Crée une entrée MediaStore « en attente », écrit son contenu puis la publie.
     * En cas d'échec, l'entrée incomplète est supprimée.
     */
    fun insertAndWrite(
        displayName: String,
        mimeType: String,
        relativePath: String,
        dateTaken: Long,
        isVideo: Boolean,
        write: (java.io.OutputStream) -> Unit,
    ): Uri {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.DATE_TAKEN, dateTaken)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = runCatching { resolver.insert(collectionFor(relativePath, isVideo), values) }
            .recoverCatching { error ->
                // Dossier racine non autorisé pour ce type (ex. vidéo dans Pictures/ sous Android 10) :
                // on replie sur le dossier standard du type, en conservant le sous-dossier.
                if (error !is IllegalArgumentException) throw error
                val fallback = (if (isVideo) "Movies/" else "Pictures/") + relativePath.substringAfter('/', "")
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, fallback)
                resolver.insert(collectionFor(fallback, isVideo), values)
            }
            .getOrThrow()
            ?: error("Insertion MediaStore refusée pour $relativePath")
        try {
            resolver.openOutputStream(uri, "w")?.use(write) ?: error("Écriture impossible")
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }

    private fun collectionFor(relativePath: String, isVideo: Boolean): Uri {
        val volume = MediaStore.VOLUME_EXTERNAL_PRIMARY
        return when {
            relativePath.startsWith("Download", ignoreCase = true) -> MediaStore.Downloads.getContentUri(volume)
            isVideo -> MediaStore.Video.Media.getContentUri(volume)
            else -> MediaStore.Images.Media.getContentUri(volume)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private suspend fun batchRequest(items: List<MediaItem>, build: (List<Uri>) -> PendingIntent): OperationResult {
        var done = 0
        for (chunk in items.chunked(REQUEST_CHUNK)) {
            val granted = runCatching { consent.request(build(chunk.map { it.uri }).intentSender) }
                .onFailure { Log.w(TAG, "Requête MediaStore refusée", it) }
                .getOrDefault(false)
            if (!granted) return if (done == 0) OperationResult.Cancelled else OperationResult.Partial(done, items.size - done)
            done += chunk.size
        }
        return OperationResult.Success(done)
    }

    /**
     * Applique [action] à chaque élément. Si [stopOnRefusal], un refus de l'utilisateur
     * interrompt la suite (inutile de redemander pour chaque élément).
     */
    private suspend fun sequential(
        items: List<MediaItem>,
        stopOnRefusal: Boolean = true,
        action: suspend (MediaItem) -> Boolean,
    ): OperationResult {
        var done = 0
        var failed = 0
        for (item in items) {
            val ok = try {
                action(item)
            } catch (cancelled: ConsentRefused) {
                if (stopOnRefusal) break else false
            } catch (error: Exception) {
                Log.w(TAG, "Opération impossible sur ${item.displayName}", error)
                false
            }
            if (ok) done++ else failed++
        }
        return when {
            done == items.size -> OperationResult.Success(done)
            done == 0 && failed == 0 -> OperationResult.Cancelled
            done == 0 -> OperationResult.Failure()
            else -> OperationResult.Partial(done, items.size - done)
        }
    }

    /** Android 10 : supprime, en demandant l'autorisation si le fichier appartient à une autre app. */
    private suspend fun deleteWithRecovery(uri: Uri): Boolean = try {
        resolver.delete(uri, null, null) > 0
    } catch (security: RecoverableSecurityException) {
        if (!consent.request(security.userAction.actionIntent.intentSender)) throw ConsentRefused()
        resolver.delete(uri, null, null) > 0
    }

    private suspend fun updateWithRecovery(uri: Uri, values: ContentValues): Boolean = try {
        resolver.update(uri, values, null, null) > 0
    } catch (security: RecoverableSecurityException) {
        if (!consent.request(security.userAction.actionIntent.intentSender)) throw ConsentRefused()
        resolver.update(uri, values, null, null) > 0
    }

    /** Android 10 : obtient l'accès en écriture à un média d'une autre application (« rw » ne tronque jamais). */
    private suspend fun ensureLegacyWriteAccess(uri: Uri): Boolean = try {
        resolver.openFileDescriptor(uri, "rw")?.close()
        true
    } catch (security: RecoverableSecurityException) {
        consent.request(security.userAction.actionIntent.intentSender)
    }

    private class ConsentRefused : Exception()

    companion object {
        private const val TAG = "MediaOperations"
        private const val COPY_BUFFER = 256 * 1024
        private const val REQUEST_CHUNK = 500
        val TRASH_RETENTION_MS: Long = TimeUnit.DAYS.toMillis(30)
    }
}
