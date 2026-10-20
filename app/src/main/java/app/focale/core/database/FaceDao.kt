package app.focale.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
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
        inheritRefusals(sourceId, targetId)
        if (source.pinned) setPinned(targetId, true)
        deletePerson(sourceId)
    }

    /** Reporte sur [targetId] les refus de fusion de [sourceId], avant la suppression de celle-ci. */
    @Query(
        """
        INSERT OR IGNORE INTO person_refusal (person_low, person_high)
        SELECT MIN(:targetId, other), MAX(:targetId, other) FROM (
            SELECT CASE WHEN person_low = :sourceId THEN person_high ELSE person_low END AS other
            FROM person_refusal WHERE person_low = :sourceId OR person_high = :sourceId
        ) WHERE other != :targetId
        """,
    )
    suspend fun inheritRefusals(sourceId: Long, targetId: Long)

    @Query("SELECT id, person_id, manual FROM face WHERE person_id = :personId AND media_id IN (:mediaIds)")
    suspend fun linksIn(personId: Long, mediaIds: List<Long>): List<FaceLink>

    /** Retire des photos d'une personne : décision manuelle, le regroupement n'y revient pas. */
    @Query("UPDATE face SET person_id = NULL, manual = 1 WHERE person_id = :personId AND media_id IN (:mediaIds)")
    suspend fun detach(personId: Long, mediaIds: List<Long>)

    @Query("UPDATE face SET person_id = :personId, manual = :manual WHERE id IN (:ids)")
    suspend fun relink(ids: List<Long>, personId: Long?, manual: Boolean)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun restorePerson(person: PersonEntity)

    // ── Revue des visages et photos ensemble ─────────────────────────────────────

    /** Visages d'une personne hors corbeille, avec leur empreinte (revue des visages). */
    @Query(
        """
        SELECT f.id AS id, f.media_id AS media_id, f.person_id AS person_id, f.box_left AS box_left,
               f.box_top AS box_top, f.box_right AS box_right, f.box_bottom AS box_bottom,
               f.quality AS quality, f.manual AS manual, m.date_modified AS date_modified,
               f.embedding AS embedding
        FROM face f JOIN media m ON m.id = f.media_id
        WHERE f.person_id = :personId AND m.is_trashed = 0
        ORDER BY f.id
        """,
    )
    fun observeReviewFaces(personId: Long): Flow<List<FaceReviewRow>>

    @Query("SELECT id, person_id, manual FROM face WHERE person_id = :personId AND id IN (:faceIds)")
    suspend fun linksOf(personId: Long, faceIds: List<Long>): List<FaceLink>

    /**
     * Rattache à la main des visages de [personId] à [targetId] (null : simple retrait), si
     * celle-ci existe toujours : le regroupement automatique n'y revient pas.
     */
    @Query(
        """
        UPDATE face SET person_id = :targetId, manual = 1
        WHERE person_id = :personId AND id IN (:faceIds)
          AND (:targetId IS NULL OR EXISTS (SELECT 1 FROM person WHERE id = :targetId))
        """,
    )
    suspend fun reassign(personId: Long, faceIds: List<Long>, targetId: Long?): Int

    @Query("DELETE FROM person WHERE id = :id AND NOT EXISTS (SELECT 1 FROM face WHERE person_id = :id)")
    suspend fun deleteIfEmpty(id: Long)

    /**
     * Rattache à la main [faceIds] (voir [reassign]) et renvoie leurs liens d'origine
     * (annulation) et le nombre de visages déplacés.
     */
    @Transaction
    suspend fun transferFaces(personId: Long, faceIds: List<Long>, targetId: Long?): Pair<List<FaceLink>, Int> {
        val chunks = faceIds.chunked(SQL_CHUNK)
        val links = chunks.flatMap { linksOf(personId, it) }
        return links to chunks.sumOf { reassign(personId, it, targetId) }
    }

    /**
     * Crée [person] et y déplace [faceIds] d'un seul tenant : vide, elle serait supprimée par
     * le regroupement avant d'être remplie. Renvoie son identifiant et le résultat de [transferFaces].
     */
    @Transaction
    suspend fun transferToNewPerson(personId: Long, faceIds: List<Long>, person: PersonEntity): Pair<Long, Pair<List<FaceLink>, Int>> {
        val created = insertPerson(person)
        val result = transferFaces(personId, faceIds, created)
        if (result.second == 0) deleteIfEmpty(created)
        return created to result
    }

    /**
     * Annule un retrait ou un déplacement : personne recréée si besoin, visages rattachés
     * comme avant ([links] : visage → manuel), personne créée pour l'occasion supprimée.
     */
    @Transaction
    suspend fun restoreFaces(person: PersonEntity, links: List<Pair<Long, Boolean>>, created: Long? = null) {
        restorePerson(person)
        links.groupBy({ it.second }, { it.first }).forEach { (manual, ids) ->
            ids.chunked(SQL_CHUNK).forEach { relink(it, person.id, manual) }
        }
        if (created != null) deleteIfEmpty(created)
    }

    /** Autres personnes présentes sur les photos (hors corbeille) de [personId], avec le nombre de ces photos. */
    @Query(
        """
        SELECT o.person_id AS person_id, COUNT(DISTINCT o.media_id) AS photo_count
        FROM face f
        JOIN face o ON o.media_id = f.media_id
        JOIN media m ON m.id = f.media_id
        WHERE f.person_id = :personId AND o.person_id IS NOT NULL AND o.person_id != :personId
          AND m.is_trashed = 0
        GROUP BY o.person_id
        """,
    )
    fun observeTogetherCounts(personId: Long): Flow<List<TogetherRow>>

    // ── Liste des personnes : tri, épinglage, suggestions de fusion ─────────────────

    /**
     * Mêmes personnes que [observePeople], avec leur épinglage et la date de leur photo la
     * plus récente : épinglées d'abord, puis les plus présentes. Les autres tris se font
     * sur cette liste (voir `PeopleRepository`).
     */
    @Query(
        """
        SELECT p.id AS id, p.name AS name, p.hidden AS hidden, p.pinned AS pinned,
               COUNT(DISTINCT f.media_id) AS photo_count, MAX(m.date_taken) AS latest_taken,
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
        ORDER BY p.pinned DESC, photo_count DESC, p.id
        """,
    )
    fun observePeopleList(includeHidden: Boolean, minPhotos: Int): Flow<List<PersonListRow>>

    @Query("UPDATE person SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    /**
     * Personnes candidates aux suggestions de fusion : celles de la liste, hors masquées.
     * [MergeCandidateRow.faceCount] et [MergeCandidateRow.faceSum] changent dès qu'un visage
     * rejoint ou quitte la personne : son empreinte moyenne est alors à recalculer.
     */
    @Query(
        """
        SELECT p.id AS id, p.name AS name, COUNT(DISTINCT f.media_id) AS photo_count,
               COUNT(*) AS face_count, SUM(f.id) AS face_sum
        FROM person p
        JOIN face f ON f.person_id = p.id
        JOIN media m ON m.id = f.media_id
        WHERE m.is_trashed = 0 AND p.hidden = 0
        GROUP BY p.id
        HAVING photo_count >= :minPhotos OR p.name IS NOT NULL
        """,
    )
    fun observeMergeCandidates(minPhotos: Int): Flow<List<MergeCandidateRow>>

    /** Empreintes des visages (hors corbeille) de [personIds], par tranches d'identifiant croissant. */
    @Query(
        """
        SELECT f.id AS id, f.person_id AS person_id, f.embedding AS embedding
        FROM face f JOIN media m ON m.id = f.media_id
        WHERE f.person_id IN (:personIds) AND m.is_trashed = 0 AND f.id > :after
        ORDER BY f.id
        LIMIT :limit
        """,
    )
    suspend fun personEmbeddings(personIds: List<Long>, after: Long, limit: Int): List<PersonEmbeddingRow>

    /** « Pas la même » pour [low] < [high], si les deux personnes existent encore. */
    @Query(
        """
        INSERT OR IGNORE INTO person_refusal (person_low, person_high)
        SELECT :low, :high
        WHERE EXISTS (SELECT 1 FROM person WHERE id = :low) AND EXISTS (SELECT 1 FROM person WHERE id = :high)
        """,
    )
    suspend fun refuse(low: Long, high: Long)

    @Query("SELECT * FROM person_refusal")
    fun observeRefusals(): Flow<List<PersonRefusalEntity>>
}

/** Taille des tranches d'identifiants des retouches (limite des paramètres SQLite). */
private const val SQL_CHUNK = 500

/** Visage d'une personne et son empreinte (revue des visages). */
data class FaceReviewRow(
    @Embedded val thumb: FaceThumbRow,
    val embedding: ByteArray,
)

/** Autre personne présente sur les photos d'une personne, et le nombre de ces photos. */
data class TogetherRow(
    @ColumnInfo(name = "person_id") val personId: Long,
    @ColumnInfo(name = "photo_count") val photoCount: Int,
)

/** Personne de la liste : comme [PersonRow], avec l'épinglage et la date de sa photo la plus récente. */
data class PersonListRow(
    val id: Long,
    val name: String?,
    val hidden: Boolean,
    val pinned: Boolean,
    @ColumnInfo(name = "photo_count") val photoCount: Int,
    @ColumnInfo(name = "latest_taken") val latestTaken: Long,
    @ColumnInfo(name = "cover_face_id") val coverFaceId: Long?,
)

/** Personne candidate à une suggestion de fusion, avec la signature de ses visages. */
data class MergeCandidateRow(
    val id: Long,
    val name: String?,
    @ColumnInfo(name = "photo_count") val photoCount: Int,
    @ColumnInfo(name = "face_count") val faceCount: Int,
    /** Somme des identifiants de ses visages : change avec eux, comme [faceCount]. */
    @ColumnInfo(name = "face_sum") val faceSum: Long,
)

/** Empreinte d'un visage rattaché à une personne (calcul des empreintes moyennes). */
class PersonEmbeddingRow(
    val id: Long,
    @ColumnInfo(name = "person_id") val personId: Long,
    val embedding: ByteArray,
)
