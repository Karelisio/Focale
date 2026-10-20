package app.focale.ui.people

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.focale.core.settings.PeopleSort
import app.focale.core.settings.SettingsRepository
import app.focale.people.MergeSuggestions
import app.focale.people.PeopleRepository
import app.focale.people.Person
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Rangée « Personnes » de l'onglet Albums : les premières personnes, dans l'ordre de la liste. */
@HiltViewModel
class PeopleSectionViewModel @Inject constructor(
    repository: PeopleRepository,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val people: StateFlow<List<Person>> = settingsRepository.settings.map { it.peopleSort }.distinctUntilChanged()
        .flatMapLatest { repository.observePeople(it) }
        .map { it.take(SHELF_SIZE) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private companion object {
        const val SHELF_SIZE = 20
    }
}

/** Suggestion « Est-ce la même personne ? » affichée : [source] rejoindrait [target]. */
@Immutable
data class MergeProposal(val target: Person, val source: Person)

/**
 * Écran « Personnes » : toutes les personnes dans l'ordre choisi, masquées comprises sur
 * demande, et suggestions de fusion, une à la fois.
 */
@HiltViewModel
class PeopleViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val repository: PeopleRepository,
    private val settingsRepository: SettingsRepository,
    suggestions: MergeSuggestions,
) : ViewModel() {

    val showHidden: StateFlow<Boolean> = savedStateHandle.getStateFlow(KEY_SHOW_HIDDEN, false)

    private val sortSetting = settingsRepository.settings.map { it.peopleSort }.distinctUntilChanged()

    val sort: StateFlow<PeopleSort> = sortSetting
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settingsRepository.current.value.peopleSort)

    /** Null tant que la première liste n'est pas chargée (pas d'écran vide fugace). */
    val people: StateFlow<List<Person>?> = combine(showHidden, sortSetting) { hidden, sort -> hidden to sort }
        .flatMapLatest { (hidden, sort) -> repository.observePeople(sort, includeHidden = hidden) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Suggestions déjà traitées ici : masquées sans attendre le recalcul. */
    private val handled = MutableStateFlow(Handled())

    /** Première suggestion dont les deux personnes figurent dans la liste, ou null. */
    val suggestion: StateFlow<MergeProposal?> = combine(people.filterNotNull(), suggestions.observe(), handled) { list, pairs, done ->
        val byId = list.associateBy { it.id }
        pairs.firstNotNullOfOrNull { pair ->
            if (pairKey(pair.targetId, pair.sourceId) in done.pairs || pair.sourceId in done.merged || pair.targetId in done.merged) {
                return@firstNotNullOfOrNull null
            }
            val target = byId[pair.targetId] ?: return@firstNotNullOfOrNull null
            val source = byId[pair.sourceId] ?: return@firstNotNullOfOrNull null
            MergeProposal(target, source)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val analysisEnabled: StateFlow<Boolean> = settingsRepository.settings.map { it.analysisEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settingsRepository.current.value.analysisEnabled)

    fun setShowHidden(show: Boolean) {
        savedStateHandle[KEY_SHOW_HIDDEN] = show
    }

    fun setSort(sort: PeopleSort) {
        viewModelScope.launch { settingsRepository.setPeopleSort(sort) }
    }

    fun setPinned(person: Person, pinned: Boolean) {
        viewModelScope.launch { repository.setPinned(person.id, pinned) }
    }

    /** « Fusionner » : la source rejoint la cible ; ses autres suggestions tombent avec elle. */
    fun merge(proposal: MergeProposal) {
        handled.update { it.copy(merged = it.merged + proposal.source.id) }
        viewModelScope.launch { repository.merge(proposal.source.id, proposal.target.id) }
    }

    /** « Pas la même » : la paire n'est plus proposée. */
    fun refuse(proposal: MergeProposal) {
        handled.update { it.copy(pairs = it.pairs + pairKey(proposal.target.id, proposal.source.id)) }
        viewModelScope.launch { repository.refuseMerge(proposal.source.id, proposal.target.id) }
    }

    fun enableAnalysis() {
        viewModelScope.launch { settingsRepository.setAnalysisEnabled(true) }
    }

    /** Paires refusées (plus petit identifiant en premier) et personnes fusionnées depuis l'ouverture de l'écran. */
    private data class Handled(val pairs: Set<Pair<Long, Long>> = emptySet(), val merged: Set<Long> = emptySet())

    private companion object {
        const val KEY_SHOW_HIDDEN = "show_hidden"

        fun pairKey(a: Long, b: Long) = minOf(a, b) to maxOf(a, b)
    }
}
