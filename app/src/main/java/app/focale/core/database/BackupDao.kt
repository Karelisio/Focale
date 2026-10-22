package app.focale.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Query

/** Média désigné par une donnée sauvegardée, avec ce qui le retrouve sur un autre téléphone. */
data class BackupMediaRow(
    val id: Long,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val size: Long,
    @ColumnInfo(name = "date_taken") val dateTaken: Long,
)

/** Média de ce téléphone, candidat à l'appariement d'une sauvegarde restaurée. */
data class LibraryMediaRow(
    val id: Long,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    val size: Long,
    @ColumnInfo(name = "date_taken") val dateTaken: Long,
    @ColumnInfo(name = "date_modified") val dateModified: Long,
    @ColumnInfo(name = "bucket_id") val bucketId: Long,
)

/** Dossier masqué, avec son chemin et son nom quand l'index en a encore un média. */
data class FolderRow(
    @ColumnInfo(name = "bucket_id") val bucketId: Long,
    @ColumnInfo(name = "relative_path") val relativePath: String,
    @ColumnInfo(name = "bucket_name") val bucketName: String,
)

/** Passage de la détection des visages sur un média ; [current] faux : le média a changé depuis. */
data class BackupScanRow(
    @ColumnInfo(name = "media_id") val mediaId: Long,
    val version: Int,
    @ColumnInfo(name = "face_count") val faceCount: Int,
    @ColumnInfo(name = "up_to_date") val current: Boolean,
)

/** Nombre de photos (hors corbeille) d'une personne. */
data class PersonPhotosRow(
    @ColumnInfo(name = "person_id") val personId: Long,
    val photos: Int,
)

/** Album de l'application, avec sa couverture choisie. */
data class AlbumNameRow(
    val id: Long,
    val name: String,
    @ColumnInfo(name = "cover_media_id") val coverMediaId: Long?,
)

/**
 * Lectures et écritures de la sauvegarde des données (`app.focale.backup`), en plus de celles des
 * autres DAO : tout ce qui se lit par tranches (identifiants croissants) pour rester en mémoire
 * bornée sur une grande bibliothèque, et les remplacements de la restauration.
 *
 * La sauvegarde ne retient que les médias hors corbeille : un média de la corbeille est sur le
 * point de disparaître, et son nom de fichier y est changé (`.trashed-…`). Chaque tranche se lit
 * dans l'ordre de la clé primaire, à partir de la précédente : « `+m.is_trashed` » écarte l'index
 * (is_trashed, date_taken), avec lequel chaque tranche relirait et trierait toute la bibliothèque
 * (voir `QueryPlanTest`).
 */
@Dao
interface BackupDao {

    // ── Sauvegarde ───────────────────────────────────────────────────────────────

    /**
     * Médias désignés par une donnée sauvegardée (favori, album, passage de la détection des
     * visages, visage, date d'origine), hors corbeille, par identifiant croissant après [after].
     */
    @Query(
        """
        SELECT m.id AS id, m.relative_path AS relative_path, m.display_name AS display_name, m.size AS size,
               m.date_taken AS date_taken
        FROM media m
        WHERE +m.is_trashed = 0 AND m.id > :after AND (
            EXISTS (SELECT 1 FROM favorite f WHERE f.media_id = m.id)
            OR EXISTS (SELECT 1 FROM album_media am WHERE am.media_id = m.id)
            OR m.id IN (SELECT a.cover_media_id FROM album a WHERE a.cover_media_id IS NOT NULL)
            OR EXISTS (SELECT 1 FROM face_scan s WHERE s.media_id = m.id)
            OR EXISTS (SELECT 1 FROM face fa WHERE fa.media_id = m.id)
            OR EXISTS (SELECT 1 FROM media_date d WHERE d.media_id = m.id)
        )
        ORDER BY m.id
        LIMIT :limit
        """,
    )
    suspend fun mediaRefs(after: Long, limit: Int): List<BackupMediaRow>

    /** Estimation du nombre d'enregistrements d'une sauvegarde (avancement). */
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM favorite) + (SELECT COUNT(*) FROM album) + (SELECT COUNT(*) FROM album_media)
             + (SELECT COUNT(*) FROM person) + (SELECT COUNT(*) FROM person_refusal) + 2 * (SELECT COUNT(*) FROM face_scan)
             + (SELECT COUNT(*) FROM face) + (SELECT COUNT(*) FROM media_date) + (SELECT COUNT(*) FROM search_history)
        """,
    )
    suspend fun recordEstimate(): Int

    @Query("SELECT d.* FROM media_date d JOIN media m ON m.id = d.media_id WHERE m.is_trashed = 0 ORDER BY d.media_id")
    suspend fun mediaDates(): List<MediaDateEntity>

    @Query("SELECT f.* FROM favorite f JOIN media m ON m.id = f.media_id WHERE m.is_trashed = 0 ORDER BY f.media_id")
    suspend fun favorites(): List<FavoriteEntity>

    @Query("SELECT * FROM album ORDER BY id")
    suspend fun albums(): List<AlbumEntity>

    /** Contenu des albums, hors corbeille, par (album, média) croissants après ([album], [media]). */
    @Query(
        """
        SELECT am.* FROM album_media am JOIN media m ON m.id = am.media_id
        WHERE +m.is_trashed = 0 AND (am.album_id, am.media_id) > (:album, :media)
        ORDER BY am.album_id, am.media_id
        LIMIT :limit
        """,
    )
    suspend fun albumMembers(album: Long, media: Long, limit: Int): List<AlbumMediaEntity>

    @Query("SELECT * FROM person ORDER BY id")
    suspend fun persons(): List<PersonEntity>

    @Query(
        """
        SELECT f.person_id AS person_id, COUNT(DISTINCT f.media_id) AS photos
        FROM face f JOIN media m ON m.id = f.media_id
        WHERE f.person_id IS NOT NULL AND m.is_trashed = 0
        GROUP BY f.person_id
        """,
    )
    suspend fun personPhotos(): List<PersonPhotosRow>

    @Query("SELECT * FROM person_refusal ORDER BY person_low, person_high")
    suspend fun refusals(): List<PersonRefusalEntity>

    /** Passages de la détection des visages, hors corbeille, par média croissant après [after]. */
    @Query(
        """
        SELECT s.media_id AS media_id, s.version AS version, s.face_count AS face_count,
               s.analyzed_modified = m.date_modified AS up_to_date
        FROM face_scan s JOIN media m ON m.id = s.media_id
        WHERE +m.is_trashed = 0 AND s.media_id > :after
        ORDER BY s.media_id
        LIMIT :limit
        """,
    )
    suspend fun faceScans(after: Long, limit: Int): List<BackupScanRow>

    /** Visages des médias hors corbeille, empreinte comprise, par identifiant croissant après [after]. */
    @Query(
        """
        SELECT f.* FROM face f JOIN media m ON m.id = f.media_id
        WHERE +m.is_trashed = 0 AND f.id > :after
        ORDER BY f.id
        LIMIT :limit
        """,
    )
    suspend fun faces(after: Long, limit: Int): List<FaceEntity>

    @Query("SELECT * FROM search_history ORDER BY searched_at DESC, query")
    suspend fun searchHistory(): List<SearchHistoryEntity>

    /** Chemin et nom des dossiers [bucketIds] qui ont encore un média dans l'index. */
    @Query(
        """
        SELECT bucket_id, MIN(relative_path) AS relative_path, MIN(bucket_name) AS bucket_name
        FROM media WHERE bucket_id IN (:bucketIds)
        GROUP BY bucket_id
        """,
    )
    suspend fun folders(bucketIds: List<Long>): List<FolderRow>

    // ── Restauration ─────────────────────────────────────────────────────────────

    /** Médias de ce téléphone, hors corbeille, par identifiant croissant après [after] (appariement). */
    @Query(
        """
        SELECT id, relative_path, display_name, size, date_taken, date_modified, bucket_id
        FROM media WHERE +is_trashed = 0 AND id > :after
        ORDER BY id
        LIMIT :limit
        """,
    )
    suspend fun library(after: Long, limit: Int): List<LibraryMediaRow>

    @Query("SELECT id, name, cover_media_id FROM album ORDER BY id")
    suspend fun albumNames(): List<AlbumNameRow>

    /** Personnes des visages des médias [mediaIds] (au plus 500). */
    @Query("SELECT DISTINCT person_id FROM face WHERE media_id IN (:mediaIds) AND person_id IS NOT NULL")
    suspend fun personsIn(mediaIds: List<Long>): List<Long>

    /** Retire les visages des médias [mediaIds] (au plus 500), remplacés par ceux de la sauvegarde. */
    @Query("DELETE FROM face WHERE media_id IN (:mediaIds)")
    suspend fun deleteFacesOf(mediaIds: List<Long>)

    /**
     * Détache les visages des personnes [ids] (au plus 500) qui n'en ont plus aucun hors
     * corbeille : non manuels, le regroupement les reprendra (s'ils ressemblent à une personne).
     */
    @Query(
        """
        UPDATE face SET person_id = NULL, manual = 0
        WHERE person_id IN (:ids) AND NOT EXISTS (
            SELECT 1 FROM face v JOIN media m ON m.id = v.media_id
            WHERE v.person_id = face.person_id AND m.is_trashed = 0
        )
        """,
    )
    suspend fun detachLeftovers(ids: List<Long>)

    /** Photos à relire par la détection des visages : version 0, inférieure à toutes (empreintes à refaire). */
    @Query("UPDATE face_scan SET version = 0 WHERE media_id IN (:mediaIds)")
    suspend fun invalidateScans(mediaIds: List<Long>)

    /** Personnes [ids] (au plus 500) de la liste des personnes : nommées, ou présentes sur [minPhotos] photos. */
    @Query(
        """
        SELECT COUNT(*) FROM (
            SELECT p.id FROM person p
            JOIN face f ON f.person_id = p.id
            JOIN media m ON m.id = f.media_id
            WHERE p.id IN (:ids) AND m.is_trashed = 0
            GROUP BY p.id
            HAVING COUNT(DISTINCT f.media_id) >= :minPhotos OR p.name IS NOT NULL
        )
        """,
    )
    suspend fun listedPeople(ids: List<Long>, minPhotos: Int): Int

    @Query("SELECT COUNT(*) FROM person")
    suspend fun personCount(): Int
}
