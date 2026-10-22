package app.focale.cast

import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Lecteur Media3 qui reflète la vidéo [mediaId] jouée par la TV : les commandes de la visionneuse
 * (lecture, pause, curseur, sauts de 10 s) fonctionnent telles quelles et passent par AVTransport.
 * Aucune image : la page montre l'affiche de la vidéo. Ni vitesse ni son (le volume de la TV se
 * règle dans la feuille « Diffuser »).
 *
 * @param playback lecture sur la TV ([CastManager.tvPlayback]) ; la position, donnée chaque
 *   seconde par la TV, est extrapolée entre deux mesures.
 */
@OptIn(UnstableApi::class)
internal class RemotePlayer(
    private val mediaId: Long,
    private val durationHintMs: Long,
    private val playback: StateFlow<TvPlayback?>,
    private val onPlaying: (Boolean) -> Unit,
    private val onSeek: (Long) -> Unit,
) : SimpleBasePlayer(Looper.getMainLooper()) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Lecture demandée par la page, montrée tant que la TV charge la vidéo. */
    private var requestedPlaying = true

    init {
        scope.launch { playback.collect { invalidateState() } }
    }

    override fun getState(): State {
        val current = playback.value?.takeIf { it.mediaId == mediaId }
        val durationMs = current?.durationMs?.takeIf { it > 0 } ?: durationHintMs
        val positionMs = current?.estimatedPositionMs() ?: 0L
        val playbackState = when {
            current == null || current.loading -> Player.STATE_BUFFERING
            current.stopped -> Player.STATE_ENDED
            else -> Player.STATE_READY
        }
        return State.Builder()
            .setAvailableCommands(
                Player.Commands.Builder()
                    .addAll(
                        Player.COMMAND_PLAY_PAUSE,
                        Player.COMMAND_PREPARE,
                        Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                        Player.COMMAND_GET_TIMELINE,
                        Player.COMMAND_RELEASE,
                    )
                    .build(),
            )
            .setPlaylist(
                listOf(
                    MediaItemData.Builder("tv-$mediaId")
                        .setMediaItem(MediaItem.Builder().setMediaId("tv-$mediaId").build())
                        .setDurationUs(if (durationMs > 0) durationMs * 1_000 else C.TIME_UNSET)
                        .setIsSeekable(true)
                        .build(),
                ),
            )
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(playbackState)
            .setPlayWhenReady(
                if (current == null || current.loading) requestedPlaying else current.playing,
                Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST,
            )
            .setContentPositionMs(
                if (current != null && current.playing && !current.loading && !current.stopped) {
                    PositionSupplier.getExtrapolating(positionMs, 1f)
                } else {
                    PositionSupplier.getConstant(positionMs)
                },
            )
            .build()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        requestedPlaying = playWhenReady
        onPlaying(playWhenReady)
        return Futures.immediateVoidFuture()
    }

    override fun handlePrepare(): ListenableFuture<*> = Futures.immediateVoidFuture()

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        onSeek(positionMs.coerceAtLeast(0))
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        scope.cancel()
        return Futures.immediateVoidFuture()
    }
}
