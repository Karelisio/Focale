package app.focale.vault

import android.content.ContentResolver
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.database.VaultDao
import app.focale.core.database.VaultItemEntity
import app.focale.core.di.IoDispatcher
import app.focale.core.mediastore.MediaSyncManager
import app.focale.core.model.MediaItem
import app.focale.data.MediaOperations
import app.focale.data.MediaRepository
import app.focale.data.OperationResult
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
 */
@Singleton
class VaultRepository @Inject constructor(
    private val resolver: ContentResolver,
    private val dao: VaultDao,
    private val crypto: VaultCrypto,
    private val storage: VaultStorage,
    private val operations: MediaOperations,
    private val syncManager: MediaSyncManager,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    fun observeItems(): Flow<List<VaultItem>> = dao.observeAll().map { list -> list.map { it.toModel() } }

    /**
     * Déplace [items] dans le coffre. Non annulable : une fois la suppression des
     * originaux demandée, son issue doit être constatée pour rester cohérent.
     */
    suspend fun import(
        items: List<MediaItem>,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): OperationResult = withContext(io + NonCancellable) {
        if (items.isEmpty()) return@withContext OperationResult.Success(0)
        val imported = ArrayList<Pair<MediaItem, VaultItemEntity>>(items.size)
        items.forEachIndexed { index, item ->
            runCatching { encrypt(item) }
                .onSuccess { imported += item to it }
                .onFailure { Log.w(TAG, "Chiffrement impossible : ${item.displayName}", it) }
            onProgress(index + 1, items.size)
        }
        if (imported.isEmpty()) return@withContext OperationResult.Failure()

        val deletion = operations.deleteForever(imported.map { it.first })
        val kept = imported.filter { (item, _) -> stillExists(item) }
        kept.forEach { (_, entity) -> discard(entity) }
        val moved = imported.size - kept.size
        when {
            moved == items.size -> OperationResult.Success(moved)
            moved > 0 -> OperationResult.Partial(moved, items.size - moved)
            deletion == OperationResult.Cancelled -> OperationResult.Cancelled
            else -> OperationResult.Failure()
        }
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

    private suspend fun encrypt(item: MediaItem): VaultItemEntity {
        if (!storage.hasRoomFor(item.size)) throw IOException("Espace insuffisant")
        val base = UUID.randomUUID().toString()
        val file = storage.file(base + MEDIA_SUFFIX)
        val thumbFile = storage.file(base + THUMB_SUFFIX)
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
            )
            return entity.copy(id = dao.insert(entity))
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

    private companion object {
        const val TAG = "VaultRepository"
        const val MEDIA_SUFFIX = ".bin"
        const val THUMB_SUFFIX = ".thumb"
        const val THUMB_SIZE = 512
        const val THUMB_QUALITY = 85
        const val BUFFER = 256 * 1024
    }
}
