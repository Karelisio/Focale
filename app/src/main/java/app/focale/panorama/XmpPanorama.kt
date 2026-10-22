package app.focale.panorama

import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * Propriétés « Photo Sphere » (espace de noms GPano) d'un paquet XMP, telles qu'écrites par
 * l'appareil ou le logiciel d'assemblage. Les tailles sont en pixels du panorama d'origine :
 * seules leurs proportions comptent, l'image ayant pu être réduite depuis.
 *
 * @property projectionType « equirectangular » pour une sphère, autre chose (cylindrique…) pour
 *   un panorama à plat ; null si la propriété manque.
 * @property usePanoramaViewer faux : l'auteur demande l'affichage d'une image ordinaire.
 * @property poseHeadingDegrees cap (boussole) du centre du panorama complet.
 * @property initialViewHeadingDegrees cap de la vue initiale, sur la même boussole.
 * @property initialHorizontalFovDegrees champ horizontal de la vue initiale.
 */
data class GPano(
    val projectionType: String? = null,
    val usePanoramaViewer: Boolean? = null,
    val croppedWidth: Int? = null,
    val croppedHeight: Int? = null,
    val croppedLeft: Int? = null,
    val croppedTop: Int? = null,
    val fullWidth: Int? = null,
    val fullHeight: Int? = null,
    val poseHeadingDegrees: Double? = null,
    val initialViewHeadingDegrees: Double? = null,
    val initialViewPitchDegrees: Double? = null,
    val initialHorizontalFovDegrees: Double? = null,
) {
    /** Projection équirectangulaire : celle des sphères, la seule que décrit la spécification. */
    val isEquirectangular: Boolean get() = projectionType == null || projectionType.trim().equals("equirectangular", ignoreCase = true)
}

/**
 * Lecture tolérante des propriétés GPano d'un paquet XMP, comme [app.focale.video.XmpMotionPhoto] :
 * préfixes résolus d'après les déclarations `xmlns` (à défaut, préfixe usuel `GPano`), propriétés
 * acceptées en attributs comme en éléments, et une erreur de syntaxe n'efface pas ce qui a déjà
 * été lu. Ni DTD ni entité externe.
 */
internal object XmpPanorama {

    const val GPANO_NS = "http://ns.google.com/photos/1.0/panorama/"
    private const val DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"

    private val knownPrefixes = mapOf("GPano" to GPANO_NS)

    /** Propriétés GPano du paquet, ou null s'il n'en porte aucune. */
    fun parse(xmp: ByteArray): GPano? {
        val handler = PanoramaHandler()
        try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
            }
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

    private class PanoramaHandler : DefaultHandler() {
        private val scopes = ArrayDeque<Map<String, String>>()
        private val text = StringBuilder()
        private val values = HashMap<String, String>()

        fun result(): GPano? {
            if (values.isEmpty()) return null
            return GPano(
                projectionType = values["ProjectionType"]?.trim()?.takeIf { it.isNotEmpty() },
                usePanoramaViewer = values["UsePanoramaViewer"]?.let(::flag),
                croppedWidth = int("CroppedAreaImageWidthPixels"),
                croppedHeight = int("CroppedAreaImageHeightPixels"),
                croppedLeft = int("CroppedAreaLeftPixels"),
                croppedTop = int("CroppedAreaTopPixels"),
                fullWidth = int("FullPanoWidthPixels"),
                fullHeight = int("FullPanoHeightPixels"),
                poseHeadingDegrees = real("PoseHeadingDegrees"),
                initialViewHeadingDegrees = real("InitialViewHeadingDegrees"),
                initialViewPitchDegrees = real("InitialViewPitchDegrees"),
                initialHorizontalFovDegrees = real("InitialHorizontalFOVDegrees"),
            )
        }

        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            scopes.addLast(declarations(attributes))
            text.setLength(0)
            for (index in 0 until attributes.length) {
                val attribute = attributes.getQName(index)
                if (attribute == "xmlns" || attribute.startsWith("xmlns:")) continue
                property(attribute, attributes.getValue(index))
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text.append(ch, start, length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String) {
            val value = text.toString().trim()
            text.setLength(0)
            if (value.isNotEmpty()) property(qName, value)
            scopes.removeLast()
        }

        private fun property(qName: String, value: String) {
            val (namespace, name) = resolve(qName)
            // Première valeur gardée : une seconde description (copie de l'extension) ne la remplace pas.
            if (namespace == GPANO_NS) values.putIfAbsent(name, value)
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

        private fun int(name: String): Int? = values[name]?.trim()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }

        private fun real(name: String): Double? = values[name]?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }

        private fun flag(value: String): Boolean? = when (value.trim().lowercase()) {
            "true", "1" -> true
            "false", "0" -> false
            else -> null
        }
    }
}
