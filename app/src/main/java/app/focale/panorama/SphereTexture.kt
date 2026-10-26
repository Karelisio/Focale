package app.focale.panorama

import android.app.ActivityManager
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.withClip
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Octets d'une photo, relus depuis le début à chaque [open] : URI de la galerie ou d'une autre
 * application ([UriPhotoStreams]), ou fichier du coffre déchiffré à la volée, en mémoire.
 */
internal interface PhotoStreams {

    /** Flux de la photo depuis son début, null s'il n'est pas lisible. */
    fun open(): InputStream?

    /** Source d'[ImageDecoder] de la photo (orientation EXIF appliquée au décodage). */
    fun decoderSource(): ImageDecoder.Source
}

/** Photo désignée par une URI du résolveur de contenu. */
internal class UriPhotoStreams(private val resolver: ContentResolver, private val uri: Uri) : PhotoStreams {
    override fun open(): InputStream? = resolver.openInputStream(uri)

    override fun decoderSource(): ImageDecoder.Source = ImageDecoder.createSource(resolver, uri)
}

/** En-tête d'une photo hors de l'index (autre application, coffre) : dimensions et orientation. */
internal object PhotoHeader {

    /** Dimensions affichées (orientation EXIF appliquée), null si l'en-tête n'est pas lisible. */
    fun displaySize(streams: PhotoStreams): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = streams.open() ?: return null
        // Dimensions seulement : aucun pixel décodé.
        stream.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return if (orientation(streams) % 180 == 0) options.outWidth to options.outHeight else options.outHeight to options.outWidth
    }

    /** Rotation EXIF (0, 90, 180 ou 270 degrés), 0 si elle n'est pas lisible. */
    fun orientation(streams: PhotoStreams): Int = try {
        streams.open()?.use { ExifInterface(it).rotationDegrees } ?: 0
    } catch (error: Exception) {
        0
    }
}

/**
 * Décodage d'une photo 360° en texture : réduite aux puissances de deux de [SphereTextureSize],
 * dans la limite de `GL_MAX_TEXTURE_SIZE` et de la mémoire de l'appareil.
 *
 * La photo est lue par bandes ([BitmapRegionDecoder], sous-échantillonnées autant que possible)
 * redimensionnées une à une dans la texture : seule une bande de l'original est en mémoire à la
 * fois, même pour une sphère de 70 Mpx (d'un flux qui n'est pas un fichier, le décodeur garde en
 * mémoire l'image compressée). Une photo tournée par son orientation EXIF, ou que ce décodeur ne
 * lit pas, passe par [ImageDecoder] (orientation appliquée).
 */
internal object SphereTexture {

    private const val TAG = "SphereTexture"

    /** Hauteur des bandes, en lignes de la texture. */
    private const val BAND_ROWS = 128

    /** Lignes lues en plus de chaque côté d'une bande : le filtrage ne voit pas la coupure. */
    private const val BAND_PADDING = 2

    fun pixelBudget(context: Context): Long {
        val manager = context.getSystemService(ActivityManager::class.java) ?: return SphereTextureSize.pixelBudget(0)
        val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        return SphereTextureSize.pixelBudget(if (manager.isLowRamDevice) 0 else info.totalMem)
    }

    /**
     * Bloquant : texture de la photo [streams], ou null si elle est illisible. [orientation] :
     * rotation EXIF connue (galerie), sinon lue dans l'en-tête.
     */
    fun decode(streams: PhotoStreams, orientation: Int?, maxTextureSize: Int, maxPixels: Long): Bitmap? = try {
        val rotation = orientation ?: PhotoHeader.orientation(streams)
        (if (rotation % 360 == 0) decodeBands(streams, maxTextureSize, maxPixels) else null)
            ?: decodeWhole(streams, maxTextureSize, maxPixels)
    } catch (error: OutOfMemoryError) {
        Log.w(TAG, "Mémoire insuffisante", error)
        null
    } catch (error: Exception) {
        Log.w(TAG, "Décodage impossible", error)
        null
    }

    /** Lecture par bandes ; null si ce décodeur ne lit pas la photo. */
    internal fun decodeBands(streams: PhotoStreams, maxTextureSize: Int, maxPixels: Long): Bitmap? {
        val decoder = streams.open()?.use(::regionDecoder) ?: return null
        try {
            val width = decoder.width
            val height = decoder.height
            if (width <= 0 || height <= 0) return null
            val (targetWidth, targetHeight) = SphereTextureSize.choose(width, height, maxTextureSize, maxPixels)
            var sample = 1
            while (width / (sample * 2) >= targetWidth && height / (sample * 2) >= targetHeight) sample *= 2
            val output = createBitmap(targetWidth, targetHeight)
            val canvas = Canvas(output)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val scaleX = targetWidth.toDouble() / width
            val scaleY = targetHeight.toDouble() / height
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            var complete = false
            try {
                var top = 0
                while (top < targetHeight) {
                    val bottom = minOf(targetHeight, top + BAND_ROWS)
                    // Lignes de l'original qui couvrent la bande, alignées sur le sous-échantillonnage.
                    var sourceTop = (floor(top / scaleY).toInt() - BAND_PADDING * sample).coerceAtLeast(0)
                    sourceTop -= sourceTop % sample
                    val sourceBottom = (ceil(bottom / scaleY).toInt() + BAND_PADDING * sample).coerceAtMost(height)
                    val region = Rect(0, sourceTop, width, sourceBottom)
                    val band = decoder.decodeRegion(region, options) ?: return null
                    val matrix = Matrix().apply {
                        setScale((scaleX * region.width() / band.width).toFloat(), (scaleY * region.height() / band.height).toFloat())
                        postTranslate(0f, (sourceTop * scaleY).toFloat())
                    }
                    // Chaque ligne de la texture n'est peinte que par sa bande.
                    canvas.withClip(0, top, targetWidth, bottom) { drawBitmap(band, matrix, paint) }
                    band.recycle()
                    top = bottom
                }
                complete = true
                return output
            } finally {
                if (!complete) output.recycle()
            }
        } finally {
            decoder.recycle()
        }
    }

    private fun regionDecoder(stream: InputStream): BitmapRegionDecoder? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            BitmapRegionDecoder.newInstance(stream)
        } else {
            @Suppress("DEPRECATION")
            BitmapRegionDecoder.newInstance(stream, false)
        }
    }.getOrNull()

    /** Photo entière, orientation EXIF appliquée, redimensionnée en une fois. */
    private fun decodeWhole(streams: PhotoStreams, maxTextureSize: Int, maxPixels: Long): Bitmap {
        return ImageDecoder.decodeBitmap(streams.decoderSource()) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val (width, height) = SphereTextureSize.choose(info.size.width, info.size.height, maxTextureSize, maxPixels)
            decoder.setTargetSize(width, height)
        }
    }
}
