package app.focale.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface VaultDao {

    @Query("SELECT * FROM vault_item ORDER BY date_taken DESC, id DESC")
    fun observeAll(): Flow<List<VaultItemEntity>>

    @Insert
    suspend fun insert(item: VaultItemEntity): Long

    /** Insertion groupée, en une transaction (restauration d'une sauvegarde). */
    @Insert
    suspend fun insertAll(items: List<VaultItemEntity>): List<Long>

    @Query("DELETE FROM vault_item WHERE id IN (:ids)")
    suspend fun delete(ids: List<Long>)
}
