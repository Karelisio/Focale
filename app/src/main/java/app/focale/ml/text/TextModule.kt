package app.focale.ml.text

import app.focale.ml.AnalysisStep
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Texte dans les photos : lecteur ML Kit et étape d'analyse de fond. */
@Module
@InstallIn(SingletonComponent::class)
object TextModule {

    @Provides
    fun photoTextReader(reader: MlKitPhotoTextReader): PhotoTextReader = reader

    @Provides
    @IntoSet
    fun textStep(step: TextRecognitionStep): AnalysisStep = step
}
