package app.focale.ui.viewer

import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.focale.core.model.MediaItem
import app.focale.data.PhotoTextRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Contenu de la feuille « Texte » de la visionneuse. */
@Immutable
sealed interface PhotoTextState {
    data object Reading : PhotoTextState
    data class Found(val text: String) : PhotoTextState
    data object Empty : PhotoTextState
    data object Failed : PhotoTextState
}

/**
 * Texte de la photo affichée : celui de l'analyse de fond, ou lu à la demande si la
 * photo n'a pas encore été analysée (le résultat est alors enregistré).
 */
@HiltViewModel
class PhotoTextViewModel @Inject constructor(
    private val repository: PhotoTextRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<PhotoTextState?>(null)

    /** Feuille affichée ; null quand elle est fermée. */
    val state: StateFlow<PhotoTextState?> = _state.asStateFlow()

    private var reading: Job? = null

    fun open(item: MediaItem) {
        reading?.cancel()
        _state.value = PhotoTextState.Reading
        reading = viewModelScope.launch {
            val text = try {
                repository.textOf(item)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Lecture du texte impossible pour ${item.id}", error)
                null
            }
            _state.value = when {
                text == null -> PhotoTextState.Failed
                text.isBlank() -> PhotoTextState.Empty
                else -> PhotoTextState.Found(text)
            }
        }
    }

    fun dismiss() {
        reading?.cancel()
        reading = null
        _state.value = null
    }

    override fun onCleared() {
        repository.release()
    }

    private companion object {
        const val TAG = "PhotoTextViewModel"
    }
}
