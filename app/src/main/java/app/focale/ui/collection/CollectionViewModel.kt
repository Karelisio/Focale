package app.focale.ui.collection

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.focale.core.image.SeedColorExtractor
import app.focale.core.model.DateBucket
import app.focale.core.model.GridItem
import app.focale.core.model.GroupLevel
import app.focale.core.model.MediaFilter
import app.focale.core.model.MediaQuery
import app.focale.core.model.MediaSort
import app.focale.core.settings.SettingsRepository
import app.focale.data.AlbumRepository
import app.focale.data.DateGrouping
import app.focale.data.MediaRepository
import app.focale.data.toGridItems
import app.focale.ui.actions.MediaActions
import app.focale.ui.components.GridSelection
import app.focale.ui.navigation.CollectionKind
import app.focale.ui.navigation.CollectionRoute
import app.focale.ui.navigation.RouteCodec
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Grille générique : album dossier, album virtuel, favoris ou catégorie
 * (vidéos, captures…). Le thème s'adapte à la couverture (couleur depuis la photo).
 */
@HiltViewModel
class CollectionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mediaRepository: MediaRepository,
    private val albumRepository: AlbumRepository,
    private val settingsRepository: SettingsRepository,
    seedExtractor: SeedColorExtractor,
    val actions: MediaActions,
) : ViewModel() {

    init {
        actions.bind(viewModelScope)
    }

    private val route = savedStateHandle.toRoute<CollectionRoute>()
    val kind: CollectionKind = route.kind
    val albumId: Long? = route.albumId
    private val baseQuery = RouteCodec.decode(route.query)

    /** Titre : suit le renommage pour un album virtuel. */
    val title: StateFlow<String> = (albumId?.let { id -> albumRepository.observeAlbum(id).map { it?.name ?: route.title } }
        ?: flowOf(route.title))
        .stateIn(viewModelScope, SharingStarted.Eagerly, route.title)

    private val _sort = MutableStateFlow(baseQuery.sort)
    val sort: StateFlow<MediaSort> = _sort.asStateFlow()

    private val _filter = MutableStateFlow(baseQuery.filter)
    val filter: StateFlow<MediaFilter> = _filter.asStateFlow()

    val query: StateFlow<MediaQuery> = combine(_sort, _filter) { sort, filter -> baseQuery.copy(sort = sort, filter = filter) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, baseQuery)

    val columns: StateFlow<Int> = settingsRepository.settings.map { it.gridColumns }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.current.value.gridColumns)

    private val groupLevel: StateFlow<GroupLevel?> = combine(settingsRepository.settings, columns, _sort) { settings, cols, sort ->
        if (sort.isChronological) DateGrouping.resolve(settings.groupMode, cols) else null
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val paged = query.flatMapLatest { mediaRepository.paged(it) }.cachedIn(viewModelScope)

    val items: Flow<PagingData<GridItem>> = combine(paged, groupLevel) { data, level -> data.toGridItems(level) }
        .cachedIn(viewModelScope)

    val count: StateFlow<Int> = query.flatMapLatest { mediaRepository.observeCount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1)

    val histogram: StateFlow<List<DateBucket>> = query.flatMapLatest { mediaRepository.observeHistogram(it, GroupLevel.MONTH) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Couleur dominante de la couverture (média le plus récent). */
    val seedColor: StateFlow<Color?> = mediaRepository.observeFirst(baseQuery)
        .distinctUntilChangedBy { it?.id }
        .mapLatest { cover -> cover?.let { seedExtractor.seedFor(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val gridSelection = GridSelection(viewModelScope, mediaRepository)
    val selection: StateFlow<Set<Long>> = gridSelection.selected

    fun setSelection(ids: Set<Long>) = gridSelection.set(ids)
    fun clearSelection() = gridSelection.clear()
    fun selectAll() = gridSelection.selectAll(query.value)

    fun setSort(sort: MediaSort) {
        _sort.value = sort
    }

    fun setFilter(filter: MediaFilter) {
        _filter.value = filter
        gridSelection.clear()
    }

    fun setColumns(columns: Int) {
        viewModelScope.launch { settingsRepository.setGridColumns(columns) }
    }

    suspend fun gridIndexOf(mediaId: Long): Int? = mediaRepository.gridPositionOf(query.value, mediaId, groupLevel.value)

    // ── Album virtuel ──────────────────────────────────────────────────────────────

    fun renameAlbum(name: String) {
        val id = albumId ?: return
        viewModelScope.launch { albumRepository.rename(id, name) }
    }

    fun deleteAlbum(onDeleted: () -> Unit) {
        val id = albumId ?: return
        viewModelScope.launch {
            albumRepository.delete(id)
            onDeleted()
        }
    }

    fun removeSelectionFromAlbum() {
        val id = albumId ?: return
        actions.removeFromAlbum(id, selection.value)
    }

    fun setSelectionAsCover() {
        val id = albumId ?: return
        val mediaId = selection.value.singleOrNull() ?: return
        viewModelScope.launch {
            albumRepository.setCover(id, mediaId)
            gridSelection.clear()
        }
    }
}
