package app.focale.video

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.SeekableByteChannel

/** Origine de la description d'une photo animée. */
enum class MotionPhotoFormat {
    /** Google « Motion Photo » : XMP `Camera:MotionPhoto` et répertoire `Container:Directory`. */
    MOTION_PHOTO,

    /** Ancien format Google : XMP `GCamera:MicroVideo` et `GCamera:MicroVideoOffset`. */
    MICRO_VIDEO,

    /** Samsung : bloc `MotionPhoto_Data` répertorié par l'en-tête SEF de fin de fichier. */
    SAMSUNG,
}

/**
 * Emplacement de la vidéo MP4 intégrée à une photo animée.
 *
 * @property offset position du premier octet de la vidéo dans le fichier.
 * @property length taille de la vidéo, en octets.
 * @property offsetFromEnd décalage du début de la vidéo depuis la fin du fichier.
 * @property presentationTimestampUs instant de la vidéo qui correspond à la photo, s'il est connu.
 */
data class MotionPhotoVideo(
    val offset: Long,
    val length: Long,
    val offsetFromEnd: Long,
    val format: MotionPhotoFormat,
    val presentationTimestampUs: Long? = null,
)

/**
 * Repère la vidéo d'une photo animée JPEG sans charger le fichier : seuls les en-têtes de
 * segments, le paquet XMP et quelques octets de vérification sont lus.
 *
 * - Google « Motion Photo » (`Camera:MotionPhoto="1"`, souvent sous le préfixe `GCamera`) : les
 *   éléments secondaires du répertoire `Container:Directory` sont accolés à l'image principale,
 *   dans l'ordre du répertoire, jusqu'à la fin du fichier ; la vidéo
 *   (`Item:Semantic="MotionPhoto"`) commence donc à `Item:Length` octets, plus ceux des éléments
 *   qui la suivent (gainmap Ultra HDR…), avant la fin. La spécification ne fait porter
 *   `Item:Padding` que sur l'image principale ; certains appareils l'appliquent aussi après la
 *   vidéo, d'où une seconde position candidate ;
 * - ancien format « MicroVideo » : `GCamera:MicroVideoOffset` est la distance entre le début de
 *   la vidéo et la fin du fichier ;
 * - Samsung : un en-tête SEF (`SEFH` … `SEFT`) termine le fichier et répertorie, par décalages
 *   comptés à rebours, le bloc nommé `MotionPhoto_Data` qui contient la vidéo.
 *
 * Une position n'est retenue que si elle désigne bien un MP4 (boîte `ftyp`), ce qui écarte les
 * descriptions devenues fausses (photo retouchée par une application qui a gardé l'XMP…).
 */
object MotionPhotoParser {

    private const val SOI = 0xD8
    private const val EOI = 0xD9
    private const val SOS = 0xDA
    private const val APP1 = 0xE1
    private const val TEM = 0x01
    private const val MAX_SEGMENTS = 128
    private const val MIN_FILE_SIZE = 64L
    private const val MIN_VIDEO_LENGTH = 16L
    private const val SEF_HEADER = 12
    private const val SEF_ENTRY = 12
    private const val SEF_BLOCK_HEADER = 8
    private const val MAX_SEF_DIRECTORY = 64 * 1024L

    private val XMP_SIGNATURE = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
    private val FTYP = "ftyp".toByteArray(Charsets.US_ASCII)
    private val SEFH = "SEFH".toByteArray(Charsets.US_ASCII)
    private val SEFT = "SEFT".toByteArray(Charsets.US_ASCII)
    private val SAMSUNG_VIDEO = "MotionPhoto_Data".toByteArray(Charsets.US_ASCII)

    /** Vidéo intégrée au JPEG lu par [channel], ou null si ce n'est pas une photo animée lisible. */
    fun parse(channel: SeekableByteChannel): MotionPhotoVideo? = try {
        parse(Source(channel))
    } catch (error: IOException) {
        null
    }

    private fun parse(source: Source): MotionPhotoVideo? {
        if (source.size < MIN_FILE_SIZE) return null
        val start = source.read(0, 2) ?: return null
        if (start[0].u8() != 0xFF || start[1].u8() != SOI) return null
        val xmp = findXmp(source)?.let(XmpMotionPhoto::parse) ?: XmpMotionInfo()
        if (xmp.disabled) return null
        return motionPhoto(source, xmp) ?: microVideo(source, xmp) ?: samsung(source)
    }

    /**
     * Positions candidates (début, taille) de la vidéo décrite par le répertoire [items] dans un
     * fichier de [fileLength] octets, de la plus probable à la moins probable.
     */
    internal fun containerCandidates(items: List<ContainerItem>, fileLength: Long): List<Pair<Long, Long>> {
        val index = items.indexOfLast { it.isMotionPhoto }.takeIf { it >= 0 }
            ?: items.indexOfLast { it.isVideo }.takeIf { it > 0 }
            ?: return emptyList()
        val video = items[index]
        if (video.length <= 0) return emptyList()
        val following = items.subList(index + 1, items.size)
        val strict = fileLength - video.length - following.sumOf { it.length.coerceAtLeast(0) }
        val padded = strict - video.padding.coerceAtLeast(0) - following.sumOf { it.padding.coerceAtLeast(0) }
        return listOf(strict, padded).distinct().filter { it > 0 }.map { it to video.length }
    }

    private fun motionPhoto(source: Source, xmp: XmpMotionInfo): MotionPhotoVideo? {
        if (xmp.motionPhoto != 1 && xmp.items.none { it.isMotionPhoto }) return null
        return containerCandidates(xmp.items, source.size).firstNotNullOfOrNull { (offset, length) ->
            video(source, offset, length, MotionPhotoFormat.MOTION_PHOTO, xmp.presentationTimestampUs)
        }
    }

    private fun microVideo(source: Source, xmp: XmpMotionInfo): MotionPhotoVideo? {
        if (xmp.microVideo != 1 && xmp.motionPhoto != 1) return null
        val fromEnd = xmp.microVideoOffset?.takeIf { it > 0 } ?: return null
        return video(source, source.size - fromEnd, fromEnd, MotionPhotoFormat.MICRO_VIDEO, xmp.presentationTimestampUs)
    }

    private fun samsung(source: Source): MotionPhotoVideo? {
        val size = source.size
        val trailer = source.read(size - 8, 8) ?: return null
        if (!trailer.matches(4, SEFT)) return null
        val directoryLength = trailer.u32le(0)
        if (directoryLength < SEF_HEADER || directoryLength > minOf(size - 8, MAX_SEF_DIRECTORY)) return null
        val directoryStart = size - 8 - directoryLength
        val directory = source.read(directoryStart, directoryLength.toInt()) ?: return null
        if (!directory.matches(0, SEFH)) return null
        val count = directory.u32le(8)
        if (count > (directoryLength - SEF_HEADER) / SEF_ENTRY) return null
        for (index in 0 until count.toInt()) {
            // Entrée : type (4 octets), décalage du bloc compté depuis l'en-tête SEFH vers le
            // début du fichier (4), taille du bloc (4), en petit-boutiste.
            val entry = SEF_HEADER + index * SEF_ENTRY
            val blockStart = directoryStart - directory.u32le(entry + 4)
            val blockLength = directory.u32le(entry + 8)
            if (blockStart < 2 || blockLength <= SEF_BLOCK_HEADER + SAMSUNG_VIDEO.size) continue
            if (blockStart + blockLength > directoryStart) continue
            // Bloc : type (4 octets), longueur du nom (4), nom, puis les données.
            val head = source.read(blockStart, SEF_BLOCK_HEADER) ?: continue
            if (head.u32le(4) != SAMSUNG_VIDEO.size.toLong()) continue
            val name = source.read(blockStart + SEF_BLOCK_HEADER, SAMSUNG_VIDEO.size) ?: continue
            if (!name.contentEquals(SAMSUNG_VIDEO)) continue
            val dataStart = blockStart + SEF_BLOCK_HEADER + SAMSUNG_VIDEO.size
            return video(source, dataStart, blockStart + blockLength - dataStart, MotionPhotoFormat.SAMSUNG, null)
        }
        return null
    }

    /** Vidéo de [length] octets à [offset], si elle tient dans le fichier et s'ouvre sur `ftyp`. */
    private fun video(
        source: Source,
        offset: Long,
        length: Long,
        format: MotionPhotoFormat,
        presentationTimestampUs: Long?,
    ): MotionPhotoVideo? {
        if (offset < 2 || length < MIN_VIDEO_LENGTH || offset + length > source.size) return null
        val header = source.read(offset, 8) ?: return null
        val boxSize = header.u32be(0)
        if (!header.matches(4, FTYP) || boxSize < 8 || boxSize > length) return null
        return MotionPhotoVideo(
            offset = offset,
            length = length,
            offsetFromEnd = source.size - offset,
            format = format,
            presentationTimestampUs = presentationTimestampUs?.takeIf { it >= 0 },
        )
    }

    /**
     * Paquet XMP principal (segment APP1 « http://ns.adobe.com/xap/1.0/ »), cherché parmi les
     * segments qui précèdent l'image : les données compressées ne sont jamais lues.
     */
    private fun findXmp(source: Source): ByteArray? {
        var position = 2L
        repeat(MAX_SEGMENTS) {
            val header = source.read(position, 4) ?: return null
            if (header[0].u8() != 0xFF) return null
            val marker = header[1].u8()
            when {
                marker == 0xFF -> {
                    // Octet de bourrage avant un marqueur.
                    position++
                    return@repeat
                }
                marker == SOS || marker == EOI -> return null
                marker in 0xD0..0xD7 || marker == TEM -> {
                    position += 2
                    return@repeat
                }
            }
            val length = header.u16be(2)
            if (length < 2) return null
            if (marker == APP1 && length - 2 > XMP_SIGNATURE.size) {
                val signature = source.read(position + 4, XMP_SIGNATURE.size) ?: return null
                if (signature.contentEquals(XMP_SIGNATURE)) {
                    return source.read(position + 4 + XMP_SIGNATURE.size, length - 2 - XMP_SIGNATURE.size)
                }
            }
            position += 2 + length
        }
        return null
    }

    /** Lectures positionnées, la taille du fichier n'étant demandée qu'une fois. */
    private class Source(private val channel: SeekableByteChannel) {
        val size: Long = channel.size()

        /** [count] octets lus à [position], ou null si le fichier s'arrête avant. */
        fun read(position: Long, count: Int): ByteArray? {
            if (position < 0 || count < 0 || position + count > size) return null
            val buffer = ByteBuffer.allocate(count)
            channel.position(position)
            while (buffer.hasRemaining()) {
                if (channel.read(buffer) < 0) return null
            }
            return buffer.array()
        }
    }

    private fun Byte.u8(): Int = toInt() and 0xFF

    private fun ByteArray.u16be(index: Int): Int = (this[index].u8() shl 8) or this[index + 1].u8()

    private fun ByteArray.u32be(index: Int): Long =
        (this[index].u8().toLong() shl 24) or (this[index + 1].u8().toLong() shl 16) or
            (this[index + 2].u8().toLong() shl 8) or this[index + 3].u8().toLong()

    private fun ByteArray.u32le(index: Int): Long =
        this[index].u8().toLong() or (this[index + 1].u8().toLong() shl 8) or
            (this[index + 2].u8().toLong() shl 16) or (this[index + 3].u8().toLong() shl 24)

    private fun ByteArray.matches(index: Int, expected: ByteArray): Boolean =
        size >= index + expected.size && expected.indices.all { this[index + it] == expected[it] }
}
