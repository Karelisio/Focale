package app.focale.cast

import android.os.SystemClock
import android.util.Log
import app.focale.cast.dlna.Didl
import app.focale.cast.dlna.DlnaRenderer
import app.focale.cast.dlna.MediaServer
import app.focale.cast.dlna.RendererClient
import app.focale.cast.dlna.TransportState
import app.focale.cast.dlna.UpnpException
import app.focale.core.model.MediaItem
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Étape et volume de la TV, pour la feuille « Diffuser ». */
internal data class TvInfo(val status: TvStatus, val volume: Int? = null)

/**
 * Une diffusion vers la TV [renderer] ([CastManager]) : le serveur local ([server]) lui sert le
 * média en cours de [content], commandé par AVTransport ([client]), et la TV est interrogée pour
 * suivre la vidéo et remarquer sa disparition.
 *
 * Une vidéo que la TV ne lit pas (refusée, acceptée puis jamais lue, impossible à préparer ou à
 * envoyer) revient au téléphone ([phoneVideos]) : sa page la lit elle-même, à la même position,
 * et la diffusion continue pour les médias suivants ; refusée, elle n'est plus proposée à la TV
 * de toute la diffusion. Une TV qui ne répond plus met fin à la diffusion ([CastEvent.Lost]) : la
 * vidéo qu'elle jouait reprend elle aussi sur le téléphone.
 *
 * @param prepare média prêt à servir ([CastMediaPreparer.prepare]).
 * @param events messages à afficher.
 * @param onEnd fin de la diffusion demandée par la session elle-même ([lost] : TV injoignable) ;
 *   le gestionnaire la referme alors ([close]).
 * @param appScope survit à la session, le temps d'arrêter la TV et le serveur.
 * @param clock horloge des délais (`elapsedRealtime`), virtuelle dans les tests.
 */
internal class TvSession(
    val renderer: DlnaRenderer,
    private val client: RendererClient,
    private val server: MediaServer,
    private val content: Flow<MediaItem?>,
    private val prepare: suspend (MediaItem) -> PreparedMedia,
    private val events: (CastEvent) -> Unit,
    private val onEnd: (session: TvSession, lost: Boolean) -> Unit,
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val job = SupervisorJob()
    private val sessionScope = CoroutineScope(job + io)
    private val calls = Mutex()
    private val volumeRequests = MutableStateFlow<Int?>(null)

    val info = MutableStateFlow(TvInfo(TvStatus.CONNECTING))

    /** Vidéo envoyée à la TV, null pour une photo ou une vidéo revenue au téléphone. */
    val playback = MutableStateFlow<TvPlayback?>(null)

    private val _phoneVideos = MutableStateFlow<Set<Long>>(emptySet())

    /** Vidéos lues par le téléphone pendant cette diffusion (identifiants de médias). */
    val phoneVideos: StateFlow<Set<Long>> = _phoneVideos.asStateFlow()

    /** Vidéos revenues au téléphone sur un échec passager (envoi) : retentées si on y revient. */
    private val retry = HashSet<Long>()

    @Volatile private var desire = Desire(-1)
    @Volatile private var shown = false
    private var loadedAt = 0L

    /** Dernière demande de volume ([clock]) : la lecture qui suit ne la contredit pas. */
    @Volatile private var volumeRequestedAt = Long.MIN_VALUE / 2

    /** Souhait de l'utilisateur pour la vidéo [mediaId], appliqué quand la TV la joue. */
    private data class Desire(val mediaId: Long, val playing: Boolean = true, val seekMs: Long? = null) {
        fun forMedia(id: Long): Desire = if (id == mediaId) this else Desire(id)
    }

    fun start() {
        sessionScope.launch {
            try {
                server.start()
            } catch (error: IOException) {
                Log.w(TAG, "Serveur local impossible", error)
                events(CastEvent.Failed())
                onEnd(this@TvSession, false)
                return@launch
            }
            launch { content.collectLatest(::show) }
            launch { monitor() }
            if (renderer.hasVolume) launch { followVolume() }
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
            onEnd(this, false)
            return
        }
        // Échec passager d'une autre vidéo : elle retentera la TV si on y revient.
        if (retry.isNotEmpty()) {
            val again = retry.filterTo(HashSet()) { it != item.id }
            retry -= again
            _phoneVideos.update { it - again }
        }
        if (item.isVideo && item.id in _phoneVideos.value) {
            // Déjà rendue au téléphone : la TV ne montre plus le média précédent pendant qu'elle se lit.
            playback.value = null
            clearTv()
            return
        }
        playback.value = if (item.isVideo) TvPlayback(item.id) else null
        val prepared = try {
            prepare(item)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "Média impossible à préparer", error)
            if (item.isVideo) handBack(item.id, retryLater = false) else playback.value = null
            events(CastEvent.Failed(videoOnPhone = item.isVideo))
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
            loadedAt = clock()
            play()
        } catch (refused: UpnpException) {
            Log.w(TAG, "Média refusé par la TV", refused)
            refuse(item.id, item.isVideo)
            return
        } catch (error: IOException) {
            // TV injoignable : la surveillance arrête la diffusion après quelques échecs ; une vidéo,
            // elle, n'attend pas sur le téléphone.
            Log.w(TAG, "Envoi à la TV impossible", error)
            if (item.isVideo) {
                handBack(item.id, retryLater = true)
                events(CastEvent.Failed(videoOnPhone = true))
            }
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

    /** La TV ne montre pas le média [mediaId] : une vidéo revient au téléphone pour toute la diffusion. */
    private suspend fun refuse(mediaId: Long, video: Boolean) {
        if (video) handBack(mediaId, retryLater = false)
        clearTv()
        // La TV a répondu : elle attend le média suivant.
        info.update { it.copy(status = TvStatus.READY) }
        events(CastEvent.Refused(video))
    }

    /** La vidéo [mediaId] se lit sur le téléphone ; [retryLater] : échec passager, retenté plus tard. */
    private fun handBack(mediaId: Long, retryLater: Boolean) {
        playback.update { if (it?.mediaId == mediaId) null else it }
        if (retryLater) retry += mediaId else retry -= mediaId
        _phoneVideos.update { it + mediaId }
    }

    /** Plus rien de servi ni d'affiché par la TV, qui attend le média suivant. */
    private suspend fun clearTv() {
        server.clear()
        if (shown) {
            shown = false
            runCatching { call { client.stop() } }
        }
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
                    // La vidéo en cours (envoyée ou jouée) reprend sur le téléphone, la diffusion finie.
                    events(CastEvent.Lost(renderer.name, videoOnPhone = playback.value != null))
                    onEnd(this, true)
                    return
                }
            }
        }
    }

    private suspend fun track(current: TvPlayback, state: TransportState, ok: Boolean, positionMs: Long?, durationMs: Long?) {
        val now = clock()
        val idle = state == TransportState.STOPPED || state == TransportState.NO_MEDIA
        if (current.loading && (!ok || (idle && now - loadedAt > REFUSE_AFTER_MS))) {
            // Média accepté puis jamais lu : format que la TV ne sait pas décoder. Lecture changée
            // entre-temps (autre média, commande) : l'arrêt couperait le média suivant, l'interrogation
            // suivante tranchera.
            if (playback.compareAndSet(current, null)) refuse(current.mediaId, video = true)
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
            if (it?.mediaId == mediaId) it.copy(playing = playing, stopped = false, positionMs = it.estimatedPositionMs(clock()), measuredAt = clock()) else it
        }
        sessionScope.launch { runCatching { call { if (playing) client.play() else client.pause() } } }
    }

    fun seek(mediaId: Long, positionMs: Long) {
        val current = playback.value?.takeIf { it.mediaId == mediaId }
        if (current == null || current.loading) {
            desire = desire.forMedia(mediaId).copy(seekMs = positionMs)
            return
        }
        playback.update { if (it?.mediaId == mediaId) it.copy(positionMs = positionMs, measuredAt = clock()) else it }
        sessionScope.launch { runCatching { call { client.seek(positionMs) } } }
    }

    /**
     * Volume lu sur la TV au début, puis toutes les [VOLUME_REFRESH_MS] : changé à la télécommande,
     * il reste juste pour la feuille et pour les touches du téléphone, qui partent de lui. Une
     * lecture qui suit de près une demande ([VOLUME_SETTLE_MS]) ne la contredit pas.
     */
    private suspend fun followVolume() {
        while (true) {
            val volume = runCatching { call { client.volume() } }.getOrNull()
            if (volume != null && clock() - volumeRequestedAt >= VOLUME_SETTLE_MS) info.update { it.copy(volume = volume) }
            delay(VOLUME_REFRESH_MS)
        }
    }

    fun requestVolume(volume: Int) {
        val wanted = volume.coerceIn(0, 100)
        volumeRequestedAt = clock()
        info.update { it.copy(volume = wanted) }
        volumeRequests.value = wanted
    }

    /** Touche de volume : [delta] ajouté au volume connu de la TV ; sans volume connu, rien. */
    fun adjustVolume(delta: Int) {
        val current = info.value.volume ?: return
        requestVolume(current + delta)
    }

    /** Fin : TV arrêtée ([sendStop]), serveur fermé, transferts coupés. */
    fun close(sendStop: Boolean) {
        job.cancel()
        appScope.launch {
            withContext(io + NonCancellable) {
                if (sendStop && shown) runCatching { withTimeoutOrNull(STOP_TIMEOUT_MS) { client.stop() } }
                server.stop()
            }
        }
    }

    internal companion object {
        private const val TAG = "TvSession"
        const val GRACE_MS = 3_000L
        const val IDLE_MS = 5 * 60_000L
        const val VIDEO_POLL_MS = 1_000L
        const val PHOTO_POLL_MS = 3_000L
        const val MAX_FAILURES = 3
        const val REFUSE_AFTER_MS = 10_000L
        const val PLAY_ATTEMPTS = 4
        const val PLAY_RETRY_MS = 800L
        const val VOLUME_DEBOUNCE_MS = 150L
        const val VOLUME_REFRESH_MS = 10_000L
        const val VOLUME_SETTLE_MS = 3_000L
        const val STOP_TIMEOUT_MS = 3_000L

        /** Une vidéo reprise sur la TV depuis la position du téléphone, sauf tout au début. */
        const val MIN_RESUME_MS = 1_000L
        const val TRANSITION_UNAVAILABLE = 701
        const val TRANSPORT_LOCKED = 705
    }
}
