package app.focale.ui.videoeditor

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.toRoute
import app.focale.core.image.SeedColorExtractor
import app.focale.core.model.MediaItem
import app.focale.data.MediaRepository
import app.focale.data.OperationResult
import app.focale.ui.navigation.VideoEditorRoute
import app.focale.video.TrimRange
import app.focale.video.TrimRequest
import app.focale.video.VideoFrameExtractor
import app.focale.video.VideoTrimmer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.media3.common.MediaItem as ExoMediaItem

/** Poignée de la frise saisie par l'utilisateur ([PLAYHEAD] : déplacement de la lecture). */
enum class TrimHandle { START, END, PLAYHEAD }

/** Résultat ponctuel, consommé par l'écran. */
sealed interface VideoEditorEvent {
    data object Saved : VideoEditorEvent
    data object Failed : VideoEditorEvent

    /** Vidéo introuvable ou illisible : l'éditeur se referme. */
    data object Unavailable : VideoEditorEvent
}

/**
 * Montage simple d'une vidéo : aperçu ExoPlayer limité à la plage choisie, frise de vignettes,
 * son coupé, puis export d'une copie ([VideoTrimmer]). L'original n'est jamais modifié.
 */
@OptIn(UnstableApi::class)
@HiltViewModel
class VideoEditorViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext context: Context,
    private val mediaRepository: MediaRepository,
    private val trimmer: VideoTrimmer,
    private val frameExtractor: VideoFrameExtractor,
    private val seedExtractor: SeedColorExtractor,
) : ViewModel() {

    private val mediaId = savedStateHandle.toRoute<VideoEditorRoute>().mediaId

    /** Lecteur de l'aperçu, propre au ViewModel : il survit aux changements de configuration. */
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setUsePlatformDiagnostics(false)
        .build()

    private val _item = MutableStateFlow<MediaItem?>(null)
    val item: StateFlow<MediaItem?> = _item.asStateFlow()

    private val _range = MutableStateFlow<TrimRange?>(null)
    val range: StateFlow<TrimRange?> = _range.asStateFlow()

    private val _muted = MutableStateFlow(savedStateHandle[KEY_MUTED] ?: false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    private val _hasAudio = MutableStateFlow(false)
    val hasAudio: StateFlow<Boolean> = _hasAudio.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing.asStateFlow()

    private val _frames = MutableStateFlow<List<ImageBitmap?>>(List(FRAME_COUNT) { null })
    val frames: StateFlow<List<ImageBitmap?>> = _frames.asStateFlow()

    /** Avancement de l'export en cours (de 0 à 1), null hors export. */
    private val _exportProgress = MutableStateFlow<Float?>(null)
    val exportProgress: StateFlow<Float?> = _exportProgress.asStateFlow()

    private val _seed = MutableStateFlow<Color?>(null)
    val seed: StateFlow<Color?> = _seed.asStateFlow()

    private val events = Channel<VideoEditorEvent>(Channel.BUFFERED)
    val eventFlow: Flow<VideoEditorEvent> = events.receiveAsFlow()

    private var exportJob: Job? = null
    private var ticker: Job? = null
    private var framesRequested = false
    private var trimming: TrimHandle? = null
    private var resumeAfterTrim = false

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playing.value = isPlaying
            if (isPlaying) {
                startTicker()
            } else {
                ticker?.cancel()
                _position.value = player.currentPosition
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> onDurationKnown(player.duration)
                Player.STATE_ENDED -> rewind()
                Player.STATE_IDLE, Player.STATE_BUFFERING -> Unit
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            // Pistes vidées par l'arrêt du lecteur pendant l'export : l'état précédent reste valable.
            if (!tracks.isEmpty) _hasAudio.value = tracks.containsType(C.TRACK_TYPE_AUDIO)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "Lecture impossible", error)
            events.trySend(VideoEditorEvent.Unavailable)
        }
    }

    init {
        player.addListener(listener)
        player.volume = if (_muted.value) 0f else 1f
        viewModelScope.launch {
            val media = mediaRepository.get(mediaId)
            if (media == null || !media.isVideo) {
                events.send(VideoEditorEvent.Unavailable)
                return@launch
            }
            _item.value = media
            player.setMediaItem(ExoMediaItem.fromUri(media.uri))
            player.prepare()
            onDurationKnown(media.durationMs)
            _seed.value = seedExtractor.seedFor(media)
        }
    }

    val isExporting: Boolean get() = exportJob?.isActive == true

    /** Copie que produirait l'enregistrement (le son n'est retiré que s'il existe). */
    fun request(): TrimRequest? = _range.value?.let { TrimRequest(it, removeAudio = _muted.value && _hasAudio.value) }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
            return
        }
        val range = _range.value ?: return
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        val start = range.playbackStart(player.currentPosition)
        if (start != player.currentPosition) player.seekTo(start)
        player.play()
    }

    fun pause() {
        player.pause()
    }

    fun setMuted(muted: Boolean) {
        _muted.value = muted
        savedStateHandle[KEY_MUTED] = muted
        player.volume = if (muted) 0f else 1f
    }

    /** Début d'un geste sur la frise : la lecture s'interrompt, le lecteur passe en mode défilement. */
    fun beginTrim(handle: TrimHandle) {
        if (trimming != null || isExporting) return
        trimming = handle
        resumeAfterTrim = handle == TrimHandle.PLAYHEAD && player.isPlaying
        player.pause()
        player.setScrubbingModeEnabled(true)
    }

    /**
     * Amène [handle] à [timeMs] et montre l'image correspondante. Renvoie la position retenue :
     * bornée par la vidéo, la durée minimale de la plage et, pour la lecture, la plage elle-même.
     */
    fun trim(handle: TrimHandle, timeMs: Long): Long {
        val range = _range.value ?: return timeMs
        val target = when (handle) {
            TrimHandle.START -> range.withStart(timeMs).also { _range.value = it }.startMs
            TrimHandle.END -> range.withEnd(timeMs).also { _range.value = it }.endMs
            TrimHandle.PLAYHEAD -> range.clamp(timeMs)
        }
        if (target != _position.value) {
            player.seekTo(target)
            _position.value = target
        }
        return target
    }

    fun endTrim() {
        trimming ?: return
        trimming = null
        player.setScrubbingModeEnabled(false)
        _range.value?.let { range ->
            savedStateHandle[KEY_START] = range.startMs
            savedStateHandle[KEY_END] = range.endMs
        }
        if (resumeAfterTrim) player.play()
    }

    fun save() {
        val media = _item.value ?: return
        val request = request()?.takeIf { it.hasChanges } ?: return
        if (isExporting) return
        player.pause()
        // Les décodeurs de l'aperçu sont libérés : un export qui réencode peut en avoir besoin.
        player.stop()
        exportJob = viewModelScope.launch {
            _exportProgress.value = 0f
            var saved = false
            try {
                val result = trimmer.saveCopy(media, request) { progress ->
                    if (isActive) _exportProgress.value = progress
                }
                saved = result is OperationResult.Success
                when (result) {
                    is OperationResult.Success -> events.send(VideoEditorEvent.Saved)
                    OperationResult.Cancelled -> Unit
                    else -> events.send(VideoEditorEvent.Failed)
                }
            } finally {
                _exportProgress.value = null
                if (!saved) restorePreview()
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
    }

    private fun onDurationKnown(durationMs: Long) {
        if (durationMs == C.TIME_UNSET || durationMs <= 0) return
        val current = _range.value
        _range.value = when {
            current == null -> TrimRange.of(
                durationMs,
                savedStateHandle.get<Long>(KEY_START) ?: 0L,
                savedStateHandle.get<Long>(KEY_END) ?: durationMs,
            )
            current.durationMs != durationMs -> current.withDuration(durationMs)
            else -> current
        }
        if (!framesRequested) loadFrames(durationMs)
    }

    private fun loadFrames(durationMs: Long) {
        val media = _item.value ?: return
        framesRequested = true
        viewModelScope.launch {
            frameExtractor.frames(media.uri, durationMs, FRAME_COUNT, FRAME_SIDE)
                .catch { Log.w(TAG, "Vignettes indisponibles", it) }
                .collect { (index, bitmap) ->
                    _frames.update { frames -> frames.toMutableList().also { it[index] = bitmap.asImageBitmap() } }
                }
        }
    }

    /** Suit la lecture et l'arrête à la fin de la plage, prête à repartir de son début. */
    private fun startTicker() {
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                val position = player.currentPosition
                _position.value = position
                val range = _range.value
                if (range != null && position >= range.endMs) {
                    rewind()
                    break
                }
                delay(TICK_MS)
            }
        }
    }

    private fun rewind() {
        player.pause()
        val start = _range.value?.startMs ?: 0L
        player.seekTo(start)
        _position.value = start
    }

    private fun restorePreview() {
        player.prepare()
        _range.value?.let { range ->
            player.seekTo(range.startMs)
            _position.value = range.startMs
        }
    }

    override fun onCleared() {
        player.removeListener(listener)
        player.release()
    }

    private companion object {
        const val TAG = "VideoEditor"
        const val KEY_START = "trim_start"
        const val KEY_END = "trim_end"
        const val KEY_MUTED = "trim_muted"
        const val FRAME_COUNT = 10
        const val FRAME_SIDE = 320
        const val TICK_MS = 32L
    }
}
