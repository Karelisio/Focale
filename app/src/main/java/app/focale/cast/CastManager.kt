package app.focale.cast

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.focale.BuildConfig
import app.focale.cast.display.ExternalDisplay
import app.focale.cast.display.ExternalDisplays
import app.focale.cast.display.PresentationDisplays
import app.focale.cast.dlna.Didl
import app.focale.cast.dlna.DlnaDiscovery
import app.focale.cast.dlna.DlnaRenderer
import app.focale.cast.dlna.LocalLink
import app.focale.cast.dlna.LocalNetworks
import app.focale.cast.dlna.MediaServer
import app.focale.cast.dlna.RendererClient
import app.focale.cast.dlna.TransportState
import app.focale.cast.dlna.UpnpException
import app.focale.core.di.ApplicationScope
import app.focale.core.di.IoDispatcher
import app.focale.core.model.MediaItem
import app.focale.core.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Diffusion de la visionneuse et du diaporama vers un écran externe ou une TV du réseau local.
 *
 * - **Écran externe** (toutes variantes) : dès qu'un écran de présentation est branché ou connecté,
 *   il devient la cible, sauf si l'utilisateur l'a arrêté (jusqu'à son départ) ou qu'une TV reçoit
 *   la diffusion. L'écran affiche lui-même le média en cours (`Presentation`, voir `CastPresentation`).
 * - **TV** (variante standard) : choisie dans la feuille ; un serveur HTTP local ([MediaServer]) lui
 *   sert le média en cours, commandé par AVTransport ([RendererClient]). Les écrans appellent
 *   [present] avec leur média ; quitter la visionneuse arrête la TV au bout de [GRACE_MS] (le temps
 *   d'ouvrir le diaporama, par exemple), puis la diffusion s'arrête après [IDLE_MS] sans média. Un
 *   service au premier plan ([CastService]) la garde en vie en arrière-plan, avec sa notification.
 *
 * Seuls les médias de la galerie (MediaStore) sont diffusés : rien du coffre-fort n'a de place ici.
 */
@Singleton
class CastManager @Inject internal constructor(
    @ApplicationContext private val context: Context,
    displays: ExternalDisplays,
    private val discovery: DlnaDiscovery,
    private val networks: LocalNetworks,
    private val preparer: CastMediaPreparer,
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    /** Écrans arrêtés par l'utilisateur, oubliés quand ils partent. */
    private val declined = MutableStateFlow<Set<Int>>(emptySet())
    private val session = MutableStateFlow<TvSession?>(null)
    private val presenters = MutableStateFlow<List<Presenter>>(emptyList())
    private val eventChannel = Channel<CastEvent>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Messages à afficher (TV qui refuse un format, connexion perdue…). */
    val events: Flow<CastEvent> = eventChannel.receiveAsFlow()

    val state: StateFlow<CastState> = combine(
        displays.displays.onEach { present ->
            declined.update { ids -> ids.filterTo(HashSet()) { id -> present.any { it.id == id } } }
        },
        discovery.state,
        declined,
        session.flatMapLatest { tv -> tv?.info?.map { tv to it } ?: flowOf(null) },
    ) { screens, found, declinedIds, tv ->
        val renderer = tv?.first?.renderer
        CastState(
            screens = screens,
            tvs = if (renderer != null && found.renderers.none { it.udn == renderer.udn }) found.renderers + renderer else found.renderers,
            searching = found.searching,
            problem = found.problem,
            target = renderer?.let(CastTarget::Tv) ?: PresentationDisplays.choose(screens, declinedIds)?.let(CastTarget::Screen),
            tvStatus = tv?.second?.status,
            volume = tv?.second?.volume,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), CastState())

    /** Lecture vidéo sur la TV, suivie par [RemotePlayer]. */
    val tvPlayback: StateFlow<TvPlayback?> = session
        .flatMapLatest { it?.playback ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    /** Média en cours de l'écran le plus récent qui en montre un (visionneuse, diaporama). */
    private val content: Flow<MediaItem?> = presenters
        .map { list -> list.lastOrNull()?.item?.takeIf(CastMediaPreparer::isCastable) }
        .distinctUntilChanged { old, new -> old?.id == new?.id && old?.dateModified == new?.dateModified }

    private class Presenter(val owner: Any, val item: MediaItem?)

    /** [owner] (un écran) montre [item] ; il remplace son média précédent. */
    fun present(owner: Any, item: MediaItem?) = presenters.update { list ->
        val index = list.indexOfFirst { it.owner === owner }
        if (index < 0) list + Presenter(owner, item) else list.toMutableList().also { it[index] = Presenter(owner, item) }
    }

    /** [owner] ne montre plus rien (écran quitté). */
    fun withdraw(owner: Any) = presenters.update { list -> list.filterNot { it.owner === owner } }

    /** Recherche des TV demandée par l'utilisateur. */
    fun search() = discovery.search()

    /** Recherche automatique à l'ouverture d'un écran, si le réglage la permet et qu'elle date. */
    fun searchIfStale() {
        if (!BuildConfig.TV_CAST_AVAILABLE || !settings.current.value.tvDiscovery || !networks.hasAccess()) return
        discovery.searchIfStale()
    }

    fun selectScreen(display: ExternalDisplay) {
        stopTv()
        declined.update { it - display.id }
    }

    /** Diffusion vers [renderer] : serveur local, service au premier plan, puis le média en cours. */
    fun selectTv(renderer: DlnaRenderer) {
        if (!BuildConfig.TV_CAST_AVAILABLE || session.value?.renderer?.udn == renderer.udn) return
        stopTv()
        val link = networks.current()
        if (link == null) {
            eventChannel.trySend(CastEvent.NoNetwork)
            return
        }
        val tv = TvSession(renderer, link)
        session.value = tv
        discovery.pinned = renderer.udn
        CastService.start(context, renderer.name)
        tv.start()
    }

    /** « Arrêter » : la TV s'arrête, ou l'écran externe revient à la recopie du téléphone. */
    fun stop(target: CastTarget) {
        when (target) {
            is CastTarget.Tv -> stopTv()
            is CastTarget.Screen -> declined.update { it + target.display.id }
        }
    }

    /** Arrêt de la diffusion vers la TV (bouton, notification, application fermée). */
    fun stopTv() = endTv(lost = false)

    val tvActive: Boolean get() = session.value != null

    fun setTvVolume(volume: Int) {
        session.value?.requestVolume(volume.coerceIn(0, 100))
    }

    /** Lecture ou pause de la vidéo [mediaId] sur la TV, appliquée dès qu'elle la joue. */
    fun tvSetPlaying(mediaId: Long, playing: Boolean) {
        session.value?.setPlaying(mediaId, playing)
    }

    fun tvSeek(mediaId: Long, positionMs: Long) {
        session.value?.seek(mediaId, positionMs.coerceAtLeast(0))
    }

    private fun endTv(lost: Boolean) {
        val tv = session.getAndUpdate { null } ?: return
        discovery.pinned = null
        tv.close(sendStop = !lost)
        CastService.stop(context)
        if (lost) {
            discovery.forget(tv.renderer.udn)
            eventChannel.trySend(CastEvent.Lost(tv.renderer.name))
        }
    }

    private data class TvInfo(val status: TvStatus, val volume: Int? = null)

    /** Souhait de l'utilisateur pour la vidéo [mediaId], appliqué quand la TV la joue. */
    private data class Desire(val mediaId: Long, val playing: Boolean = true, val seekMs: Long? = null) {
        fun forMedia(id: Long): Desire = if (id == mediaId) this else Desire(id)
    }

    private inner class TvSession(val renderer: DlnaRenderer, link: LocalLink) {
        private val client = RendererClient(renderer, link.httpClient())
        private val server = MediaServer(link.address)
        private val job = SupervisorJob()
        private val sessionScope = CoroutineScope(job + io)
        private val calls = Mutex()
        private val volumeRequests = MutableStateFlow<Int?>(null)

        val info = MutableStateFlow(TvInfo(TvStatus.CONNECTING))
        val playback = MutableStateFlow<TvPlayback?>(null)

        @Volatile private var desire = Desire(-1)
        @Volatile private var shown = false
        private var loadedAt = 0L

        fun start() {
            sessionScope.launch {
                try {
                    server.start()
                } catch (error: IOException) {
                    Log.w(TAG, "Serveur local impossible", error)
                    eventChannel.trySend(CastEvent.Failed)
                    endTv(lost = false)
                    return@launch
                }
                launch { content.collectLatest(::show) }
                launch { monitor() }
                launch {
                    val volume = runCatching { call { client.volume() } }.getOrNull()
                    info.update { it.copy(volume = volume) }
                }
                launch {
                    volumeRequests.filterNotNull().collectLatest { volume ->
                        delay(VOLUME_DEBOUNCE_MS)
                        runCatching { call { client.setVolume(volume) } }
                    }
                }
            }
        }

        /** Commandes une à une : plusieurs TV refusent deux requêtes simultanées. */
        private suspend fun <T> call(block: () -> T): T = calls.withLock { block() }

        private suspend fun show(item: MediaItem?) {
            if (item == null) {
                if (shown) {
                    delay(GRACE_MS)
                    runCatching { call { client.stop() } }
                    server.clear()
                    shown = false
                    playback.value = null
                }
                delay(IDLE_MS)
                endTv(lost = false)
                return
            }
            playback.value = if (item.isVideo) TvPlayback(item.id) else null
            val prepared = try {
                preparer.prepare(item)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Média impossible à préparer", error)
                playback.value = null
                eventChannel.trySend(CastEvent.Failed)
                return
            }
            val url = server.publish(prepared.media)
            val metadata = Didl.metadata(
                title = item.displayName,
                url = url,
                mimeType = prepared.media.mimeType,
                video = item.isVideo,
                sizeBytes = prepared.media.length,
                durationMs = item.durationMs.takeIf { item.isVideo },
                width = prepared.width ?: item.displayWidth,
                height = prepared.height ?: item.displayHeight,
            )
            try {
                call { setUri(url, metadata) }
                loadedAt = SystemClock.elapsedRealtime()
                play()
            } catch (refused: UpnpException) {
                Log.w(TAG, "Média refusé par la TV", refused)
                refuse(item.isVideo)
                return
            } catch (error: IOException) {
                // TV injoignable : la surveillance arrête la diffusion après quelques échecs.
                Log.w(TAG, "Envoi à la TV impossible", error)
                return
            }
            shown = true
            info.update { it.copy(status = TvStatus.READY) }
        }

        /** SetAVTransportURI, après un Stop si la TV refuse de changer de média en pleine lecture. */
        private fun setUri(url: String, metadata: String) {
            try {
                client.setUri(url, metadata)
            } catch (busy: UpnpException) {
                if (busy.code != TRANSITION_UNAVAILABLE && busy.code != TRANSPORT_LOCKED) throw busy
                runCatching { client.stop() }
                client.setUri(url, metadata)
            }
        }

        /** Play, répété le temps que la TV ait chargé le média (« transition indisponible »). */
        private suspend fun play() {
            repeat(PLAY_ATTEMPTS) { attempt ->
                try {
                    call { client.play() }
                    return
                } catch (busy: UpnpException) {
                    if (busy.code != TRANSITION_UNAVAILABLE || attempt == PLAY_ATTEMPTS - 1) throw busy
                    delay(PLAY_RETRY_MS)
                }
            }
        }

        private fun refuse(video: Boolean) {
            playback.value = null
            server.clear()
            shown = false
            eventChannel.trySend(CastEvent.Refused(video))
        }

        /** Interroge la TV : position de la vidéo, et disparition (plusieurs échecs de suite). */
        private suspend fun monitor() {
            var failures = 0
            while (true) {
                delay(if (playback.value != null) VIDEO_POLL_MS else PHOTO_POLL_MS)
                try {
                    val transport = call { client.transportInfo() }
                    failures = 0
                    val current = playback.value ?: continue
                    val position = if (transport.state == TransportState.PLAYING || transport.state == TransportState.PAUSED) {
                        runCatching { call { client.position() } }.getOrNull()
                    } else {
                        null
                    }
                    track(current, transport.state, transport.ok, position?.positionMs, position?.durationMs)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (answered: UpnpException) {
                    failures = 0
                } catch (error: IOException) {
                    if (++failures >= MAX_FAILURES) {
                        endTv(lost = true)
                        return
                    }
                }
            }
        }

        private suspend fun track(current: TvPlayback, state: TransportState, ok: Boolean, positionMs: Long?, durationMs: Long?) {
            val now = SystemClock.elapsedRealtime()
            val idle = state == TransportState.STOPPED || state == TransportState.NO_MEDIA
            if (current.loading && (!ok || (idle && now - loadedAt > REFUSE_AFTER_MS))) {
                // Média accepté puis jamais lu : format que la TV ne sait pas décoder.
                refuse(video = true)
                return
            }
            val started = current.loading && (state == TransportState.PLAYING || state == TransportState.PAUSED)
            val next = current.copy(
                loading = current.loading && !started,
                playing = state == TransportState.PLAYING,
                stopped = !current.loading && idle,
                positionMs = positionMs ?: current.positionMs,
                durationMs = durationMs ?: current.durationMs,
                measuredAt = now,
            )
            playback.update { if (it?.mediaId == current.mediaId) next else it }
            if (started) applyDesire(current.mediaId)
        }

        /** Position et pause demandées avant que la TV ne joue la vidéo. */
        private suspend fun applyDesire(mediaId: Long) {
            val wanted = desire.takeIf { it.mediaId == mediaId } ?: return
            desire = wanted.copy(seekMs = null)
            wanted.seekMs?.takeIf { it >= MIN_RESUME_MS }?.let { position -> runCatching { call { client.seek(position) } } }
            if (!wanted.playing) runCatching { call { client.pause() } }
        }

        fun setPlaying(mediaId: Long, playing: Boolean) {
            desire = desire.forMedia(mediaId).copy(playing = playing)
            val current = playback.value?.takeIf { it.mediaId == mediaId } ?: return
            if (current.loading) return
            playback.update {
                if (it?.mediaId == mediaId) it.copy(playing = playing, stopped = false, positionMs = it.estimatedPositionMs(), measuredAt = SystemClock.elapsedRealtime()) else it
            }
            sessionScope.launch { runCatching { call { if (playing) client.play() else client.pause() } } }
        }

        fun seek(mediaId: Long, positionMs: Long) {
            val current = playback.value?.takeIf { it.mediaId == mediaId }
            if (current == null || current.loading) {
                desire = desire.forMedia(mediaId).copy(seekMs = positionMs)
                return
            }
            playback.update { if (it?.mediaId == mediaId) it.copy(positionMs = positionMs, measuredAt = SystemClock.elapsedRealtime()) else it }
            sessionScope.launch { runCatching { call { client.seek(positionMs) } } }
        }

        fun requestVolume(volume: Int) {
            info.update { it.copy(volume = volume) }
            volumeRequests.value = volume
        }

        /** Fin : TV arrêtée ([sendStop]), serveur fermé, transferts coupés. */
        fun close(sendStop: Boolean) {
            job.cancel()
            scope.launch {
                withContext(io + NonCancellable) {
                    if (sendStop && shown) runCatching { withTimeoutOrNull(STOP_TIMEOUT_MS) { client.stop() } }
                    server.stop()
                }
            }
        }
    }

    private companion object {
        const val TAG = "CastManager"
        const val GRACE_MS = 3_000L
        const val IDLE_MS = 5 * 60_000L
        const val VIDEO_POLL_MS = 1_000L
        const val PHOTO_POLL_MS = 3_000L
        const val MAX_FAILURES = 3
        const val REFUSE_AFTER_MS = 10_000L
        const val PLAY_ATTEMPTS = 4
        const val PLAY_RETRY_MS = 800L
        const val VOLUME_DEBOUNCE_MS = 150L
        const val STOP_TIMEOUT_MS = 3_000L
        /** Une vidéo reprise sur la TV depuis la position du téléphone, sauf tout au début. */
        const val MIN_RESUME_MS = 1_000L
        const val TRANSITION_UNAVAILABLE = 701
        const val TRANSPORT_LOCKED = 705
    }
}

/** Position estimée maintenant, la TV ne la donnant qu'à chaque interrogation. */
fun TvPlayback.estimatedPositionMs(now: Long = SystemClock.elapsedRealtime()): Long {
    val elapsed = if (playing && !loading && !stopped && measuredAt > 0) now - measuredAt else 0L
    val position = positionMs + elapsed.coerceAtLeast(0)
    return if (durationMs > 0) position.coerceAtMost(durationMs) else position
}
