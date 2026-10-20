package app.focale.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Texte reconnu dans les photos (OCR). */
@Dao
interface TextDao {

    /**
     * Photos à lire : jamais lues, modifiées depuis, ou lues par une ancienne version.
     * Les captures d'écran, les plus riches en texte, passent en premier.
     */
    @Query(
        """
        SELECT m.* FROM media m LEFT JOIN media_text t ON t.media_id = m.id
        WHERE m.is_trashed = 0 AND m.media_type = ${MediaQueryBuilder.MEDIA_TYPE_IMAGE}
          AND (t.media_id IS NULL OR t.analyzed_modified != m.date_modified OR t.version < :version)
        ORDER BY m.is_screenshot DESC, m.date_taken DESC
        LIMIT :limit
        """,
    )
    suspend fun pending(version: Int, limit: Int): List<MediaEntity>

    /** Avancement en direct : photos concernées par [pending] et photos déjà lues. */
    @Query(
        """
        SELECT COUNT(*) AS total,
               COALESCE(SUM(t.media_id IS NOT NULL AND t.analyzed_modified = m.date_modified AND t.version >= :version), 0) AS done
        FROM media m LEFT JOIN media_text t ON t.media_id = m.id
        WHERE m.is_trashed = 0 AND m.media_type = ${MediaQueryBuilder.MEDIA_TYPE_IMAGE}
        """,
    )
    fun observeProgress(version: Int): Flow<StepCounts>

    @Upsert
    suspend fun upsert(texts: List<MediaTextEntity>)

    @Query("SELECT * FROM media_text WHERE media_id = :mediaId")
    suspend fun get(mediaId: Long): MediaTextEntity?
}
