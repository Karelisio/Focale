package app.focale.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import app.focale.core.model.MediaItem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Prépare des copies sans métadonnées des médias à partager :
 * - JPEG : segments EXIF, XMP, IPTC retirés sans recompression ([JpegMetadataStripper]),
 *   seule l'orientation est réécrite ;
 * - autres images (PNG, WebP, HEIF, AVIF…) : réencodage, qui ne conserve aucune
 *   métadonnée ;
 * - vidéos MP4, 3GP et WebM : remultiplexage des pistes audio et vidéo sans
 *   réencodage, qui abandonne la localisation et les autres métadonnées du conteneur ;
 * - GIF et autres vidéos : partagés tels quels.
 *
 * Les copies sont exposées par un FileProvider et effacées après [RETENTION_MS].
 */
@Singleton
class MetadataStripper @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: ContentResolver,
) {
    private val root: File get() = File(context.cacheDir, SHARE_DIR)

    /** Bloquant : à appeler hors du thread principal. */
    fun prepareForSharing(items: List<MediaItem>): List<Uri> {
        deleteExpiredCopies()
        return items.mapNotNull { item ->
            runCatching { cleanCopy(item) }
                .onFailure { Log.w(TAG, "Nettoyage impossible : ${item.displayName}", it) }
                // Une image qui n'a pas pu être nettoyée n'est pas partagée.
                .getOrElse { if (item.isVideo) item.uri else null }
        }
    }

    private fun cleanCopy(item: MediaItem): Uri {
        if (item.isGif) return item.uri
        val dir = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        return try {
            val file = if (item.isVideo) remuxVideo(item, dir) else cleanImage(item, dir)
            file?.let(::uriFor) ?: item.uri
        } catch (error: Throwable) {
            dir.deleteRecursively()
            throw error
        }
    }

    private fun cleanImage(item: MediaItem, dir: File): File {
        val baseName = item.displayName.substringBeforeLast('.').ifBlank { "image" }
        if (item.mimeType.equals("image/jpeg", ignoreCase = true)) {
            val target = File(dir, "$baseName.jpg")
            val stripped = runCatching {
                val orientation = resolver.openInputStream(item.uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL
                resolver.openInputStream(item.uri)?.use { input ->
                    target.outputStream().use { JpegMetadataStripper.strip(input, it) }
                } ?: throw IOException("Lecture impossible")
                if (orientation != ExifInterface.ORIENTATION_NORMAL) {
                    ExifInterface(target).apply {
                        setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                        saveAttributes()
                    }
                }
            }
            if (stripped.isSuccess) return target
            target.delete()
        }
        return reencode(item, dir, baseName)
    }

    /** Réencode l'image (orientation appliquée) : PNG si elle a de la transparence, JPEG sinon. */
    private fun reencode(item: MediaItem, dir: File, baseName: String): File {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item.uri)) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val pixels = info.size.width.toLong() * info.size.height
            if (pixels > MAX_PIXELS) {
                val scale = sqrt(MAX_PIXELS.toDouble() / pixels)
                decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
            }
        }
        val png = bitmap.hasAlpha()
        val target = File(dir, baseName + if (png) ".png" else ".jpg")
        target.outputStream().use {
            bitmap.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)
        }
        bitmap.recycle()
        return target
    }

    /**
     * Copie les pistes audio et vidéo dans un nouveau conteneur, sans réencodage.
     * Renvoie null si le format n'est pas pris en charge (la vidéo est alors partagée
     * telle quelle).
     */
    private fun remuxVideo(item: MediaItem, dir: File): File? {
        val (outputFormat, extension) = when (item.mimeType.lowercase()) {
            "video/mp4", "video/quicktime" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4 to "mp4"
            "video/3gpp" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_3GPP to "3gp"
            "video/webm" -> MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM to "webm"
            else -> return null
        }
        val target = File(dir, item.displayName.substringBeforeLast('.').ifBlank { "video" } + ".$extension")
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(context, item.uri, null)
            val output = MediaMuxer(target.path, outputFormat)
            muxer = output
            val tracks = HashMap<Int, Int>()
            var bufferSize = MIN_SAMPLE_BUFFER
            var rotation = 0
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                tracks[index] = output.addTrack(format)
                extractor.selectTrack(index)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    bufferSize = maxOf(bufferSize, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
                if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_ROTATION)) {
                    rotation = format.getInteger(MediaFormat.KEY_ROTATION)
                }
            }
            if (tracks.isEmpty()) return null
            output.setOrientationHint(rotation)
            output.start()
            val buffer = ByteBuffer.allocate(bufferSize)
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                info.set(0, size, extractor.sampleTime, flags)
                tracks[extractor.sampleTrackIndex]?.let { output.writeSampleData(it, buffer, info) }
                extractor.advance()
            }
            output.stop()
            return target
        } catch (error: Exception) {
            Log.w(TAG, "Remultiplexage impossible : ${item.displayName}", error)
            target.delete()
            return null
        } finally {
            runCatching { muxer?.release() }
            extractor.release()
        }
    }

    private fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.share", file)

    /** Les applications destinataires lisent les copies plus tard : on ne purge que les anciennes. */
    private fun deleteExpiredCopies() {
        val limit = System.currentTimeMillis() - RETENTION_MS
        root.listFiles()?.filter { it.lastModified() < limit }?.forEach { it.deleteRecursively() }
    }

    private companion object {
        const val TAG = "MetadataStripper"
        const val SHARE_DIR = "shared"
        const val JPEG_QUALITY = 95
        const val MAX_PIXELS = 24_000_000L
        const val MIN_SAMPLE_BUFFER = 1024 * 1024
        val RETENTION_MS: Long = TimeUnit.HOURS.toMillis(1)
    }
}
