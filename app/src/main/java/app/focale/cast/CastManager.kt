package app.focale.cast

import android.content.Context
import android.os.SystemClock
import app.focale.BuildConfig
import app.focale.cast.display.ExternalDisplay
import app.focale.cast.display.ExternalDisplays
import app.focale.cast.display.PresentationDisplays
import app.focale.cast.dlna.DlnaDiscovery
import app.focale.cast.dlna.DlnaRenderer
import app.focale.cast.dlna.LocalNetworks
import app.focale.cast.dlna.MediaServer
import app.focale.cast.dlna.RendererClient
import app.focale.core.di.ApplicationScope
import app.focale.core.di.IoDispatcher
import app.focale.core.model.MediaItem
import app.focale.core.settings.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Diffusion de la visionneuse et du diaporama vers un écran externe ou une TV du réseau local.
 *
 * - **Écran externe** (toutes variantes) : dès qu'un écran de présentation est branché ou connecté,
 *   il devient la cible, sauf si l'utilisateur l'a arrêté (jusqu'à son départ) ou qu'une TV reçoit
 *   la diffusion. L'écran affiche lui-même le média en cours (`Presentation`, voir `CastPresentation`).
 * - **TV** (variante standard) : choisie dans la feuille ; un serveur HTTP local ([MediaServer]) lui
 *   sert le média en cours, commandé par AVTransport ([RendererClient]), une diffusion à la fois
 *   ([TvSession]). Les écrans appellent [present] avec leur média ; quitter la visionneuse arrête
 *   la TV au bout de [TvSession.GRACE_MS] (le temps d'ouvrir le diaporama, par exemple), puis la
 *   diffusion s'arrête après [TvSession.IDLE_MS] sans média. Une vidéo que la TV ne lit pas se lit
 *   sur le téléphone ([phoneVideos]). Un service au premier plan ([CastService]) garde la diffusion
 *   en vie en arrière-plan, avec sa notification.
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

    /**
     * Vidéos que la TV ne lit pas (refusées, ou qu'elle n'a pas pu recevoir) : la visionneuse les
     * lit sur le téléphone, la diffusion continuant pour les médias suivants.
     */
    val phoneVideos: StateFlow<Set<Long>> = session
        .flatMapLatest { it?.phoneVideos ?: flowOf(emptySet()) }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

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
        val tv = TvSession(
            renderer = renderer,
            client = RendererClient(renderer, link.httpClient()),
            server = MediaServer(link.address),
            content = content,
            prepare = preparer::prepare,
            events = { eventChannel.trySend(it) },
            onEnd = ::endTv,
            appScope = scope,
            io = io,
        )
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
    fun stopTv() {
        session.value?.let { endTv(it, lost = false) }
    }

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

    /**
     * Fin de la diffusion [tv], si c'est toujours la diffusion en cours ; [lost] : TV injoignable
     * (la session l'a annoncé), oubliée jusqu'à la prochaine recherche.
     */
    private fun endTv(tv: TvSession, lost: Boolean) {
        if (!session.compareAndSet(tv, null)) return
        discovery.pinned = null
        tv.close(sendStop = !lost)
        CastService.stop(context)
        if (lost) discovery.forget(tv.renderer.udn)
    }
}

/** Position estimée maintenant, la TV ne la donnant qu'à chaque interrogation. */
fun TvPlayback.estimatedPositionMs(now: Long = SystemClock.elapsedRealtime()): Long {
    val elapsed = if (playing && !loading && !stopped && measuredAt > 0) now - measuredAt else 0L
    val position = positionMs + elapsed.coerceAtLeast(0)
    return if (durationMs > 0) position.coerceAtMost(durationMs) else position
}
