package app.focale.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** Empreintes sémantiques des photos. */
@Dao
interface EmbeddingDao {

    /** Photos à traiter : jamais vues, modifiées depuis, ou traitées par une ancienne version. */
    @Query(
        """
        SELECT m.* FROM media m LEFT JOIN media_embedding e ON e.media_id = m.id
        WHERE m.is_trashed = 0 AND m.media_type = ${MediaQueryBuilder.MEDIA_TYPE_IMAGE}
          AND (e.media_id IS NULL OR e.analyzed_modified != m.date_modified OR e.version < :version)
        ORDER BY m.date_taken DESC
        LIMIT :limit
        """,
    )
    suspend fun pending(version: Int, limit: Int): List<MediaEntity>

    @Upsert
    suspend fun upsert(embeddings: List<MediaEmbeddingEntity>)
}
