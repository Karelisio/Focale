package app.focale.cast

import android.content.Context
import android.media.MediaMetadata
import android.media.VolumeProvider
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Volume de la TV réglé par le téléphone, le temps d'une diffusion : session média du framework en
 * lecture distante ([MediaSession.setPlaybackToRemote]), dont le fournisseur de volume reçoit ce
 * que le système lui adresse : touches de volume (celles de l'activité, qui lui confie la session ;
 * hors de Focale, quand le système choisit la session active) et curseur du volume du système, qui
 * montre alors celui de la TV. Pas à pas de [STEP] sur l'échelle de 0 à 100 de RenderingControl.
 *
 * @param adjust écart à appliquer au volume de la TV ([TvSession.adjustVolume]).
 * @param set volume de 0 à 100 demandé par le curseur du système ([TvSession.requestVolume]).
 */
internal class TvRemoteVolume(context: Context, name: String, volume: Int, adjust: (Int) -> Unit, set: (Int) -> Unit) {

    private val session = MediaSession(context, TAG)

    /** Le système fait passer les commandes par le fil principal ; elles n'agissent que sur la TV. */
    @VisibleForTesting
    internal val provider = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, MAX_STEPS, steps(volume)) {
        override fun onAdjustVolume(direction: Int) {
            // Couper le son d'une lecture distante n'existe pas : seuls plus et moins comptent.
            if (direction != 1 && direction != -1) return
            currentVolume = (currentVolume + direction.sign).coerceIn(0, MAX_STEPS)
            adjust(direction.sign * STEP)
        }

        override fun onSetVolumeTo(volume: Int) {
            currentVolume = volume.coerceIn(0, MAX_STEPS)
            set(currentVolume * STEP)
        }
    }

    /** Session confiée à l'activité ([android.app.Activity.setMediaController]). */
    val controller: MediaController get() = session.controller

    init {
        // Sans rappel, la session ne transmet pas les commandes de volume à son fournisseur.
        session.setCallback(object : MediaSession.Callback() {}, Handler(Looper.getMainLooper()))
        session.setPlaybackToRemote(provider)
        session.setMetadata(MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, name).build())
        // Lecture en cours (le système ne confie les touches qu'à une session qui joue), sans aucune
        // action : les boutons multimédias d'un casque ne commandent rien ici.
        session.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_PLAYING, 0L, 1f).setActions(0L).build())
        session.isActive = true
    }

    /** Volume de la TV connu de la diffusion (lu sur la TV, ou demandé). */
    fun show(volume: Int) {
        val steps = steps(volume)
        if (provider.currentVolume != steps) provider.currentVolume = steps
    }

    fun release() {
        session.isActive = false
        session.release()
    }

    companion object {
        private const val TAG = "FocaleTvVolume"

        /** Volume de la TV changé par chaque touche. */
        const val STEP = 2
        const val MAX_STEPS = 100 / STEP

        private fun steps(volume: Int): Int = (volume.coerceIn(0, 100) / STEP.toFloat()).roundToInt()
    }
}

/**
 * Touches de volume d'une diffusion vers une TV ([CastManager]) : [follow] tient une session
 * [TvRemoteVolume] tant que dure la diffusion, dès que le volume de la TV est connu (sans
 * RenderingControl, jamais) ; [controller] la donne à l'activité. Hors diffusion, rien : les
 * touches règlent le téléphone.
 */
internal class TvVolumeKeys(private val context: Context, private val main: CoroutineDispatcher = Dispatchers.Main.immediate) {

    private val _controller = MutableStateFlow<MediaController?>(null)

    /** Session de la diffusion en cours, à laquelle l'activité confie les touches ; null hors diffusion. */
    val controller: StateFlow<MediaController?> = _controller.asStateFlow()

    @Volatile private var remote: TvRemoteVolume? = null

    /** Fournisseur de volume de la session en cours (ce que le système appelle à chaque touche). */
    @get:VisibleForTesting
    internal val provider: VolumeProvider? get() = remote?.provider

    /** Suit [tv] jusqu'à l'annulation de l'appel, sur le fil principal ; la session est alors libérée. */
    suspend fun follow(tv: TvSession): Unit = withContext(main) {
        var current: TvRemoteVolume? = null
        try {
            tv.info.map { it.volume }.distinctUntilChanged().collect { volume ->
                if (volume == null) return@collect
                val session = current ?: TvRemoteVolume(context, tv.renderer.name, volume, tv::adjustVolume, tv::requestVolume).also {
                    current = it
                    remote = it
                    _controller.value = it.controller
                }
                session.show(volume)
            }
        } finally {
            current?.let { session ->
                if (remote === session) remote = null
                if (_controller.value === session.controller) _controller.value = null
                session.release()
            }
        }
    }
}
