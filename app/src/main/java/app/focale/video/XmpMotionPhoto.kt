package app.focale.video

import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/** Élément du répertoire XMP `Container:Directory` (image principale, gainmap, vidéo…). */
internal data class ContainerItem(
    val mime: String? = null,
    val semantic: String? = null,
    val length: Long = 0,
    val padding: Long = 0,
) {
    val isMotionPhoto: Boolean get() = semantic.equals("MotionPhoto", ignoreCase = true)
    val isVideo: Boolean get() = mime?.startsWith("video/", ignoreCase = true) == true
}

/** Propriétés « photo animée » d'un paquet XMP. */
internal data class XmpMotionInfo(
    val motionPhoto: Int? = null,
    val microVideo: Int? = null,
    val microVideoOffset: Long? = null,
    val presentationTimestampUs: Long? = null,
    val items: List<ContainerItem> = emptyList(),
) {
    /** Mouvement explicitement désactivé (retiré dans une autre application, par exemple). */
    val disabled: Boolean get() = motionPhoto == 0 || (motionPhoto == null && microVideo == 0)
}

/**
 * Lecture tolérante des propriétés de photo animée d'un paquet XMP.
 *
 * Les préfixes sont résolus d'après les déclarations `xmlns` (à défaut, d'après les préfixes
 * usuels : `GCamera`, `Camera`, `Container`, `Item`), les propriétés sont acceptées sous forme
 * d'attributs comme d'éléments, et une erreur de syntaxe n'efface pas ce qui a déjà été lu.
 */
internal object XmpMotionPhoto {

    private const val CAMERA_NS = "http://ns.google.com/photos/1.0/camera/"
    private const val CONTAINER_NS = "http://ns.google.com/photos/1.0/container/"
    private const val ITEM_NS = "http://ns.google.com/photos/1.0/container/item/"
    private const val RDF_NS = "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
    private const val DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"

    private val knownPrefixes = mapOf(
        "GCamera" to CAMERA_NS,
        "Camera" to CAMERA_NS,
        "Container" to CONTAINER_NS,
        "GContainer" to CONTAINER_NS,
        "Item" to ITEM_NS,
        "rdf" to RDF_NS,
    )

    fun parse(xmp: ByteArray): XmpMotionInfo {
        val handler = MotionHandler()
        try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
            }
            // Ni DTD ni entité externe : un fichier piégé ne doit rien pouvoir faire charger.
            runCatching { factory.setFeature(DISALLOW_DOCTYPE, true) }
            val reader = factory.newSAXParser().xmlReader
            reader.entityResolver = EntityResolver { _, _ -> InputSource(StringReader("")) }
            reader.contentHandler = handler
            reader.parse(InputSource(ByteArrayInputStream(xmp, 0, contentLength(xmp))))
        } catch (malformed: Exception) {
            // XMP mal formé ou tronqué : on garde ce qui précède l'erreur.
        }
        return handler.result()
    }

    /** Longueur sans les octets nuls de remplissage qui suivent parfois le paquet. */
    private fun contentLength(xmp: ByteArray): Int {
        var end = xmp.size
        while (end > 0 && xmp[end - 1].toInt() == 0) end--
        return end
    }

    private class MotionHandler : DefaultHandler() {
        private val scopes = ArrayDeque<Map<String, String>>()
        private val text = StringBuilder()
        private var depth = 0
        private var directoryDepth = 0
        private var itemDepth = 0
        private var item: MutableMap<String, String>? = null
        private val items = mutableListOf<ContainerItem>()
        private var motionPhoto: Int? = null
        private var microVideo: Int? = null
        private var microVideoOffset: Long? = null
        private var presentationTimestampUs: Long? = null

        fun result() = XmpMotionInfo(motionPhoto, microVideo, microVideoOffset, presentationTimestampUs, items.toList())

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            depth++
            scopes.addLast(declarations(attributes))
            text.setLength(0)
            val (namespace, name) = resolve(qName)
            if (directoryDepth == 0 && namespace == CONTAINER_NS && name == "Directory") {
                directoryDepth = depth
            } else if (directoryDepth > 0 && item == null &&
                ((namespace == RDF_NS && name == "li") || (namespace == CONTAINER_NS && name == "Item"))
            ) {
                // Un élément du répertoire : `rdf:li` (forme standard) ou `Container:Item` nu.
                item = HashMap()
                itemDepth = depth
            }
            for (index in 0 until attributes.length) {
                val attribute = attributes.getQName(index)
                if (attribute == "xmlns" || attribute.startsWith("xmlns:")) continue
                val (attributeNamespace, attributeName) = resolve(attribute)
                property(attributeNamespace, attributeName, attributes.getValue(index))
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val value = text.toString().trim()
            text.setLength(0)
            if (value.isNotEmpty()) {
                val (namespace, name) = resolve(qName)
                property(namespace, name, value)
            }
            if (depth == itemDepth) {
                item?.let { items += it.toContainerItem() }
                item = null
                itemDepth = 0
            }
            if (depth == directoryDepth) directoryDepth = 0
            scopes.removeLast()
            depth--
        }

        private fun property(namespace: String, name: String, value: String) {
            when (namespace) {
                CAMERA_NS -> when (name) {
                    "MotionPhoto" -> motionPhoto = flag(value)
                    "MicroVideo" -> microVideo = flag(value)
                    "MicroVideoOffset" -> microVideoOffset = value.trim().toLongOrNull()
                    "MotionPhotoPresentationTimestampUs", "MicroVideoPresentationTimestampUs" ->
                        presentationTimestampUs = value.trim().toLongOrNull()
                }
                ITEM_NS -> item?.put(name, value.trim())
            }
        }

        private fun declarations(attributes: Attributes): Map<String, String> {
            var declared: MutableMap<String, String>? = null
            for (index in 0 until attributes.length) {
                val name = attributes.getQName(index)
                if (name.startsWith("xmlns:")) {
                    val map = declared ?: HashMap<String, String>().also { declared = it }
                    map[name.substring("xmlns:".length)] = attributes.getValue(index)
                }
            }
            return declared ?: emptyMap()
        }

        /** (espace de noms, nom local) d'un nom qualifié, d'après la déclaration la plus proche. */
        private fun resolve(qName: String): Pair<String, String> {
            val colon = qName.indexOf(':')
            if (colon < 0) return "" to qName
            val prefix = qName.substring(0, colon)
            val namespace = scopes.lastOrNull { prefix in it }?.get(prefix) ?: knownPrefixes[prefix].orEmpty()
            return namespace to qName.substring(colon + 1)
        }

        private fun flag(value: String): Int? = when (value.trim().lowercase()) {
            "1", "true" -> 1
            "0", "false" -> 0
            else -> value.trim().toIntOrNull()
        }

        private fun Map<String, String>.toContainerItem() = ContainerItem(
            mime = this["Mime"],
            semantic = this["Semantic"],
            length = this["Length"]?.toLongOrNull() ?: 0,
            padding = this["Padding"]?.toLongOrNull() ?: 0,
        )
    }
}
