package app.focale.update

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import app.focale.core.di.AppInitializer
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/**
 * Recherche des mises à jour au passage au premier plan, et non au démarrage du
 * processus : un réveil en arrière-plan (widget, tâche) ne déclenche aucune requête.
 */
@Module
@InstallIn(SingletonComponent::class)
object UpdateModule {

    @Provides
    @IntoSet
    fun updateCheck(repository: Lazy<UpdateRepository>): AppInitializer = AppInitializer {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = repository.get().checkIfDue()
            },
        )
    }
}
