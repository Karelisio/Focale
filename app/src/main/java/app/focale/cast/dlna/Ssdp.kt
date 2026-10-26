package app.focale.cast.dlna

/** Annonce d'un appareil UPnP : réponse à une recherche, ou NOTIFY de présence ou de départ. */
internal data class SsdpMessage(
    val kind: Kind,
    /** URL de la description de l'appareil (absente d'un départ). */
    val location: String?,
    /** Type cherché (ST) ou annoncé (NT). */
    val type: String?,
    val usn: String?,
    val server: String?,
    /** Durée de validité de l'annonce (CACHE-CONTROL max-age), en secondes. */
    val maxAgeSeconds: Int?,
) {
    enum class Kind { RESPONSE, ALIVE, BYEBYE }

    /** Identifiant unique de l'appareil (`uuid:…`), tiré de l'USN. */
    val udn: String? get() = usn?.substringBefore("::")?.trim()?.takeIf { it.startsWith("uuid:", ignoreCase = true) }

    /** Vrai si l'annonce concerne un lecteur de médias (MediaRenderer ou son service AVTransport). */
    val isRenderer: Boolean
        get() = type == null ||
            type.startsWith(Ssdp.MEDIA_RENDERER_PREFIX, ignoreCase = true) ||
            type.startsWith(Ssdp.AV_TRANSPORT_PREFIX, ignoreCase = true)
}

/**
 * Découverte SSDP (UPnP Device Architecture 1.1, § 1) : requête M-SEARCH envoyée au groupe
 * multicast, réponses reçues en unicast. La requête ne dit rien de l'appareil qui cherche
 * (pas d'en-tête USER-AGENT, facultatif). En IPv4, le groupe 239.255.255.250 ; sur un réseau sans
 * IPv4, le groupe IPv6 de lien local FF02::C (UDA 1.1, § 1.1.2).
 */
internal object Ssdp {
    const val ADDRESS = "239.255.255.250"

    /** Groupe SSDP IPv6 de lien local : le réseau Wi-Fi seulement, jamais routé. */
    const val ADDRESS_V6 = "FF02::C"
    const val PORT = 1900
    const val MEDIA_RENDERER = "urn:schemas-upnp-org:device:MediaRenderer:1"
    const val MEDIA_RENDERER_PREFIX = "urn:schemas-upnp-org:device:MediaRenderer:"
    const val AV_TRANSPORT_PREFIX = "urn:schemas-upnp-org:service:AVTransport:"

    /**
     * Requête de recherche des lecteurs de médias ; [mx] : délai de réponse maximal (s) ; [ipv6] :
     * adressée au groupe IPv6.
     */
    fun searchRequest(target: String = MEDIA_RENDERER, mx: Int = 2, ipv6: Boolean = false): ByteArray = buildString {
        append("M-SEARCH * HTTP/1.1\r\n")
        append("HOST: ").append(if (ipv6) "[$ADDRESS_V6]" else ADDRESS).append(':').append(PORT).append("\r\n")
        append("MAN: \"ssdp:discover\"\r\n")
        append("MX: ").append(mx.coerceIn(1, 5)).append("\r\n")
        append("ST: ").append(target).append("\r\n")
        append("\r\n")
    }.toByteArray(Charsets.US_ASCII)

    /**
     * Lecture d'un datagramme SSDP : réponse « HTTP/1.1 200 OK » ou NOTIFY (`ssdp:alive`,
     * `ssdp:byebye`). En-têtes sans casse, fins de ligne CRLF ou LF ; null pour toute autre chose
     * (M-SEARCH d'un autre appareil, réponse d'erreur, présence sans LOCATION).
     */
    fun parse(datagram: String): SsdpMessage? {
        val lines = datagram.split('\n').map { it.trimEnd('\r') }
        val start = lines.firstOrNull()?.trim() ?: return null
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) break
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().uppercase()] = line.substring(colon + 1).trim()
        }
        val kind = when {
            start.startsWith("HTTP/", ignoreCase = true) -> {
                if (start.split(' ').getOrNull(1) != "200") return null
                SsdpMessage.Kind.RESPONSE
            }
            start.startsWith("NOTIFY", ignoreCase = true) -> when (headers["NTS"]?.lowercase()) {
                "ssdp:alive" -> SsdpMessage.Kind.ALIVE
                "ssdp:byebye" -> SsdpMessage.Kind.BYEBYE
                else -> return null
            }
            else -> return null
        }
        val location = headers["LOCATION"]?.takeIf { it.isNotEmpty() }
        if (kind != SsdpMessage.Kind.BYEBYE && location == null) return null
        return SsdpMessage(
            kind = kind,
            location = location,
            type = (if (kind == SsdpMessage.Kind.RESPONSE) headers["ST"] else headers["NT"])?.takeIf { it.isNotEmpty() },
            usn = headers["USN"]?.takeIf { it.isNotEmpty() },
            server = headers["SERVER"]?.takeIf { it.isNotEmpty() },
            maxAgeSeconds = headers["CACHE-CONTROL"]?.let { MAX_AGE.find(it)?.groupValues?.get(1)?.toIntOrNull() },
        )
    }

    private val MAX_AGE = Regex("""max-age\s*=\s*"?(\d+)""", RegexOption.IGNORE_CASE)
}
