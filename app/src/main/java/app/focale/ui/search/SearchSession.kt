package app.focale.ui.search

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import app.focale.core.model.MediaQuery
import app.focale.data.SearchChip
import app.focale.data.SearchFilters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Recherche de l'historique : sa clé enregistrée et ce qu'elle cherche. */
@Immutable
data class RecentSearch(val key: String, val filters: SearchFilters)

/**
 * Recherche en cours, sans interface : texte validé et pastilles, gardés dans [handle] (rotation,
 * processus tué) ; requête des résultats ; historique.
 *
 * Une pastille change aussitôt la requête. Un texte validé passe d'abord par [resolve] (recherche
 * par le sens) : la requête précédente reste affichée le temps du calcul. L'historique garde une
 * entrée par recherche ([remember] remplace celle de la recherche en cours à mesure qu'elle s'affine).
 */
class SearchSession(
    private val handle: SavedStateHandle,
    scope: CoroutineScope,
    private val resolve: suspend (String) -> MediaQuery,
    private val remember: suspend (key: String, replacing: String?) -> Unit,
    historyDelayMs: Long = HISTORY_DELAY_MS,
) {
    private val _filters = MutableStateFlow(SearchFilters.decode(handle[KEY_FILTERS]))

    /** Texte validé et pastilles ; vide : page d'exploration. */
    val filters: StateFlow<SearchFilters> = _filters.asStateFlow()

    /** Dernier texte validé et sa requête. */
    private val resolved = MutableStateFlow<ResolvedText?>(null)

    /** Vrai pendant l'analyse d'un texte validé. */
    val searching: StateFlow<Boolean> = combine(_filters, resolved, ::pending)
        .stateIn(scope, SharingStarted.Eagerly, pending(_filters.value, null))

    /**
     * Requête des résultats, commune à la grille et à la visionneuse ; la précédente pendant
     * l'analyse d'un texte, null sans recherche (ou avant le premier résultat).
     */
    val query: StateFlow<MediaQuery?> = combine(_filters, resolved) { filters, text -> filters to resultsQuery(filters, text) }
        .scan(null as MediaQuery?) { previous, (filters, query) ->
            when {
                filters.isEmpty -> null
                query != null -> query
                else -> previous
            }
        }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    /**
     * Requêtes de la recherche privée d'une de ses pastilles, ou de son texte (clé null), tant qu'il
     * lui reste un critère : de quoi l'élargir quand elle ne donne rien.
     */
    val relaxations: Flow<List<Pair<SearchChip?, MediaQuery>>> = combine(_filters, resolved) { filters, text ->
        val textQuery = textQuery(filters, text) ?: return@combine emptyList()
        buildList {
            filters.chips.forEach { chip ->
                val rest = filters - chip
                if (!rest.isEmpty) add(chip to rest.query(textQuery))
            }
            if (filters.text.isNotBlank() && filters.chips.isNotEmpty()) add(null to filters.query(MediaQuery()))
        }
    }.distinctUntilChanged()

    /** La recherche sans sa période (périodes proposées pour la pastille). */
    val withoutPeriod: Flow<MediaQuery> = combine(_filters, resolved) { filters, text ->
        SearchFilters(chips = filters.chips.filterNot { it is SearchChip.Period }).query(textQuery(filters, text) ?: MediaQuery())
    }.distinctUntilChanged()

    /** Entrée d'historique de la recherche en cours. */
    private var historyEntry: String?
        get() = handle[KEY_HISTORY]
        set(value) {
            handle[KEY_HISTORY] = value
        }

    init {
        // Texte validé (ou restauré) : sa requête complète ; un nouveau texte annule le calcul en cours.
        scope.launch {
            _filters.map { it.text }.distinctUntilChanged().collectLatest { text ->
                if (text.isNotBlank()) resolved.value = ResolvedText(text, resolve(text))
            }
        }
        // Historique : après un temps sans changement, l'entrée de la recherche est mise à jour.
        scope.launch {
            _filters.debounce(historyDelayMs).collect { filters ->
                if (filters.isEmpty) return@collect
                val key = filters.historyKey
                remember(key, historyEntry)
                historyEntry = key
            }
        }
    }

    /** Remplace la recherche ; vide, elle prend fin (la suivante aura sa propre entrée d'historique). */
    fun update(filters: SearchFilters) {
        if (filters == _filters.value) return
        _filters.value = filters
        handle[KEY_FILTERS] = filters.encode()
        if (filters.isEmpty) historyEntry = null
    }

    /** Relance [entry] : son entrée d'historique devient celle de la recherche (la précédente reste). */
    fun restore(entry: RecentSearch, filters: SearchFilters = entry.filters) {
        update(filters)
        historyEntry = entry.key
    }

    private companion object {
        const val KEY_FILTERS = "search_filters"
        const val KEY_HISTORY = "search_history_entry"

        /** Une recherche n'entre dans l'historique qu'après ce délai sans changement. */
        const val HISTORY_DELAY_MS = 1_500L

        fun pending(filters: SearchFilters, text: ResolvedText?): Boolean = filters.text.isNotBlank() && text?.text != filters.text

        /** Requête du texte validé ; null tant qu'elle se calcule. */
        fun textQuery(filters: SearchFilters, text: ResolvedText?): MediaQuery? = when {
            filters.text.isBlank() -> MediaQuery()
            text?.text == filters.text -> text.query
            else -> null
        }

        fun resultsQuery(filters: SearchFilters, text: ResolvedText?): MediaQuery? =
            if (filters.isEmpty) null else textQuery(filters, text)?.let(filters::query)
    }
}

/** Texte validé et sa requête (recherche par le sens comprise). */
@Immutable
private data class ResolvedText(val text: String, val query: MediaQuery)
