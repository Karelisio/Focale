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
        ORDER BY m.is_screenshot DESC, m.date_taken DESC, m.id DESC
        LIMIT :limit
        """,
    )
    suspend fun pending(version: Int, limit: Int): List<MediaEntity>

    /**
     * Suite de [pending] parmi les captures ([screenshot]) ou les autres photos, après le média
     * ([dateTaken], [id]) du lot précédent, dans l'ordre de la timeline : chaque groupe se lit par
     * l'index sans tri ni retour sur les photos déjà lues (voir `TextRecognitionStep.pendingAfter`).
     */
    @Query(
        """
        SELECT m.* FROM media m LEFT JOIN media_text t ON t.media_id = m.id
        WHERE m.is_trashed = 0 AND m.media_type = ${MediaQueryBuilder.MEDIA_TYPE_IMAGE} AND m.is_screenshot = :screenshot
          AND m.date_taken <= :dateTaken AND (m.date_taken < :dateTaken OR m.id < :id)
          AND (t.media_id IS NULL OR t.analyzed_modified != m.date_modified OR t.version < :version)
        ORDER BY m.date_taken DESC, m.id DESC
        LIMIT :limit
        """,
    )
    suspend fun pendingAfter(version: Int, screenshot: Boolean, dateTaken: Long, id: Long, limit: Int): List<MediaEntity>

    /**
     * Avancement en direct : photos concernées par [pending] et photos déjà lues. Parcours dans
     * l'ordre des identifiants (« + » écarte les index triés par date) : voir
     * `AnalysisDao.observePendingCount`.
     */
    @Query(
        """
        SELECT COUNT(*) AS total,
               COALESCE(SUM(t.media_id IS NOT NULL AND t.analyzed_modified = m.date_modified AND t.version >= :version), 0) AS done
        FROM media m LEFT JOIN media_text t ON t.media_id = m.id
        WHERE +m.is_trashed = 0 AND +m.media_type = ${MediaQueryBuilder.MEDIA_TYPE_IMAGE}
        """,
    )
    fun observeProgress(version: Int): Flow<StepCounts>

    @Upsert
    suspend fun upsert(texts: List<MediaTextEntity>)

    @Query("SELECT * FROM media_text WHERE media_id = :mediaId")
    suspend fun get(mediaId: Long): MediaTextEntity?
}
