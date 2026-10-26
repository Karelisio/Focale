package app.focale.cast.dlna

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/**
 * Adresses du réseau local. La diffusion DLNA ne parle qu'à elles : un appareil qui annoncerait
 * une adresse publique (réponse SSDP piégée), ou un nom qui y mène, n'est jamais contacté, et le
 * serveur de médias refuse tout client venu d'ailleurs.
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
        is Inet6Address -> address.isLinkLocalAddress || isUniqueLocal(address)
        else -> false
    }

    /** IPv6 unique locale (ULA, fc00::/7) : routée dans le réseau local, jamais au-delà. */
    fun isUniqueLocal(address: Inet6Address): Boolean = (address.address[0].toInt() and 0xFE) == 0xFC

    /** Clients admis par le serveur de médias : réseau local, ou l'appareil lui-même. */
    fun isLocalClient(address: InetAddress): Boolean = address.isLoopbackAddress || isLan(address)

    /**
     * Adresse écrite littéralement dans [host] (« 192.168.1.20 », « [fe80::1] »), null pour un nom.
     * La zone d'une IPv6 (« %wlan0 », « %25eth0 ») est ignorée : elle désigne l'interface de
     * l'appareil qui l'a écrite ; celle du téléphone est ajoutée au moment de se connecter
     * ([LinkAddresses.scoped]).
     */
    fun literal(host: String): InetAddress? {
        val bare = host.removePrefix("[").removeSuffix("]")
        if (IPV4.matches(bare)) {
            val parts = bare.split('.').map(String::toInt)
            if (parts.any { it > 255 }) return null
            return InetAddress.getByAddress(ByteArray(4) { parts[it].toByte() })
        }
        // Un littéral IPv6 (deux-points) n'est jamais résolu par getByName.
        val address = bare.substringBefore('%')
        if (':' in address && address.all { it.isLetterOrDigit() || it == ':' || it == '.' }) {
            return runCatching { InetAddress.getByName(address) }.getOrNull()
        }
        return null
    }

    /**
     * Nom d'hôte bien formé (RFC 1123) : étiquettes de lettres ASCII, chiffres et tirets, séparées
     * par des points (« tv-salon », « bravia.local », « tv.home.arpa »). Ni adresse, ni rien qui
     * ressemble à une URL ou à une adresse IPv4 incomplète : seul un tel nom part à la résolution.
     */
    fun isHostName(host: String): Boolean {
        val name = host.removeSuffix(".")
        if (name.isEmpty() || name.length > 253 || literal(name) != null) return false
        val labels = name.split('.')
        if (labels.last().all(Char::isDigit)) return false
        return labels.all { label ->
            label.length in 1..63 && label.first() != '-' && label.last() != '-' &&
                label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
        }
    }

    private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")
}

/**
 * Adresses du téléphone sur le réseau local ([LocalLink]) et ce qu'elles permettent de joindre.
 * Logique pure. L'IPv4 privée sert d'abord ; sans elle (réseau IPv6 seul), les IPv6 locales :
 * unique locale, puis de lien local, qui porte l'interface du réseau ([scopeId]).
 *
 * @param ipv4 IPv4 privée, null si le réseau n'en donne pas.
 * @param ipv6 IPv6 locales, uniques locales d'abord.
 * @param scopeId index de l'interface du réseau, donné aux adresses de lien local ; null s'il est inconnu.
 */
internal class LinkAddresses(val ipv4: Inet4Address?, val ipv6: List<Inet6Address>, val scopeId: Int?) {

    /** Adresse principale : IPv4 si elle existe, sinon la première IPv6 locale. */
    val primary: InetAddress get() = ipv4 ?: ipv6.first()

    /** Recherche des TV : en IPv4 si le réseau en a une, sinon en IPv6 ([Ssdp.ADDRESS_V6]). */
    val ipv6Only: Boolean get() = ipv4 == null

    private val uniqueLocal: Inet6Address? get() = ipv6.firstOrNull(LanAddresses::isUniqueLocal)
    private val linkLocal: Inet6Address? get() = ipv6.firstOrNull { it.isLinkLocalAddress }

    /** Adresse d'où part la recherche : l'IPv4, sinon celle de lien local, qui suffit au groupe de lien local. */
    val searchAddress: InetAddress get() = ipv4 ?: linkLocal ?: primary

    /** Groupe SSDP de la recherche, sur l'interface du réseau en IPv6. */
    val searchGroup: InetAddress
        get() = if (ipv4 != null) {
            checkNotNull(LanAddresses.literal(Ssdp.ADDRESS))
        } else {
            scopedGroup(checkNotNull(LanAddresses.literal(Ssdp.ADDRESS_V6)))
        }

    private fun scopedGroup(group: InetAddress): InetAddress =
        scopeId?.let { Inet6Address.getByAddress(null, group.address, it) } ?: group

    /** Vrai si [address] est du réseau local et joignable d'ici : même famille qu'une adresse du téléphone. */
    fun reaches(address: InetAddress): Boolean = LanAddresses.isLan(address) &&
        if (address is Inet4Address) ipv4 != null else ipv6.isNotEmpty()

    /**
     * [address] prête pour une connexion : une IPv6 de lien local n'a de sens que sur une interface,
     * celle du réseau local lui est donnée.
     */
    fun scoped(address: InetAddress): InetAddress {
        val scope = scopeId ?: return address
        if (address !is Inet6Address || !address.isLinkLocalAddress) return address
        return Inet6Address.getByAddress(null, address.address, scope)
    }

    /**
     * Adresse du serveur de médias pour la TV dont la description est servie par [host] : celle de
     * la même famille, pour que la TV puisse la joindre (unique locale pour une unique locale, lien
     * local pour un lien local, chacune à défaut de l'autre) ; pour un nom, l'adresse principale.
     */
    fun serverFor(host: String?): InetAddress {
        val tv = host?.let(LanAddresses::literal)
        val chosen = when {
            tv is Inet4Address -> ipv4
            tv is Inet6Address && tv.isLinkLocalAddress -> linkLocal ?: uniqueLocal
            tv is Inet6Address -> uniqueLocal ?: linkLocal
            else -> null
        }
        return scoped(chosen ?: primary)
    }

    companion object {
        /**
         * Adresses retenues parmi celles du réseau ([addresses]) ; null s'il n'en a aucune du réseau
         * local. [scopeId] : index de son interface.
         */
        fun of(addresses: List<InetAddress>, scopeId: Int?): LinkAddresses? {
            val ipv4 = addresses.filterIsInstance<Inet4Address>().firstOrNull(LanAddresses::isLan)
            // Lien local sans interface connue : inutilisable, ni pour écouter ni pour se connecter.
            val ipv6 = addresses.filterIsInstance<Inet6Address>()
                .filter { LanAddresses.isLan(it) && (scopeId != null || !it.isLinkLocalAddress) }
                .sortedBy { if (LanAddresses.isUniqueLocal(it)) 0 else 1 }
                .map { address ->
                    if (address.isLinkLocalAddress && scopeId != null) Inet6Address.getByAddress(null, address.address, scopeId) else address
                }
            if (ipv4 == null && ipv6.isEmpty()) return null
            return LinkAddresses(ipv4, ipv6, scopeId)
        }
    }
}
