package app.focale.ml.text

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import app.focale.core.di.IoDispatcher
import app.focale.core.model.MediaItem
import app.focale.ml.await
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Lecture du texte d'une photo, derrière une interface pour les tests. */
interface PhotoTextReader {
    /**
     * Texte de [item], une ligne par ligne reconnue ("" si la photo n'en contient pas), ou
     * null si l'image ne peut pas être décodée. Lève une exception si la reconnaissance échoue.
     */
    suspend fun read(item: MediaItem): String?

    /** Libère le modèle chargé (fin d'un passage d'analyse, fermeture de la visionneuse). */
    fun release() {}
}

/**
 * Reconnaissance de texte ML Kit (alphabet latin, modèle embarqué dans l'APK : aucun
 * téléchargement) sur une version réduite de la photo, orientation EXIF appliquée par
 * `ImageDecoder` ; les images très hautes sont lues par bandes (voir [TextFraming]).
 */
@Singleton
class MlKitPhotoTextReader @Inject constructor(
    private val resolver: ContentResolver,
    @IoDispatcher private val io: CoroutineDispatcher,
) : PhotoTextReader {

    // Le même client sert à l'analyse de fond et à la visionneuse : il n'est fermé qu'une
    // fois la dernière lecture en cours terminée.
    private val lock = Any()
    private var recognizer: TextRecognizer? = null
    private var readers = 0
    private var releasePending = false

    override suspend fun read(item: MediaItem): String? = withContext(io) {
        val bitmap = decode(item) ?: return@withContext null
        try {
            if (min(bitmap.width, bitmap.height) < TextFraming.MIN_SIDE) return@withContext ""
            val client = acquire()
            try {
                val lines = ArrayList<String>()
                for (band in TextFraming.bands(bitmap.height)) {
                    ensureActive()
                    val image = if (band.height == bitmap.height) {
                        bitmap
                    } else {
                        Bitmap.createBitmap(bitmap, 0, band.top, bitmap.width, band.height)
                    }
                    try {
                        val text = client.process(InputImage.fromBitmap(image, 0)).await()
                        for (block in text.textBlocks) {
                            for (line in block.lines) {
                                val box = line.boundingBox
                                if (box == null || band.owns(band.top + box.exactCenterY())) lines += line.text
                            }
                        }
                    } finally {
                        if (image !== bitmap) image.recycle()
                    }
                }
                RecognizedText.assemble(lines)
            } finally {
                relinquish()
            }
        } finally {
            bitmap.recycle()
        }
    }

    override fun release() {
        synchronized(lock) {
            if (readers == 0) {
                close()
            } else {
                releasePending = true
            }
        }
    }

    private fun acquire(): TextRecognizer = synchronized(lock) {
        readers++
        releasePending = false
        recognizer ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
    }

    private fun relinquish() {
        synchronized(lock) {
            readers--
            if (readers == 0 && releasePending) close()
        }
    }

    /** À appeler sous [lock]. */
    private fun close() {
        recognizer?.close()
        recognizer = null
        releasePending = false
    }

    /** Image réduite, en ARGB_8888 logiciel (format attendu par ML Kit) ; null si illisible. */
    private fun decode(item: MediaItem): Bitmap? {
        val decoded = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item.uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                val (width, height) = TextFraming.decodeSize(info.size.width, info.size.height)
                if (width != info.size.width || height != info.size.height) decoder.setTargetSize(width, height)
            }
        } catch (error: IOException) {
            null
        } catch (error: OutOfMemoryError) {
            null
        } ?: return null
        if (decoded.config == Bitmap.Config.ARGB_8888) return decoded
        // Images 16 bits (RGBA_F16) ou 565 : converties pour ML Kit.
        val copy: Bitmap? = decoded.copy(Bitmap.Config.ARGB_8888, false)
        decoded.recycle()
        return copy
    }
}
