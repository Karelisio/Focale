package app.focale

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import app.focale.core.di.AppInitializer
import app.focale.core.image.ImageComponentContributor
import app.focale.core.image.ImageLoaderFactory
import app.focale.core.mediastore.MediaChangeObserver
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FocaleApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var mediaChangeObserver: MediaChangeObserver
    @Inject lateinit var appInitializers: Set<@JvmSuppressWildcards AppInitializer>
    @Inject lateinit var imageContributors: Set<@JvmSuppressWildcards ImageComponentContributor>

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setMinimumLoggingLevel(android.util.Log.WARN)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Synchronise l'index à chaque retour au premier plan et suit les changements MediaStore.
        ProcessLifecycleOwner.get().lifecycle.addObserver(mediaChangeObserver)
        appInitializers.forEach { it.initialize(this) }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoaderFactory.create(context, imageContributors)
}
