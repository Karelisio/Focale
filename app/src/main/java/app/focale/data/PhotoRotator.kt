package app.focale.data

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import app.focale.core.di.IoDispatcher
import app.focale.core.model.MediaItem
import app.focale.edit.EditState
import app.focale.edit.ExifOrientation
import app.focale.edit.SaveMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Enregistre une rotation par quarts de tour faite dans la visionneuse.
 *
 * JPEG et WebP : sans perte, seule l'orientation EXIF change. Si la balise existe, ses deux
 * octets sont remplacés sur place : rien d'autre ne bouge (carte de gain Ultra HDR, vidéo
 * d'une photo animée, autres métadonnées). Sinon ExifInterface réécrit le segment EXIF et
 * recopie le reste tel quel. Dans les deux cas, même consentement que « remplacer
 * l'original » dans l'éditeur ([MediaOperations]).
 *
 * Autres formats : Android ignore leur orientation EXIF (PNG) ou se fie à une autre (HEIF,
 * AVIF : propriétés `irot` / `imir`). La photo passe alors par le rendu de l'éditeur,
 * rotation seule, en copie ou à la place de l'original selon les mêmes règles que lui.
 */
@Singleton
class PhotoRotator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val resolver: ContentResolver,
    private val operations: MediaOperations,
    private val editor: ImageEditor,
    private val details: MediaDetailsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /** [lossless] : enregistrement direct, sur place ; sinon copie, ou remplacement si [canReplace]. */
    data class Options(val lossless: Boolean, val canReplace: Boolean)

    fun options(item: MediaItem): Options = when {
        isLossless(item) -> Options(lossless = true, canReplace = true)
        // Au-delà de la limite de rendu, l'image serait réduite : copie seule.
        else -> Options(
            lossless = false,
            canReplace = editor.canReplace(item) && item.width.toLong() * item.height <= ImageEditor.MAX_OUTPUT_PIXELS,
        )
    }

    /** Enregistre [quarterTurns] quarts de tour horaires ; sans perte, [mode] est ignoré (toujours sur place). */
    suspend fun save(item: MediaItem, quarterTurns: Int, mode: SaveMode): OperationResult {
        val turns = Math.floorMod(quarterTurns, 4)
        return when {
            turns == 0 -> OperationResult.Success(0)
            isLossless(item) -> withContext(io) {
                runCatching { rotateExif(item, turns) }.getOrElse { OperationResult.Failure(it) }
            }
            else -> editor.save(item, EditState(quarterTurns = turns), mode)
        }
    }

    private fun isLossless(item: MediaItem): Boolean = item.mimeType.lowercase() in LOSSLESS_TYPES

    private suspend fun rotateExif(item: MediaItem, turns: Int): OperationResult {
        val exif = resolver.openInputStream(item.uri)?.use { ExifInterface(it) } ?: error("Lecture impossible")
        val current = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)
        val target = ExifOrientation.rotate(current, turns)
        val slot = OrientationSlot.locate(exif, current) { offset, length -> read(item.uri, offset, length) }
        if (slot != null) return operations.patch(item) { slot.write(it, target) }

        // Réécriture complète : sans accès à l'original, la localisation lue est masquée (mise
        // à zéro) et serait perdue.
        val source = details.originalUri(item.uri)
        if (source == item.uri && (GPS_TAGS.any { exif.getAttribute(it) != null } || exif.getAttribute(ExifInterface.TAG_XMP) != null)) {
            return OperationResult.Failure(IllegalStateException("Localisation masquée sans ACCESS_MEDIA_LOCATION"))
        }
        val temp = File.createTempFile("rotation", null, context.cacheDir)
        try {
            resolver.openInputStream(source)?.use { input -> temp.outputStream().use { input.copyTo(it) } }
                ?: error("Lecture impossible")
            rewriteOrientation(temp, target)
            return operations.overwrite(item) { output -> temp.inputStream().use { it.copyTo(output) } }
        } finally {
            temp.delete()
        }
    }

    /** [length] octets à [offset], ou null si le fichier est plus court. */
    private fun read(uri: Uri, offset: Long, length: Int): ByteArray? {
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: return null
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
            val buffer = ByteBuffer.allocate(length)
            while (buffer.hasRemaining()) {
                if (stream.channel.read(buffer, offset + buffer.position()) < 0) return null
            }
            buffer.array()
        }
    }

    companion object {
        private val LOSSLESS_TYPES = setOf("image/jpeg", "image/webp")

        /** Balises de localisation masquées par MediaProvider sans ACCESS_MEDIA_LOCATION. */
        private val GPS_TAGS = listOf(
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
        )

        /** Écrit l'orientation d'un fichier local par ExifInterface : seul le segment EXIF est refait. */
        internal fun rewriteOrientation(file: File, orientation: Int) {
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
                saveAttributes()
            }
        }
    }
}

/**
 * Valeur de l'orientation EXIF dans le fichier : deux octets à [offset], dans l'ordre des
 * octets du fichier. La remplacer sur place ne touche à rien d'autre.
 */
internal class OrientationSlot(val offset: Long, private val bigEndian: Boolean) {

    fun bytes(orientation: Int): ByteArray =
        if (bigEndian) byteArrayOf(0, orientation.toByte()) else byteArrayOf(orientation.toByte(), 0)

    fun write(channel: FileChannel, orientation: Int) {
        val buffer = ByteBuffer.wrap(bytes(orientation))
        while (buffer.hasRemaining()) channel.write(buffer, offset + buffer.position())
    }

    companion object {
        /**
         * Emplacement de l'orientation [current] (un seul SHORT, de 1 à 8) lue dans [exif]. L'entrée
         * IFD entière que [read] trouve devant cette position doit la décrire (balise 0x0112, type
         * SHORT, un élément, valeur [current]) : l'emplacement est vérifié (ExifInterface se trompe
         * de quelques octets sur certains WebP) et l'ordre des octets s'en déduit. Null si la
         * balise manque ou se présente autrement : l'appelant réécrit alors le segment EXIF.
         */
        fun locate(exif: ExifInterface, current: Int, read: (offset: Long, length: Int) -> ByteArray?): OrientationSlot? {
            if (current !in 1..8) return null
            val range = runCatching { exif.getAttributeRange(ExifInterface.TAG_ORIENTATION) }.getOrNull()
            if (range == null || range[1] != 2L || range[0] < ENTRY_HEADER) return null
            val entry = read(range[0] - ENTRY_HEADER, ENTRY_HEADER + 2) ?: return null
            val value = current.toByte()
            return when {
                entry.contentEquals(byteArrayOf(0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, value)) -> OrientationSlot(range[0], bigEndian = true)
                entry.contentEquals(byteArrayOf(0x12, 0x01, 3, 0, 1, 0, 0, 0, value, 0)) -> OrientationSlot(range[0], bigEndian = false)
                else -> null
            }
        }

        /** Balise, type et nombre d'éléments d'une entrée IFD, devant sa valeur. */
        private const val ENTRY_HEADER = 8
    }
}
