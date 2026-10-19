package app.focale.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Étiquette et nombre de médias qui la portent, avec un média représentatif. */
data class LabelStat(
    val label: String,
    val count: Int,
    @ColumnInfo(name = "cover_id") val coverId: Long,
)

/** Lieu et nombre de médias pris sur place, avec le plus récent. */
data class PlaceStat(
    val place: String,
    val country: String,
    val count: Int,
    @ColumnInfo(name = "cover_id") val coverId: Long,
)

data class HashRow(val id: Long, val phash: Long, val size: Long, val width: Int, val height: Int)

/** Média géolocalisé, tel qu'affiché sur la carte. */
data class GeoPhoto(
    @ColumnInfo(name = "media_id") val mediaId: Long,
    val latitude: Double,
    val longitude: Double,
    @ColumnInfo(name = "media_type") val mediaType: Int,
    @ColumnInfo(name = "date_taken") val dateTaken: Long,
    @ColumnInfo(name = "date_modified") val dateModified: Long,
    val place: String?,
)

@Dao
interface AnalysisDao {

    /** Médias à analyser (jamais analysés, modifiés depuis, ou analysés par une ancienne version). */
    @Query(
        """
        SELECT m.* FROM media m LEFT JOIN media_analysis a ON a.media_id = m.id
        WHERE m.is_trashed = 0
          AND (a.media_id IS NULL OR a.analyzed_modified != m.date_modified OR a.version < :version)
        ORDER BY m.date_taken DESC
        LIMIT :limit
        """,
    )
    suspend fun pending(version: Int, limit: Int): List<MediaEntity>

    @Query(
        """
        SELECT COUNT(*) FROM media m LEFT JOIN media_analysis a ON a.media_id = m.id
        WHERE m.is_trashed = 0
          AND (a.media_id IS NULL OR a.analyzed_modified != m.date_modified OR a.version < :version)
        """,
    )
    fun observePendingCount(version: Int): Flow<Int>

    @Upsert
    suspend fun upsert(analysis: List<MediaAnalysisEntity>)

    @Query("DELETE FROM media_label WHERE media_id IN (:mediaIds)")
    suspend fun deleteLabels(mediaIds: List<Long>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLabels(labels: List<MediaLabelEntity>)

    /** Enregistre un lot d'analyses et remplace les étiquettes correspondantes. */
    @Transaction
    suspend fun save(analysis: List<MediaAnalysisEntity>, labels: List<MediaLabelEntity>) {
        deleteLabels(analysis.map { it.mediaId })
        upsert(analysis)
        insertLabels(labels)
    }

    @Query("SELECT * FROM media_analysis WHERE media_id = :mediaId")
    fun observe(mediaId: Long): Flow<MediaAnalysisEntity?>

    @Query("SELECT label FROM media_label WHERE media_id = :mediaId ORDER BY confidence DESC")
    fun observeLabels(mediaId: Long): Flow<List<String>>

    @Query(
        """
        SELECT l.label AS label, COUNT(*) AS count,
               (SELECT l2.media_id FROM media_label l2 JOIN media m2 ON m2.id = l2.media_id
                WHERE l2.label = l.label AND m2.is_trashed = 0
                ORDER BY l2.confidence DESC, m2.date_taken DESC LIMIT 1) AS cover_id
        FROM media_label l JOIN media m ON m.id = l.media_id
        WHERE m.is_trashed = 0
        GROUP BY l.label
        ORDER BY count DESC
        LIMIT :limit
        """,
    )
    fun observeLabelStats(limit: Int): Flow<List<LabelStat>>

    @Query(
        """
        SELECT a.place AS place, a.country AS country, COUNT(*) AS count,
               (SELECT a2.media_id FROM media_analysis a2 JOIN media m2 ON m2.id = a2.media_id
                WHERE a2.place = a.place AND a2.country = a.country AND m2.is_trashed = 0
                ORDER BY m2.date_taken DESC LIMIT 1) AS cover_id
        FROM media_analysis a JOIN media m ON m.id = a.media_id
        WHERE m.is_trashed = 0 AND a.place IS NOT NULL AND a.country IS NOT NULL
        GROUP BY a.place, a.country
        ORDER BY count DESC
        LIMIT :limit
        """,
    )
    fun observePlaceStats(limit: Int): Flow<List<PlaceStat>>

    /** Empreintes des photos (détection de doublons). */
    @Query(
        """
        SELECT a.media_id AS id, a.phash AS phash, m.size AS size, m.width AS width, m.height AS height
        FROM media_analysis a JOIN media m ON m.id = a.media_id
        WHERE m.is_trashed = 0 AND m.media_type = 1 AND a.phash IS NOT NULL
        """,
    )
    suspend fun hashes(): List<HashRow>

    @Query(
        """
        SELECT m.* FROM media m JOIN media_analysis a ON a.media_id = m.id
        WHERE m.is_trashed = 0 AND m.media_type = 1 AND m.is_screenshot = 0
          AND a.blur_score IS NOT NULL AND a.blur_score < :threshold
        ORDER BY a.blur_score ASC
        """,
    )
    fun observeBlurry(threshold: Float): Flow<List<MediaEntity>>

    @Query(
        """
        SELECT a.media_id AS media_id, a.latitude AS latitude, a.longitude AS longitude,
               m.media_type AS media_type, m.date_taken AS date_taken,
               m.date_modified AS date_modified, a.place AS place
        FROM media_analysis a JOIN media m ON m.id = a.media_id
        WHERE m.is_trashed = 0 AND a.latitude IS NOT NULL AND a.longitude IS NOT NULL
        ORDER BY m.date_taken DESC
        """,
    )
    fun observeGeoPhotos(): Flow<List<GeoPhoto>>
}
