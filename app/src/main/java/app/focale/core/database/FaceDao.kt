package app.focale.core.database

import androidx.room.Dao
import androidx.room.Query

/** Visages détectés et personnes. */
@Dao
interface FaceDao {

    /** Photos à analyser : jamais vues, modifiées depuis, ou vues par une ancienne version. */
    @Query(
        """
        SELECT m.* FROM media m LEFT JOIN face_scan s ON s.media_id = m.id
        WHERE m.is_trashed = 0 AND m.media_type = ${MediaQueryBuilder.MEDIA_TYPE_IMAGE}
          AND (s.media_id IS NULL OR s.analyzed_modified != m.date_modified OR s.version < :version)
        ORDER BY m.date_taken DESC
        LIMIT :limit
        """,
    )
    suspend fun pending(version: Int, limit: Int): List<MediaEntity>
}
