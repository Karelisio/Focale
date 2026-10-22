package app.focale.cast.dlna

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Adresses du réseau local. La diffusion DLNA ne parle qu'à elles : un appareil qui annoncerait
 * une adresse publique (réponse SSDP piégée) n'est jamais contacté, et le serveur de médias
 * refuse tout client venu d'ailleurs.
 */
internal object LanAddresses {

    /**
     * Vrai pour une adresse privée : IPv4 de la RFC 1918 (10/8, 172.16/12, 192.168/16) ou de lien
     * local (169.254/16), IPv6 unique locale (fc00::/7) ou de lien local (fe80::/10).
     */
    fun isLan(address: InetAddress): Boolean = when (address) {
        is Inet4Address -> {
            val bytes = address.address
            val first = bytes[0].toInt() and 0xFF
            val second = bytes[1].toInt() and 0xFF
            first == 10 ||
                (first == 172 && second in 16..31) ||
                (first == 192 && second == 168) ||
                (first == 169 && second == 254)
        }
        is Inet6Address -> address.isLinkLocalAddress || (address.address[0].toInt() and 0xFE) == 0xFC
        else -> false
    }

    /** Clients admis par le serveur de médias : réseau local, ou l'appareil lui-même. */
    fun isLocalClient(address: InetAddress): Boolean = address.isLoopbackAddress || isLan(address)

    /**
     * Adresse écrite littéralement dans [host] (« 192.168.1.20 », « [fe80::1] »), null pour un nom :
     * aucun nom n'est résolu, la diffusion ne fait aucune requête DNS.
     */
    fun literal(host: String): InetAddress? {
        val bare = host.removePrefix("[").removeSuffix("]")
        if (IPV4.matches(bare)) {
            val parts = bare.split('.').map(String::toInt)
            if (parts.any { it > 255 }) return null
            return InetAddress.getByAddress(ByteArray(4) { parts[it].toByte() })
        }
        // Un littéral IPv6 (deux-points) n'est jamais résolu par getByName.
        if (':' in bare && bare.all { it.isLetterOrDigit() || it == ':' || it == '.' || it == '%' }) {
            return runCatching { InetAddress.getByName(bare) }.getOrNull()
        }
        return null
    }

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
}
