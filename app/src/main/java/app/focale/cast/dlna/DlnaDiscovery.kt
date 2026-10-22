package app.focale.cast.dlna

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Immutable
import app.focale.BuildConfig
import app.focale.core.di.ApplicationScope
import app.focale.core.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pourquoi la recherche des TV n'a pas pu se faire. */
enum class DiscoveryProblem {
    /** Ni Wi-Fi ni Ethernet. */
    NO_WIFI,

    /** Android 17+ : accès au réseau local non accordé. */
    PERMISSION,
}

/** TV connues, recherche en cours et dernier empêchement. */
@Immutable
data class DiscoveryState(
    val renderers: List<DlnaRenderer> = emptyList(),
    val searching: Boolean = false,
    val problem: DiscoveryProblem? = null,
    /** Fin de la dernière recherche (`elapsedRealtime`), 0 avant la première. */
    val searchedAt: Long = 0L,
)

/**
 * Recherche des TV (lecteurs de médias UPnP) du réseau local, variante standard seulement :
 * M-SEARCH envoyé trois fois au groupe SSDP sous verrou multicast Wi-Fi, réponses écoutées
 * quelques secondes, puis description de chaque appareil lue sur le réseau local. Une recherche
 * remplace la liste : une TV éteinte depuis disparaît, sauf celle qui reçoit la diffusion.
 */
@Singleton
class DlnaDiscovery @Inject constructor(
    @ApplicationContext private val context: Context,
    private val networks: LocalNetworks,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val _state = MutableStateFlow(DiscoveryState())
    val state: StateFlow<DiscoveryState> = _state.asStateFlow()

    private var job: Job? = null

    /** UDN de la TV à garder dans la liste même si elle ne répond plus (diffusion en cours). */
    @Volatile var pinned: String? = null

    /** Lance une recherche, sauf si une est déjà en cours. */
    @Synchronized
    fun search() {
        if (!BuildConfig.TV_CAST_AVAILABLE || job?.isActive == true) return
        if (!networks.hasAccess()) {
            _state.update { it.copy(problem = DiscoveryProblem.PERMISSION) }
            return
        }
        _state.update { it.copy(searching = true, problem = null) }
        job = scope.launch {
            try {
                val link = networks.current()
                if (link == null) {
                    _state.update { it.copy(problem = DiscoveryProblem.NO_WIFI) }
                    return@launch
                }
                val found = withContext(io) { search(link) }
                _state.update { state ->
                    val kept = state.renderers.filter { it.udn == pinned && found.none { new -> new.udn == it.udn } }
                    state.copy(renderers = (found + kept).sortedBy { it.name.lowercase() })
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: IOException) {
                Log.w(TAG, "Recherche des TV impossible", error)
            } finally {
                _state.update { it.copy(searching = false, searchedAt = SystemClock.elapsedRealtime()) }
            }
        }
    }

    /** Recherche si la dernière a plus de [maxAgeMs] (ouverture de la visionneuse, retour au premier plan). */
    fun searchIfStale(maxAgeMs: Long = STALE_MS) {
        val last = _state.value.searchedAt
        if (last == 0L || SystemClock.elapsedRealtime() - last >= maxAgeMs) search()
    }

    /** Retire une TV qui ne répond plus. */
    fun forget(udn: String) {
        _state.update { state -> state.copy(renderers = state.renderers.filterNot { it.udn == udn }) }
    }

    /** Annonces reçues pendant [LISTEN_MS], puis leurs descriptions. */
    private suspend fun search(link: LocalLink): List<DlnaRenderer> {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock(LOCK_TAG)?.apply { setReferenceCounted(false) }
        val locations = LinkedHashMap<String, SsdpMessage>()
        try {
            runCatching { lock?.acquire() }
            MulticastSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(link.address, 0))
                runCatching { link.network.bindSocket(socket) }
                runCatching { NetworkInterface.getByInetAddress(link.address)?.let { socket.networkInterface = it } }
                socket.timeToLive = MULTICAST_TTL
                socket.soTimeout = RECEIVE_SLICE_MS
                val request = Ssdp.searchRequest()
                val group = InetSocketAddress(checkNotNull(LanAddresses.literal(Ssdp.ADDRESS)), Ssdp.PORT)
                val buffer = ByteArray(DATAGRAM_SIZE)
                val start = SystemClock.elapsedRealtime()
                var sent = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val elapsed = SystemClock.elapsedRealtime() - start
                    if (elapsed >= LISTEN_MS) break
                    if (sent < SEND_AT_MS.size && elapsed >= SEND_AT_MS[sent]) {
                        runCatching { socket.send(DatagramPacket(request, request.size, group)) }
                        sent++
                    }
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (timeout: SocketTimeoutException) {
                        continue
                    }
                    val message = Ssdp.parse(String(packet.data, packet.offset, packet.length, Charsets.UTF_8)) ?: continue
                    val location = message.location ?: continue
                    if (message.kind == SsdpMessage.Kind.BYEBYE || !message.isRenderer) continue
                    if (!location.isLanUrl()) continue
                    locations.putIfAbsent(message.udn ?: location, message)
                }
            }
        } finally {
            runCatching { lock?.release() }
        }
        val http = link.httpClient()
        return coroutineScope {
            locations.values.map { message ->
                async {
                    runCatching {
                        val location = message.location.orEmpty()
                        val response = http.get(location)
                        if (response.status == 200) DeviceDescription.parse(response.body, location) else null
                    }.getOrNull()
                }
            }.awaitAll().filterNotNull().distinctBy { it.udn }
        }
    }

    private fun String.isLanUrl(): Boolean = runCatching {
        URI(trim()).host?.let(LanAddresses::literal)?.let(LanAddresses::isLan) == true
    }.getOrDefault(false)

    private companion object {
        const val TAG = "DlnaDiscovery"
        const val LOCK_TAG = "focale-ssdp"
        /** Trois envois (pertes UDP), réponses attendues MX = 2 s après le dernier. */
        val SEND_AT_MS = longArrayOf(0, 600, 1_400)
        const val LISTEN_MS = 4_000L
        const val RECEIVE_SLICE_MS = 200
        const val DATAGRAM_SIZE = 2_048
        const val MULTICAST_TTL = 2
        /** Une recherche par minute au plus à l'ouverture de la visionneuse ou du diaporama. */
        const val STALE_MS = 60_000L
    }
}
