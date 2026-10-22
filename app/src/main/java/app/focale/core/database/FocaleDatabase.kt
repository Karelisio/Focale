package app.focale.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Base locale de Focale : index MediaStore + métadonnées propres à l'application.
 *
 * MediaStore reste la source de vérité des fichiers ; l'index n'est qu'un miroir
 * reconstruisible, ce qui autorise une migration destructive de la table `media`.
 */
@Database(
    entities = [
        MediaEntity::class,
        FavoriteEntity::class,
        AlbumEntity::class,
        AlbumMediaEntity::class,
        MediaAnalysisEntity::class,
        MediaLabelEntity::class,
        TrashItemEntity::class,
        VaultItemEntity::class,
        SearchHistoryEntity::class,
        MediaTextEntity::class,
        FaceScanEntity::class,
        FaceEntity::class,
        PersonEntity::class,
        MediaEmbeddingEntity::class,
        PersonRefusalEntity::class,
        VaultAlbumEntity::class,
        MediaDateEntity::class,
    ],
    version = 6,
    exportSchema = true,
)
abstract class FocaleDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun albumDao(): AlbumDao
    abstract fun trashDao(): TrashDao
    abstract fun analysisDao(): AnalysisDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun vaultDao(): VaultDao
    abstract fun textDao(): TextDao
    abstract fun faceDao(): FaceDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun memoryDao(): MemoryDao
    abstract fun backupDao(): BackupDao

    companion object {
        const val NAME = "focale.db"
    }
}
