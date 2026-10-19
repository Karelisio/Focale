package app.focale.ui.search

import androidx.compose.runtime.Immutable
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Recherche validée, prête à ouvrir dans la grille de résultats. */
@Immutable
data class SearchResult(val title: String, val query: MediaQuery)

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

    private val input = MutableStateFlow("")
    private var preparation: Preparation? = null
    private var submission: Job? = null

    private val _results = Channel<SearchResult>(Channel.BUFFERED)

    /** Une grille à ouvrir par recherche validée. */
    val results: Flow<SearchResult> = _results.receiveAsFlow()

    private val _searching = MutableStateFlow(false)

    /** Vrai pendant qu'une recherche validée attend la recherche par le sens. */
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    init {
        // Préparée pendant la frappe (anti-rebond), la recherche par le sens est le plus
        // souvent prête à la validation ; une nouvelle saisie annule la précédente.
        viewModelScope.launch {
            input.debounce(PREPARE_DEBOUNCE_MS).distinctUntilChanged().collect { text ->
                if (!SearchQueryParser.isEmpty(repository.parse(text))) prepare(text)
            }
        }
    }

    /** Saisie en cours dans la barre de recherche. */
    fun onTextChange(text: String) {
        input.value = text.trim()
    }

    /** Charge le modèle de texte et les empreintes dès l'ouverture de la recherche. */
    fun warmUp() {
        viewModelScope.launch { repository.warmUp() }
    }

    /** Valide la saisie : la requête arrive par [results]. La recherche est mémorisée. */
    fun submit(text: String) {
        val clean = text.trim()
        if (SearchQueryParser.isEmpty(repository.parse(clean))) return
        submission?.cancel()
        submission = viewModelScope.launch {
            repository.remember(clean)
            _searching.value = true
            try {
                val query = withTimeoutOrNull(SUBMIT_TIMEOUT_MS) {
                    try {
                        prepare(clean).await()
                    } catch (cancelled: CancellationException) {
                        // Préparation remplacée par une saisie plus récente : calcul direct.
                        ensureActive()
                        repository.resolve(clean)
                    }
                } ?: repository.parse(clean)
                _results.send(SearchResult(clean, query))
            } finally {
                _searching.value = false
            }
        }
    }

    /** Abandonne la recherche validée en attente (écran quitté) : elle n'ouvrira pas de grille plus tard. */
    fun cancelPending() {
        submission?.cancel()
        submission = null
    }

    /** Requête complète de [text], partagée entre la préparation et la validation. */
    private fun prepare(text: String): Deferred<MediaQuery> {
        preparation?.takeIf { it.text == text && !it.result.isCancelled }?.let { return it.result }
        preparation?.result?.cancel()
        return viewModelScope.async { repository.resolve(text) }.also { preparation = Preparation(text, it) }
    }

    private class Preparation(val text: String, val result: Deferred<MediaQuery>)

    fun forget(text: String) {
        viewModelScope.launch { repository.forget(text) }
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearHistory() }
    }

    fun enableAnalysis() {
        viewModelScope.launch { settingsRepository.setAnalysisEnabled(true) }
    }

    private companion object {
        const val PREPARE_DEBOUNCE_MS = 300L

        /** Au-delà, la grille s'ouvre avec les seules correspondances exactes. */
        const val SUBMIT_TIMEOUT_MS = 4_000L
    }
}
