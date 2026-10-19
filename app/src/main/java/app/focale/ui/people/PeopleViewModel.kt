package app.focale.ui.people

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.focale.core.settings.SettingsRepository
import app.focale.people.PeopleRepository
import app.focale.people.Person
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Rangée « Personnes » de l'onglet Albums : les personnes les plus présentes. */
@HiltViewModel
class PeopleSectionViewModel @Inject constructor(repository: PeopleRepository) : ViewModel() {

    val people: StateFlow<List<Person>> = repository.observePeople()
        .map { it.take(SHELF_SIZE) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private companion object {
        const val SHELF_SIZE = 20
    }
}

/** Écran « Personnes » : toutes les personnes, masquées comprises sur demande. */
@HiltViewModel
class PeopleViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    repository: PeopleRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val showHidden: StateFlow<Boolean> = savedStateHandle.getStateFlow(KEY_SHOW_HIDDEN, false)

    /** Null tant que la première liste n'est pas chargée (pas d'écran vide fugace). */
    val people: StateFlow<List<Person>?> = showHidden
        .flatMapLatest { repository.observePeople(includeHidden = it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val analysisEnabled: StateFlow<Boolean> = settingsRepository.settings.map { it.analysisEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), settingsRepository.current.value.analysisEnabled)

    fun setShowHidden(show: Boolean) {
        savedStateHandle[KEY_SHOW_HIDDEN] = show
    }

    fun enableAnalysis() {
        viewModelScope.launch { settingsRepository.setAnalysisEnabled(true) }
    }

    private companion object {
        const val KEY_SHOW_HIDDEN = "show_hidden"
    }
}
