package app.focale.data

import androidx.compose.runtime.Immutable
import app.focale.core.database.MediaDao
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.database.TrashDao
import app.focale.core.database.TrashItemEntity
import app.focale.core.image.thumbnail
import app.focale.core.model.MediaItem
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Élément de la corbeille, quelle que soit son origine (système ou applicative). */
@Immutable
data class TrashEntry(
    val id: Long,
    val displayName: String,
    val isVideo: Boolean,
    val durationMs: Long,
    /** Donnée Coil de la miniature (miniature MediaStore ou fichier privé). */
    val thumbnail: Any,
    /** Date de suppression définitive (ms). */
    val expiresAt: Long,
    val media: MediaItem? = null,
    val legacy: TrashItemEntity? = null,
)

/**
 * Corbeille unifiée : corbeille système MediaStore sur Android 11+ (expiration
 * gérée par Android), corbeille applicative sur Android 10.
 */
@Singleton
class TrashRepository @Inject constructor(
    private val mediaDao: MediaDao,
    private val trashDao: TrashDao,
    private val operations: MediaOperations,
) {

    fun observe(): Flow<List<TrashEntry>> =
        if (operations.hasSystemTrash) {
            mediaDao.observeTrashed().map { rows ->
                rows.map { row ->
                    val item = row.toModel()
                    TrashEntry(
                        id = item.id,
                        displayName = item.displayName,
                        isVideo = item.isVideo,
                        durationMs = item.durationMs,
                        thumbnail = item.thumbnail(),
                        expiresAt = row.dateExpires.takeIf { it > 0 }
                            ?: (row.dateModified + MediaOperations.TRASH_RETENTION_MS),
                        media = item,
                    )
                }
            }
        } else {
            trashDao.observeAll().map { rows ->
                rows.map { row ->
                    TrashEntry(
                        id = row.id,
                        displayName = row.displayName,
                        isVideo = row.mediaType == MediaQueryBuilder.MEDIA_TYPE_VIDEO,
                        durationMs = row.duration,
                        thumbnail = operations.legacyTrashFile(row),
                        expiresAt = row.trashedAt + MediaOperations.TRASH_RETENTION_MS,
                        legacy = row,
                    )
                }
            }
        }

    fun observeCount(): Flow<Int> = observe().map { it.size }

    suspend fun restore(entries: List<TrashEntry>): OperationResult =
        if (operations.hasSystemTrash) {
            operations.restore(entries.mapNotNull { it.media })
        } else {
            operations.restoreLegacy(entries.mapNotNull { it.legacy })
        }

    suspend fun deleteForever(entries: List<TrashEntry>): OperationResult =
        if (operations.hasSystemTrash) {
            operations.deleteForever(entries.mapNotNull { it.media })
        } else {
            operations.deleteLegacy(entries.mapNotNull { it.legacy })
        }
}
