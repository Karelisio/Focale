package app.focale.ui.people

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.focale.R
import app.focale.core.image.SeedColorExtractor
import app.focale.core.model.DateBucket
import app.focale.core.model.GridItem
import app.focale.core.model.GroupLevel
import app.focale.core.model.MediaFilter
import app.focale.core.model.MediaQuery
import app.focale.core.model.MediaSort
import app.focale.core.settings.SettingsRepository
import app.focale.core.util.UiText
import app.focale.data.DateGrouping
import app.focale.data.MediaRepository
import app.focale.data.toGridItems
import app.focale.people.FaceThumbnail
import app.focale.people.PeopleRepository
import app.focale.people.Person
import app.focale.people.PersonTogether
import app.focale.people.photosTogether
import app.focale.ui.actions.MediaActions
import app.focale.ui.components.GridSelection
import app.focale.ui.navigation.PersonRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Événement ponctuel de l'écran d'une personne. */
sealed interface PersonEvent {
    data class Message(val text: UiText, val undo: (() -> Unit)? = null) : PersonEvent

    /** La personne a été fusionnée dans [personId] : l'écran passe à celle-ci. */
    data class Merged(val personId: Long) : PersonEvent
}

/**
 * Photos d'une personne (même grille que les albums, via `MediaQuery.personId`) et
 * retouches : nom, vignette, masquage, fusion, retrait de photos ; photos avec d'autres
 * personnes (« Avec… »).
 */
@HiltViewModel
class PersonViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val people: PeopleRepository,
    private val mediaRepository: MediaRepository,
    private val settingsRepository: SettingsRepository,
    seedExtractor: SeedColorExtractor,
    val actions: MediaActions,
) : ViewModel() {

    init {
        actions.bind(viewModelScope)
    }

    val personId: Long = savedStateHandle.toRoute<PersonRoute>().personId
    private val baseQuery = MediaQuery(personId = personId)

    /** Null avant le premier chargement ou une fois la personne disparue (fusion, retrait). */
    val person: StateFlow<Person?> = people.observePerson(personId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Autres personnes, candidates à une fusion (masquées comprises), nommées d'abord. */
    val others: StateFlow<List<Person>> = people.observeOthers(personId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Personnes vues sur les photos de celle-ci (« Avec… ») ; null avant le chargement. */
    val together: StateFlow<List<PersonTogether>?> = people.observeTogether(personId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _togetherSelection = MutableStateFlow<Set<Long>>(emptySet())

    /** Personnes choisies dans « Avec… ». */
    val togetherSelection: StateFlow<Set<Long>> = _togetherSelection.asStateFlow()

    /** Photos où la personne figure avec toutes celles choisies ; -1 sans choix. */
    val togetherCount: StateFlow<Int> = _togetherSelection
        .flatMapLatest { ids -> if (ids.isEmpty()) flowOf(-1) else mediaRepository.observeCount(togetherQuery(ids)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1)

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

    /** Couleur de la photo qui porte la vignette (thème « couleur depuis la photo »). */
    val seedColor: StateFlow<Color?> = person
        .map { it?.cover?.mediaId }
        .distinctUntilChanged()
        .mapLatest { mediaId -> mediaId?.let { mediaRepository.get(it) }?.let { seedExtractor.seedFor(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val gridSelection = GridSelection(viewModelScope, mediaRepository)
    val selection: StateFlow<Set<Long>> = gridSelection.selected

    private val channel = Channel<PersonEvent>(Channel.BUFFERED)
    val events: Flow<PersonEvent> = channel.receiveAsFlow()

    /** Visages proposés comme vignette (chargés à l'ouverture du choix). */
    private val _faces = MutableStateFlow<List<FaceThumbnail>?>(null)
    val faces: StateFlow<List<FaceThumbnail>?> = _faces.asStateFlow()

    fun setSelection(ids: Set<Long>) = gridSelection.set(ids)
    fun clearSelection() = gridSelection.clear()
    fun selectAll() = gridSelection.selectAll(query.value)

    fun setTogetherSelection(ids: Set<Long>) {
        _togetherSelection.value = ids
    }

    /** Photos où la personne figure avec toutes celles de [ids] (« Avec… »). */
    fun togetherQuery(ids: Set<Long> = togetherSelection.value): MediaQuery = photosTogether(listOf(personId) + ids)

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

    fun rename(name: String?) {
        viewModelScope.launch { people.rename(personId, name) }
    }

    fun setHidden(hidden: Boolean) {
        viewModelScope.launch {
            people.setHidden(personId, hidden)
            if (hidden) {
                channel.send(PersonEvent.Message(UiText.Res(R.string.people_hidden_done)) { setHidden(false) })
            }
        }
    }

    fun loadFaces() {
        viewModelScope.launch { _faces.value = people.faces(personId) }
    }

    fun setCover(faceId: Long) {
        viewModelScope.launch {
            people.setCover(personId, faceId)
            channel.send(PersonEvent.Message(UiText.Res(R.string.people_cover_updated)))
        }
    }

    fun setSelectionAsCover() {
        val mediaId = selection.value.singleOrNull() ?: return
        viewModelScope.launch {
            people.setCoverFromMedia(personId, mediaId)
            gridSelection.clear()
            channel.send(PersonEvent.Message(UiText.Res(R.string.people_cover_updated)))
        }
    }

    fun mergeInto(targetId: Long) {
        viewModelScope.launch {
            people.merge(personId, targetId)
            channel.send(PersonEvent.Merged(targetId))
        }
    }

    /** Retire les photos sélectionnées de la personne (annulable). */
    fun removeSelection() {
        val ids = selection.value
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val removal = people.removeMedia(personId, ids) ?: return@launch
            gridSelection.clear()
            channel.send(
                PersonEvent.Message(UiText.Plural(R.plurals.people_removed, removal.count)) {
                    viewModelScope.launch { people.restore(removal) }
                },
            )
        }
    }
}
