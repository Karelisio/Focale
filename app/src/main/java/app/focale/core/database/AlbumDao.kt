package app.focale.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Album virtuel avec son nombre de médias visibles et sa couverture. */
data class UserAlbumRow(
    val id: Long,
    val name: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    val count: Int,
    @ColumnInfo(name = "cover_id") val coverId: Long?,
)

@Dao
interface AlbumDao {

    @Query(
        """
        SELECT a.id AS id, a.name AS name, a.created_at AS created_at,
               (SELECT COUNT(*) FROM album_media am JOIN media m ON m.id = am.media_id
                WHERE am.album_id = a.id AND m.is_trashed = 0) AS count,
               COALESCE(
                   (SELECT m.id FROM media m WHERE m.id = a.cover_media_id AND m.is_trashed = 0),
                   (SELECT m.id FROM album_media am JOIN media m ON m.id = am.media_id
                    WHERE am.album_id = a.id AND m.is_trashed = 0
                    ORDER BY m.date_taken DESC, m.id DESC LIMIT 1)
               ) AS cover_id
        FROM album a
        ORDER BY a.created_at DESC
        """,
    )
    fun observeUserAlbums(): Flow<List<UserAlbumRow>>

    @Query("SELECT * FROM album WHERE id = :id")
    fun observe(id: Long): Flow<AlbumEntity?>

    @Insert
    suspend fun insert(album: AlbumEntity): Long

    @Query("UPDATE album SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE album SET cover_media_id = :mediaId WHERE id = :id")
    suspend fun setCover(id: Long, mediaId: Long?)

    @Query("DELETE FROM album WHERE id = :id")
    suspend fun delete(id: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addMedia(entries: List<AlbumMediaEntity>)

    @Query("DELETE FROM album_media WHERE album_id = :albumId AND media_id IN (:mediaIds)")
    suspend fun removeMedia(albumId: Long, mediaIds: List<Long>)
}
