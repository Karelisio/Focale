package app.focale.ui.slideshow

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.focale.core.model.MediaFilter
import app.focale.core.model.MediaItem
import app.focale.core.settings.SettingsRepository
import app.focale.data.MediaRepository
import app.focale.ui.navigation.RouteCodec
import app.focale.ui.navigation.SlideshowRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Diaporama : parcourt la requête de la grille d'origine, photos uniquement si elle est mixte. */
@HiltViewModel
class SlideshowViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    mediaRepository: MediaRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<SlideshowRoute>()

    private val query = RouteCodec.decode(route.query).let { query ->
        if (query.filter == MediaFilter.ALL) query.copy(filter = MediaFilter.PHOTOS) else query
    }

    private val startIndex = viewModelScope.async {
        route.startId?.let { mediaRepository.positionOf(query, it) } ?: 0
    }

    private val _initialIndex = MutableStateFlow<Int?>(null)
    val initialIndex: StateFlow<Int?> = _initialIndex.asStateFlow()

    val pages: Flow<PagingData<MediaItem>> = flow { emit(startIndex.await()) }
        .flatMapLatest { index -> mediaRepository.paged(query, initialIndex = index) }
        .cachedIn(viewModelScope)

    val intervalMs: StateFlow<Long> = settingsRepository.settings
        .map { it.slideshowIntervalSeconds * 1_000L }
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.current.value.slideshowIntervalSeconds * 1_000L)

    init {
        viewModelScope.launch { _initialIndex.value = startIndex.await() }
    }
}
