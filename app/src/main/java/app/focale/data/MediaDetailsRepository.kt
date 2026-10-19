package app.focale.data

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.runtime.Immutable
import androidx.exifinterface.media.ExifInterface
import app.focale.core.di.IoDispatcher
import app.focale.core.model.MediaItem
import app.focale.core.permissions.MediaAccessManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Informations détaillées d'un média (panneau Infos de la visionneuse). */
@Immutable
data class MediaDetails(
    val item: MediaItem,
    val captureTime: Long?,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val lensModel: String? = null,
    val fNumber: Double? = null,
    val exposureTime: Double? = null,
    val iso: Int? = null,
    val focalLength: Double? = null,
    val focalLength35mm: Int? = null,
    val flashFired: Boolean? = null,
    val software: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val videoBitrate: Int? = null,
    val frameRate: Float? = null,
) {
    val hasLocation: Boolean get() = latitude != null && longitude != null
    val hasCameraInfo: Boolean get() = cameraModel != null || fNumber != null || iso != null || focalLength != null
}

/** Lecture des métadonnées EXIF (images) et des métadonnées conteneur (vidéos). */
@Singleton
class MediaDetailsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val accessManager: MediaAccessManager,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    suspend fun load(item: MediaItem): MediaDetails = withContext(io) {
        runCatching { if (item.isVideo) loadVideo(item) else loadImage(item) }
            .getOrElse { MediaDetails(item = item, captureTime = item.dateTaken) }
    }

    /** URI donnant accès aux coordonnées GPS non masquées, si la permission le permet. */
    fun originalUri(uri: Uri): Uri =
        if (accessManager.canReadLocation()) {
            runCatching { MediaStore.setRequireOriginal(uri) }.getOrDefault(uri)
        } else {
            uri
        }

    private fun loadImage(item: MediaItem): MediaDetails {
        val exif = openExif(item.uri) ?: return MediaDetails(item = item, captureTime = item.dateTaken)
        val latLong = exif.latLong
        return MediaDetails(
            item = item,
            captureTime = parseExifDate(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)) ?: item.dateTaken,
            cameraMake = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf { it.isNotEmpty() },
            cameraModel = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotEmpty() },
            lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim()?.takeIf { it.isNotEmpty() },
            fNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 },
            exposureTime = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 },
            iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 },
            focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 },
            focalLength35mm = exif.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0).takeIf { it > 0 },
            flashFired = exif.getAttribute(ExifInterface.TAG_FLASH)?.toIntOrNull()?.let { it and 1 == 1 },
            software = exif.getAttribute(ExifInterface.TAG_SOFTWARE)?.trim()?.takeIf { it.isNotEmpty() },
            latitude = latLong?.get(0)?.takeIf { latLong[0] != 0.0 || latLong[1] != 0.0 },
            longitude = latLong?.get(1)?.takeIf { latLong[0] != 0.0 || latLong[1] != 0.0 },
            altitude = exif.getAltitude(Double.NaN).takeIf { !it.isNaN() },
        )
    }

    /** Ouvre les EXIF d'un média, en tentant d'abord l'original non masqué. */
    fun openExif(uri: Uri): ExifInterface? {
        val candidates = listOf(originalUri(uri), uri).distinct()
        for (candidate in candidates) {
            val exif = runCatching {
                context.contentResolver.openInputStream(candidate)?.use { ExifInterface(it) }
            }.getOrNull()
            if (exif != null) return exif
        }
        return null
    }

    private fun loadVideo(item: MediaItem): MediaDetails {
        val retriever = MediaMetadataRetriever()
        return try {
            runCatching { retriever.setDataSource(context, originalUri(item.uri)) }
                .recoverCatching { retriever.setDataSource(context, item.uri) }
                .getOrThrow()
            val location = parseIso6709(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
            MediaDetails(
                item = item,
                captureTime = item.dateTaken,
                videoBitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull(),
                frameRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull(),
                latitude = location?.first,
                longitude = location?.second,
            )
        } finally {
            retriever.release()
        }
    }

    companion object {
        private val EXIF_DATE = ThreadLocal.withInitial { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT) }

        fun parseExifDate(value: String?): Long? =
            value?.let { runCatching { EXIF_DATE.get()!!.parse(it)?.time }.getOrNull() }

        /** Coordonnées ISO 6709 des vidéos, ex. « +48.8584+002.2945/ ». */
        fun parseIso6709(value: String?): Pair<Double, Double>? {
            if (value.isNullOrBlank()) return null
            val match = Regex("""([+-]\d+(?:\.\d+)?)([+-]\d+(?:\.\d+)?)""").find(value) ?: return null
            val lat = match.groupValues[1].toDoubleOrNull() ?: return null
            val lon = match.groupValues[2].toDoubleOrNull() ?: return null
            return if (lat == 0.0 && lon == 0.0) null else lat to lon
        }
    }
}
