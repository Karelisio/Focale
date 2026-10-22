package app.focale.ui.viewer

import android.os.Looper
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import androidx.media3.common.util.UnstableApi
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

/**
 * Lecteur factice des tests de la page vidéo : une vidéo prête de [durationMs], sans décodage ;
 * la position ne change qu'aux sauts, la vitesse (et la hauteur du son) qu'aux réglages.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal class FakePlayer(var positionMs: Long, private val durationMs: Long) : SimpleBasePlayer(Looper.getMainLooper()) {
    /** Demandes de lecture (vrai) ou de pause (faux), dans l'ordre. */
    val playRequests = mutableListOf<Boolean>()

    /** Vrai une fois le lecteur libéré, quand sa page n'est plus la page courante. */
    var released = false
        private set

    private var parameters = PlaybackParameters.DEFAULT

    override fun getState(): State = State.Builder()
        .setAvailableCommands(
            Player.Commands.Builder()
                .addAll(
                    Player.COMMAND_PLAY_PAUSE,
                    Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_SET_SPEED_AND_PITCH,
                    Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_TIMELINE,
                    Player.COMMAND_RELEASE,
                )
                .build(),
        )
        .setPlaylist(listOf(MediaItemData.Builder("clip").setDurationUs(durationMs * 1_000).setIsSeekable(true).build()))
        .setCurrentMediaItemIndex(0)
        .setContentPositionMs(positionMs)
        .setPlaybackState(Player.STATE_READY)
        .setPlayWhenReady(playRequests.lastOrNull() ?: false, Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
        .setPlaybackParameters(parameters)
        .build()

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        this.positionMs = positionMs
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        playRequests += playWhenReady
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        parameters = playbackParameters
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        return Futures.immediateVoidFuture()
    }
}
