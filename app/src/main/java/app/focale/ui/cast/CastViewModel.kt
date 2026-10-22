package app.focale.ui.cast

import androidx.lifecycle.ViewModel
import androidx.media3.common.Player
import app.focale.cast.CastEvent
import app.focale.cast.CastManager
import app.focale.cast.CastState
import app.focale.cast.CastTarget
import app.focale.cast.RemotePlayer
import app.focale.cast.display.ExternalDisplay
import app.focale.cast.dlna.DlnaRenderer
import app.focale.core.model.MediaItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Diffusion vue par un écran (visionneuse, diaporama) : état, choix de l'appareil, média en cours. */
@HiltViewModel
class CastViewModel @Inject constructor(private val manager: CastManager) : ViewModel() {

    val state: StateFlow<CastState> = manager.state
    val events: Flow<CastEvent> = manager.events

    /** Vidéos que la TV ne lit pas, lues par le téléphone ([CastManager.phoneVideos]). */
    val phoneVideos: StateFlow<Set<Long>> = manager.phoneVideos

    /** Une TV reçoit la diffusion (lu sans abonnement, même en arrière-plan). */
    val tvActive: Boolean get() = manager.tvActive

    fun present(owner: Any, item: MediaItem?) = manager.present(owner, item)

    fun withdraw(owner: Any) = manager.withdraw(owner)

    fun search() = manager.search()

    fun searchIfStale() = manager.searchIfStale()

    fun selectScreen(display: ExternalDisplay) = manager.selectScreen(display)

    fun selectTv(renderer: DlnaRenderer) = manager.selectTv(renderer)

    fun stop(target: CastTarget) = manager.stop(target)

    fun setVolume(volume: Int) = manager.setTvVolume(volume)

    /** Lecteur de la vidéo [item] jouée par la TV, pour les commandes de la page vidéo. */
    fun remotePlayer(item: MediaItem): Player = RemotePlayer(
        mediaId = item.id,
        durationHintMs = item.durationMs,
        playback = manager.tvPlayback,
        onPlaying = { manager.tvSetPlaying(item.id, it) },
        onSeek = { manager.tvSeek(item.id, it) },
    )
}
