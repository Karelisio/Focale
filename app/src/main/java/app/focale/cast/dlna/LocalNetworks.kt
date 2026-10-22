package app.focale.cast.dlna

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.Inet4Address
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/** Réseau Wi-Fi (ou Ethernet) de l'appareil et son adresse IPv4 privée. */
internal class LocalLink(val network: Network, val address: Inet4Address) {
    /** Socket non connectée qui passe par ce réseau, même quand un VPN est actif. */
    fun socket(): Socket = network.socketFactory.createSocket()

    fun httpClient(): LanHttpClient = LanHttpClient(socketFactory = ::socket)
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

    /** Réseau local actuel : le réseau par défaut s'il est Wi-Fi ou Ethernet, sinon l'un d'eux (VPN actif). */
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
            val address = manager.getLinkProperties(network)?.linkAddresses.orEmpty()
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull(LanAddresses::isLan)
                ?: continue
            return LocalLink(network, address)
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
