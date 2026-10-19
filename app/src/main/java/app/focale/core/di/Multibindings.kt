package app.focale.core.di

import android.app.Application
import app.focale.core.image.ImageComponentContributor
import app.focale.ml.AnalysisStep
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds

/** Initialisation exécutée au démarrage de l'application (planification de tâches…). */
fun interface AppInitializer {
    fun initialize(application: Application)
}

/** Déclare les ensembles multi-liés, qui peuvent rester vides. */
@Module
@InstallIn(SingletonComponent::class)
abstract class MultibindingsModule {
    @Multibinds
    abstract fun appInitializers(): Set<AppInitializer>

    @Multibinds
    abstract fun imageComponentContributors(): Set<ImageComponentContributor>

    @Multibinds
    abstract fun analysisSteps(): Set<AnalysisStep>
}
