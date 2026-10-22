package app.focale.cast.dlna

import java.io.ByteArrayInputStream
import java.io.StringReader
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.EntityResolver
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

/**
 * Élément XML réduit à son nom local (préfixe d'espace de noms retiré), ses enfants et son texte :
 * descriptions d'appareils et réponses SOAP n'ont besoin de rien d'autre, et les TV n'écrivent pas
 * toutes leurs préfixes de la même façon (`s:Envelope`, `SOAP-ENV:Envelope`, `u:PlayResponse`…).
 */
internal class XmlNode(val name: String) {
    val children = mutableListOf<XmlNode>()
    internal val content = StringBuilder()

    /** Texte de l'élément, espaces de bord retirés. */
    val text: String get() = content.toString().trim()

    fun child(name: String): XmlNode? = children.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun childText(name: String): String? = child(name)?.text?.takeIf { it.isNotEmpty() }

    fun all(name: String): List<XmlNode> = children.filter { it.name.equals(name, ignoreCase = true) }

    /** Premier élément nommé [name] parmi les descendants, en profondeur d'abord. */
    fun find(name: String): XmlNode? {
        for (child in children) {
            if (child.name.equals(name, ignoreCase = true)) return child
            child.find(name)?.let { return it }
        }
        return null
    }
}

internal object XmlTree {
    private const val DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl"

    /** Arbre de [xml], null s'il est mal formé. Ni DTD ni entité externe ne sont chargées. */
    fun parse(xml: ByteArray): XmlNode? = try {
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = false
            isValidating = false
        }
        runCatching { factory.setFeature(DISALLOW_DOCTYPE, true) }
        val handler = TreeHandler()
        val reader = factory.newSAXParser().xmlReader
        reader.entityResolver = EntityResolver { _, _ -> InputSource(StringReader("")) }
        reader.contentHandler = handler
        reader.parse(InputSource(ByteArrayInputStream(xml)))
        handler.root
    } catch (malformed: Exception) {
        null
    }

    fun parse(xml: String): XmlNode? = parse(xml.toByteArray())

    private class TreeHandler : DefaultHandler() {
        var root: XmlNode? = null
        private val stack = ArrayDeque<XmlNode>()

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            val raw = qName?.takeIf { it.isNotEmpty() } ?: localName.orEmpty()
            val node = XmlNode(raw.substringAfter(':'))
            stack.lastOrNull()?.children?.add(node) ?: run { root = node }
            stack.addLast(node)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            stack.removeLastOrNull()
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            stack.lastOrNull()?.content?.append(ch, start, length)
        }
    }
}

/** Texte échappé pour un contenu ou un attribut XML. */
internal fun String.xmlEscaped(): String = buildString(length) {
    for (char in this@xmlEscaped) {
        when (char) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            else -> if (char >= ' ' || char == '\t' || char == '\n' || char == '\r') append(char)
        }
    }
}
