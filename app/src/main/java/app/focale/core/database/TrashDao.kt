package app.focale.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** Corbeille applicative (Android 10 uniquement, faute de corbeille MediaStore). */
@Dao
interface TrashDao {

    @Query("SELECT * FROM trash_item ORDER BY trashed_at DESC")
    fun observeAll(): Flow<List<TrashItemEntity>>

    @Query("SELECT * FROM trash_item WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<TrashItemEntity>

    @Query("SELECT * FROM trash_item WHERE trashed_at < :before")
    suspend fun expired(before: Long): List<TrashItemEntity>

    @Query("SELECT * FROM trash_item")
    suspend fun all(): List<TrashItemEntity>

    @Insert
    suspend fun insert(item: TrashItemEntity): Long

    @Query("DELETE FROM trash_item WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}
