package app.focale.cast.dlna

import androidx.compose.runtime.Immutable
import java.net.URI

/** Service UPnP d'un appareil : type complet (`urn:…:AVTransport:1`) et URL de commande absolue. */
@Immutable
data class DlnaService(val type: String, val controlUrl: String)

/**
 * TV (ou tout lecteur de médias UPnP) du réseau local, telle que la décrit sa description :
 * nom affiché, fabricant et modèle, service de lecture (AVTransport, indispensable) et, s'il
 * existe, celui du volume (RenderingControl).
 */
@Immutable
data class DlnaRenderer(
    val udn: String,
    val name: String,
    val manufacturer: String?,
    val model: String?,
    val location: String,
    val avTransport: DlnaService,
    val renderingControl: DlnaService?,
) {
    val hasVolume: Boolean get() = renderingControl != null
}

/**
 * Lecture d'une description d'appareil UPnP (`<root><device>…`). Le lecteur peut être l'appareil
 * racine ou un appareil imbriqué (`deviceList`) : on retient le premier qui offre AVTransport, un
 * MediaRenderer d'abord. Les URL relatives se résolvent sur `URLBase` (UPnP 1.0), à défaut sur
 * l'adresse de la description.
 */
internal object DeviceDescription {

    private const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:"
    private const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:"
    private const val MAX_NAME = 80

    fun parse(xml: ByteArray, location: String): DlnaRenderer? {
        val root = XmlTree.parse(xml) ?: return null
        if (!root.name.equals("root", ignoreCase = true)) return null
        val base = root.childText("URLBase") ?: location
        val devices = root.all("device").flatMap(::flatten)
        val device = devices.firstOrNull { it.isRenderer && it.service(AV_TRANSPORT) != null }
            ?: devices.firstOrNull { it.service(AV_TRANSPORT) != null }
            ?: return null
        val avTransport = device.service(AV_TRANSPORT) ?: return null
        val name = device.childText("friendlyName")
            ?: devices.firstNotNullOfOrNull { it.childText("friendlyName") }
            ?: device.childText("modelName")
            ?: return null
        return DlnaRenderer(
            udn = device.childText("UDN") ?: location,
            name = name.clean(),
            manufacturer = device.childText("manufacturer")?.clean(),
            model = device.childText("modelName")?.clean(),
            location = location,
            avTransport = DlnaService(avTransport.type, resolve(base, avTransport.controlUrl) ?: return null),
            renderingControl = device.service(RENDERING_CONTROL)?.let { service ->
                resolve(base, service.controlUrl)?.let { DlnaService(service.type, it) }
            },
        )
    }

    /** L'appareil et ses appareils imbriqués, en profondeur d'abord. */
    private fun flatten(device: XmlNode): List<XmlNode> =
        listOf(device) + device.child("deviceList")?.all("device").orEmpty().flatMap(::flatten)

    private val XmlNode.isRenderer: Boolean
        get() = childText("deviceType")?.startsWith(Ssdp.MEDIA_RENDERER_PREFIX, ignoreCase = true) == true

    private fun XmlNode.service(typePrefix: String): RawService? = child("serviceList")?.all("service")
        ?.firstOrNull { it.childText("serviceType")?.startsWith(typePrefix, ignoreCase = true) == true }
        ?.let { service ->
            val type = service.childText("serviceType") ?: return@let null
            val control = service.childText("controlURL") ?: return@let null
            RawService(type, control)
        }

    private class RawService(val type: String, val controlUrl: String)

    /**
     * URL absolue de [reference] (absolue, relative à la racine ou au dossier de [base]) ; null si
     * illisible ou autre que HTTP.
     */
    fun resolve(base: String, reference: String): String? = runCatching {
        var baseUri = URI(base.trim())
        // URI.resolve colle « x » à « http://hôte:port » sans chemin : la racine d'abord.
        if (baseUri.rawPath.isNullOrEmpty()) baseUri = baseUri.resolve("/")
        val resolved = baseUri.resolve(URI(reference.trim()))
        resolved.takeIf { it.scheme.equals("http", ignoreCase = true) && it.host != null }?.toString()
    }.getOrNull()

    /** Nom affichable : espaces réunis, longueur bornée. */
    private fun String.clean(): String = trim().replace(WHITESPACE, " ").take(MAX_NAME)

    private val WHITESPACE = Regex("""\s+""")
}
