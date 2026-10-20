package app.focale.ml

import android.content.ContentResolver
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Size
import app.focale.core.model.MediaItem
import app.focale.data.MediaDetailsRepository
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeler
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

/** Résultat brut de l'analyse d'un média. */
data class AnalysisResult(
    /** Étiquettes ; null si l'étiquetage n'a pas eu lieu (image illisible) ou a échoué. */
    val labels: List<Pair<String, Float>>?,
    val phash: Long?,
    val blurScore: Float?,
    val latitude: Double?,
    val longitude: Double?,
    val place: Place?,
    /** Échec de l'étiquetage ML Kit, distinct d'une image sans étiquette. */
    val labelError: Exception? = null,
)

/**
 * Analyse sur l'appareil d'un média : étiquettes ML Kit (modèle embarqué), empreinte
 * perceptuelle, netteté, coordonnées GPS et lieu le plus proche.
 * Les vidéos sont analysées via leur miniature (étiquettes et lieu uniquement).
 */
@Singleton
class ImageAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: ContentResolver,
    private val details: MediaDetailsRepository,
    private val geocoder: OfflineGeocoder,
) {
    private val labeler: ImageLabeler by lazy {
        ImageLabeling.getClient(
            ImageLabelerOptions.Builder().setConfidenceThreshold(LABEL_CONFIDENCE).build(),
        )
    }

    suspend fun analyze(item: MediaItem): AnalysisResult {
        val bitmap = loadBitmap(item)
        try {
            var labelError: Exception? = null
            val labels = bitmap?.let { image ->
                try {
                    labeler.process(InputImage.fromBitmap(image, 0)).await().map { it.text to it.confidence }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    labelError = error
                    null
                }
            }
            val phash = if (!item.isVideo && bitmap != null) {
                ImageMetrics.dHash(ImageMetrics.grayscale(bitmap, ImageMetrics.HASH_WIDTH, ImageMetrics.HASH_HEIGHT))
            } else {
                null
            }
            val blur = if (!item.isVideo && bitmap != null) {
                val (w, h) = ImageMetrics.fit(bitmap.width, bitmap.height, BLUR_SIDE)
                ImageMetrics.laplacianVariance(ImageMetrics.grayscale(bitmap, w, h), w, h).toFloat()
            } else {
                null
            }
            val location = readLocation(item)
            val place = location?.let { (lat, lon) -> runCatching { geocoder.nearest(lat, lon) }.getOrNull() }
            return AnalysisResult(labels, phash, blur, location?.first, location?.second, place, labelError)
        } finally {
            bitmap?.recycle()
        }
    }

    private fun loadBitmap(item: MediaItem): Bitmap? = runCatching {
        val thumbnail = resolver.loadThumbnail(item.uri, Size(ANALYSIS_SIZE, ANALYSIS_SIZE), null)
        if (thumbnail.config == Bitmap.Config.HARDWARE) {
            thumbnail.copy(Bitmap.Config.ARGB_8888, false).also { thumbnail.recycle() }
        } else {
            thumbnail
        }
    }.getOrNull()

    private fun readLocation(item: MediaItem): Pair<Double, Double>? = runCatching {
        if (item.isVideo) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, details.originalUri(item.uri))
                MediaDetailsRepository.parseIso6709(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION))
            } finally {
                retriever.release()
            }
        } else {
            details.openExif(item.uri)?.latLong
                ?.takeIf { it[0] != 0.0 || it[1] != 0.0 }
                ?.let { it[0] to it[1] }
        }
    }.getOrNull()

    companion object {
        /**
         * Version de l'algorithme : l'incrémenter relance l'analyse de toute la bibliothèque.
         * 2 : étiquetage réellement actif en release (ML Kit inopérant jusqu'à 1.4.0).
         */
        const val VERSION = 2
        private const val ANALYSIS_SIZE = 512
        private const val BLUR_SIDE = 384
        private const val LABEL_CONFIDENCE = 0.6f
    }
}
