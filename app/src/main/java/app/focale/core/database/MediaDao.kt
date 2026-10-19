package app.focale.core.database

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/**
 * Accès à l'index des médias.
 *
 * Les requêtes dynamiques (filtres, recherche, tri) sont construites par
 * [MediaQueryBuilder] et exécutées via `@RawQuery` ; les tables observées couvrent
 * toutes celles qui peuvent apparaître dans une clause WHERE, de sorte que la grille
 * se rafraîchit dès qu'un favori, un album ou une étiquette change.
 */
@Dao
interface MediaDao {

    @RawQuery(observedEntities = [MediaEntity::class, FavoriteEntity::class, AlbumMediaEntity::class, MediaLabelEntity::class, MediaAnalysisEntity::class])
    fun pagingSource(query: SupportSQLiteQuery): PagingSource<Int, MediaEntity>

    @RawQuery(observedEntities = [MediaEntity::class, FavoriteEntity::class, AlbumMediaEntity::class, MediaLabelEntity::class, MediaAnalysisEntity::class])
    fun observeList(query: SupportSQLiteQuery): Flow<List<MediaEntity>>

    @RawQuery
    suspend fun list(query: SupportSQLiteQuery): List<MediaEntity>

    @RawQuery
    suspend fun ids(query: SupportSQLiteQuery): List<Long>

    @RawQuery
    suspend fun count(query: SupportSQLiteQuery): Int

    @RawQuery(observedEntities = [MediaEntity::class, FavoriteEntity::class, AlbumMediaEntity::class, MediaLabelEntity::class, MediaAnalysisEntity::class])
    fun observeCount(query: SupportSQLiteQuery): Flow<Int>

    @RawQuery(observedEntities = [MediaEntity::class, FavoriteEntity::class, AlbumMediaEntity::class, MediaLabelEntity::class, MediaAnalysisEntity::class])
    fun observeHistogram(query: SupportSQLiteQuery): Flow<List<DateBucketRow>>

    @RawQuery(observedEntities = [MediaEntity::class])
    fun observeBuckets(query: SupportSQLiteQuery): Flow<List<BucketRow>>

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun get(id: Long): MediaEntity?

    @Query("SELECT * FROM media WHERE id = :id")
    fun observe(id: Long): Flow<MediaEntity?>

    /** À appeler avec au plus 500 identifiants (limite de paramètres SQLite). */
    @Query("SELECT * FROM media WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<MediaEntity>

    @Query("SELECT id, date_modified, generation, is_trashed FROM media")
    suspend fun syncKeys(): List<MediaSyncKey>

    @Upsert
    suspend fun upsert(items: List<MediaEntity>)

    /** À appeler avec au plus 500 identifiants. */
    @Query("DELETE FROM media WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM media WHERE is_trashed = 0")
    fun observeTotalCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM media")
    suspend fun totalCount(): Int

    /** Médias de la corbeille système, les plus récemment supprimés en premier. */
    @Query("SELECT * FROM media WHERE is_trashed = 1 ORDER BY date_expires DESC, id DESC")
    fun observeTrashed(): Flow<List<MediaEntity>>

    /** Nettoie les métadonnées devenues orphelines (après une synchro avec accès complet). */
    @Query("DELETE FROM favorite WHERE media_id NOT IN (SELECT id FROM media)")
    suspend fun deleteOrphanFavorites()

    @Query("DELETE FROM album_media WHERE media_id NOT IN (SELECT id FROM media)")
    suspend fun deleteOrphanAlbumEntries()

    @Query("DELETE FROM media_label WHERE media_id NOT IN (SELECT id FROM media)")
    suspend fun deleteOrphanLabels()

    @Query("DELETE FROM media_analysis WHERE media_id NOT IN (SELECT id FROM media)")
    suspend fun deleteOrphanAnalysis()
}
