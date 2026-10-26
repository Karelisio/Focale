package app.focale.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageDecoder
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import app.focale.collage.BitmapSource
import app.focale.collage.CellRect
import app.focale.collage.CollageDetail
import app.focale.collage.CollageGainLayer
import app.focale.collage.CollageGainmap
import app.focale.collage.CollageRenderer
import app.focale.collage.CollageSource
import app.focale.collage.CollageState
import app.focale.collage.DecodedRegion
import app.focale.collage.DetailRequest
import app.focale.core.di.IoDispatcher
import app.focale.core.image.GainmapParams
import app.focale.core.image.UltraHdr
import app.focale.core.mediastore.MediaSyncManager
import app.focale.core.model.MediaItem
import app.focale.edit.ExifOrientation
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Issue de l'enregistrement d'un collage. [item] : le collage indexé, null s'il ne l'est pas encore. */
sealed interface CollageResult {
    data class Saved(val uri: Uri, val item: MediaItem?) : CollageResult
    data class Failed(val error: Throwable) : CollageResult
}

/**
 * Collages : aperçu des photos et enregistrement du collage, nouveau JPEG de
 * [CollageRenderer.LONG_SIDE] px sur le grand côté dans `Pictures/Focale`.
 *
 * Les photos sont lues par le décodeur de zones d'Android (JPEG, PNG, WebP, HEIF), qui ne décode
 * que la partie utile, réduite au besoin ; leur orientation EXIF est appliquée par le rendu. Les
 * autres formats sont décodés entiers, réduits d'avance. Aperçu et export lisent les photos de la
 * même façon : le cadrage réglé à l'écran est celui du collage enregistré.
 */
@Singleton
class CollageMaker @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: ContentResolver,
    private val operations: MediaOperations,
    private val syncManager: MediaSyncManager,
    private val media: MediaRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** Aperçu de [item], orientation appliquée, grand côté ≤ [maxSide]. */
    suspend fun loadPreview(item: MediaItem, maxSide: Int): Bitmap = withContext(io) {
        open(item).use { CollageRenderer.preview(it, maxSide) }
    }

    /**
     * Détail de [item] pour sa case agrandie ([CollageDetail], [CollageRenderer.detail]) : l'image et
     * la zone qu'elle couvre. Null pour un format sans décodeur de zones : il faudrait décoder la
     * photo entière, bien au-delà de la mémoire prévue pour les détails ; l'aperçu reste.
     */
    suspend fun loadDetail(item: MediaItem, request: DetailRequest): Pair<Bitmap, CellRect>? = withContext(io) {
        val decoder = runCatching { resolver.openInputStream(item.uri)?.use(::regionDecoder) }.getOrNull() ?: return@withContext null
        RegionSource(decoder, orientationOf(item), gainmap = null).use { CollageRenderer.detail(it, request) }
    }

    /**
     * Rend [state] (photos de [items]) et l'enregistre comme une nouvelle photo datée de [now], par
     * le chemin d'écriture commun ([MediaOperations.insertAndWrite]) : entrée MediaStore en attente,
     * écrite puis publiée. L'index est ensuite synchronisé pour que la visionneuse puisse l'ouvrir.
     */
    suspend fun save(state: CollageState, items: Map<Long, MediaItem>, now: Long = System.currentTimeMillis()): CollageResult =
        withContext(io) {
            val (width, height) = state.aspect.size(CollageRenderer.LONG_SIDE)
            val temp = File.createTempFile("collage", ".jpg", context.cacheDir)
            try {
                // Photos Ultra HDR (Android 14+) : le collage l'est aussi, gainmap composée case par case.
                val gainmaps = gainmaps(state.photos.mapNotNull(items::get))
                val layer = CollageGainmap.combine(state.photos.map { gainmaps[it] })?.let { CollageGainLayer(width, height, it) }
                val bitmap = try {
                    CollageRenderer.render(state, width, height, open = { open(items.getValue(it), gainmaps[it]) }, gainmap = layer)
                        .also { if (layer != null && UltraHdr.supported) it.gainmap = UltraHdr.gainmap(layer.contents(), layer.params) }
                } finally {
                    layer?.recycle()
                }
                try {
                    // Avec une gainmap, Android écrit un JPEG Ultra HDR.
                    temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
                } finally {
                    bitmap.recycle()
                }
                writeExif(temp, width, height, now)
                val uri = operations.insertAndWrite(
                    displayName = fileName(now),
                    mimeType = MIME_TYPE,
                    relativePath = RELATIVE_PATH,
                    dateTaken = now,
                    isVideo = false,
                ) { output -> temp.inputStream().use { it.copyTo(output) } }
                syncManager.sync()
                CollageResult.Saved(uri, media.get(ContentUris.parseId(uri)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: OutOfMemoryError) {
                CollageResult.Failed(error)
            } catch (error: Exception) {
                Log.w(TAG, "Collage non enregistré", error)
                CollageResult.Failed(error)
            } finally {
                temp.delete()
            }
        }

    /**
     * Ouvre [item] : décodeur de zones si son format le permet, sinon image réduite en mémoire.
     * [gainmap] : paramètres de sa gainmap ([gainmaps]), dont les zones sont alors décodées aussi.
     */
    fun open(item: MediaItem, gainmap: GainmapParams? = null): CollageSource {
        val decoder = runCatching { resolver.openInputStream(item.uri)?.use(::regionDecoder) }
            .onFailure { Log.i(TAG, "Pas de décodeur de zones pour ${item.mimeType}", it) }
            .getOrNull()
        return if (decoder != null) RegionSource(decoder, orientationOf(item), gainmap) else decodeWhole(item, gainmap)
    }

    /**
     * Paramètres des gainmaps des photos Ultra HDR de [items], par identifiant ; vide avant
     * Android 14, où ni le décodage ni l'encodage des gainmaps n'existent.
     */
    private fun gainmaps(items: List<MediaItem>): Map<Long, GainmapParams> {
        if (!UltraHdr.supported) return emptyMap()
        return items.filter { UltraHdr.isCandidate(it.mimeType) }
            .mapNotNull { item -> UltraHdr.params(resolver, item.uri)?.let { item.id to it } }
            .toMap()
    }

    private fun regionDecoder(stream: InputStream): BitmapRegionDecoder? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        BitmapRegionDecoder.newInstance(stream)
    } else {
        @Suppress("DEPRECATION")
        BitmapRegionDecoder.newInstance(stream, false)
    }

    /**
     * Orientation EXIF : Android ne l'applique pas aux PNG, GIF et BMP (ni la galerie), le collage
     * non plus ; illisible, l'image est prise telle quelle.
     */
    private fun orientationOf(item: MediaItem): Int {
        if (item.mimeType.lowercase(Locale.ROOT) in UNORIENTED) return ExifOrientation.NORMAL
        return runCatching {
            resolver.openInputStream(item.uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            }
        }.getOrNull() ?: ExifOrientation.NORMAL
    }

    /**
     * Repli (AVIF, DNG…) : image entière décodée, orientation appliquée par le décodeur, réduite à
     * [WHOLE_SIDE] px sur le grand côté et [WHOLE_PIXELS] pixels au plus ; sa gainmap, réduite
     * avec elle par le décodeur, est gardée si [gainmap] l'annonce.
     */
    private fun decodeWhole(item: MediaItem, gainmap: GainmapParams?): CollageSource {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item.uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val width = info.size.width
            val height = info.size.height
            val scale = min(WHOLE_SIDE / max(width, height).toFloat(), sqrt(WHOLE_PIXELS / (width.toFloat() * height)))
            if (scale < 1f) decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
        }
        val contents = if (gainmap != null && UltraHdr.supported) bitmap.gainmap?.gainmapContents else null
        return BitmapSource(bitmap, ExifOrientation.NORMAL, gainmapContents = contents, gainmap = gainmap.takeIf { contents != null })
    }

    /** Date de création en EXIF (MediaStore en tire la date de prise de vue), logiciel, orientation normale. */
    private fun writeExif(file: File, width: Int, height: Int, now: Long) {
        runCatching {
            val exif = ExifInterface(file)
            val stamp = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.ROOT).format(Date(now))
            val offset = offset(now)
            listOf(ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED)
                .forEach { exif.setAttribute(it, stamp) }
            listOf(ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED)
                .forEach { exif.setAttribute(it, offset) }
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            exif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "Focale")
            exif.saveAttributes()
        }.onFailure { Log.w(TAG, "EXIF du collage non écrits", it) }
    }

    /** Décalage horaire local à [now], au format EXIF (« +02:00 »). */
    private fun offset(now: Long): String {
        val minutes = TimeZone.getDefault().getOffset(now) / 60_000
        val sign = if (minutes < 0) "-" else "+"
        return String.format(Locale.ROOT, "%s%02d:%02d", sign, abs(minutes) / 60, abs(minutes) % 60)
    }

    private fun fileName(now: Long): String = "Collage_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(Date(now)) + ".jpg"

    /**
     * Photo lue zone par zone ; le décodeur est libéré à la fermeture. Sur Android 14+, il décode
     * aussi la zone de la gainmap, rattachée au bitmap rendu.
     */
    private class RegionSource(
        private val decoder: BitmapRegionDecoder,
        override val orientation: Int,
        override val gainmap: GainmapParams?,
    ) : CollageSource {
        override val width: Int = decoder.width
        override val height: Int = decoder.height
        private val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }

        override fun decode(region: Rect, sampleSize: Int): Bitmap {
            options.inSampleSize = sampleSize
            return decoder.decodeRegion(region, options) ?: error("Zone illisible : $region")
        }

        override fun decodeWithGainmap(region: Rect, sampleSize: Int): DecodedRegion {
            val bitmap = decode(region, sampleSize)
            val contents = if (UltraHdr.supported) bitmap.gainmap?.gainmapContents else null
            return DecodedRegion(bitmap, contents, owned = false)
        }

        override fun close() = decoder.recycle()
    }

    companion object {
        private const val TAG = "CollageMaker"
        const val RELATIVE_PATH = "Pictures/Focale/"
        const val MIME_TYPE = "image/jpeg"
        private const val QUALITY = 95
        private const val WHOLE_SIDE = 4096f
        private const val WHOLE_PIXELS = 16_000_000f
        private val UNORIENTED = setOf("image/png", "image/gif", "image/bmp", "image/x-ms-bmp")
    }
}
