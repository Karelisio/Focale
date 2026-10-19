package app.focale.people

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.CancellationSignal
import android.util.Size
import androidx.compose.runtime.Immutable
import androidx.core.graphics.scale
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.pxOrElse
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Job

/**
 * Donnée Coil : vignette carrée centrée sur un visage, découpée dans sa photo. Comme
 * pour les miniatures, [dateModified] fait partie de la clé de cache (photo éditée).
 */
@Immutable
data class FaceThumbnail(
    val faceId: Long,
    val mediaId: Long,
    val uri: Uri,
    val dateModified: Long,
    val box: FaceBox,
)

/**
 * Découpe le visage dans la miniature MediaStore (rapide, déjà en cache système) si elle
 * offre assez de pixels, sinon décode la région utile de l'original, réduite à la taille
 * demandée. Le résultat reste dans le cache mémoire de Coil.
 */
class FaceThumbnailFetcher(
    private val data: FaceThumbnail,
    private val options: Options,
    private val resolver: ContentResolver,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val target = max(options.size.width.pxOrElse { DEFAULT_SIZE }, options.size.height.pxOrElse { DEFAULT_SIZE })
            .coerceIn(1, MAX_SIZE)
        val signal = CancellationSignal()
        coroutineContext[Job]?.invokeOnCompletion { if (it != null) signal.cancel() }
        val bitmap = fromThumbnail(target, signal) ?: fromOriginal(target)
        return ImageFetchResult(image = bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    private fun fromThumbnail(target: Int, signal: CancellationSignal): Bitmap? {
        val thumbnail = runCatching { resolver.loadThumbnail(data.uri, Size(THUMBNAIL_SIZE, THUMBNAIL_SIZE), signal) }
            .getOrNull() ?: return null
        val crop = FaceGeometry.squareCrop(data.box, thumbnail.width, thumbnail.height)
        if (crop.width < target * ENOUGH_PIXELS) {
            thumbnail.recycle()
            return null
        }
        val software = if (thumbnail.config == Bitmap.Config.HARDWARE) {
            thumbnail.copy(Bitmap.Config.ARGB_8888, false).also { thumbnail.recycle() }
        } else {
            thumbnail
        }
        val side = min(target, crop.width)
        val cropped = Bitmap.createBitmap(software, crop.left, crop.top, crop.width, crop.height)
        val scaled = cropped.scale(side, side)
        if (cropped !== scaled) cropped.recycle()
        if (software !== cropped) software.recycle()
        return scaled
    }

    /** Décodage de l'original, mis à l'échelle pour que le recadrage fasse [target] pixels. */
    private fun fromOriginal(target: Int): Bitmap =
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, data.uri)) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            val crop = FaceGeometry.squareCrop(data.box, width, height)
            val scale = min(1f, target.toFloat() / crop.width)
            val scaledWidth = max(1, (width * scale).roundToInt())
            val scaledHeight = max(1, (height * scale).roundToInt())
            if (scale < 1f) decoder.setTargetSize(scaledWidth, scaledHeight)
            decoder.crop = Rect(
                (crop.left * scale).roundToInt().coerceIn(0, scaledWidth - 1),
                (crop.top * scale).roundToInt().coerceIn(0, scaledHeight - 1),
                (crop.right * scale).roundToInt().coerceIn(1, scaledWidth),
                (crop.bottom * scale).roundToInt().coerceIn(1, scaledHeight),
            )
        }

    class Factory(private val resolver: ContentResolver) : Fetcher.Factory<FaceThumbnail> {
        override fun create(data: FaceThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            FaceThumbnailFetcher(data, options, resolver)
    }

    private companion object {
        const val DEFAULT_SIZE = 256
        const val MAX_SIZE = 768
        const val THUMBNAIL_SIZE = 1024

        /** Part minimale de la taille demandée offerte par la miniature pour s'en contenter. */
        const val ENOUGH_PIXELS = 0.75f
    }
}

class FaceThumbnailKeyer : Keyer<FaceThumbnail> {
    override fun key(data: FaceThumbnail, options: Options): String =
        "face:${data.faceId}:${data.dateModified}:${data.box.hashCode()}"
}
