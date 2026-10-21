package app.focale.core.image

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.CancellationSignal
import android.util.Size
import app.focale.core.model.MediaItem
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.size.pxOrElse
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Job

/**
 * Donnée Coil représentant la miniature système d'un média.
 *
 * [dateModified] fait partie de la clé : une photo éditée obtient une nouvelle miniature.
 */
data class MediaThumbnail(
    val id: Long,
    val uri: Uri,
    val dateModified: Long,
)

fun MediaItem.thumbnail(): MediaThumbnail = MediaThumbnail(id, uri, dateModified)

/**
 * Lie la clé mémoire d'une image chargée par son URI à la version du fichier : une photo
 * modifiée sur place (rotation, édition) n'est jamais servie depuis l'ancienne image.
 */
fun ImageRequest.Builder.version(item: MediaItem): ImageRequest.Builder =
    memoryCacheKeyExtra("modified", item.dateModified.toString())

/**
 * Charge les miniatures via `ContentResolver.loadThumbnail`, qui s'appuie sur le cache
 * de miniatures de MediaStore : beaucoup plus rapide que de décoder l'original, y
 * compris pour les vidéos.
 */
class MediaThumbnailFetcher(
    private val data: MediaThumbnail,
    private val options: Options,
    private val resolver: ContentResolver,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val width = options.size.width.pxOrElse { DEFAULT_SIZE }
        val height = options.size.height.pxOrElse { DEFAULT_SIZE }
        val signal = CancellationSignal()
        coroutineContext[Job]?.invokeOnCompletion { if (it != null) signal.cancel() }
        val bitmap: Bitmap = resolver.loadThumbnail(data.uri, Size(width, height), signal)
        return ImageFetchResult(
            image = bitmap.asImage(),
            isSampled = true,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val resolver: ContentResolver) : Fetcher.Factory<MediaThumbnail> {
        override fun create(data: MediaThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            MediaThumbnailFetcher(data, options, resolver)
    }

    companion object {
        private const val DEFAULT_SIZE = 384
    }
}

class MediaThumbnailKeyer : Keyer<MediaThumbnail> {
    override fun key(data: MediaThumbnail, options: Options): String =
        "thumb:${data.id}:${data.dateModified}"
}

/**
 * Tailles de miniatures normalisées : quelques paliers seulement, pour maximiser les
 * réutilisations du cache mémoire entre écrans et niveaux de zoom de la grille.
 */
object ThumbnailSizes {
    private val buckets = intArrayOf(192, 288, 384, 512, 768)

    fun bucketFor(cellPx: Int): Int = buckets.firstOrNull { it >= cellPx } ?: buckets.last()

    /** Clé mémoire explicite, partagée par la grille et le placeholder de la visionneuse. */
    fun memoryKey(id: Long, dateModified: Long, bucket: Int): String = "thumb:$id:$dateModified:$bucket"

    /** Palier utilisé en dernier par la grille (pour retrouver la miniature en mémoire). */
    @Volatile
    var lastGridBucket: Int = 384
}
