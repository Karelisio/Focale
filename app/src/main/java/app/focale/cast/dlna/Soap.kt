package app.focale.cast.dlna

import java.io.IOException
import java.util.Locale

/** Réponse à une action SOAP : arguments de sortie, ou erreur UPnP. */
internal sealed interface SoapResult {
    data class Success(val values: Map<String, String>) : SoapResult
    data class Fault(val code: Int?, val description: String?) : SoapResult
}

/** Erreur renvoyée par la TV ([code] UPnP, 714 « type MIME illégal », 716 « ressource introuvable »…). */
internal class UpnpException(val code: Int?, description: String?) :
    IOException("UPnP ${code ?: "?"} : ${description.orEmpty()}") {

    /** La TV refuse le média lui-même (format, type, ressource) plutôt que la commande. */
    val refusesMedia: Boolean get() = code in MEDIA_ERRORS

    private companion object {
        /** AVTransport : contenu illisible (714 type MIME, 715 ressource verrouillée, 716 introuvable). */
        val MEDIA_ERRORS = setOf(714, 715, 716)
    }
}

/** Messages SOAP des services UPnP (AVTransport, RenderingControl). */
internal object Soap {

    /** Valeur de l'en-tête SOAPACTION, guillemets compris. */
    fun actionHeader(serviceType: String, action: String): String = "\"$serviceType#$action\""

    /** Enveloppe de l'action [action] du service [serviceType], arguments dans l'ordre du service. */
    fun envelope(serviceType: String, action: String, arguments: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append("<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" ")
        append("s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>")
        append("<u:").append(action).append(" xmlns:u=\"").append(serviceType.xmlEscaped()).append("\">")
        for ((name, value) in arguments) {
            append('<').append(name).append('>').append(value.xmlEscaped()).append("</").append(name).append('>')
        }
        append("</u:").append(action).append("></s:Body></s:Envelope>")
    }

    /**
     * Lecture de la réponse à [action] : les arguments de `<actionResponse>`, ou l'erreur UPnP
     * (`Fault/detail/UPnPError`). Null si le document n'est ni l'un ni l'autre.
     */
    fun parseResponse(xml: ByteArray, action: String): SoapResult? {
        val root = XmlTree.parse(xml) ?: return null
        root.find("Fault")?.let { fault ->
            val error = fault.find("UPnPError")
            return SoapResult.Fault(
                code = error?.childText("errorCode")?.toIntOrNull(),
                description = error?.childText("errorDescription") ?: fault.childText("faultstring"),
            )
        }
        val response = root.find("${action}Response") ?: return null
        return SoapResult.Success(response.children.associate { it.name to it.text })
    }
}

/**
 * Métadonnées DIDL-Lite d'un média (argument `CurrentURIMetaData`) : plusieurs TV (Samsung, LG)
 * refusent une URL sans elles. Le profil DLNA d'une photo dépend de sa taille (JPEG_LRG jusqu'à
 * 4096 px) ; celui d'une vidéo reste implicite, la TV lit l'en-tête du fichier.
 */
internal object Didl {

    /** Champ « informations supplémentaires » du protocole, repris par l'en-tête `contentFeatures.dlna.org`. */
    fun contentFeatures(video: Boolean): String =
        if (video) VIDEO_FEATURES else PHOTO_FEATURES

    fun protocolInfo(mimeType: String, video: Boolean): String = "http-get:*:$mimeType:${contentFeatures(video)}"

    fun metadata(
        title: String,
        url: String,
        mimeType: String,
        video: Boolean,
        sizeBytes: Long? = null,
        durationMs: Long? = null,
        width: Int? = null,
        height: Int? = null,
    ): String = buildString {
        append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" ")
        append("xmlns:dc=\"http://purl.org/dc/elements/1.1/\" ")
        append("xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" ")
        append("xmlns:dlna=\"urn:schemas-dlna-org:metadata-1-0/\">")
        append("<item id=\"focale\" parentID=\"0\" restricted=\"1\">")
        append("<dc:title>").append(title.xmlEscaped()).append("</dc:title>")
        append("<upnp:class>").append(if (video) "object.item.videoItem" else "object.item.imageItem.photo").append("</upnp:class>")
        append("<res protocolInfo=\"").append(protocolInfo(mimeType, video).xmlEscaped()).append('"')
        if (sizeBytes != null && sizeBytes > 0) append(" size=\"").append(sizeBytes).append('"')
        if (durationMs != null && durationMs > 0) append(" duration=\"").append(UpnpTime.format(durationMs)).append(".000\"")
        if (width != null && height != null && width > 0 && height > 0) {
            append(" resolution=\"").append(width).append('x').append(height).append('"')
        }
        append('>').append(url.xmlEscaped()).append("</res>")
        append("</item></DIDL-Lite>")
    }

    /** Diffusion en flux, déplacement par octets (`Range`). */
    private const val VIDEO_FEATURES = "DLNA.ORG_OP=01;DLNA.ORG_CI=0;DLNA.ORG_FLAGS=01700000000000000000000000000000"

    /** Photo JPEG jusqu'à 4096 px, transfert interactif. */
    private const val PHOTO_FEATURES = "DLNA.ORG_PN=JPEG_LRG;DLNA.ORG_OP=01;DLNA.ORG_FLAGS=00f00000000000000000000000000000"
}

/** Durées UPnP (`H+:MM:SS[.F+]`), en millisecondes. */
internal object UpnpTime {

    /** « 01:02:03 » (heures sur deux chiffres au moins, que toutes les TV acceptent). */
    fun format(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1_000
        return String.format(Locale.ROOT, "%02d:%02d:%02d", total / 3_600, (total % 3_600) / 60, total % 60)
    }

    /** Lecture de « 0:01:02 », « 00:01:02.500 » ou « 00:01:02.1/2 » ; null pour « NOT_IMPLEMENTED » ou illisible. */
    fun parse(text: String?): Long? {
        val parts = text?.trim()?.split(':') ?: return null
        if (parts.size != 3) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val secondsPart = parts[2]
        val seconds = secondsPart.substringBefore('.').toLongOrNull() ?: return null
        val fraction = secondsPart.substringAfter('.', "").let { raw ->
            val numerator = raw.substringBefore('/')
            val denominator = raw.substringAfter('/', "").toLongOrNull()
            when {
                raw.isEmpty() -> 0L
                denominator != null && denominator > 0 -> (numerator.toLongOrNull() ?: 0L) * 1_000 / denominator
                else -> ("0.$numerator".toDoubleOrNull() ?: 0.0).times(1_000).toLong()
            }
        }
        if (hours < 0 || minutes !in 0..59 || seconds !in 0..59) return null
        return ((hours * 60 + minutes) * 60 + seconds) * 1_000 + fraction
    }
}
