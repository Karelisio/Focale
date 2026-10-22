package app.focale.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import app.focale.core.di.IoDispatcher
import app.focale.core.model.MediaItem
import app.focale.edit.EditRenderer
import app.focale.edit.EditState
import app.focale.edit.SaveMode
import app.focale.edit.TextRenderer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Chargement et enregistrement des photos éditées.
 *
 * L'export est rendu à pleine résolution dans la limite de [MAX_OUTPUT_PIXELS] (au-delà,
 * l'image est réduite pour rester dans la mémoire disponible). Les métadonnées EXIF
 * utiles (date, appareil, exposition, GPS) sont recopiées sur le résultat.
 */
@Singleton
class ImageEditor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: ContentResolver,
    private val operations: MediaOperations,
    private val details: MediaDetailsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Format de sortie pour un type source donné. */
    data class OutputFormat(val compress: Bitmap.CompressFormat, val mimeType: String, val extension: String)

    fun outputFormat(mimeType: String): OutputFormat = when (mimeType.lowercase()) {
        "image/png" -> OutputFormat(Bitmap.CompressFormat.PNG, "image/png", "png")
        "image/webp" -> OutputFormat(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP,
            "image/webp",
            "webp",
        )
        else -> OutputFormat(Bitmap.CompressFormat.JPEG, "image/jpeg", "jpg")
    }

    /** Le remplacement n'est proposé que si le format est conservé à l'identique. */
    fun canReplace(item: MediaItem): Boolean = outputFormat(item.mimeType).mimeType.equals(item.mimeType, ignoreCase = true)

    fun canEdit(item: MediaItem): Boolean = !item.isVideo && !item.isGif

    /** Bitmap logiciel, orientation EXIF appliquée, côté le plus long ≤ [maxSide]. */
    suspend fun loadPreview(item: MediaItem, maxSide: Int = PREVIEW_SIDE): Bitmap = withContext(io) {
        decode(item) { width, height ->
            val scale = maxSide.toFloat() / maxOf(width, height)
            if (scale < 1f) (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1) else width to height
        }
    }

    suspend fun save(item: MediaItem, state: EditState, mode: SaveMode): OperationResult = withContext(io) {
        val format = outputFormat(item.mimeType)
        val temp = File.createTempFile("edit", ".${format.extension}", context.cacheDir)
        try {
            val source = decode(item) { width, height ->
                val pixels = width.toLong() * height
                if (pixels > MAX_OUTPUT_PIXELS) {
                    val scale = sqrt(MAX_OUTPUT_PIXELS.toDouble() / pixels)
                    (width * scale).toInt() to (height * scale).toInt()
                } else {
                    width to height
                }
            }
            val rendered = EditRenderer.render(source, state, faces = TextRenderer.faces(context))
            source.recycle()
            temp.outputStream().use { rendered.compress(format.compress, QUALITY, it) }
            val (outWidth, outHeight) = rendered.width to rendered.height
            rendered.recycle()
            copyExif(item, temp, outWidth, outHeight)

            when (mode) {
                SaveMode.COPY -> {
                    val base = item.displayName.substringBeforeLast('.')
                    runCatching {
                        operations.insertAndWrite(
                            displayName = "${base}_edit.${format.extension}",
                            mimeType = format.mimeType,
                            relativePath = item.relativePath.ifBlank { "Pictures/Focale/" },
                            dateTaken = item.dateTaken,
                            isVideo = false,
                        ) { output -> temp.inputStream().use { it.copyTo(output) } }
                    }.fold(onSuccess = { OperationResult.Success(1) }, onFailure = { OperationResult.Failure(it) })
                }
                SaveMode.REPLACE -> operations.overwrite(item) { output -> temp.inputStream().use { it.copyTo(output) } }
            }
        } catch (error: OutOfMemoryError) {
            OperationResult.Failure(error)
        } catch (error: Exception) {
            OperationResult.Failure(error)
        } finally {
            temp.delete()
        }
    }

    private fun decode(item: MediaItem, targetSize: (Int, Int) -> Pair<Int, Int>): Bitmap {
        val source = ImageDecoder.createSource(resolver, item.uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.isMutableRequired = false
            val (width, height) = targetSize(info.size.width, info.size.height)
            if (width != info.size.width || height != info.size.height) decoder.setTargetSize(width, height)
        }
    }

    /** Recopie les balises EXIF pertinentes ; l'orientation est remise à « normale ». */
    private fun copyExif(item: MediaItem, target: File, width: Int, height: Int) {
        val original = details.openExif(item.uri) ?: return
        runCatching {
            val exif = ExifInterface(target)
            for (tag in PRESERVED_TAGS) {
                original.getAttribute(tag)?.let { exif.setAttribute(tag, it) }
            }
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Focale")
            exif.saveAttributes()
        }
    }

    companion object {
        const val PREVIEW_SIDE = 1600
        private const val QUALITY = 95
        const val MAX_OUTPUT_PIXELS = 24_000_000L

        private val PRESERVED_TAGS = listOf(
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_LENS_MAKE, ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_EXPOSURE_BIAS_VALUE, ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP,
        )
    }
}
