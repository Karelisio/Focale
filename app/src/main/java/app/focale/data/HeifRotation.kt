package app.focale.data

import app.focale.edit.ExifOrientation
import app.focale.panorama.HeifXmp
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * Rotation sans perte d'une photo HEIF ou AVIF (ISO/IEC 23008-12, conteneur ISOBMFF) : les
 * données d'image ne sont ni décodées ni réencodées, seule change la propriété de rotation
 * `irot` de l'image principale et de ses miniatures (boîte `meta` : propriétés `iprp/ipco`,
 * associations `ipma`), dont les décodeurs tiennent compte (ImageDecoder, libheif, Apple…).
 *
 * - Propriété `irot` présente : son angle est réécrit sur place, d'un octet, si elle ne sert
 *   qu'à ces images (ou à leurs images auxiliaires, alpha ou profondeur, qui suivent l'image
 *   principale) ; sinon ces images reçoivent une nouvelle propriété.
 * - Absente : une propriété est ajoutée à la fin de `ipco` et associée en dernier, donc
 *   appliquée après les autres transformations (recadrage `clap`, miroir `imir`). La boîte `meta`
 *   grandit de quelques octets : les positions des données qui la suivent (`iloc`) sont
 *   décalées d'autant et le fichier est récrit en entier ([copy]).
 *
 * L'orientation EXIF de l'élément `Exif` et le `tiff:Orientation` du XMP ([HeifXmp],
 * [XmpOrientation]), s'ils existent, prennent la valeur de l'orientation affichée, réécrite sur
 * place : Android en tire l'orientation de MediaStore (ExifInterface), et les deux disent ensuite
 * la même chose que `irot` / `imir`. Toute structure inattendue (rotation suivie d'un recadrage,
 * carte de gain `tmap`, boîte de premier niveau inconnue à décaler, position hors de portée…)
 * fait renoncer ([plan] rend null) : l'appelant garde alors le réencodage. Le résultat est relu
 * avant d'être rendu : orientation obtenue et emplacements des données vérifiés.
 *
 * @property orientation orientation EXIF de l'image affichée après rotation.
 * @property hasMetadata le fichier porte un élément EXIF ou XMP : une récriture complète doit
 *   partir de l'original, dont la localisation n'est pas masquée.
 */
internal class HeifRotation private constructor(
    val orientation: Int,
    val hasMetadata: Boolean,
    private val length: Long,
    private val metaStart: Long,
    private val metaEnd: Long,
    private val grownMeta: ByteArray?,
    private val patches: List<Patch>,
) {
    /** Quelques octets remplacés, rien ne bouge : [write] ; sinon le fichier est récrit par [copy]. */
    val inPlace: Boolean get() = grownMeta == null

    /** Octets [bytes] à la position [offset] du fichier d'origine. */
    private class Patch(val offset: Long, val bytes: ByteArray)

    /** Modification sur place ([inPlace]), sans tronquer le fichier. */
    fun write(channel: FileChannel) {
        check(inPlace) { "La boîte meta grandit : récriture complète" }
        for (patch in patches) {
            val buffer = ByteBuffer.wrap(patch.bytes)
            while (buffer.hasRemaining()) channel.write(buffer, patch.offset + buffer.position())
        }
    }

    /**
     * Recopie le fichier d'origine lu dans [input] vers [output], nouvelle boîte `meta` à la place
     * de l'ancienne ; les octets de l'orientation EXIF et XMP qui la suivent sont remplacés au
     * passage. Échoue si [input] n'a pas la longueur du fichier analysé.
     */
    fun copy(input: InputStream, output: OutputStream) {
        val meta = grownMeta ?: error("Modification sur place")
        val buffer = ByteArray(BUFFER)
        var position = 0L

        fun pump(until: Long) {
            while (position < until) {
                val count = input.read(buffer, 0, min(buffer.size.toLong(), until - position).toInt())
                if (count < 0) throw EOFException("Fichier plus court que prévu")
                for (patch in patches) {
                    val from = max(patch.offset, position)
                    val to = min(patch.offset + patch.bytes.size, position + count)
                    for (index in from until to) buffer[(index - position).toInt()] = patch.bytes[(index - patch.offset).toInt()]
                }
                output.write(buffer, 0, count)
                position += count
            }
        }

        pump(metaStart)
        var skipped = 0L
        while (skipped < metaEnd - metaStart) {
            val count = input.read(buffer, 0, min(buffer.size.toLong(), metaEnd - metaStart - skipped).toInt())
            if (count < 0) throw EOFException("Boîte meta tronquée")
            skipped += count
        }
        position = metaEnd
        output.write(meta)
        pump(length)
        if (input.read() >= 0) throw IOException("Fichier plus long que prévu")
    }

    companion object {
        private const val BUFFER = 64 * 1024
        private const val MAX_EXIF_HEAD = 64 * 1024

        /** Boîtes de premier niveau qu'une `meta` plus longue peut décaler : aucune position absolue dedans. */
        private val SHIFTABLE = setOf("mdat", "free", "skip", "mpvd")

        /** Transformations qui changent le cadre : une rotation qu'elles suivent ne se réécrit pas. */
        private val FRAMING = setOf("clap", "iscl")

        /**
         * Rotation de [quarterTurns] quarts de tour horaires du fichier lu par [read] ([length]
         * octets à [offset], null au-delà de la fin), long de [length] octets ; null si elle ne
         * peut pas se faire sûrement sans réencodage.
         */
        fun plan(read: (offset: Long, length: Int) -> ByteArray?, length: Long, quarterTurns: Int): HeifRotation? =
            runCatching { build(read, length, Math.floorMod(quarterTurns, 4)) }.getOrNull()

        /**
         * Orientation EXIF de l'image [item] (l'image principale si null) telle qu'elle
         * s'affiche (`irot`, `imir`) ; null si le fichier ne se lit pas.
         */
        fun orientation(read: (offset: Long, length: Int) -> ByteArray?, length: Long, item: Long? = null): Int? = runCatching {
            val container = Container.read(read, length) ?: return null
            val meta = HeifMeta.parse(container.meta) ?: return null
            meta.display(item ?: meta.primary)?.exif
        }.getOrNull()

        private fun build(read: (Long, Int) -> ByteArray?, length: Long, turns: Int): HeifRotation? {
            if (turns == 0) return null
            val container = Container.read(read, length) ?: return null
            val meta = HeifMeta.parse(container.meta) ?: return null
            if (meta.handler != "pict" || meta.types[meta.primary] == null) return null
            // Carte de gain ISO 21496-1 : image dérivée à orienter comme l'image de base, non gérée.
            if (meta.types.values.any { it == "tmap" }) return null

            val thumbnails = meta.references.filter { it.type == "thmb" && meta.primary in it.to }.map { it.from }
            val targets = (listOf(meta.primary) + thumbnails).distinct().filter { it in meta.types }
            val followers = meta.references.filter { it.type == "auxl" && it.to.any(targets::contains) }.map { it.from }.toSet()

            // Pour chaque image : angle de sa propriété irot (existante, ou à ajouter) qui tourne son affichage.
            val modified = ArrayList<Triple<Long, Int, Int>>() // image, index de la propriété, angle
            val appended = ArrayList<Pair<Long, Int>>() // image, angle
            val wanted = HashMap<Long, Display>()
            for (target in targets) {
                val chain = meta.chain(target) ?: return null
                val rotations = chain.indices.filter { chain[it].property.type == "irot" }
                if (rotations.size > 1) return null
                val current = Display.of(chain.map { it.property }, meta.bytes) ?: return null
                val want = Display.fromExif(ExifOrientation.rotate(current.exif, turns))
                wanted[target] = want
                val properties = chain.map { it.property }
                if (rotations.isNotEmpty()) {
                    val at = rotations.single()
                    if (chain.drop(at + 1).any { it.property.type in FRAMING }) return null
                    val angle = (0..3).firstOrNull { Display.of(properties, meta.bytes, rotation = at to it) == want } ?: return null
                    modified += Triple(target, chain[at].association.index, angle)
                } else {
                    val angle = (1..3).firstOrNull { Display.of(properties, meta.bytes, appended = it) == want } ?: return null
                    appended += target to angle
                }
            }

            // Une propriété irot partagée n'est réécrite que si toutes les images qui l'utilisent tournent pareil.
            val inMeta = ArrayList<Pair<Int, ByteArray>>() // positions dans la boîte meta, même longueur
            val repointed = ArrayList<Triple<Association, Long, Int>>() // association, image, angle
            for ((index, uses) in modified.groupBy { it.second }) {
                val users = meta.associations.values.filter { item -> item.entries.any { it.index == index } }.map { it.id }
                val ids = uses.map { it.first }.toSet()
                val angle = uses.map { it.third }.distinct().singleOrNull()
                if (angle != null && users.all { it in ids || it in followers }) {
                    val property = meta.properties[index - 1]
                    if (property.size < 9) return null
                    val byte = meta.bytes[property.content].toInt()
                    if ((byte and 0x03) != angle) inMeta += property.content to byteArrayOf(((byte and 0xFC) or angle).toByte())
                } else {
                    for ((image, _, wantedAngle) in uses) {
                        val association = meta.associations.getValue(image).entries.first { it.index == index }
                        repointed += Triple(association, image, wantedAngle)
                    }
                }
            }

            val newAngles = (appended.map { it.second } + repointed.map { it.third }).distinct().sorted()
            val newIndex = newAngles.withIndex().associate { (i, angle) -> angle to meta.properties.size + 1 + i }
            for ((association, _, angle) in repointed) {
                val index = newIndex.getValue(angle)
                if (index > association.maxIndex) return null
                inMeta += association.position to association.encode(index, association.essential)
            }

            val orientation = wanted.getValue(meta.primary).exif
            val filePatches = ArrayList<Patch>()
            metadataPatches(read, length, container, meta, orientation, filePatches) ?: return null
            val hasMetadata = meta.types.values.any { it == "Exif" || it == "mime" }

            // Positions dans meta, de l'élément EXIF ou XMP rangé dans `idat` : appliquées à la boîte.
            val outside = ArrayList<Patch>()
            for (patch in filePatches) {
                if (patch.offset >= container.metaStart && patch.offset + patch.bytes.size <= container.metaEnd) {
                    inMeta += (patch.offset - container.metaStart).toInt() to patch.bytes
                } else if (patch.offset + patch.bytes.size <= container.metaStart || patch.offset >= container.metaEnd) {
                    outside += patch
                } else {
                    return null
                }
            }

            if (newAngles.isEmpty()) {
                val updated = meta.bytes.copyOf().apply { inMeta.forEach { (at, bytes) -> bytes.copyInto(this, at) } }
                if (!verify(HeifMeta.parse(updated) ?: return null, wanted)) return null
                val patches = inMeta.map { (at, bytes) -> Patch(container.metaStart + at, bytes) } + outside
                return HeifRotation(orientation, hasMetadata, length, container.metaStart, container.metaEnd, null, patches)
            }

            // La boîte meta grandit : seules des boîtes sans position absolue peuvent être décalées.
            if (!container.clean || container.types.any { it != "ftyp" && it != "meta" && it !in SHIFTABLE }) return null
            val width = if (meta.ipmaFlags and 1 != 0) 2 else 1
            val insertions = ArrayList<Pair<Int, ByteArray>>()
            insertions += meta.ipco.end to newAngles.fold(ByteArray(0)) { bytes, angle -> bytes + irot(angle) }
            var ipmaGrowth = 0
            for ((image, angle) in appended) {
                val item = meta.associations[image] ?: return null
                val count = meta.bytes[item.countPosition].toInt() and 0xFF
                val index = newIndex.getValue(angle)
                if (count >= 255 || index > Association.maxIndex(width)) return null
                inMeta += item.countPosition to byteArrayOf((count + 1).toByte())
                insertions += item.end to Association(index, true, item.end, width).encode(index, true)
                ipmaGrowth += width
            }
            val ipcoGrowth = 9 * newAngles.size
            val delta = ipcoGrowth + ipmaGrowth
            for ((box, growth) in listOf(meta.box to delta, meta.iprp to delta, meta.ipco to ipcoGrowth, meta.ipma to ipmaGrowth)) {
                inMeta += box.sizeField.position to (encode(box.size + growth, box.sizeField.width) ?: return null)
            }
            locationPatches(meta, container, delta.toLong(), inMeta) ?: return null

            val grown = meta.bytes.copyOf().apply { inMeta.forEach { (at, bytes) -> bytes.copyInto(this, at) } }
                .let { bytes -> insert(bytes, insertions) }
            val parsed = HeifMeta.parse(grown) ?: return null
            if (!verify(parsed, wanted) || !sameData(meta, parsed, container, delta.toLong())) return null
            return HeifRotation(orientation, hasMetadata, length, container.metaStart, container.metaEnd, grown, outside)
        }

        /** Orientation EXIF et XMP mises à [orientation] ; null si un élément est mal formé. */
        private fun metadataPatches(
            read: (Long, Int) -> ByteArray?,
            length: Long,
            container: Container,
            meta: HeifMeta,
            orientation: Int,
            patches: MutableList<Patch>,
        ): Unit? {
            for ((id, type) in meta.types) {
                if (type != "Exif") continue
                val location = meta.locations.firstOrNull { it.id == id } ?: continue
                val (start, size) = meta.fileRange(location, container.metaStart) ?: continue
                val head = read(start, min(size, MAX_EXIF_HEAD.toLong()).toInt()) ?: continue
                val slot = ExifSlot.locate(head) ?: continue
                patches += Patch(start + slot.position, slot.bytes(orientation))
            }
            for ((start, size) in HeifXmp.locate(read)) {
                if (start < 0 || start + size > length) return null
                val packet = read(start, size) ?: continue
                for (digit in XmpOrientation.digits(packet)) patches += Patch(start + digit, byteArrayOf(('0'.code + orientation).toByte()))
            }
            return Unit
        }

        /**
         * Décale de [delta] les positions des données rangées après la boîte meta (`iloc`, méthode
         * 0, dans ce fichier) ; null si une donnée est dans la boîte, la chevauche, ou si une
         * position ne tient plus dans son champ.
         */
        private fun locationPatches(meta: HeifMeta, container: Container, delta: Long, patches: MutableList<Pair<Int, ByteArray>>): Unit? {
            for (location in meta.locations) {
                if (location.method != 0 || location.dataReference != 0) continue
                val absolute = location.extents.map { location.base + it.offset }
                for ((extent, start) in location.extents.zip(absolute)) {
                    if (start >= container.metaStart && start < container.metaEnd) return null
                    if (start < container.metaStart && (extent.length == 0L || start + extent.length > container.metaStart)) return null
                }
                if (absolute.all { it < container.metaStart }) continue
                val baseField = location.baseField
                if (baseField != null && location.base >= container.metaEnd) {
                    patches += baseField.position to (encode(location.base + delta, baseField.width) ?: return null)
                } else {
                    for ((extent, start) in location.extents.zip(absolute)) {
                        if (start < container.metaEnd) continue
                        val field = extent.offsetField ?: return null
                        patches += field.position to (encode(extent.offset + delta, field.width) ?: return null)
                    }
                }
            }
            return Unit
        }

        /** Chaque image cible s'affiche dans l'orientation voulue. */
        private fun verify(meta: HeifMeta, wanted: Map<Long, Display>): Boolean = wanted.all { (id, want) ->
            val chain = meta.chain(id) ?: return false
            Display.of(chain.map { it.property }, meta.bytes) == want
        }

        /** Mêmes éléments aux mêmes données : positions d'avant la boîte inchangées, celles d'après décalées de [delta]. */
        private fun sameData(before: HeifMeta, after: HeifMeta, container: Container, delta: Long): Boolean {
            if (before.locations.size != after.locations.size) return false
            return before.locations.zip(after.locations).all { (old, new) ->
                old.id == new.id && old.method == new.method && old.extents.size == new.extents.size &&
                    old.extents.zip(new.extents).all { (a, b) ->
                        val start = old.base + a.offset
                        val shifted = if (old.method == 0 && old.dataReference == 0 && start >= container.metaEnd) start + delta else start
                        a.length == b.length && new.base + b.offset == shifted
                    }
            }
        }

        private fun irot(angle: Int): ByteArray = byteArrayOf(0, 0, 0, 9, 'i'.code.toByte(), 'r'.code.toByte(), 'o'.code.toByte(), 't'.code.toByte(), angle.toByte())

        /** [bytes] avec les insertions faites, de la dernière à la première : les positions restent celles d'origine. */
        private fun insert(bytes: ByteArray, insertions: List<Pair<Int, ByteArray>>): ByteArray {
            var result = bytes
            for ((at, inserted) in insertions.sortedByDescending { it.first }) {
                result = result.copyOfRange(0, at) + inserted + result.copyOfRange(at, result.size)
            }
            return result
        }

        /** Entier non signé gros-boutiste sur [width] octets, null s'il n'y tient pas. */
        private fun encode(value: Long, width: Int): ByteArray? {
            if (value < 0 || (width < 8 && value ushr (8 * width) != 0L)) return null
            return ByteArray(width) { (value ushr (8 * (width - 1 - it))).toByte() }
        }
    }
}

/**
 * Transformation d'affichage, comme [ExifOrientation] : [turns] quarts de tour horaires, puis
 * miroir horizontal si [mirrored]. Les propriétés s'appliquent dans l'ordre de leurs
 * associations : `irot` tourne de quarts de tour antihoraires ; `imir` échange le haut et le bas
 * (mode 0, orientation EXIF 4) ou la gauche et la droite (mode 1, orientation EXIF 2), comme le
 * précise l'édition 2022 de la norme et comme le lisent libheif et libavif.
 */
private data class Display(val turns: Int, val mirrored: Boolean) {

    val exif: Int get() = (1..8).first { ExifOrientation.quarterTurns(it) == turns && ExifOrientation.mirrored(it) == mirrored }

    /** Puis [quarters] quarts de tour antihoraires : avec miroir, l'image stockée tourne dans l'autre sens. */
    fun rotated(quarters: Int): Display =
        Display(Math.floorMod(if (mirrored) turns + quarters else turns - quarters, 4), mirrored)

    /** Puis miroir de mode [mode] : gauche-droite (1), ou haut-bas (0), soit un demi-tour et un miroir gauche-droite. */
    fun mirror(mode: Int): Display = if (mode == 1) Display(turns, !mirrored) else Display(Math.floorMod(turns + 2, 4), !mirrored)

    companion object {
        fun fromExif(orientation: Int) = Display(ExifOrientation.quarterTurns(orientation), ExifOrientation.mirrored(orientation))

        /**
         * Affichage obtenu par les propriétés [properties] (boîtes de [bytes]), l'angle de la
         * [rotation]-ième remplacé, ou une rotation [appended] ajoutée à la fin ; null si une
         * propriété de transformation est tronquée.
         */
        fun of(properties: List<HeifMeta.Box>, bytes: ByteArray, rotation: Pair<Int, Int>? = null, appended: Int? = null): Display? {
            var display = Display(0, false)
            properties.forEachIndexed { index, property ->
                if (property.type != "irot" && property.type != "imir") return@forEachIndexed
                if (property.size < 9) return null
                val value = bytes[property.content].toInt()
                display = if (property.type == "irot") {
                    display.rotated(if (rotation?.first == index) rotation.second else value and 0x03)
                } else {
                    display.mirror(value and 0x01)
                }
            }
            return if (appended != null) display.rotated(appended) else display
        }
    }
}

/** Association d'une propriété à une image (`ipma`) : champ de [width] octets à [position] dans meta. */
private class Association(val index: Int, val essential: Boolean, val position: Int, val width: Int) {
    val maxIndex: Int get() = maxIndex(width)

    fun encode(index: Int, essential: Boolean): ByteArray = if (width == 1) {
        byteArrayOf(((if (essential) 0x80 else 0) or index).toByte())
    } else {
        val value = (if (essential) 0x8000 else 0) or index
        byteArrayOf((value shr 8).toByte(), value.toByte())
    }

    companion object {
        fun maxIndex(width: Int): Int = if (width == 1) 0x7F else 0x7FFF
    }
}

/**
 * Boîtes de premier niveau du fichier et boîte `meta`, lue entière (quelques kilo-octets).
 * [clean] : les boîtes se suivent jusqu'à la fin exacte du fichier.
 */
private class Container(val types: List<String>, val clean: Boolean, val metaStart: Long, val meta: ByteArray) {
    val metaEnd: Long get() = metaStart + meta.size

    companion object {
        fun read(read: (Long, Int) -> ByteArray?, length: Long): Container? {
            val types = ArrayList<String>()
            var metaStart = -1L
            var metaSize = 0L
            var position = 0L
            var clean = false
            while (types.size < MAX_TOP_BOXES) {
                if (position == length) {
                    clean = true
                    break
                }
                val header = read(position, 16) ?: read(position, 8) ?: break
                val size32 = header.u32(0)
                val size = when (size32) {
                    1L -> if (header.size >= 16) header.u64(8) else break
                    0L -> length - position
                    else -> size32
                }
                val headerLength = if (size32 == 1L) 16 else 8
                if (size < headerLength || position + size > length) break
                val type = String(header, 4, 4, Charsets.ISO_8859_1)
                if (types.isEmpty() && type != "ftyp") return null
                if (type == "meta") {
                    if (metaStart >= 0) return null
                    metaStart = position
                    metaSize = size
                }
                types += type
                position += size
            }
            if (metaStart < 0 || metaSize > MAX_META) return null
            val meta = read(metaStart, metaSize.toInt()) ?: return null
            return Container(types, clean, metaStart, meta)
        }

        private const val MAX_TOP_BOXES = 64
        private const val MAX_META = 8L shl 20
    }
}

/** Orientation EXIF (balise 0x0112, un SHORT) dans le début d'un élément `Exif` : décalage du TIFF, puis le TIFF. */
private class ExifSlot(val position: Int, private val bigEndian: Boolean) {
    fun bytes(orientation: Int): ByteArray =
        if (bigEndian) byteArrayOf(0, orientation.toByte()) else byteArrayOf(orientation.toByte(), 0)

    companion object {
        fun locate(data: ByteArray): ExifSlot? {
            val tiff = 4 + data.u32(0).toInt()
            if (tiff < 4 || tiff + 8 > data.size) return null
            val bigEndian = when {
                data[tiff] == 'M'.code.toByte() && data[tiff + 1] == 'M'.code.toByte() -> true
                data[tiff] == 'I'.code.toByte() && data[tiff + 1] == 'I'.code.toByte() -> false
                else -> return null
            }
            fun u16(at: Int) = if (bigEndian) data.u16(at) else (data[at].u8() or (data[at + 1].u8() shl 8))
            fun u32(at: Int): Long = if (bigEndian) data.u32(at) else (u16(at).toLong() or (u16(at + 2).toLong() shl 16))
            if (u16(tiff + 2) != 42) return null
            val ifd = tiff + u32(tiff + 4).toInt()
            if (ifd < tiff + 8 || ifd + 2 > data.size) return null
            val count = u16(ifd)
            for (entry in 0 until count) {
                val at = ifd + 2 + 12 * entry
                if (at + 12 > data.size) return null
                if (u16(at) != 0x0112) continue
                if (u16(at + 2) != 3 || u32(at + 4) != 1L) return null
                return ExifSlot(at + 8, bigEndian).takeIf { u16(at + 8) in 1..8 }
            }
            return null
        }
    }
}

/**
 * Boîte `meta` d'un HEIF lue en entier : poignée, image principale, éléments, références,
 * emplacements des données, propriétés et associations, avec la position (dans la boîte) de
 * chaque champ que la rotation peut réécrire.
 */
private class HeifMeta private constructor(
    val bytes: ByteArray,
    val box: Box,
    val handler: String,
    val primary: Long,
    val types: Map<Long, String>,
    val references: List<Reference>,
    val locations: List<Location>,
    val idat: Box?,
    val iprp: Box,
    val ipco: Box,
    val ipma: Box,
    val ipmaFlags: Int,
    val properties: List<Box>,
    val associations: Map<Long, Associations>,
) {
    /** Champ entier gros-boutiste de [width] octets à [position]. */
    class Field(val position: Int, val width: Int)

    class Box(val type: String, val start: Int, val header: Int, val size: Long, val sizeField: Field) {
        val content: Int get() = start + header
        val end: Int get() = (start + size).toInt()
    }

    class Reference(val type: String, val from: Long, val to: List<Long>)

    class Extent(val offset: Long, val offsetField: Field?, val length: Long)

    class Location(val id: Long, val method: Int, val dataReference: Int, val base: Long, val baseField: Field?, val extents: List<Extent>)

    /** Associations d'une image : nombre en [countPosition], entrées jusqu'à [end]. */
    class Associations(val id: Long, val countPosition: Int, val end: Int, val entries: List<Association>)

    class Link(val association: Association, val property: Box)

    /** Propriétés associées à l'image [id], dans l'ordre d'application ; null si un index est invalide. */
    fun chain(id: Long): List<Link>? {
        val item = associations[id] ?: return emptyList()
        return item.entries.filter { it.index != 0 }.map { Link(it, properties.getOrNull(it.index - 1) ?: return null) }
    }

    fun display(id: Long): Display? = chain(id)?.let { links -> Display.of(links.map { it.property }, bytes) }

    /** Position et longueur, dans le fichier, des données d'un élément en un seul morceau. */
    fun fileRange(location: Location, metaStart: Long): Pair<Long, Long>? {
        val extent = location.extents.singleOrNull() ?: return null
        if (location.dataReference != 0 || extent.length <= 0) return null
        val origin = when (location.method) {
            0 -> 0L
            1 -> metaStart + (idat?.content ?: return null)
            else -> return null
        }
        return (origin + location.base + extent.offset) to extent.length
    }

    companion object {
        fun parse(bytes: ByteArray): HeifMeta? = runCatching { read(bytes) }.getOrNull()

        private fun read(data: ByteArray): HeifMeta? {
            val box = box(data, 0, data.size) ?: return null
            if (box.type != "meta" || box.end != data.size) return null
            val children = boxes(data, box.content + 4, box.end) ?: return null
            fun single(type: String): Box? = children.filter { it.type == type }.let { if (it.size > 1) error("$type en double") else it.firstOrNull() }
            val hdlr = single("hdlr") ?: return null
            val handler = String(data, hdlr.content + 8, 4, Charsets.ISO_8859_1)
            val pitm = single("pitm") ?: return null
            val primary = if (data[pitm.content].u8() == 0) data.u16(pitm.content + 4).toLong() else data.u32(pitm.content + 4)
            val types = items(data, single("iinf") ?: return null)
            val references = single("iref")?.let { references(data, it) } ?: emptyList()
            val locations = locations(data, single("iloc") ?: return null) ?: return null
            val iprp = single("iprp") ?: return null
            val inside = boxes(data, iprp.content, iprp.end) ?: return null
            val ipco = inside.singleOrNull { it.type == "ipco" } ?: return null
            val ipma = inside.singleOrNull { it.type == "ipma" } ?: return null
            val properties = boxes(data, ipco.content, ipco.end) ?: return null
            val version = data[ipma.content].u8()
            val flags = (data[ipma.content + 1].u8() shl 16) or (data[ipma.content + 2].u8() shl 8) or data[ipma.content + 3].u8()
            val associations = associations(data, ipma, version, flags) ?: return null
            return HeifMeta(
                data, box, handler, primary, types, references, locations, single("idat"), iprp, ipco, ipma, flags, properties, associations,
            )
        }

        /** Boîte qui commence en [start], contenue dans [end] ; null si invalide (taille nulle comprise). */
        private fun box(data: ByteArray, start: Int, end: Int): Box? {
            if (start + 8 > end) return null
            val size32 = data.u32(start)
            val (size, header) = when (size32) {
                1L -> if (start + 16 <= end) data.u64(start + 8) to 16 else return null
                0L -> return null
                else -> size32 to 8
            }
            if (size < header || start + size > end) return null
            val field = if (header == 16) Field(start + 8, 8) else Field(start, 4)
            return Box(String(data, start + 4, 4, Charsets.ISO_8859_1), start, header, size, field)
        }

        /** Boîtes qui remplissent exactement [start, end[ ; null si l'une déborde. */
        private fun boxes(data: ByteArray, start: Int, end: Int): List<Box>? {
            val found = ArrayList<Box>()
            var position = start
            while (position < end) {
                val child = box(data, position, end) ?: return null
                found += child
                position = child.end
            }
            return found
        }

        private fun items(data: ByteArray, iinf: Box): Map<Long, String> {
            val version = data[iinf.content].u8()
            val start = iinf.content + 4 + if (version == 0) 2 else 4
            val types = LinkedHashMap<Long, String>()
            for (infe in boxes(data, start, iinf.end) ?: error("iinf")) {
                if (infe.type != "infe") continue
                val infeVersion = data[infe.content].u8()
                if (infeVersion < 2) continue
                val id = if (infeVersion == 2) data.u16(infe.content + 4).toLong() else data.u32(infe.content + 4)
                val typeAt = infe.content + 4 + (if (infeVersion == 2) 2 else 4) + 2
                if (types.put(id, String(data, typeAt, 4, Charsets.ISO_8859_1)) != null) error("élément $id en double")
            }
            return types
        }

        private fun references(data: ByteArray, iref: Box): List<Reference> {
            val wide = data[iref.content].u8() != 0
            fun id(at: Int): Long = if (wide) data.u32(at) else data.u16(at).toLong()
            val step = if (wide) 4 else 2
            return (boxes(data, iref.content + 4, iref.end) ?: error("iref")).map { box ->
                val from = id(box.content)
                val count = data.u16(box.content + step)
                val to = (0 until count).map { id(box.content + step + 2 + it * step) }
                if (box.content + step + 2 + count * step > box.end) error("référence tronquée")
                Reference(box.type, from, to)
            }
        }

        private fun locations(data: ByteArray, iloc: Box): List<Location>? {
            val version = data[iloc.content].u8()
            if (version > 2) return null
            var position = iloc.content + 4
            val offsetSize = data[position].u8() shr 4
            val lengthSize = data[position].u8() and 0x0F
            val baseSize = data[position + 1].u8() shr 4
            val indexSize = if (version >= 1) data[position + 1].u8() and 0x0F else 0
            if (listOf(offsetSize, lengthSize, baseSize, indexSize).any { it != 0 && it != 4 && it != 8 }) return null
            position += 2
            val count: Long
            if (version < 2) {
                count = data.u16(position).toLong()
                position += 2
            } else {
                count = data.u32(position)
                position += 4
            }
            fun value(size: Int): Pair<Long, Field?> {
                if (position + size > iloc.end) error("iloc tronquée")
                val result = when (size) {
                    0 -> 0L to null
                    4 -> data.u32(position) to Field(position, 4)
                    else -> data.u64(position).also { if (it < 0) error("position hors de portée") } to Field(position, 8)
                }
                position += size
                return result
            }
            val found = ArrayList<Location>()
            repeat(count.toInt()) {
                val idSize = if (version < 2) 2 else 4
                position.check(idSize, iloc)
                val id = if (version < 2) data.u16(position).toLong() else data.u32(position)
                position += idSize
                var method = 0
                if (version >= 1) {
                    position.check(2, iloc)
                    method = data.u16(position) and 0x0F
                    position += 2
                }
                position.check(2, iloc)
                val reference = data.u16(position)
                position += 2
                val (base, baseField) = value(baseSize)
                position.check(2, iloc)
                val extentCount = data.u16(position)
                position += 2
                val extents = (0 until extentCount).map {
                    value(indexSize)
                    val (offset, offsetField) = value(offsetSize)
                    val (length, _) = value(lengthSize)
                    Extent(offset, offsetField, length)
                }
                found += Location(id, method, reference, base, baseField, extents)
            }
            return found
        }

        /** Vérifie que [size] octets suivent la position dans [box]. */
        private fun Int.check(size: Int, box: Box) {
            if (this + size > box.end) error("${box.type} tronquée")
        }

        private fun associations(data: ByteArray, ipma: Box, version: Int, flags: Int): Map<Long, Associations>? {
            var position = ipma.content + 4
            val count = data.u32(position)
            position += 4
            val width = if (flags and 1 != 0) 2 else 1
            val found = LinkedHashMap<Long, Associations>()
            repeat(count.toInt()) {
                val id: Long
                if (version < 1) {
                    position.check(2, ipma)
                    id = data.u16(position).toLong()
                    position += 2
                } else {
                    position.check(4, ipma)
                    id = data.u32(position)
                    position += 4
                }
                position.check(1, ipma)
                val countPosition = position
                val entries = (0 until data[position++].u8()).map {
                    position.check(width, ipma)
                    val value = if (width == 1) data[position].u8() else data.u16(position)
                    val essential = value and (if (width == 1) 0x80 else 0x8000) != 0
                    val index = value and Association.maxIndex(width)
                    Association(index, essential, position, width).also { position += width }
                }
                if (found.put(id, Associations(id, countPosition, position, entries)) != null) return null
            }
            return found
        }
    }
}

private fun Byte.u8(): Int = toInt() and 0xFF

private fun ByteArray.u16(index: Int): Int = (this[index].u8() shl 8) or this[index + 1].u8()

private fun ByteArray.u32(index: Int): Long =
    (this[index].u8().toLong() shl 24) or (this[index + 1].u8().toLong() shl 16) or
        (this[index + 2].u8().toLong() shl 8) or this[index + 3].u8().toLong()

private fun ByteArray.u64(index: Int): Long = (u32(index) shl 32) or u32(index + 4)
