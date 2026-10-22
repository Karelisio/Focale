package app.focale.cast

import android.content.ContentResolver
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorSpace
import android.graphics.ImageDecoder
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.core.graphics.createBitmap
import app.focale.cast.dlna.BytesMedia
import app.focale.cast.dlna.ServedMedia
import app.focale.core.di.IoDispatcher
import app.focale.core.model.MediaItem
import app.focale.ml.ImageMetrics
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Média prêt à servir à la TV, avec les dimensions de l'image envoyée (photo). */
internal class PreparedMedia(val media: ServedMedia, val width: Int? = null, val height: Int? = null)

/**
 * Préparation d'un média de la galerie pour une TV : photo décodée (orientation EXIF appliquée
 * par `ImageDecoder`), réduite à [MAX_SIDE] px et réencodée en JPEG (aucune métadonnée, GIF et
 * HEIF compris) ; vidéo servie telle quelle depuis MediaStore, lue à la position demandée.
 *
 * Seuls les médias de MediaStore sont acceptés : le coffre-fort, déchiffré en mémoire, n'a pas
 * d'URI MediaStore et ne peut pas être servi.
 */
@Singleton
internal class CastMediaPreparer @Inject constructor(
    private val resolver: ContentResolver,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun prepare(item: MediaItem): PreparedMedia = withContext(io) {
        require(isCastable(item)) { "média hors de la galerie" }
        if (item.isVideo) video(item) else photo(item)
    }

    private fun photo(item: MediaItem): PreparedMedia {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item.uri)) { decoder, info, _ ->
            val (width, height) = ImageMetrics.fit(info.size.width, info.size.height, MAX_SIDE)
            if (width < info.size.width) decoder.setTargetSize(width, height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB))
        }
        try {
            val out = ByteArrayOutputStream(bitmap.width * bitmap.height / 4)
            // Fond noir sous la transparence (PNG, WebP) : le JPEG n'en a pas.
            val opaque = if (bitmap.hasAlpha()) {
                createBitmap(bitmap.width, bitmap.height).also { target ->
                    Canvas(target).apply {
                        drawColor(Color.BLACK)
                        drawBitmap(bitmap, 0f, 0f, null)
                    }
                }
            } else {
                bitmap
            }
            opaque.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            if (opaque !== bitmap) opaque.recycle()
            return PreparedMedia(BytesMedia(out.toByteArray(), JPEG), bitmap.width, bitmap.height)
        } finally {
            bitmap.recycle()
        }
    }

    private fun video(item: MediaItem): PreparedMedia {
        val length = resolver.openAssetFileDescriptor(item.uri, "r")?.use { descriptor ->
            descriptor.length.takeIf { it != AssetFileDescriptor.UNKNOWN_LENGTH && it > 0 }
                ?: descriptor.parcelFileDescriptor.statSize.takeIf { it > 0 }
        } ?: item.size.takeIf { it > 0 } ?: throw FileNotFoundException("taille inconnue")
        val uri = item.uri
        val mimeType = item.mimeType.ifBlank { "video/mp4" }
        return PreparedMedia(
            object : ServedMedia {
                override val mimeType: String = mimeType
                override val length: Long = length
                override val video: Boolean = true

                override fun open(offset: Long): InputStream {
                    val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException(uri.toString())
                    return ParcelFileDescriptor.AutoCloseInputStream(descriptor).apply { channel.position(offset) }
                }
            },
        )
    }

    companion object {
        /** Côté le plus long d'une photo envoyée : 4K UHD. */
        const val MAX_SIDE = 3_840
        private const val JPEG_QUALITY = 90
        private const val JPEG = "image/jpeg"

        /** Média de MediaStore (galerie), le seul qui puisse être diffusé. */
        fun isCastable(item: MediaItem): Boolean = item.uri.scheme == ContentResolver.SCHEME_CONTENT &&
            item.uri.authority == MediaStore.AUTHORITY
    }
}
