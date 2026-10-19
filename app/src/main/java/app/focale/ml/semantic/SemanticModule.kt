package app.focale.ml.semantic

import app.focale.ml.AnalysisStep
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Recherche en langage naturel : étape d'analyse des empreintes. */
@Module
@InstallIn(SingletonComponent::class)
object SemanticModule {

    @Provides
    @IntoSet
    fun embeddingStep(step: EmbeddingStep): AnalysisStep = step
}
