package app.focale.vault

import android.content.ContentResolver
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.database.VaultAlbumEntity
import app.focale.core.database.VaultDao
import app.focale.core.database.VaultItemEntity
import app.focale.core.di.IoDispatcher
import app.focale.core.mediastore.MediaSyncManager
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaQuery
import app.focale.data.MediaOperations
import app.focale.data.MediaRepository
import app.focale.data.OperationResult
import app.focale.data.toModel
import app.focale.widget.WidgetRefresher
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Entrées et sorties du coffre-fort.
 *
 * Import : chaque média est chiffré avec sa miniature, la copie est relue
 * intégralement, puis l'original est supprimé de MediaStore. Si un original n'a pas
 * pu être supprimé (refus de l'utilisateur, erreur), sa copie chiffrée est annulée :
 * un média n'est jamais à la fois dans la galerie et dans le coffre, ni perdu.
 *
 * Albums : de la métadonnée seulement. Remplir, compléter ou réorganiser un album ne chiffre
 * que les médias qui entrent dans le coffre ; ceux qui y sont déjà ne sont ni relus ni
 * réécrits. Rien de tout cela n'exige de déverrouiller le coffre (clé dans le Keystore).
 */
@Singleton
class VaultRepository @Inject constructor(
    private val resolver: ContentResolver,
    private val dao: VaultDao,
    private val crypto: VaultCrypto,
    private val storage: VaultStorage,
    private val operations: MediaOperations,
    private val syncManager: MediaSyncManager,
    private val media: MediaRepository,
    private val widgets: WidgetRefresher,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Tout le contenu du coffre, ou celui de l'album [albumId]. */
    fun observeItems(albumId: Long? = null): Flow<List<VaultItem>> =
        (if (albumId == null) dao.observeAll() else dao.observeInAlbum(albumId)).map { list -> list.map { it.toModel() } }

    fun observeAlbums(): Flow<List<VaultAlbum>> = dao.observeAlbums().map { rows -> rows.map { it.toModel() } }

    fun observeAlbum(id: Long): Flow<VaultAlbum?> = dao.observeAlbum(id).map { it?.toModel() }

    /** Médias arrivés dans le dossier d'origine de l'album [id] depuis son dernier import. */
    fun observeNewInSource(id: Long): Flow<List<MediaItem>> =
        dao.observeNewInSource(id).map { rows -> rows.map { it.toModel() } }

    suspend fun createAlbum(name: String): Long = withContext(io) {
        dao.insertAlbum(VaultAlbumEntity(name = VaultAlbumNames.of(name), createdAt = System.currentTimeMillis()))
    }

    suspend fun renameAlbum(id: Long, name: String) = withContext(io) { dao.renameAlbum(id, VaultAlbumNames.of(name)) }

    /** Supprime l'album ; ses éléments restent dans le coffre, hors album. */
    suspend fun deleteAlbum(id: Long) = withContext(io) { dao.deleteAlbum(id) }

    /** Range [items] dans [destination] et renvoie l'album retenu : aucun fichier n'est touché. */
    suspend fun moveToAlbum(items: List<VaultItem>, destination: VaultDestination): Long? = withContext(io) {
        val albumId = when (destination) {
            VaultDestination.None -> null
            is VaultDestination.Album -> destination.id
            is VaultDestination.NewAlbum -> createAlbum(destination.name)
        }
        items.map { it.id }.chunked(MediaRepository.MAX_SQL_ARGS).forEach { dao.setAlbum(it, albumId) }
        albumId
    }

    /**
     * Déplace [items] dans le coffre, dans l'album [destination]. Non annulable : une fois la
     * suppression des originaux demandée, son issue doit être constatée pour rester cohérent.
     * Un nouvel album qui ne reçoit rien est retiré.
     */
    suspend fun import(
        items: List<MediaItem>,
        destination: VaultDestination = VaultDestination.None,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): OperationResult = withContext(io + NonCancellable) {
        if (items.isEmpty()) return@withContext OperationResult.Success(0)
        when (destination) {
            VaultDestination.None -> importInto(items, albumId = null, onProgress)
            is VaultDestination.Album -> importInto(items, destination.id, onProgress)
            is VaultDestination.NewAlbum -> {
                val albumId = createAlbum(destination.name)
                importInto(items, albumId, onProgress).also { if (it.moved == 0) dao.deleteAlbum(albumId) }
            }
        }
    }

    /**
     * Déplace tout le dossier [bucketId] dans son album du coffre : celui déjà lié à ce dossier,
     * sinon un nouvel album nommé [name]. Les médias qui y arriveront ensuite seront proposés
     * par [observeNewInSource].
     */
    suspend fun importFolder(
        bucketId: Long,
        name: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): OperationResult = withContext(io + NonCancellable) {
        val startedAt = System.currentTimeMillis()
        syncManager.sync()
        val items = media.list(MediaQuery(bucketId = bucketId))
        if (items.isEmpty()) return@withContext OperationResult.Success(0)
        val existing = dao.albumOfSource(bucketId)
        val albumId = existing?.id ?: dao.insertAlbum(
            VaultAlbumEntity(
                name = VaultAlbumNames.of(name),
                sourceBucketId = bucketId,
                sourcePath = items.first().relativePath,
                createdAt = startedAt,
            ),
        )
        val result = importInto(items, albumId, onProgress)
        when {
            result.moved > 0 -> dao.setLastImport(albumId, lastImportAfter(items, startedAt))
            existing == null -> dao.deleteAlbum(albumId)
        }
        result
    }

    /** Déplace dans l'album [albumId] les médias arrivés dans son dossier d'origine depuis le dernier import. */
    suspend fun importNewFromSource(
        albumId: Long,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): OperationResult = withContext(io + NonCancellable) {
        val startedAt = System.currentTimeMillis()
        syncManager.sync()
        val items = dao.newInSource(albumId).map { it.toModel() }
        if (items.isEmpty()) return@withContext OperationResult.Success(0)
        importInto(items, albumId, onProgress).also { if (it.moved > 0) dao.setLastImport(albumId, lastImportAfter(items, startedAt)) }
    }

    /**
     * Date du dernier import d'un dossier : [startedAt], ou l'arrivée du plus ancien original
     * resté dans MediaStore (refus, échec de chiffrement) pour que la bannière le repropose.
     */
    private suspend fun lastImportAfter(items: List<MediaItem>, startedAt: Long): Long {
        val remaining = items.filter(::stillExists).map { it.id }
        if (remaining.isEmpty()) return startedAt
        val oldest = remaining.chunked(MediaRepository.MAX_SQL_ARGS).mapNotNull { dao.oldestDateAdded(it) }.minOrNull()
        return minOf(startedAt, oldest ?: startedAt)
    }

    /** Sort tout l'album du coffre (restauration dans les dossiers d'origine) ; vidé, il est supprimé. */
    suspend fun restoreAlbum(id: Long): OperationResult = withContext(io + NonCancellable) {
        val result = restore(dao.inAlbum(id).map { it.toModel() })
        if (dao.inAlbum(id).isEmpty()) dao.deleteAlbum(id)
        result
    }

    /** Restaure des éléments dans leur dossier d'origine, puis efface leur copie chiffrée. */
    suspend fun restore(items: List<VaultItem>): OperationResult = withContext(io + NonCancellable) {
        if (items.isEmpty()) return@withContext OperationResult.Success(0)
        var restored = 0
        for (item in items) {
            val written = runCatching {
                operations.insertAndWrite(
                    displayName = item.displayName,
                    mimeType = item.mimeType,
                    relativePath = item.originalPath.ifBlank { if (item.isVideo) "Movies/" else "Pictures/" },
                    dateTaken = item.dateTaken,
                    isVideo = item.isVideo,
                ) { output ->
                    crypto.openDecrypting(storage.file(item.fileName)).use { it.copyTo(output, BUFFER) }
                }
            }.onFailure { Log.w(TAG, "Restauration impossible : ${item.displayName}", it) }
            if (written.isSuccess) {
                deleteFiles(item.fileName, item.thumbFileName)
                dao.delete(listOf(item.id))
                restored++
            }
        }
        syncManager.sync()
        when (restored) {
            items.size -> OperationResult.Success(restored)
            0 -> OperationResult.Failure()
            else -> OperationResult.Partial(restored, items.size - restored)
        }
    }

    /** Suppression définitive : les fichiers chiffrés sont effacés. */
    suspend fun delete(items: List<VaultItem>): OperationResult = withContext(io) {
        items.forEach { deleteFiles(it.fileName, it.thumbFileName) }
        items.map { it.id }.chunked(MediaRepository.MAX_SQL_ARGS).forEach { dao.delete(it) }
        OperationResult.Success(items.size)
    }

    private suspend fun importInto(
        items: List<MediaItem>,
        albumId: Long?,
        onProgress: (done: Int, total: Int) -> Unit,
    ): OperationResult {
        val imported = ArrayList<Pair<MediaItem, VaultItemEntity>>(items.size)
        items.forEachIndexed { index, item ->
            runCatching { encrypt(item, albumId) }
                .onSuccess { imported += item to it }
                .onFailure { Log.w(TAG, "Chiffrement impossible : ${item.displayName}", it) }
            onProgress(index + 1, items.size)
        }
        if (imported.isEmpty()) return OperationResult.Failure()

        val deletion = operations.deleteForever(imported.map { it.first })
        val kept = imported.filter { (item, _) -> stillExists(item) }
        kept.forEach { (_, entity) -> discard(entity) }
        val moved = imported.size - kept.size
        if (moved > 0) widgets.refresh()
        return when {
            moved == items.size -> OperationResult.Success(moved)
            moved > 0 -> OperationResult.Partial(moved, items.size - moved)
            deletion == OperationResult.Cancelled -> OperationResult.Cancelled
            else -> OperationResult.Failure()
        }
    }

    private suspend fun encrypt(item: MediaItem, albumId: Long?): VaultItemEntity {
        if (!storage.hasRoomFor(item.size)) throw IOException("Espace insuffisant")
        val base = UUID.randomUUID().toString()
        val file = storage.file(base + VaultStorage.MEDIA_SUFFIX)
        val thumbFile = storage.file(base + VaultStorage.THUMB_SUFFIX)
        try {
            val written = resolver.openInputStream(item.uri)?.use { crypto.encrypt(it, file) }
                ?: throw IOException("Lecture impossible : ${item.uri}")
            // Relecture complète : l'original ne sera supprimé que si la copie est intègre.
            if (written <= 0 || crypto.plaintextSize(file) != written) throw IOException("Copie chiffrée invalide")
            val thumb = runCatching { writeThumbnail(item, thumbFile) }
                .onFailure { Log.w(TAG, "Miniature impossible : ${item.displayName}", it) }
                .getOrNull()
            val entity = VaultItemEntity(
                displayName = item.displayName,
                mimeType = item.mimeType,
                mediaType = if (item.isVideo) MediaQueryBuilder.MEDIA_TYPE_VIDEO else MediaQueryBuilder.MEDIA_TYPE_IMAGE,
                originalPath = item.relativePath,
                dateTaken = item.dateTaken,
                size = written,
                width = item.displayWidth,
                height = item.displayHeight,
                duration = item.durationMs,
                fileName = file.name,
                thumbFileName = thumb?.name,
                addedAt = System.currentTimeMillis(),
                albumId = albumId,
            )
            return entity.copy(id = dao.insertImported(entity))
        } catch (error: Throwable) {
            deleteFiles(file.name, thumbFile.name)
            throw error
        }
    }

    private fun writeThumbnail(item: MediaItem, target: File): File {
        val bitmap = resolver.loadThumbnail(item.uri, Size(THUMB_SIZE, THUMB_SIZE), null)
        val bytes = ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, THUMB_QUALITY, output)
            output.toByteArray()
        }
        bitmap.recycle()
        crypto.encrypt(ByteArrayInputStream(bytes), target)
        return target
    }

    /**
     * L'original est-il toujours dans MediaStore ? En cas de doute (requête en échec),
     * on le considère supprimé : la copie chiffrée est alors conservée.
     */
    private fun stillExists(item: MediaItem): Boolean = runCatching {
        resolver.query(item.uri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { it.moveToFirst() } == true
    }.getOrDefault(false)

    private suspend fun discard(entity: VaultItemEntity) {
        dao.delete(listOf(entity.id))
        deleteFiles(entity.fileName, entity.thumbFileName)
    }

    private fun deleteFiles(vararg names: String?) {
        names.filterNotNull().forEach { storage.file(it).delete() }
    }

    /** Éléments effectivement déplacés. */
    private val OperationResult.moved: Int
        get() = when (this) {
            is OperationResult.Success -> count
            is OperationResult.Partial -> succeeded
            else -> 0
        }

    private companion object {
        const val TAG = "VaultRepository"
        const val THUMB_SIZE = 512
        const val THUMB_QUALITY = 85
        const val BUFFER = 256 * 1024
    }
}
