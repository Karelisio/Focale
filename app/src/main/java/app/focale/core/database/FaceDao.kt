package app.focale.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Visage tel que chargé pour le regroupement (empreinte comprise). */
data class FaceClusterRow(
    val id: Long,
    @ColumnInfo(name = "media_id") val mediaId: Long,
    @ColumnInfo(name = "person_id") val personId: Long?,
    val manual: Boolean,
    val embedding: ByteArray,
)

/** Visage sans son empreinte, avec la date de modification de sa photo (vignettes). */
data class FaceThumbRow(
    val id: Long,
    @ColumnInfo(name = "media_id") val mediaId: Long,
    @ColumnInfo(name = "person_id") val personId: Long?,
    @ColumnInfo(name = "box_left") val boxLeft: Float,
    @ColumnInfo(name = "box_top") val boxTop: Float,
    @ColumnInfo(name = "box_right") val boxRight: Float,
    @ColumnInfo(name = "box_bottom") val boxBottom: Float,
    val quality: Float,
    val manual: Boolean,
    @ColumnInfo(name = "date_modified") val dateModified: Long,
)

/** Personne avec son nombre de photos et le visage qui lui sert de vignette. */
data class PersonRow(
    val id: Long,
    val name: String?,
    val hidden: Boolean,
    @ColumnInfo(name = "photo_count") val photoCount: Int,
    @ColumnInfo(name = "cover_face_id") val coverFaceId: Long?,
)

/** Rattachement d'un visage, mémorisé pour pouvoir l'annuler. */
data class FaceLink(
    val id: Long,
    @ColumnInfo(name = "person_id") val personId: Long?,
    val manual: Boolean,
)

/** Visages détectés et personnes. */
@Dao
interface FaceDao {

    // ── Analyse ──────────────────────────────────────────────────────────────────

    /**
     * Photos à analyser : jamais vues, modifiées depuis, ou vues par une ancienne version.
     * Captures d'écran et GIF sont écartés : visages d'inconnus (réseaux sociaux, mèmes).
     */
    @Query(
        """
        SELECT m.* FROM media m LEFT JOIN face_scan s ON s.media_id = m.id
        WHERE m.is_trashed = 0 AND m.media_type = ${MediaQueryBuilder.MEDIA_TYPE_IMAGE}
          AND m.is_screenshot = 0 AND m.is_gif = 0
          AND (s.media_id IS NULL OR s.analyzed_modified != m.date_modified OR s.version < :version)
        ORDER BY m.date_taken DESC
        LIMIT :limit
        """,
    )
    suspend fun pending(version: Int, limit: Int): List<MediaEntity>

    /** Avancement en direct : photos concernées par [pending] et photos déjà analysées. */
    @Query(
        """
        SELECT COUNT(*) AS total,
               COALESCE(SUM(s.media_id IS NOT NULL AND s.analyzed_modified = m.date_modified AND s.version >= :version), 0) AS done
        FROM media m LEFT JOIN face_scan s ON s.media_id = m.id
        WHERE m.is_trashed = 0 AND m.media_type = ${MediaQueryBuilder.MEDIA_TYPE_IMAGE}
          AND m.is_screenshot = 0 AND m.is_gif = 0
        """,
    )
    fun observeProgress(version: Int): Flow<StepCounts>

    @Query("SELECT * FROM face WHERE media_id IN (:mediaIds)")
    suspend fun facesOfMedia(mediaIds: List<Long>): List<FaceEntity>

    @Insert
    suspend fun insertFaces(faces: List<FaceEntity>): List<Long>

    /** Nouvelle détection d'un visage déjà connu : sa personne et son rattachement ne bougent pas. */
    @Query(
        """
        UPDATE face SET box_left = :left, box_top = :top, box_right = :right, box_bottom = :bottom,
            embedding = :embedding, quality = :quality
        WHERE id = :id
        """,
    )
    suspend fun updateDetection(id: Long, left: Float, top: Float, right: Float, bottom: Float, embedding: ByteArray, quality: Float)

    @Query("DELETE FROM face WHERE id IN (:ids)")
    suspend fun deleteFaces(ids: List<Long>)

    @Upsert
    suspend fun upsertScans(scans: List<FaceScanEntity>)

    /** Enregistre un lot d'analyses ; renvoie les identifiants des visages ajoutés, dans l'ordre. */
    @Transaction
    suspend fun saveDetections(
        scans: List<FaceScanEntity>,
        deleted: List<Long>,
        updated: List<FaceEntity>,
        inserted: List<FaceEntity>,
    ): List<Long> {
        if (deleted.isNotEmpty()) deleteFaces(deleted)
        updated.forEach { updateDetection(it.id, it.boxLeft, it.boxTop, it.boxRight, it.boxBottom, it.embedding, it.quality) }
        val ids = if (inserted.isEmpty()) emptyList() else insertFaces(inserted)
        upsertScans(scans)
        return ids
    }

    @Query("SELECT id, media_id, person_id, manual, embedding FROM face")
    suspend fun clusterRows(): List<FaceClusterRow>

    @Insert
    suspend fun insertPerson(person: PersonEntity): Long

    /**
     * Rattachement automatique, seulement si le visage est toujours libre (aucune
     * décision manuelle entre-temps) et la personne toujours là (pas de fusion entre-temps).
     */
    @Query(
        """
        UPDATE face SET person_id = :personId
        WHERE id = :faceId AND person_id IS NULL AND manual = 0
          AND EXISTS (SELECT 1 FROM person WHERE id = :personId)
        """,
    )
    suspend fun assignIfFree(faceId: Long, personId: Long): Int

    @Query("DELETE FROM person WHERE id NOT IN (SELECT person_id FROM face WHERE person_id IS NOT NULL)")
    suspend fun deleteEmptyPersons(): Int

    /**
     * Enregistre les décisions du regroupement : crée les personnes [created] (identifiants
     * provisoires), applique [assignments] (visage → personne) et retire les personnes
     * restées sans visage. Renvoie la correspondance identifiant provisoire → définitif et
     * le nombre de rattachements refusés (modifiés par l'utilisateur entre-temps).
     */
    @Transaction
    suspend fun saveClustering(created: Collection<Long>, assignments: Map<Long, Long>, now: Long): Pair<Map<Long, Long>, Int> {
        val ids = created.associateWith { insertPerson(PersonEntity(createdAt = now)) }
        var refused = 0
        for ((faceId, personId) in assignments) {
            if (assignIfFree(faceId, ids[personId] ?: personId) == 0) refused++
        }
        deleteEmptyPersons()
        return ids to refused
    }

    // ── Interface ────────────────────────────────────────────────────────────────

    /**
     * Personnes avec leurs photos hors corbeille, les plus présentes d'abord. Une personne
     * sans nom n'apparaît qu'à partir de [minPhotos] photos. La vignette est le visage
     * choisi s'il appartient toujours à la personne, sinon son meilleur visage.
     */
    @Query(
        """
        SELECT p.id AS id, p.name AS name, p.hidden AS hidden,
               COUNT(DISTINCT f.media_id) AS photo_count,
               COALESCE(
                   (SELECT c.id FROM face c JOIN media cm ON cm.id = c.media_id
                    WHERE c.id = p.cover_face_id AND c.person_id = p.id AND cm.is_trashed = 0),
                   (SELECT b.id FROM face b JOIN media bm ON bm.id = b.media_id
                    WHERE b.person_id = p.id AND bm.is_trashed = 0
                    ORDER BY b.quality DESC, bm.date_taken DESC LIMIT 1)
               ) AS cover_face_id
        FROM person p
        JOIN face f ON f.person_id = p.id
        JOIN media m ON m.id = f.media_id
        WHERE m.is_trashed = 0 AND (:includeHidden OR p.hidden = 0)
        GROUP BY p.id
        HAVING photo_count >= :minPhotos OR p.name IS NOT NULL
        ORDER BY photo_count DESC, p.id
        """,
    )
    fun observePeople(includeHidden: Boolean, minPhotos: Int): Flow<List<PersonRow>>

    @Query(
        """
        SELECT p.id AS id, p.name AS name, p.hidden AS hidden,
               (SELECT COUNT(DISTINCT f.media_id) FROM face f JOIN media m ON m.id = f.media_id
                WHERE f.person_id = p.id AND m.is_trashed = 0) AS photo_count,
               COALESCE(
                   (SELECT c.id FROM face c JOIN media cm ON cm.id = c.media_id
                    WHERE c.id = p.cover_face_id AND c.person_id = p.id AND cm.is_trashed = 0),
                   (SELECT b.id FROM face b JOIN media bm ON bm.id = b.media_id
                    WHERE b.person_id = p.id AND bm.is_trashed = 0
                    ORDER BY b.quality DESC, bm.date_taken DESC LIMIT 1)
               ) AS cover_face_id
        FROM person p WHERE p.id = :id
        """,
    )
    fun observePerson(id: Long): Flow<PersonRow?>

    /** Personnes nommées ayant au moins une photo (recherche par nom), masquées comprises. */
    @Query(
        """
        SELECT p.id AS id, p.name AS name, p.hidden AS hidden,
               COUNT(DISTINCT f.media_id) AS photo_count, NULL AS cover_face_id
        FROM person p
        JOIN face f ON f.person_id = p.id
        JOIN media m ON m.id = f.media_id
        WHERE m.is_trashed = 0 AND p.name IS NOT NULL
        GROUP BY p.id
        ORDER BY p.name COLLATE NOCASE
        """,
    )
    fun observeNamed(): Flow<List<PersonRow>>

    /** Personnes reconnues sur une photo ; la vignette est leur visage sur cette photo. */
    @Query(
        """
        SELECT p.id AS id, p.name AS name, p.hidden AS hidden,
               (SELECT COUNT(DISTINCT f2.media_id) FROM face f2 JOIN media m2 ON m2.id = f2.media_id
                WHERE f2.person_id = p.id AND m2.is_trashed = 0) AS photo_count,
               MAX(f.id) AS cover_face_id
        FROM face f JOIN person p ON p.id = f.person_id
        WHERE f.media_id = :mediaId
        GROUP BY p.id
        ORDER BY MIN(f.box_left)
        """,
    )
    fun observePeopleIn(mediaId: Long): Flow<List<PersonRow>>

    @Query(
        """
        SELECT f.id AS id, f.media_id AS media_id, f.person_id AS person_id, f.box_left AS box_left,
               f.box_top AS box_top, f.box_right AS box_right, f.box_bottom AS box_bottom,
               f.quality AS quality, f.manual AS manual, m.date_modified AS date_modified
        FROM face f JOIN media m ON m.id = f.media_id
        WHERE f.id IN (:ids)
        """,
    )
    suspend fun thumbs(ids: List<Long>): List<FaceThumbRow>

    /** Visages d'une personne, les meilleurs d'abord (choix de la vignette). */
    @Query(
        """
        SELECT f.id AS id, f.media_id AS media_id, f.person_id AS person_id, f.box_left AS box_left,
               f.box_top AS box_top, f.box_right AS box_right, f.box_bottom AS box_bottom,
               f.quality AS quality, f.manual AS manual, m.date_modified AS date_modified
        FROM face f JOIN media m ON m.id = f.media_id
        WHERE f.person_id = :personId AND m.is_trashed = 0
        ORDER BY f.quality DESC, m.date_taken DESC
        LIMIT :limit
        """,
    )
    suspend fun facesOfPerson(personId: Long, limit: Int): List<FaceThumbRow>

    @Query("SELECT * FROM person WHERE id = :id")
    suspend fun person(id: Long): PersonEntity?

    @Query("UPDATE person SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String?)

    @Query("UPDATE person SET hidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("UPDATE person SET cover_face_id = :faceId WHERE id = :id")
    suspend fun setCover(id: Long, faceId: Long?)

    @Query("SELECT id FROM face WHERE person_id = :personId AND media_id = :mediaId ORDER BY quality DESC LIMIT 1")
    suspend fun bestFaceIn(personId: Long, mediaId: Long): Long?

    @Query("UPDATE face SET person_id = :targetId WHERE person_id = :sourceId")
    suspend fun moveFaces(sourceId: Long, targetId: Long)

    @Query("DELETE FROM person WHERE id = :id")
    suspend fun deletePerson(id: Long)

    /**
     * Fusionne [sourceId] dans [targetId] : tous ses visages la rejoignent ; la cible garde
     * son nom, ou prend celui de la source si elle n'en a pas.
     */
    @Transaction
    suspend fun merge(sourceId: Long, targetId: Long) {
        if (sourceId == targetId) return
        val source = person(sourceId) ?: return
        val target = person(targetId) ?: return
        moveFaces(sourceId, targetId)
        if (target.name == null && source.name != null) rename(targetId, source.name)
        deletePerson(sourceId)
    }

    @Query("SELECT id, person_id, manual FROM face WHERE person_id = :personId AND media_id IN (:mediaIds)")
    suspend fun linksIn(personId: Long, mediaIds: List<Long>): List<FaceLink>

    /** Retire des photos d'une personne : décision manuelle, le regroupement n'y revient pas. */
    @Query("UPDATE face SET person_id = NULL, manual = 1 WHERE person_id = :personId AND media_id IN (:mediaIds)")
    suspend fun detach(personId: Long, mediaIds: List<Long>)

    @Query("UPDATE face SET person_id = :personId, manual = :manual WHERE id IN (:ids)")
    suspend fun relink(ids: List<Long>, personId: Long?, manual: Boolean)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun restorePerson(person: PersonEntity)
}
