package app.focale.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

/** Empreinte valide d'une photo, telle que chargée pour la recherche. */
class StoredEmbedding(
    val mediaId: Long,
    val vector: ByteArray,
)

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

    /**
     * Empreintes calculées par la version [version], hors corbeille, par tranches d'au plus
     * [limit] lignes d'identifiant supérieur à [after] (pagination sans OFFSET).
     */
    @Query(
        """
        SELECT e.media_id AS mediaId, e.vector AS vector FROM media_embedding e
        JOIN media m ON m.id = e.media_id
        WHERE e.version = :version AND e.vector IS NOT NULL AND m.is_trashed = 0 AND e.media_id > :after
        ORDER BY e.media_id
        LIMIT :limit
        """,
    )
    suspend fun page(version: Int, after: Long, limit: Int): List<StoredEmbedding>

    /** Vrai si [word] est un mot entier du nom d'un lieu de la bibliothèque (forme normalisée). */
    @Query("SELECT EXISTS(SELECT 1 FROM media_analysis WHERE (' ' || place_key || ' ') LIKE '% ' || :word || ' %')")
    suspend fun isPlaceWord(word: String): Boolean
}
