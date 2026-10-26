package app.focale.cast.dlna

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.NetworkInterface
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Réseau Wi-Fi (ou Ethernet) de l'appareil et ses adresses locales ([addresses] : IPv4 privée, sinon
 * IPv6 unique locale ou de lien local).
 */
internal class LocalLink(val network: Network, val addresses: LinkAddresses) {
    /** Adresse principale de l'appareil sur ce réseau. */
    val address get() = addresses.primary

    /** Socket non connectée qui passe par ce réseau, même quand un VPN est actif. */
    fun socket(): Socket = network.socketFactory.createSocket()

    /**
     * Client des TV de ce réseau : sockets du réseau, adresses locales joignables d'ici, noms résolus
     * par le système sur ce réseau (pas par le VPN), interface donnée aux IPv6 de lien local.
     */
    fun httpClient(): LanHttpClient = LanHttpClient(
        socketFactory = ::socket,
        hostAllowed = addresses::reaches,
        resolver = { name -> network.getAllByName(name).toList() },
        scoped = addresses::scoped,
    )
}

/** Accès au réseau local : réseau Wi-Fi courant et autorisation d'Android 17+. */
@Singleton
class LocalNetworks @Inject constructor(@ApplicationContext private val context: Context) {

    /**
     * Android 17+ (`ACCESS_LOCAL_NETWORK`, accordée à l'exécution) : sans elle, aucun appareil du
     * réseau local n'est joignable. Toujours vrai avant.
     */
    fun hasAccess(): Boolean = Build.VERSION.SDK_INT < LOCAL_NETWORK_SDK ||
        ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

    /**
     * Réseau local actuel : le réseau par défaut s'il est Wi-Fi ou Ethernet, sinon l'un d'eux (VPN
     * actif), pourvu qu'il donne à l'appareil une IPv4 privée ou, à défaut, une IPv6 locale.
     */
    internal fun current(): LocalLink? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val active = manager.activeNetwork
        @Suppress("DEPRECATION") // Seul moyen synchrone de voir le Wi-Fi sous un VPN.
        val candidates = listOfNotNull(active) + manager.allNetworks.filter { it != active }
        for (network in candidates) {
            val capabilities = manager.getNetworkCapabilities(network) ?: continue
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            ) {
                continue
            }
            val properties = manager.getLinkProperties(network) ?: continue
            val scope = properties.interfaceName?.let { name -> runCatching { NetworkInterface.getByName(name)?.index }.getOrNull() }
            val addresses = LinkAddresses.of(properties.linkAddresses.map { it.address }, scope) ?: continue
            return LocalLink(network, addresses)
        }
        return null
    }

    companion object {
        /** Android 17. */
        const val LOCAL_NETWORK_SDK = Build.VERSION_CODES.CINNAMON_BUN

        /** `Manifest.permission.ACCESS_LOCAL_NETWORK`, déclarée dans le manifeste de la variante standard. */
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
