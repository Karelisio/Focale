package app.focale.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow

/** Ligne de [MediaQueryBuilder.summaries] : rang de la requête, nombre de médias et couverture. */
data class SummaryRow(
    val position: Int,
    val count: Int,
    @ColumnInfo(name = "cover_id") val coverId: Long?,
)

/**
 * Accès à l'index des médias.
 *
 * Les requêtes dynamiques (filtres, recherche, tri) sont construites par
 * [MediaQueryBuilder] et exécutées via `@RawQuery` ; les tables observées couvrent
 * toutes celles qui peuvent apparaître dans une clause WHERE, de sorte que la grille
 * se rafraîchit dès qu'un favori, un album ou une étiquette change. Les pages des grilles
 * ([MediaPagingSource]) et leur histogramme suivent les mêmes tables (`MediaRepository`).
 *
 * Toute écriture de l'index passe par [upsert] et [deleteByIds], qui tiennent à jour avec lui les
 * mots des noms de fichiers ([MediaNames]) et les comptes des étiquettes ([LabelCountQueries]).
 */
@Dao
interface MediaDao : LabelCountQueries {

    @RawQuery(observedEntities = [MediaEntity::class, FavoriteEntity::class, AlbumMediaEntity::class, MediaLabelEntity::class, MediaAnalysisEntity::class])
    fun observeList(query: SupportSQLiteQuery): Flow<List<MediaEntity>>

    @RawQuery
    suspend fun list(query: SupportSQLiteQuery): List<MediaEntity>

    @RawQuery
    suspend fun ids(query: SupportSQLiteQuery): List<Long>

    @RawQuery
    suspend fun count(query: SupportSQLiteQuery): Int

    /** Dates extrêmes ([MediaQueryBuilder.dateRange]). */
    @RawQuery
    suspend fun dateRange(query: SupportSQLiteQuery): DateRangeRow?

    /** Médias par période ([MediaQueryBuilder.histogram]). */
    @RawQuery
    suspend fun histogram(query: SupportSQLiteQuery): List<DateBucketRow>

    @RawQuery(observedEntities = [MediaEntity::class, FavoriteEntity::class, AlbumMediaEntity::class, MediaLabelEntity::class, MediaAnalysisEntity::class])
    fun observeCount(query: SupportSQLiteQuery): Flow<Int>

    @RawQuery(observedEntities = [MediaEntity::class])
    fun observeBuckets(query: SupportSQLiteQuery): Flow<List<BucketRow>>

    // Timeline et dossiers : leur requête ne lit que `media` (MediaQueryBuilder.readsOnlyMedia).
    // L'analyse, qui écrit dans media_analysis et media_label à chaque lot pendant des heures sur
    // une grande bibliothèque, ne recharge ni leurs pages, ni leur compteur, ni leur histogramme.

    @RawQuery(observedEntities = [MediaEntity::class])
    fun observeListOfMedia(query: SupportSQLiteQuery): Flow<List<MediaEntity>>

    @RawQuery(observedEntities = [MediaEntity::class])
    fun observeCountOfMedia(query: SupportSQLiteQuery): Flow<Int>

    // Recherche et personnes : suivent aussi les étapes d'analyse complémentaires (texte,
    // visages, empreintes), dont les résultats s'affinent pendant que l'analyse avance ou
    // que l'utilisateur réorganise ses personnes. La timeline n'est pas concernée.

    @RawQuery(observedEntities = [MediaEntity::class, FavoriteEntity::class, AlbumMediaEntity::class, MediaLabelEntity::class, MediaAnalysisEntity::class, MediaTextEntity::class, FaceEntity::class, MediaEmbeddingEntity::class])
    fun observeListWithAnalysis(query: SupportSQLiteQuery): Flow<List<MediaEntity>>

    @RawQuery(observedEntities = [MediaEntity::class, FavoriteEntity::class, AlbumMediaEntity::class, MediaLabelEntity::class, MediaAnalysisEntity::class, MediaTextEntity::class, FaceEntity::class, MediaEmbeddingEntity::class])
    fun observeCountWithAnalysis(query: SupportSQLiteQuery): Flow<Int>

    /**
     * Résumés de requêtes sur les étiquettes et le texte reconnu (albums automatiques) : seules
     * ces tables sont suivies, pas les visages ni les empreintes, dont chaque lot d'analyse
     * relancerait la requête pour rien.
     */
    @RawQuery(observedEntities = [MediaEntity::class, MediaLabelEntity::class, MediaAnalysisEntity::class, MediaTextEntity::class])
    fun observeSummaries(query: SupportSQLiteQuery): Flow<List<SummaryRow>>

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun get(id: Long): MediaEntity?

    @Query("SELECT * FROM media WHERE id = :id")
    fun observe(id: Long): Flow<MediaEntity?>

    /** À appeler avec au plus 500 identifiants (limite de paramètres SQLite). */
    @Query("SELECT * FROM media WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<MediaEntity>

    @Query("SELECT id, date_modified, generation, is_trashed FROM media")
    suspend fun syncKeys(): List<MediaSyncKey>

    /**
     * Ajoute ou réécrit [items] dans l'index, avec les mots de leur nom et les comptes de leurs
     * étiquettes (un média qui entre dans la corbeille, en sort ou revient dans l'index).
     */
    @Transaction
    suspend fun upsert(items: List<MediaEntity>) = withLabelCounts(items.map { it.id }) {
        upsertRows(items)
        upsertNames(items.map(MediaNames::entity))
    }

    /** Lignes seules : passer par [upsert]. */
    @Upsert
    suspend fun upsertRows(items: List<MediaEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNames(names: List<MediaNameEntity>)

    /** Retire de l'index les médias [ids] (au plus 500), leurs mots et leurs étiquettes des comptes. */
    @Transaction
    suspend fun deleteByIds(ids: List<Long>) = withLabelCounts(ids) {
        deleteRows(ids)
        deleteNames(ids)
    }

    /** Lignes seules : passer par [deleteByIds]. */
    @Query("DELETE FROM media WHERE id IN (:ids)")
    suspend fun deleteRows(ids: List<Long>)

    @Query("DELETE FROM media_name WHERE rowid IN (:ids)")
    suspend fun deleteNames(ids: List<Long>)

    /** Débuts des périodes non vides ([MediaQueryBuilder.nonEmptyPeriods]). */
    @RawQuery
    suspend fun periodStarts(query: SupportSQLiteQuery): List<Long>

    @Query("SELECT COUNT(*) FROM media WHERE is_trashed = 0")
    fun observeTotalCount(): Flow<Int>

    /** Date de prise de vue la plus ancienne (souvenirs), null si la bibliothèque est vide. */
    @Query("SELECT MIN(date_taken) FROM media WHERE is_trashed = 0 AND date_taken > 0")
    suspend fun oldestDateTaken(): Long?

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

    /** Résultats des étapes d'analyse complémentaires (texte, visages, empreintes). */
    @Query("DELETE FROM media_text WHERE media_id NOT IN (SELECT id FROM media)")
    suspend fun deleteOrphanTexts()

    @Query("DELETE FROM face_scan WHERE media_id NOT IN (SELECT id FROM media)")
    suspend fun deleteOrphanFaceScans()

    @Query("DELETE FROM face WHERE media_id NOT IN (SELECT id FROM media)")
    suspend fun deleteOrphanFaces()

    @Query("DELETE FROM media_embedding WHERE media_id NOT IN (SELECT id FROM media)")
    suspend fun deleteOrphanEmbeddings()

    // ── Dates d'origine retenues par l'application ──────────────────────────────────

    @Query("SELECT * FROM media_date")
    suspend fun originalDates(): List<MediaDateEntity>

    @Upsert
    suspend fun upsertOriginalDate(date: MediaDateEntity)

    /** Date de tri d'un média déjà indexé. */
    @Query("UPDATE media SET date_taken = :dateTaken WHERE id = :id")
    suspend fun setDateTaken(id: Long, dateTaken: Long)

    /**
     * Dates de médias disparus. Un média tout juste publié n'est peut-être pas encore indexé :
     * son identifiant, attribué après tous ceux de l'index, est épargné.
     */
    @Query("DELETE FROM media_date WHERE media_id NOT IN (SELECT id FROM media) AND media_id < (SELECT COALESCE(MAX(id), 0) FROM media)")
    suspend fun deleteOrphanDates()

    /** Base MediaStore recréée : ses identifiants ne désignent plus les mêmes médias. */
    @Query("DELETE FROM media_date")
    suspend fun clearOriginalDates()
}
