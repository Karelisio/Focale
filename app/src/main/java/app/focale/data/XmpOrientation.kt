package app.focale.data

import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Orientation XMP (`tiff:Orientation`) de l'image principale, que Lightroom et d'autres
 * logiciels lisent en plus de l'EXIF : laissée telle quelle, elle leur ferait appliquer
 * l'ancienne orientation. Seul le paquet de l'image principale est lu : segment APP1
 * « http://ns.adobe.com/xap/1.0/ » d'un JPEG, avant le début de l'image (celui de la carte de
 * gain Ultra HDR, qui suit la fin de l'image, n'est jamais atteint), ou bloc RIFF « XMP » d'un
 * WebP.
 *
 * La propriété est mise à jour plutôt que retirée : sa valeur, de 1 à 8, tient en un chiffre,
 * remplacé sur place à longueur égale. Rien ne bouge dans le fichier (décalages MPF d'une
 * photo Ultra HDR, vidéo d'une photo animée), aucune métadonnée n'est perdue et XMP et EXIF
 * disent la même chose. La retirer obligerait à raccourcir le paquet, ou à la masquer
 * d'espaces, pour un résultat moins complet. Absente, elle n'est pas ajoutée : l'EXIF fait foi.
 *
 * @property offsets position dans le fichier du chiffre de chaque occurrence.
 */
internal class XmpOrientationSlot(val offsets: List<Long>) {

    fun write(channel: FileChannel, orientation: Int) {
        require(orientation in 1..8) { "Orientation invalide : $orientation" }
        for (offset in offsets) {
            val buffer = ByteBuffer.wrap(byteArrayOf(('0'.code + orientation).toByte()))
            while (buffer.hasRemaining()) channel.write(buffer, offset + buffer.position())
        }
    }

    companion object {
        /**
         * Chiffres de l'orientation XMP d'un JPEG ou d'un WebP lu par [read] ([length] octets à
         * [offset], null au-delà de la fin) ; null si aucune valeur n'est à mettre à jour.
         */
        fun locate(read: (offset: Long, length: Int) -> ByteArray?): XmpOrientationSlot? {
            val offsets = XmpPackets.locate(read).flatMap { (start, length) ->
                val packet = read(start, length) ?: return@flatMap emptyList()
                XmpOrientation.digits(packet).map { start + it }
            }
            return if (offsets.isEmpty()) null else XmpOrientationSlot(offsets)
        }
    }
}

/** [length] octets lus à [offset], ou null si le fichier s'arrête avant. */
internal fun FileChannel.readAt(offset: Long, length: Int): ByteArray? {
    if (offset < 0 || length < 0) return null
    val buffer = ByteBuffer.allocate(length)
    while (buffer.hasRemaining()) {
        if (read(buffer, offset + buffer.position()) < 0) return null
    }
    return buffer.array()
}

/** Paquets XMP de l'image principale d'un JPEG ou d'un WebP, sans lire les données d'image. */
internal object XmpPackets {

    private const val SOS = 0xDA
    private const val EOI = 0xD9
    private const val APP1 = 0xE1
    private const val TEM = 0x01
    private const val MAX_SEGMENTS = 128

    /** Un paquet XMP de WebP n'a pas de limite de taille : au-delà, le bloc est ignoré. */
    private const val MAX_WEBP_PACKET = 4L * 1024 * 1024

    private val XMP_SIGNATURE = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
    private val RIFF = "RIFF".toByteArray(Charsets.US_ASCII)
    private val WEBP = "WEBP".toByteArray(Charsets.US_ASCII)
    private val XMP_CHUNK = "XMP ".toByteArray(Charsets.US_ASCII)

    /** (position, longueur) de chaque paquet dans le fichier. */
    fun locate(read: (offset: Long, length: Int) -> ByteArray?): List<Pair<Long, Int>> {
        val head = read(0, 12) ?: return emptyList()
        return when {
            head[0].u8() == 0xFF && head[1].u8() == 0xD8 -> jpeg(read)
            head.matches(0, RIFF) && head.matches(8, WEBP) -> webp(read, end = 8 + head.u32le(4))
            else -> emptyList()
        }
    }

    /** Segments APP1 XMP qui précèdent le début de l'image (SOS) : rien au-delà n'est lu. */
    private fun jpeg(read: (Long, Int) -> ByteArray?): List<Pair<Long, Int>> {
        val packets = ArrayList<Pair<Long, Int>>()
        var position = 2L
        repeat(MAX_SEGMENTS) {
            val header = read(position, 4) ?: return packets
            if (header[0].u8() != 0xFF) return packets
            val marker = header[1].u8()
            when {
                marker == 0xFF -> {
                    // Octet de bourrage avant un marqueur.
                    position++
                    return@repeat
                }
                marker == SOS || marker == EOI -> return packets
                marker in 0xD0..0xD7 || marker == TEM -> {
                    position += 2
                    return@repeat
                }
            }
            val length = header.u16be(2)
            if (length < 2) return packets
            if (marker == APP1 && length - 2 > XMP_SIGNATURE.size) {
                val signature = read(position + 4, XMP_SIGNATURE.size) ?: return packets
                if (signature.contentEquals(XMP_SIGNATURE)) {
                    packets += (position + 4 + XMP_SIGNATURE.size) to (length - 2 - XMP_SIGNATURE.size)
                }
            }
            position += 2 + length
        }
        return packets
    }

    /** Blocs « XMP » du conteneur RIFF, jusqu'à la fin qu'il déclare. */
    private fun webp(read: (Long, Int) -> ByteArray?, end: Long): List<Pair<Long, Int>> {
        val packets = ArrayList<Pair<Long, Int>>()
        var position = 12L
        while (position + 8 <= end) {
            val header = read(position, 8) ?: break
            val size = header.u32le(4)
            if (header.matches(0, XMP_CHUNK) && size in 1..MAX_WEBP_PACKET) packets += (position + 8) to size.toInt()
            // Les blocs de taille impaire sont suivis d'un octet de remplissage.
            position += 8 + size + (size and 1)
        }
        return packets
    }

    private fun Byte.u8(): Int = toInt() and 0xFF

    private fun ByteArray.u16be(index: Int): Int = (this[index].u8() shl 8) or this[index + 1].u8()

    private fun ByteArray.u32le(index: Int): Long =
        this[index].u8().toLong() or (this[index + 1].u8().toLong() shl 8) or
            (this[index + 2].u8().toLong() shl 16) or (this[index + 3].u8().toLong() shl 24)

    private fun ByteArray.matches(index: Int, expected: ByteArray): Boolean =
        size >= index + expected.size && expected.indices.all { this[index + it] == expected[it] }
}

/**
 * Repère, dans un paquet XMP, le chiffre de chaque `tiff:Orientation` de premier niveau
 * (propriété d'un nœud enfant de `rdf:RDF`), en attribut (guillemets simples ou doubles) ou en
 * élément, quel que soit le préfixe lié à l'espace de noms TIFF ; à défaut de déclaration, le
 * préfixe usuel `tiff` est reconnu, comme dans [app.focale.video.XmpMotionPhoto].
 *
 * Le paquet est lu en ISO-8859-1 : un caractère par octet, d'où des positions exactes ; les
 * caractères UTF-8 du texte n'apparaissent jamais dans les noms ASCII cherchés. Seule une
 * valeur d'un chiffre, entourée ou non d'espaces, est retenue ; toute autre forme (entité,
 * structure, commentaire…) est laissée telle quelle. Les octets nuls (localisation masquée par
 * MediaProvider) comptent comme des espaces entre les attributs. Un paquet mal formé arrête la
 * lecture : seules les valeurs déjà repérées sont gardées.
 */
internal object XmpOrientation {

    private const val TIFF_NS = "http://ns.adobe.com/tiff/1.0/"
    private const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"

    private val knownPrefixes = mapOf("tiff" to TIFF_NS, "rdf" to RDF_NS)

    private val ORIENTATION = TIFF_NS to "Orientation"

    /** Élément ouvert : nom qualifié, nom résolu (espace de noms, nom local) et préfixes qu'il déclare. */
    private class Element(val qName: String, val resolved: Pair<String, String>, val scope: Map<String, String>) {
        val isRdf: Boolean get() = resolved == RDF_NS to "RDF"
    }

    /** Positions, dans [packet], des chiffres à remplacer. */
    fun digits(packet: ByteArray): List<Int> {
        val text = String(packet, Charsets.ISO_8859_1)
        val found = ArrayList<Int>()
        val open = ArrayList<Element>()
        var index = 0
        while (index >= 0) {
            val start = text.indexOf('<', index)
            if (start < 0) break
            index = when {
                text.startsWith("<!--", start) -> after(text, start, "-->")
                text.startsWith("<![CDATA[", start) -> after(text, start, "]]>")
                text.startsWith("<?", start) -> after(text, start, "?>")
                text.startsWith("<!", start) -> after(text, start, ">")
                text.startsWith("</", start) -> endTag(text, start, open)
                else -> startTag(text, start, open, found)
            }
        }
        return found
    }

    /** Position qui suit [end] cherché depuis [start], ou -1. */
    private fun after(text: String, start: Int, end: String): Int = text.indexOf(end, start).let { if (it < 0) -1 else it + end.length }

    private fun endTag(text: String, start: Int, open: ArrayList<Element>): Int {
        val close = text.indexOf('>', start)
        if (close < 0) return -1
        val qName = text.substring(start + 2, close).trimEnd { it.isSeparator() }
        if (open.removeLastOrNull()?.qName != qName) return -1
        return close + 1
    }

    /** Lit la balise ouvrante en [start] ; renvoie la position qui la suit, ou -1 si elle est mal formée. */
    private fun startTag(text: String, start: Int, open: ArrayList<Element>, found: MutableList<Int>): Int {
        var i = start + 1
        while (i < text.length && !text[i].isSeparator() && text[i] != '>' && text[i] != '/') i++
        val qName = text.substring(start + 1, i)
        if (qName.isEmpty()) return -1
        // Attributs : nom, puis début et fin de la valeur (guillemets exclus).
        val attributes = ArrayList<Triple<String, Int, Int>>()
        var selfClosing = false
        while (true) {
            while (i < text.length && text[i].isSeparator()) i++
            if (i >= text.length) return -1
            if (text[i] == '>') {
                i++
                break
            }
            if (text.startsWith("/>", i)) {
                i += 2
                selfClosing = true
                break
            }
            val nameStart = i
            while (i < text.length && !text[i].isSeparator() && text[i] != '=' && text[i] != '>' && text[i] != '/') i++
            val name = text.substring(nameStart, i)
            while (i < text.length && text[i].isSeparator()) i++
            if (name.isEmpty() || i >= text.length || text[i] != '=') return -1
            i++
            while (i < text.length && text[i].isSeparator()) i++
            if (i >= text.length || (text[i] != '"' && text[i] != '\'')) return -1
            val end = text.indexOf(text[i], i + 1)
            if (end < 0) return -1
            attributes += Triple(name, i + 1, end)
            i = end + 1
        }

        val scope = HashMap<String, String>()
        for ((name, from, to) in attributes) {
            if (name == "xmlns") scope[""] = text.substring(from, to)
            if (name.startsWith("xmlns:")) scope[name.substring("xmlns:".length)] = text.substring(from, to)
        }
        val resolved = resolve(qName, open, scope, attribute = false)
        // Attributs d'un nœud enfant de rdf:RDF : propriétés de premier niveau.
        if (open.lastOrNull()?.isRdf == true) {
            for ((name, from, to) in attributes) {
                if (resolve(name, open, scope, attribute = true) == ORIENTATION) digit(text, from, to)?.let(found::add)
            }
        }
        if (selfClosing) return i
        // Élément propriété d'un tel nœud : sa valeur est le texte qui précède la balise fermante.
        if (resolved == ORIENTATION && open.getOrNull(open.size - 2)?.isRdf == true) {
            val next = text.indexOf('<', i)
            if (next >= 0 && text.startsWith("</", next)) digit(text, i, next)?.let(found::add)
        }
        open += Element(qName, resolved, scope)
        return i
    }

    /**
     * (espace de noms, nom local) de [qName] : préfixe déclaré par la balise elle-même ([scope]),
     * sinon par l'élément ouvert le plus proche, sinon préfixe usuel.
     */
    private fun resolve(qName: String, open: List<Element>, scope: Map<String, String>, attribute: Boolean): Pair<String, String> {
        fun lookup(prefix: String): String? = scope[prefix] ?: open.asReversed().firstNotNullOfOrNull { it.scope[prefix] }
        val colon = qName.indexOf(':')
        // Sans préfixe, un attribut n'a pas d'espace de noms ; un élément prend celui par défaut.
        if (colon < 0) return (if (attribute) "" else lookup("").orEmpty()) to qName
        val prefix = qName.substring(0, colon)
        return (lookup(prefix) ?: knownPrefixes[prefix].orEmpty()) to qName.substring(colon + 1)
    }

    /** Position de l'unique chiffre de [from, to[, espaces autour admis ; null pour toute autre valeur. */
    private fun digit(text: String, from: Int, to: Int): Int? {
        var position = -1
        for (index in from until to) {
            val char = text[index]
            when {
                char == ' ' || char == '\t' || char == '\n' || char == '\r' -> Unit
                char in '0'..'9' && position < 0 -> position = index
                else -> return null
            }
        }
        return position.takeIf { it >= 0 }
    }

    /** Espaces XML, et octets nuls laissés par le masquage de la localisation. */
    private fun Char.isSeparator(): Boolean = this == ' ' || this == '\t' || this == '\n' || this == '\r' || this == '\u0000'
}
