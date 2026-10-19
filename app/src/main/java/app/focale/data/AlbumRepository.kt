package app.focale.data

import app.focale.core.database.AlbumDao
import app.focale.core.database.AlbumEntity
import app.focale.core.database.AlbumMediaEntity
import app.focale.core.database.MediaDao
import app.focale.core.database.MediaQueryBuilder
import app.focale.core.di.IoDispatcher
import app.focale.core.model.Album
import app.focale.core.model.MediaQuery
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext

/** Albums dossiers (MediaStore) et albums virtuels (Room). */
@Singleton
class AlbumRepository @Inject constructor(
    private val albumDao: AlbumDao,
    private val mediaDao: MediaDao,
    private val mediaRepository: MediaRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Dossiers de l'appareil, les plus récemment alimentés en premier. */
    fun observeFolders(query: MediaQuery = MediaQuery()): Flow<List<Album.Folder>> =
        mediaDao.observeBuckets(MediaQueryBuilder.buckets(query))
            .mapLatest { rows ->
                val covers = mediaRepository.getByIds(rows.map { it.coverId }).associateBy { it.id }
                rows.map { row ->
                    Album.Folder(
                        bucketId = row.bucketId,
                        name = row.bucketName.ifBlank { row.relativePath.trimEnd('/').substringAfterLast('/') },
                        relativePath = row.relativePath,
                        count = row.count,
                        cover = covers[row.coverId],
                    )
                }
            }
            .flowOn(io)

    fun observeUserAlbums(): Flow<List<Album.User>> =
        albumDao.observeUserAlbums()
            .mapLatest { rows ->
                val covers = mediaRepository.getByIds(rows.mapNotNull { it.coverId }).associateBy { it.id }
                rows.map { row ->
                    Album.User(id = row.id, name = row.name, count = row.count, cover = row.coverId?.let(covers::get))
                }
            }
            .flowOn(io)

    fun observeAlbum(id: Long) = albumDao.observe(id)

    suspend fun create(name: String): Long = withContext(io) {
        albumDao.insert(AlbumEntity(name = name.trim(), createdAt = System.currentTimeMillis()))
    }

    suspend fun rename(id: Long, name: String) = withContext(io) { albumDao.rename(id, name.trim()) }

    suspend fun delete(id: Long) = withContext(io) { albumDao.delete(id) }

    suspend fun setCover(id: Long, mediaId: Long?) = withContext(io) { albumDao.setCover(id, mediaId) }

    suspend fun add(albumId: Long, mediaIds: Collection<Long>) = withContext(io) {
        val now = System.currentTimeMillis()
        albumDao.addMedia(mediaIds.map { AlbumMediaEntity(albumId, it, now) })
    }

    suspend fun remove(albumId: Long, mediaIds: Collection<Long>) = withContext(io) {
        mediaIds.chunked(MediaRepository.MAX_SQL_ARGS).forEach { albumDao.removeMedia(albumId, it) }
    }
}
