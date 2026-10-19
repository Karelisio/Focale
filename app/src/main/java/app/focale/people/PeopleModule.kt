package app.focale.people

import android.content.ContentResolver
import app.focale.core.image.ImageComponentContributor
import app.focale.ml.AnalysisStep
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Branche les personnes dans l'analyse (étape « visages ») et dans Coil (vignettes). */
@Module
@InstallIn(SingletonComponent::class)
object PeopleModule {

    @Provides
    @IntoSet
    fun faceStep(step: FaceAnalysisStep): AnalysisStep = step

    @Provides
    @IntoSet
    fun faceThumbnails(resolver: ContentResolver): ImageComponentContributor =
        ImageComponentContributor { registry ->
            registry.add(FaceThumbnailFetcher.Factory(resolver))
            registry.add(FaceThumbnailKeyer())
        }
}
