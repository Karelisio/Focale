package app.focale.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Album du coffre avec son nombre d'éléments et sa couverture. */
data class VaultAlbumRow(
    @Embedded val album: VaultAlbumEntity,
    val count: Int,
    /** Miniature chiffrée de l'élément le plus récent qui en a une. */
    @ColumnInfo(name = "cover_thumb") val coverThumb: String?,
)

/** Élément du coffre et le média de la galerie dont il est la copie. */
data class VaultOriginalRow(
    @ColumnInfo(name = "item_id") val itemId: Long,
    @ColumnInfo(name = "media_id") val mediaId: Long,
)

/**
 * Accès au coffre. Les copies d'un import en cours (`importing`) n'apparaissent dans aucune
 * liste : elles n'entrent au coffre qu'une fois leur original supprimé de la galerie.
 */
@Dao
interface VaultDao {

    @Query("SELECT * FROM vault_item WHERE importing = 0 ORDER BY date_taken DESC, id DESC")
    fun observeAll(): Flow<List<VaultItemEntity>>

    @Query("SELECT * FROM vault_item WHERE album_id = :albumId AND importing = 0 ORDER BY date_taken DESC, id DESC")
    fun observeInAlbum(albumId: Long): Flow<List<VaultItemEntity>>

    @Query("SELECT * FROM vault_item WHERE album_id = :albumId AND importing = 0 ORDER BY date_taken DESC, id DESC")
    suspend fun inAlbum(albumId: Long): List<VaultItemEntity>

    /** 500 identifiants au plus. */
    @Query("SELECT * FROM vault_item WHERE id IN (:ids)")
    suspend fun byIds(ids: List<Long>): List<VaultItemEntity>

    // ── Journal de l'import ──────────────────────────────────────────────────────

    /** Copies déjà faites des médias [mediaIds] de la galerie (500 au plus), cachées ou non. */
    @Query("SELECT * FROM vault_item WHERE source_media_id IN (:mediaIds)")
    suspend fun copiesOf(mediaIds: List<Long>): List<VaultItemEntity>

    /** Copies d'imports inachevés. */
    @Query("SELECT * FROM vault_item WHERE importing = 1")
    suspend fun importing(): List<VaultItemEntity>

    /**
     * Éléments du coffre dont l'original est resté dans la galerie (import interrompu) : ceux que
     * l'index connaît encore.
     */
    @Query(
        """
        SELECT v.id AS item_id, m.id AS media_id FROM vault_item v JOIN media m ON m.id = v.source_media_id
        WHERE v.importing = 0 AND m.is_trashed = 0
        """,
    )
    fun observeLeftInGallery(): Flow<List<VaultOriginalRow>>

    @Query(
        """
        SELECT v.id AS item_id, m.id AS media_id FROM vault_item v JOIN media m ON m.id = v.source_media_id
        WHERE v.importing = 0 AND m.is_trashed = 0
        """,
    )
    suspend fun leftInGallery(): List<VaultOriginalRow>

    /**
     * Médias de la galerie identiques en apparence (nom, dossier, taille, date) à un élément du
     * coffre qui ne se sait la copie d'aucun : imports interrompus des versions précédentes.
     */
    @Query(
        """
        SELECT v.id AS item_id, m.id AS media_id FROM vault_item v JOIN media m
          ON m.display_name = v.display_name AND m.relative_path = v.original_path
          AND m.size = v.size AND m.date_taken = v.date_taken
        WHERE v.source_media_id IS NULL AND v.importing = 0 AND m.is_trashed = 0
        """,
    )
    suspend fun lookalikesInGallery(): List<VaultOriginalRow>

    /**
     * Copies dont l'original a quitté la galerie : elles entrent au coffre, dans l'album [albumId]
     * si elles n'en ont pas et qu'il existe ; 500 au plus.
     */
    @Query(
        """
        UPDATE vault_item SET importing = 0, source_media_id = NULL,
          album_id = COALESCE(album_id, (SELECT id FROM vault_album WHERE id = :albumId))
        WHERE id IN (:ids)
        """,
    )
    suspend fun commitImported(ids: List<Long>, albumId: Long?)

    /** Copies gardées alors que leur original est resté dans la galerie : signalées dans le coffre. */
    @Query("UPDATE vault_item SET importing = 0 WHERE id IN (:ids)")
    suspend fun keepWithOriginal(ids: List<Long>)

    @Query("UPDATE vault_item SET source_media_id = :mediaId WHERE id = :itemId")
    suspend fun setSource(itemId: Long, mediaId: Long)

    /** Fichiers chiffrés référencés : médias et miniatures. */
    @Query("SELECT file_name FROM vault_item UNION SELECT thumb_file_name FROM vault_item WHERE thumb_file_name IS NOT NULL")
    suspend fun fileNames(): List<String>

    @Insert
    suspend fun insert(item: VaultItemEntity): Long

    /** Le nombre d'éléments visibles du coffre. */
    @Query("SELECT COUNT(*) FROM vault_item WHERE importing = 0")
    suspend fun count(): Int

    /** Insertion groupée, en une transaction (restauration d'une sauvegarde). */
    @Insert
    suspend fun insertAll(items: List<VaultItemEntity>): List<Long>

    /** Élément tout juste chiffré : hors album si le sien a été supprimé pendant l'import. */
    @Transaction
    suspend fun insertImported(item: VaultItemEntity): Long =
        insert(if (item.albumId == null || album(item.albumId) != null) item else item.copy(albumId = null))

    @Query("DELETE FROM vault_item WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)

    /**
     * Range des éléments dans l'album [albumId], hors album si null ou s'il n'existe plus ;
     * 500 identifiants au plus.
     */
    @Query("UPDATE vault_item SET album_id = (SELECT id FROM vault_album WHERE id = :albumId) WHERE id IN (:ids)")
    suspend fun setAlbum(ids: List<Long>, albumId: Long?)

    // ── Albums ───────────────────────────────────────────────────────────────────

    @Query("$ALBUM_ROWS ORDER BY a.created_at DESC, a.id DESC")
    fun observeAlbums(): Flow<List<VaultAlbumRow>>

    @Query("$ALBUM_ROWS WHERE a.id = :id")
    fun observeAlbum(id: Long): Flow<VaultAlbumRow?>

    @Query("SELECT * FROM vault_album ORDER BY created_at, id")
    suspend fun albums(): List<VaultAlbumEntity>

    @Query("SELECT * FROM vault_album WHERE id = :id")
    suspend fun album(id: Long): VaultAlbumEntity?

    /** Album lié au dossier [bucketId], le plus ancien s'il y en a plusieurs. */
    @Query("SELECT * FROM vault_album WHERE source_bucket_id = :bucketId ORDER BY created_at, id LIMIT 1")
    suspend fun albumOfSource(bucketId: Long): VaultAlbumEntity?

    @Insert
    suspend fun insertAlbum(album: VaultAlbumEntity): Long

    @Query("UPDATE vault_album SET name = :name WHERE id = :id")
    suspend fun renameAlbum(id: Long, name: String)

    @Query("UPDATE vault_album SET last_import_at = :at WHERE id = :id")
    suspend fun setLastImport(id: Long, at: Long)

    /** Supprime l'album ; ses éléments restent dans le coffre, hors album (clé étrangère). */
    @Query("DELETE FROM vault_album WHERE id = :id")
    suspend fun deleteAlbum(id: Long)

    // ── Nouveaux médias du dossier d'origine ─────────────────────────────────────

    /**
     * Médias du dossier d'origine de l'album [albumId] arrivés depuis son dernier import, à
     * [NEW_MEDIA_MARGIN_MS] près, hors corbeille. Les originaux importés ayant quitté
     * MediaStore, la marge ne fait que reproposer ceux dont la suppression a échoué juste avant.
     */
    @Query(NEW_IN_SOURCE)
    fun observeNewInSource(albumId: Long): Flow<List<MediaEntity>>

    @Query(NEW_IN_SOURCE)
    suspend fun newInSource(albumId: Long): List<MediaEntity>

    @Query("SELECT MIN(date_added) FROM media WHERE id IN (:ids)")
    suspend fun oldestDateAdded(ids: List<Long>): Long?

    /**
     * Ajoute en une transaction les éléments d'une sauvegarde restaurée : chaque groupe rejoint
     * son album, celui de même nom et de même dossier d'origine s'il existe, un nouvel album
     * sinon (l'identifiant des clés ne sert qu'à les distinguer) ; la clé null regroupe les
     * éléments sans album.
     */
    @Transaction
    suspend fun insertRestored(groups: Map<VaultAlbumEntity?, List<VaultItemEntity>>) {
        val existing = albums()
        for ((album, items) in groups) {
            val albumId = album?.let { wanted ->
                existing.firstOrNull {
                    it.name == wanted.name && it.sourceBucketId == wanted.sourceBucketId && it.sourcePath == wanted.sourcePath
                }?.id ?: insertAlbum(wanted.copy(id = 0))
            }
            if (items.isNotEmpty()) insertAll(items.map { it.copy(albumId = albumId) })
        }
    }
}

/**
 * Tolérance sur la date du dernier import : `DATE_ADDED` est à la seconde près, et un média
 * peut entrer dans l'index un peu après son arrivée dans MediaStore.
 */
private const val NEW_MEDIA_MARGIN_MS = 60_000L

private const val ALBUM_ROWS = """
    SELECT a.*,
           (SELECT COUNT(*) FROM vault_item i WHERE i.album_id = a.id AND i.importing = 0) AS count,
           (SELECT i.thumb_file_name FROM vault_item i WHERE i.album_id = a.id AND i.importing = 0
              AND i.thumb_file_name IS NOT NULL
            ORDER BY i.date_taken DESC, i.id DESC LIMIT 1) AS cover_thumb
    FROM vault_album a
"""

/** Les médias dont le coffre a déjà une copie (import inachevé) ne sont pas nouveaux. */
private const val NEW_IN_SOURCE = """
    SELECT m.* FROM media m JOIN vault_album a ON m.bucket_id = a.source_bucket_id
    WHERE a.id = :albumId AND m.is_trashed = 0
      AND m.date_added >= COALESCE(a.last_import_at, 0) - $NEW_MEDIA_MARGIN_MS
      AND m.id NOT IN (SELECT source_media_id FROM vault_item WHERE source_media_id IS NOT NULL)
    ORDER BY m.date_taken DESC, m.id DESC
"""
