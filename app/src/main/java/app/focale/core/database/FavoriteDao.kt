package app.focale.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE media_id = :mediaId)")
    fun observeIsFavorite(mediaId: Long): Flow<Boolean>

    @Query("SELECT media_id FROM favorite WHERE media_id IN (:mediaIds)")
    suspend fun favoritesAmong(mediaIds: List<Long>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(favorites: List<FavoriteEntity>)

    @Query("DELETE FROM favorite WHERE media_id IN (:mediaIds)")
    suspend fun delete(mediaIds: List<Long>)

    @Query(
        "SELECT COUNT(*) FROM favorite f JOIN media m ON m.id = f.media_id WHERE m.is_trashed = 0",
    )
    fun observeCount(): Flow<Int>
}
