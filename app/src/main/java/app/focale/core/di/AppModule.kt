package app.focale.core.di

import android.content.ContentResolver
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import androidx.work.WorkManager
import app.focale.core.database.AlbumDao
import app.focale.core.database.AnalysisDao
import app.focale.core.database.DatabaseMigrations
import app.focale.core.database.EmbeddingDao
import app.focale.core.database.FaceDao
import app.focale.core.database.FavoriteDao
import app.focale.core.database.FocaleDatabase
import app.focale.core.database.MediaDao
import app.focale.core.database.MemoryDao
import app.focale.core.database.SearchHistoryDao
import app.focale.core.database.TextDao
import app.focale.core.database.TrashDao
import app.focale.core.database.VaultDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Dispatcher des entrées/sorties (disque, ContentResolver). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Dispatcher des calculs (analyse d'image, regroupements). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

/** Portée applicative, survit aux écrans (synchronisation, observateurs). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

/** DataStore des réglages utilisateur. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsStore

/** DataStore du coffre-fort (empreinte du code, préférences d'accès). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class VaultStore

/** DataStore de l'état technique (synchronisation, versions MediaStore). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class StateStore

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @IoDispatcher
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @ApplicationScope
    fun applicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)

    @Provides
    fun contentResolver(@ApplicationContext context: Context): ContentResolver = context.contentResolver

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): FocaleDatabase =
        Room.databaseBuilder(context, FocaleDatabase::class.java, FocaleDatabase.NAME)
            .addMigrations(*DatabaseMigrations.ALL)
            .build()

    @Provides
    fun mediaDao(database: FocaleDatabase): MediaDao = database.mediaDao()

    @Provides
    fun favoriteDao(database: FocaleDatabase): FavoriteDao = database.favoriteDao()

    @Provides
    fun albumDao(database: FocaleDatabase): AlbumDao = database.albumDao()

    @Provides
    fun trashDao(database: FocaleDatabase): TrashDao = database.trashDao()

    @Provides
    fun analysisDao(database: FocaleDatabase): AnalysisDao = database.analysisDao()

    @Provides
    fun searchHistoryDao(database: FocaleDatabase): SearchHistoryDao = database.searchHistoryDao()

    @Provides
    @Singleton
    @SettingsStore
    fun settingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("settings") }

    @Provides
    @Singleton
    @StateStore
    fun stateDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("state") }

    @Provides
    @Singleton
    @VaultStore
    fun vaultDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create { context.preferencesDataStoreFile("vault") }

    @Provides
    fun vaultDao(database: FocaleDatabase): VaultDao = database.vaultDao()

    @Provides
    fun textDao(database: FocaleDatabase): TextDao = database.textDao()

    @Provides
    fun faceDao(database: FocaleDatabase): FaceDao = database.faceDao()

    @Provides
    fun embeddingDao(database: FocaleDatabase): EmbeddingDao = database.embeddingDao()

    @Provides
    fun memoryDao(database: FocaleDatabase): MemoryDao = database.memoryDao()

    @Provides
    @Singleton
    fun workManager(@ApplicationContext context: Context): WorkManager = WorkManager.getInstance(context)
}
