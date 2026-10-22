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
 * été lu. Ni DTD ni entité externe. Les vidéos 360° (GSpherical) se lisent de la même façon
 * ([properties]).
 */
internal object XmpPanorama {

    const val GPANO_NS = "http://ns.google.com/photos/1.0/panorama/"
    private const val DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"

    /** Propriétés GPano du paquet, ou null s'il n'en porte aucune. */
    fun parse(xmp: ByteArray): GPano? = gpano(properties(xmp, GPANO_NS, "GPano"))

    /**
     * Propriétés de l'espace de noms [namespace] du document [xml] (nom local → valeur), la
     * première de chaque nom ; [prefix] : préfixe usuel, admis sans déclaration.
     */
    fun properties(xml: ByteArray, namespace: String, prefix: String): Map<String, String> {
        val handler = PropertiesHandler(namespace, mapOf(prefix to namespace))
        try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = false
                isValidating = false
            }
            runCatching { factory.setFeature(DISALLOW_DOCTYPE, true) }
            val reader = factory.newSAXParser().xmlReader
            reader.entityResolver = EntityResolver { _, _ -> InputSource(StringReader("")) }
            reader.contentHandler = handler
            reader.parse(InputSource(ByteArrayInputStream(xml, 0, contentLength(xml))))
        } catch (malformed: Exception) {
            // XMP mal formé ou tronqué : on garde ce qui précède l'erreur.
        }
        return handler.values
    }

    private fun gpano(values: Map<String, String>): GPano? {
        if (values.isEmpty()) return null
        fun int(name: String): Int? = values[name]?.trim()?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() }
        fun real(name: String): Double? = values[name]?.trim()?.toDoubleOrNull()?.takeIf { it.isFinite() }
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

    /** « true » / « 1 », « false » / « 0 » ; null sinon. */
    fun flag(value: String): Boolean? = when (value.trim().lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> null
    }

    /** Longueur sans les octets nuls de remplissage qui suivent parfois le paquet. */
    private fun contentLength(xmp: ByteArray): Int {
        var end = xmp.size
        while (end > 0 && xmp[end - 1].toInt() == 0) end--
        return end
    }

    private class PropertiesHandler(private val namespace: String, private val knownPrefixes: Map<String, String>) : DefaultHandler() {
        private val scopes = ArrayDeque<Map<String, String>>()
        private val text = StringBuilder()
        val values = HashMap<String, String>()

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
            if (namespace == this.namespace) values.putIfAbsent(name, value)
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
    }
}
