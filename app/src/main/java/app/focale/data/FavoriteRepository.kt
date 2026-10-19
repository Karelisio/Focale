package app.focale.data

import app.focale.core.database.FavoriteDao
import app.focale.core.database.FavoriteEntity
import app.focale.core.di.IoDispatcher
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

/** Favoris, stockés localement (indépendants des autres applications de galerie). */
@Singleton
class FavoriteRepository @Inject constructor(
    private val dao: FavoriteDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    fun observeIsFavorite(mediaId: Long): Flow<Boolean> = dao.observeIsFavorite(mediaId).distinctUntilChanged()

    fun observeCount(): Flow<Int> = dao.observeCount()

    suspend fun setFavorite(mediaIds: Collection<Long>, favorite: Boolean) = withContext(io) {
        val now = System.currentTimeMillis()
        mediaIds.chunked(MediaRepository.MAX_SQL_ARGS).forEach { chunk ->
            if (favorite) dao.insert(chunk.map { FavoriteEntity(it, now) }) else dao.delete(chunk)
        }
    }

    /** Vrai si tous les médias donnés sont déjà favoris. */
    suspend fun allFavorite(mediaIds: Collection<Long>): Boolean = withContext(io) {
        if (mediaIds.isEmpty()) return@withContext false
        mediaIds.chunked(MediaRepository.MAX_SQL_ARGS).sumOf { dao.favoritesAmong(it).size } == mediaIds.size
    }
}
