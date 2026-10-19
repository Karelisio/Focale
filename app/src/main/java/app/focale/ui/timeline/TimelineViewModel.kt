package app.focale.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.focale.core.mediastore.MediaSyncManager
import app.focale.core.mediastore.SyncState
import app.focale.core.model.DateBucket
import app.focale.core.model.GridItem
import app.focale.core.model.GroupLevel
import app.focale.core.model.MediaFilter
import app.focale.core.model.MediaQuery
import app.focale.core.model.MediaSort
import app.focale.core.permissions.MediaAccess
import app.focale.core.permissions.MediaAccessManager
import app.focale.core.settings.SettingsRepository
import app.focale.data.DateGrouping
import app.focale.data.MediaRepository
import app.focale.data.MemoriesRepository
import app.focale.data.Memory
import app.focale.data.toGridItems
import app.focale.ui.actions.MediaActions
import app.focale.ui.components.GridSelection
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** État de la timeline : grille paginée, filtres, zoom, sélection. */
@HiltViewModel
class TimelineViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val settingsRepository: SettingsRepository,
    private val accessManager: MediaAccessManager,
    private val syncManager: MediaSyncManager,
    memoriesRepository: MemoriesRepository,
    val actions: MediaActions,
) : ViewModel() {

    init {
        actions.bind(viewModelScope)
    }

    val access: StateFlow<MediaAccess> = accessManager.access
    val syncState: StateFlow<SyncState> = syncManager.state

    private val _filter = MutableStateFlow(MediaFilter.ALL)
    val filter: StateFlow<MediaFilter> = _filter.asStateFlow()

    private val _sort = MutableStateFlow(MediaSort())
    val sort: StateFlow<MediaSort> = _sort.asStateFlow()

    private val hiddenFolders: Flow<List<Long>> = settingsRepository.settings
        .map { it.hiddenFolders.sorted() }
        .distinctUntilChanged()

    val query: StateFlow<MediaQuery> = combine(_filter, _sort, hiddenFolders) { filter, sort, hidden ->
        MediaQuery(filter = filter, sort = sort, excludedBuckets = hidden)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MediaQuery(excludedBuckets = settingsRepository.current.value.hiddenFolders.sorted()))

    val columns: StateFlow<Int> = settingsRepository.settings
        .map { it.gridColumns }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.current.value.gridColumns)

    private val groupLevel: StateFlow<GroupLevel?> = combine(
        settingsRepository.settings.map { it.groupMode },
        columns,
        _sort,
    ) { mode, cols, sort -> if (sort.isChronological) DateGrouping.resolve(mode, cols) else null }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val pagedMedia = query
        .flatMapLatest { mediaRepository.paged(it) }
        .cachedIn(viewModelScope)

    /** Le changement de niveau d'en-tête réutilise les pages en cache (pas de rechargement). */
    val items: Flow<PagingData<GridItem>> = combine(pagedMedia, groupLevel) { data, level ->
        data.toGridItems(level)
    }.cachedIn(viewModelScope)

    val count: StateFlow<Int> = query
        .flatMapLatest { mediaRepository.observeCount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1)

    val histogram: StateFlow<List<DateBucket>> = query
        .flatMapLatest { mediaRepository.observeHistogram(it, GroupLevel.MONTH) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Souvenirs du jour, recalculés quand la bibliothèque ou la date change. */
    val memories: StateFlow<List<Memory>> = combine(
        settingsRepository.settings.map { it.showMemories }.distinctUntilChanged(),
        hiddenFolders,
        mediaRepository.observeCount(MediaQuery.Timeline),
        memoriesRepository.today,
    ) { show, _, _, day -> if (show) day else null }
        .mapLatest { day -> if (day != null) memoriesRepository.memories(day) else emptyList() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val gridSelection = GridSelection(viewModelScope, mediaRepository)
    val selection: StateFlow<Set<Long>> = gridSelection.selected

    fun setFilter(filter: MediaFilter) {
        _filter.value = filter
        gridSelection.clear()
    }

    fun setSort(sort: MediaSort) {
        _sort.value = sort
    }

    fun setColumns(columns: Int) {
        viewModelScope.launch { settingsRepository.setGridColumns(columns) }
    }

    fun setSelection(ids: Set<Long>) = gridSelection.set(ids)

    fun clearSelection() = gridSelection.clear()

    fun selectAll() = gridSelection.selectAll(query.value)

    fun refreshAccess() {
        accessManager.refresh()
        syncManager.requestSync()
    }

    /** Index de grille d'un média (pour y revenir après la visionneuse). */
    suspend fun gridIndexOf(mediaId: Long): Int? =
        mediaRepository.gridPositionOf(query.value, mediaId, groupLevel.value)
}
