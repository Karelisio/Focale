package app.focale.ui.collage

import android.content.Intent
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.focale.collage.CollageLayouts
import app.focale.core.model.MediaItem
import app.focale.data.CollageMaker
import app.focale.data.CollageResult
import app.focale.data.MediaRepository
import app.focale.data.MediaSharer
import app.focale.ui.navigation.CollageRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Issue de l'écran, consommée par lui. */
sealed interface CollageEvent {
    /** Collage enregistré ; [item] null s'il n'est pas encore indexé. [share] : partage à lancer. */
    data class Saved(val item: MediaItem?, val share: Intent?) : CollageEvent
    data object Failed : CollageEvent

    /** Moins de deux photos encore là (supprimées entre-temps) : l'écran se ferme. */
    data object Unavailable : CollageEvent
}

@HiltViewModel
class CollageViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mediaRepository: MediaRepository,
    private val maker: CollageMaker,
    private val sharer: MediaSharer,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<CollageRoute>()

    /** La sélection comptait plus de photos qu'un collage n'en prend. */
    val truncated: Boolean get() = route.truncated

    /** Collage et historique, une fois les photos trouvées dans l'index. */
    private val _editor = MutableStateFlow<CollageEditor?>(null)
    val editor: StateFlow<CollageEditor?> = _editor.asStateFlow()

    private val _items = MutableStateFlow<Map<Long, MediaItem>>(emptyMap())
    val items: StateFlow<Map<Long, MediaItem>> = _items.asStateFlow()

    /** Aperçus des photos, arrivés un à un. */
    private val _previews = MutableStateFlow<Map<Long, ImageBitmap>>(emptyMap())
    val previews: StateFlow<Map<Long, ImageBitmap>> = _previews.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val events = Channel<CollageEvent>(Channel.BUFFERED)
    val eventFlow: Flow<CollageEvent> = events.receiveAsFlow()

    init {
        viewModelScope.launch {
            val found = mediaRepository.getByIds(route.mediaIds).associateBy { it.id }
            val photos = route.mediaIds.mapNotNull(found::get).filterNot { it.isVideo }.take(CollageLayouts.MAX_PHOTOS)
            if (photos.size < CollageLayouts.MIN_PHOTOS) return@launch events.send(CollageEvent.Unavailable)
            _items.value = photos.associateBy { it.id }
            _editor.value = CollageEditor(photos.map { it.id })
            loadPreviews(photos)
        }
    }

    /** Aperçus décodés deux à la fois, plus petits quand les cases le sont. */
    private fun loadPreviews(photos: List<MediaItem>) {
        val side = if (photos.size <= 4) PREVIEW_SIDE_LARGE else PREVIEW_SIDE_SMALL
        val permits = Semaphore(2)
        for (photo in photos) {
            viewModelScope.launch {
                val preview = permits.withPermit {
                    try {
                        maker.loadPreview(photo, side).asImageBitmap()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        // Photo illisible : sa case reste vide à l'écran, l'export échouera s'il la lit.
                        Log.w(TAG, "Aperçu impossible : ${photo.displayName}", error)
                        null
                    }
                } ?: return@launch
                _previews.update { it + (photo.id to preview) }
            }
        }
    }

    /** Enregistre le collage, puis prépare son partage si [share]. */
    fun save(share: Boolean) {
        val editor = _editor.value ?: return
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                when (val result = maker.save(editor.current, _items.value)) {
                    is CollageResult.Saved -> {
                        val intent = if (share) result.item?.let { sharer.shareIntent(listOf(it)) } else null
                        events.send(CollageEvent.Saved(result.item, intent))
                    }
                    is CollageResult.Failed -> events.send(CollageEvent.Failed)
                }
            } finally {
                _saving.value = false
            }
        }
    }

    private companion object {
        const val TAG = "CollageViewModel"
        const val PREVIEW_SIDE_LARGE = 1280
        const val PREVIEW_SIDE_SMALL = 960
    }
}
