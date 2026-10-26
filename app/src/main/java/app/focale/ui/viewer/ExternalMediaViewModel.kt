package app.focale.ui.viewer

import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.focale.core.model.MediaItem
import app.focale.core.settings.AppSettings
import app.focale.core.settings.SettingsRepository
import app.focale.data.ExternalMedia
import app.focale.data.ExternalMediaRepository
import app.focale.data.MediaSharer
import app.focale.panorama.Panorama
import app.focale.panorama.PanoramaRepository
import app.focale.ui.navigation.ExternalMediaRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Ouverture d'un média reçu d'une autre application. */
sealed interface ExternalMediaState {
    /** Recherche dans l'index, synchronisation comprise pour un média qui vient d'arriver. */
    data object Resolving : ExternalMediaState

    /** Média de l'index : la visionneuse de la galerie prend le relais. */
    data class Indexed(val item: MediaItem) : ExternalMediaState

    /** Fichier hors index, affiché seul. */
    data class Standalone(val media: ExternalMedia) : ExternalMediaState
}

@HiltViewModel
class ExternalMediaViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: ExternalMediaRepository,
    private val sharer: MediaSharer,
    private val panoramas: PanoramaRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<ExternalMediaRoute>()
    val uri: Uri = route.uri.toUri()
    val closeOnBack: Boolean = route.closeOnBack
    val settings: StateFlow<AppSettings> = settingsRepository.current

    private val _state = MutableStateFlow<ExternalMediaState>(ExternalMediaState.Resolving)
    val state: StateFlow<ExternalMediaState> = _state.asStateFlow()

    private val shareIntents = Channel<Intent>(Channel.BUFFERED)
    val shares: Flow<Intent> = shareIntents.receiveAsFlow()

    private val _panorama = MutableStateFlow<Panorama?>(null)

    /** Vue 360° ou panoramique proposée pour un fichier hors index (en-tête lu à l'ouverture). */
    val panorama: StateFlow<Panorama?> = _panorama.asStateFlow()

    init {
        viewModelScope.launch {
            // Reconnu hors index avant une recréation : inutile de réattendre une synchronisation.
            val item = if (savedStateHandle.get<Boolean>(KEY_STANDALONE) == true) null else repository.find(uri)
            if (item != null) {
                _state.value = ExternalMediaState.Indexed(item)
            } else {
                savedStateHandle[KEY_STANDALONE] = true
                val media = repository.describe(uri, route.mimeType)
                _state.value = ExternalMediaState.Standalone(media)
                _panorama.value = panoramas.find(media)
            }
        }
    }

    /** Partage du fichier affiché, sans ses métadonnées si l'option est active. */
    fun share() {
        val media = (_state.value as? ExternalMediaState.Standalone)?.media ?: return
        viewModelScope.launch {
            // Fournisseur d'une autre application : il peut refuser ou disparaître entre-temps.
            runCatching { sharer.shareIntent(listOf(media.asMediaItem())) }.getOrNull()?.let { shareIntents.send(it) }
        }
    }

    private companion object {
        const val KEY_STANDALONE = "standalone"
    }
}
