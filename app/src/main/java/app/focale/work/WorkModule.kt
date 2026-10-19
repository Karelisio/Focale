package app.focale.work

import androidx.work.WorkManager
import app.focale.core.di.AppInitializer
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Planification des tâches de fond au démarrage. WorkManager est obtenu de façon
 * paresseuse : sa configuration dépend de l'Application, encore en cours d'injection.
 */
@Module
@InstallIn(SingletonComponent::class)
object WorkModule {

    @Provides
    @IntoSet
    fun trashPurge(workManager: Lazy<WorkManager>): AppInitializer =
        AppInitializer { TrashPurgeWorker.schedule(workManager.get()) }
}
