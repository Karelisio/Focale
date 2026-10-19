package app.focale.ui.cleanup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.focale.core.model.MediaItem
import app.focale.data.CleanupRepository
import app.focale.data.DuplicateGroup
import app.focale.ui.actions.MediaActions
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class CleanupTab { DUPLICATES, BLURRY, SCREENSHOTS, LARGE_VIDEOS }

@HiltViewModel
class CleanupViewModel @Inject constructor(
    private val repository: CleanupRepository,
    val actions: MediaActions,
) : ViewModel() {

    private val _tab = MutableStateFlow(CleanupTab.DUPLICATES)
    val tab: StateFlow<CleanupTab> = _tab.asStateFlow()

    private val _duplicates = MutableStateFlow<List<DuplicateGroup>?>(null)
    val duplicates: StateFlow<List<DuplicateGroup>?> = _duplicates.asStateFlow()

    private val _threshold = MutableStateFlow(CleanupRepository.DEFAULT_BLUR_THRESHOLD)
    val threshold: StateFlow<Float> = _threshold.asStateFlow()

    val blurry: StateFlow<List<MediaItem>?> = _threshold
        .debounce(150)
        .flatMapLatest { repository.observeBlurry(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val screenshots: StateFlow<List<MediaItem>?> = repository.observeScreenshots()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val largeVideos: StateFlow<List<MediaItem>?> = repository.observeLargeVideos()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    init {
        actions.bind(viewModelScope)
        refreshDuplicates()
    }

    fun setTab(tab: CleanupTab) {
        _tab.value = tab
        _selection.value = emptySet()
    }

    fun setThreshold(value: Float) {
        _threshold.value = value
    }

    fun toggle(id: Long) {
        _selection.value = _selection.value.let { if (id in it) it - id else it + id }
    }

    /** Présélectionne tout sauf la meilleure photo de chaque groupe. */
    fun selectSuggested() {
        _selection.value = when (_tab.value) {
            CleanupTab.DUPLICATES -> _duplicates.value.orEmpty().flatMap { group -> group.items.filter { it.id != group.keepId }.map { it.id } }.toSet()
            CleanupTab.BLURRY -> blurry.value.orEmpty().map { it.id }.toSet()
            CleanupTab.SCREENSHOTS -> screenshots.value.orEmpty().map { it.id }.toSet()
            CleanupTab.LARGE_VIDEOS -> largeVideos.value.orEmpty().map { it.id }.toSet()
        }
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    fun trashSelection() {
        val ids = _selection.value
        if (ids.isNotEmpty()) actions.trash(ids)
    }

    /** Après une mise à la corbeille réussie : sélection vidée, groupes recalculés. */
    fun onActionDone() {
        _selection.value = emptySet()
        refreshDuplicates()
    }

    fun refreshDuplicates() {
        viewModelScope.launch { _duplicates.value = repository.findDuplicates() }
    }
}
