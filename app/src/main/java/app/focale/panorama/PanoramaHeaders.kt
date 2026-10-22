package app.focale.panorama

import app.focale.data.XmpPackets

/**
 * Propriétés GPano d'une photo, lues dans son en-tête sans toucher aux données d'image : paquet
 * XMP principal d'un JPEG (segment APP1) ou d'un WebP (bloc « XMP »), comme l'orientation XMP
 * ([XmpPackets]), ou élément XMP d'un HEIF / AVIF ([HeifXmp]).
 */
internal object PanoramaHeaders {

    /** GPano du fichier lu par [read] ([length] octets à [offset], null au-delà de la fin). */
    fun gpano(read: (offset: Long, length: Int) -> ByteArray?): GPano? {
        val packets = XmpPackets.locate(read).ifEmpty { HeifXmp.locate(read) }
        return packets.firstNotNullOfOrNull { (start, length) -> read(start, length)?.let(XmpPanorama::parse) }
    }
}

/**
 * Élément XMP d'un fichier HEIF ou AVIF (ISO/IEC 23008-12) : élément `mime` de type
 * `application/rdf+xml` déclaré par la boîte `iinf` de la boîte `meta`, situé par sa boîte
 * `iloc`. Seule la boîte `meta` de premier niveau est lue (quelques kilo-octets) ; un élément
 * en plusieurs morceaux, ou rangé ailleurs que dans le fichier ou la boîte `idat`, est ignoré.
 */
internal object HeifXmp {

    private const val MAX_TOP_BOXES = 32
    private const val MAX_META = 1L shl 20
    private const val MAX_XMP = 4L shl 20
    private const val XMP_MIME = "application/rdf+xml"

    /** (position, longueur) du paquet XMP dans le fichier, s'il y en a un. */
    fun locate(read: (offset: Long, length: Int) -> ByteArray?): List<Pair<Long, Int>> {
        val first = read(0, 8) ?: return emptyList()
        if (!first.matches(4, "ftyp")) return emptyList()
        var position = 0L
        repeat(MAX_TOP_BOXES) {
            val header = read(position, 16) ?: read(position, 8) ?: return emptyList()
            val (size, headerLength) = boxSize(header, position) ?: return emptyList()
            if (header.matches(4, "meta")) {
                if (size > MAX_META || size < headerLength + 4) return emptyList()
                val meta = read(position, size.toInt()) ?: return emptyList()
                // Boîte pleine : version et drapeaux avant les boîtes filles.
                return xmp(meta, from = headerLength + 4, base = position)
            }
            if (size <= 0) return emptyList()
            position += size
        }
        return emptyList()
    }

    /** Taille et longueur d'en-tête de la boîte qui commence [header] ; null si invalide. */
    private fun boxSize(header: ByteArray, position: Long): Pair<Long, Int>? {
        val size32 = header.u32(0)
        return when {
            size32 == 1L && header.size >= 16 -> header.u64(8).takeIf { it >= 16 }?.let { it to 16 }
            size32 == 0L -> null // jusqu'à la fin du fichier : ce n'est pas la boîte meta cherchée
            size32 >= 8 -> size32 to 8
            else -> null
        }?.takeIf { position + it.first > position }
    }

    private fun xmp(meta: ByteArray, from: Int, base: Long): List<Pair<Long, Int>> {
        val children = boxes(meta, from, meta.size)
        val xmpIds = children["iinf"]?.let { (start, end) -> xmpItems(meta, start, end) }.orEmpty()
        if (xmpIds.isEmpty()) return emptyList()
        val (ilocStart, ilocEnd) = children["iloc"] ?: return emptyList()
        // Données de la boîte idat, pour les éléments rangés dans la boîte meta elle-même.
        val idat = children["idat"]?.first?.let { base + it }
        return locations(meta, ilocStart, ilocEnd, xmpIds, idat)
    }

    /** Boîtes filles de [start, end[ : type → (début du contenu, fin), la première de chaque type. */
    private fun boxes(data: ByteArray, start: Int, end: Int): Map<String, Pair<Int, Int>> {
        val found = HashMap<String, Pair<Int, Int>>()
        var position = start
        while (position + 8 <= end) {
            var size = data.u32(position)
            var header = 8
            if (size == 1L) {
                if (position + 16 > end) break
                size = data.u64(position + 8)
                header = 16
            } else if (size == 0L) {
                size = (end - position).toLong()
            }
            if (size < header || position + size > end) break
            val type = String(data, position + 4, 4, Charsets.ISO_8859_1)
            found.putIfAbsent(type, (position + header) to (position + size.toInt()))
            position += size.toInt()
        }
        return found
    }

    /** Identifiants des éléments XMP déclarés par la boîte `iinf` dont le contenu est [start, end[. */
    private fun xmpItems(data: ByteArray, start: Int, end: Int): Set<Long> {
        if (start + 4 > end) return emptySet()
        val version = data[start].u8()
        var position = start + 4 + if (version == 0) 2 else 4
        val ids = HashSet<Long>()
        while (position + 8 <= end) {
            val size = data.u32(position)
            if (size < 8 || position + size > end) break
            val boxEnd = position + size.toInt()
            if (data.matches(position + 4, "infe")) itemInfo(data, position + 8, boxEnd)?.let(ids::add)
            position = boxEnd
        }
        return ids
    }

    /** Identifiant d'une boîte `infe` (contenu [start, end[) si elle déclare un élément XMP. */
    private fun itemInfo(data: ByteArray, start: Int, end: Int): Long? {
        if (start + 4 > end) return null
        val version = data[start].u8()
        var position = start + 4
        val id: Long
        if (version >= 2) {
            id = if (version == 2) data.u16(position).toLong().also { position += 2 } else data.u32(position).also { position += 4 }
            position += 2 // item_protection_index
            if (position + 4 > end) return null
            val type = String(data, position, 4, Charsets.ISO_8859_1)
            position += 4
            if (type != "mime") return null
            position = skipString(data, position, end) ?: return null // item_name
        } else {
            id = data.u16(position).toLong()
            position += 4 // item_ID, item_protection_index
            position = skipString(data, position, end) ?: return null // item_name
        }
        val contentType = readString(data, position, end) ?: return null
        return id.takeIf { contentType.trim().equals(XMP_MIME, ignoreCase = true) }
    }

    /** Emplacements, dans le fichier, des éléments [ids] décrits par la boîte `iloc` ([start, end[). */
    private fun locations(data: ByteArray, start: Int, end: Int, ids: Set<Long>, idat: Long?): List<Pair<Long, Int>> {
        if (start + 8 > end) return emptyList()
        val version = data[start].u8()
        if (version > 2) return emptyList()
        var position = start + 4
        val offsetSize = data[position].u8() shr 4
        val lengthSize = data[position].u8() and 0x0F
        val baseOffsetSize = data[position + 1].u8() shr 4
        val indexSize = if (version >= 1) data[position + 1].u8() and 0x0F else 0
        position += 2
        val count: Long
        if (version < 2) {
            count = data.u16(position).toLong()
            position += 2
        } else {
            count = data.u32(position)
            position += 4
        }
        val found = ArrayList<Pair<Long, Int>>()
        var remaining = count
        while (remaining-- > 0) {
            val idSize = if (version < 2) 2 else 4
            if (position + idSize > end) break
            val id = if (version < 2) data.u16(position).toLong() else data.u32(position)
            position += idSize
            var method = 0
            if (version >= 1) {
                if (position + 2 > end) break
                method = data.u16(position) and 0x0F
                position += 2
            }
            position += 2 // data_reference_index
            val baseOffset = data.uN(position, baseOffsetSize, end) ?: break
            position += baseOffsetSize
            if (position + 2 > end) break
            val extents = data.u16(position)
            position += 2
            var location: Pair<Long, Long>? = null
            for (extent in 0 until extents) {
                position += indexSize
                val offset = data.uN(position, offsetSize, end) ?: return found
                position += offsetSize
                val length = data.uN(position, lengthSize, end) ?: return found
                position += lengthSize
                if (extent == 0) location = offset to length
            }
            if (id !in ids || extents != 1) continue
            val (offset, length) = location ?: continue
            val origin = when (method) {
                0 -> 0L
                1 -> idat ?: continue
                else -> continue
            }
            if (length in 1..MAX_XMP) found += (origin + baseOffset + offset) to length.toInt()
        }
        return found
    }

    /** Position qui suit la chaîne terminée par un octet nul qui commence à [start], ou null. */
    private fun skipString(data: ByteArray, start: Int, end: Int): Int? {
        var position = start
        while (position < end && data[position].toInt() != 0) position++
        return if (position < end) position + 1 else null
    }

    /** Chaîne terminée par un octet nul (ou par la fin de la boîte) qui commence à [start]. */
    private fun readString(data: ByteArray, start: Int, end: Int): String? {
        if (start >= end) return null
        var stop = start
        while (stop < end && data[stop].toInt() != 0) stop++
        return String(data, start, stop - start, Charsets.US_ASCII)
    }

    private fun Byte.u8(): Int = toInt() and 0xFF

    private fun ByteArray.u16(index: Int): Int = (this[index].u8() shl 8) or this[index + 1].u8()

    private fun ByteArray.u32(index: Int): Long =
        (this[index].u8().toLong() shl 24) or (this[index + 1].u8().toLong() shl 16) or
            (this[index + 2].u8().toLong() shl 8) or this[index + 3].u8().toLong()

    private fun ByteArray.u64(index: Int): Long = (u32(index) shl 32) or u32(index + 4)

    /** Entier non signé gros-boutiste de [size] octets (0, 4 ou 8), null s'il déborde de [end]. */
    private fun ByteArray.uN(index: Int, size: Int, end: Int): Long? = when (size) {
        0 -> 0L
        4 -> if (index + 4 <= end) u32(index) else null
        8 -> if (index + 8 <= end) u64(index).takeIf { it >= 0 } else null
        else -> null
    }

    private fun ByteArray.matches(index: Int, type: String): Boolean =
        size >= index + 4 && (0 until 4).all { this[index + it] == type[it].code.toByte() }
}
