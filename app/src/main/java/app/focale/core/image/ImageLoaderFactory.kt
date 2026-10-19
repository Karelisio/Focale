package app.focale.core.image

import android.content.Context
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.gif.AnimatedImageDecoder
import coil3.memory.MemoryCache
import coil3.request.crossfade
import coil3.video.VideoFrameDecoder

/**
 * Construit l'`ImageLoader` Coil de l'application.
 *
 * Pas de cache disque : les sources sont locales (MediaStore) et le contenu du coffre
 * ne doit jamais être écrit en clair sur le disque. Le cache mémoire est généreux
 * pour garder le défilement fluide sur de très grandes bibliothèques.
 */
object ImageLoaderFactory {

    fun create(context: Context, contributors: Set<ImageComponentContributor> = emptySet()): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(MediaThumbnailFetcher.Factory(context.contentResolver))
                add(MediaThumbnailKeyer())
                add(AnimatedImageDecoder.Factory())
                add(VideoFrameDecoder.Factory())
                contributors.forEach { it.contribute(this) }
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, MEMORY_CACHE_PERCENT)
                    .build()
            }
            .diskCache(null)
            .crossfade(false)
            .build()

    private const val MEMORY_CACHE_PERCENT = 0.3
}

/** Permet à une fonctionnalité d'ajouter ses fetchers / décodeurs Coil. */
fun interface ImageComponentContributor {
    fun contribute(registry: ComponentRegistry.Builder)
}
