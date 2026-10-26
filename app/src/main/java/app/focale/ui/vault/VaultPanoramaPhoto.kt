package app.focale.ui.vault

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import app.focale.panorama.PhotoStreams
import app.focale.panorama.SphereTexture
import app.focale.ui.viewer.PanoramaPhoto
import app.focale.vault.VaultItem
import coil3.compose.AsyncImagePainter
import coil3.compose.rememberAsyncImagePainter
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.flow.Flow
import me.saket.telephoto.zoomable.ZoomableImageSource

/**
 * Photo panoramique du coffre, déchiffrée en mémoire seulement ([streams]) : texture d'une sphère
 * décodée comme celle de la galerie ; panorama à plat décodé par Coil (cache mémoire, purgé au
 * verrouillage) au plus à [FLAT_MAX_SIDE] pixels de long, sans tuiles faute de fichier en clair,
 * et donné tout fait à telephoto, qui ne passe pas par son chargement Coil (il demande l'écriture
 * d'un cache disque pour découper l'image).
 */
@Immutable
internal class VaultPanoramaPhoto(private val item: VaultItem, private val streams: PhotoStreams) : PanoramaPhoto {
    override val key: Any get() = item.id
    override val contentDescription: String get() = item.displayName

    override fun texture(context: Context, maxTextureSize: Int, maxPixels: Long): Bitmap? =
        SphereTexture.decode(streams, orientation = null, maxTextureSize, maxPixels)

    @Composable
    override fun flatImage(): ZoomableImageSource {
        val context = LocalContext.current
        val request = remember(item.fileName) {
            ImageRequest.Builder(context)
                .data(item.image())
                .size(FLAT_MAX_SIDE)
                .diskCachePolicy(CachePolicy.DISABLED)
                .apply { item.thumbFileName?.let { placeholderMemoryCacheKey(MemoryCache.Key(thumbnailKey(it))) } }
                .build()
        }
        return remember(request) { DecodedImageSource(request) }
    }

    override fun equals(other: Any?): Boolean = other is VaultPanoramaPhoto && other.item == item

    override fun hashCode(): Int = item.hashCode()

    private companion object {
        /** Côté le plus long d'un panorama à plat du coffre : 4 096 × 683 pixels pour un 6:1. */
        const val FLAT_MAX_SIDE = 4096
    }
}

/** Image décodée par Coil dans son cache mémoire, donnée telle quelle à telephoto, sans tuiles. */
private class DecodedImageSource(private val request: ImageRequest) : ZoomableImageSource {
    @Composable
    override fun resolve(canvasSize: Flow<Size>): ZoomableImageSource.ResolveResult {
        val painter = rememberAsyncImagePainter(request)
        val state by painter.state.collectAsState()
        return ZoomableImageSource.ResolveResult(
            delegate = if (state is AsyncImagePainter.State.Success) ZoomableImageSource.PainterDelegate(painter) else null,
        )
    }
}
