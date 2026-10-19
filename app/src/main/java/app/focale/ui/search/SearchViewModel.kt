package app.focale.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.focale.core.model.MediaQuery
import app.focale.core.permissions.MediaAccess
import app.focale.core.permissions.MediaAccessManager
import app.focale.core.settings.SettingsRepository
import app.focale.data.PlaceSuggestion
import app.focale.data.SearchRepository
import app.focale.data.ThingSuggestion
import app.focale.ml.SearchQueryParser
import app.focale.work.AnalysisProgress
import app.focale.work.AnalysisScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val repository: SearchRepository,
    scheduler: AnalysisScheduler,
    private val settingsRepository: SettingsRepository,
    accessManager: MediaAccessManager,
) : ViewModel() {

    val access: StateFlow<MediaAccess> = accessManager.access

    val things: StateFlow<List<ThingSuggestion>> = repository.observeThings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val places: StateFlow<List<PlaceSuggestion>> = repository.observePlaces()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recent: StateFlow<List<String>> = repository.observeRecent()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val progress: StateFlow<AnalysisProgress?> = scheduler.progress
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val analysisEnabled: StateFlow<Boolean> = settingsRepository.settings.map { it.analysisEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Analyse la saisie ; null si elle ne filtre rien. La recherche est mémorisée. */
    fun submit(text: String): MediaQuery? {
        val query = repository.parse(text)
        if (SearchQueryParser.isEmpty(query)) return null
        viewModelScope.launch { repository.remember(text) }
        return query
    }

    fun forget(text: String) {
        viewModelScope.launch { repository.forget(text) }
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearHistory() }
    }

    fun enableAnalysis() {
        viewModelScope.launch { settingsRepository.setAnalysisEnabled(true) }
    }
}
