package app.focale.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import app.focale.core.model.DateBucket
import app.focale.core.model.GridItem
import app.focale.core.model.GroupLevel
import app.focale.core.model.MediaQuery
import app.focale.core.permissions.MediaAccess
import app.focale.core.permissions.MediaAccessManager
import app.focale.core.settings.SettingsRepository
import app.focale.data.ChipSuggestion
import app.focale.data.DateGrouping
import app.focale.data.MediaRepository
import app.focale.data.PeriodChoices
import app.focale.data.PlaceSuggestion
import app.focale.data.SearchChip
import app.focale.data.SearchChipDetector
import app.focale.data.SearchFilters
import app.focale.data.SearchRepository
import app.focale.data.ThingSuggestion
import app.focale.data.toGridItems
import app.focale.ml.SearchQueryParser
import app.focale.people.PeopleRepository
import app.focale.people.Person
import app.focale.ui.actions.MediaActions
import app.focale.ui.components.GridSelection
import app.focale.work.AnalysisScheduler
import app.focale.work.AnalysisStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Onglet Recherche : exploration (lieux, choses, personnes), puis recherche combinant texte libre et
 * pastilles (personnes, lieu, période, type), dont les résultats s'affichent dans l'onglet.
 *
 * La recherche en cours ([SearchSession]) est gardée dans l'état sauvegardé (rotation, processus
 * tué) ; la grille et la visionneuse reçoivent la même [gridQuery].
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: SearchRepository,
    private val scheduler: AnalysisScheduler,
    private val settingsRepository: SettingsRepository,
    private val mediaRepository: MediaRepository,
    accessManager: MediaAccessManager,
    peopleRepository: PeopleRepository,
    val actions: MediaActions,
) : ViewModel() {

    init {
        actions.bind(viewModelScope)
    }

    val access: StateFlow<MediaAccess> = accessManager.access

    val things: StateFlow<List<ThingSuggestion>> = repository.observeThings()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Lieux visités, les plus photographiés d'abord : rangée « Lieux », choix d'un lieu, suggestions. */
    val places: StateFlow<List<PlaceSuggestion>> = repository.observePlaces(PLACE_CHOICES)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Recherches récentes, avec ou sans pastilles (illisibles écartées). */
    val recent: StateFlow<List<RecentSearch>> = repository.observeRecent()
        .map { keys -> keys.mapNotNull { key -> SearchFilters.fromHistory(key)?.let { RecentSearch(key, it) } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Personnes visibles (rangée « Personnes », choix d'une personne), dans l'ordre choisi, épinglées en tête. */
    val people: StateFlow<List<Person>> = settingsRepository.settings.map { it.peopleSort }.distinctUntilChanged()
        .flatMapLatest { peopleRepository.observePeople(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Personnes nommées, masquées comprises (leur nom est reconnu dans la saisie) ; null avant le chargement. */
    private val named: StateFlow<Map<Long, String>?> = peopleRepository.observeNamed()
        .map { list -> list.mapNotNull { person -> person.name?.let { person.id to it } }.toMap() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Personnes nommées : pastilles suggérées pendant la frappe. */
    val namedPeople: StateFlow<Map<Long, String>> = named.map { it.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** Avancement détaillé (étiquettes, texte, visages, recherche par le sens). */
    val analysisStatus: StateFlow<AnalysisStatus?> = scheduler.status
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun runAnalysisNow() = scheduler.runNow()

    val analysisEnabled: StateFlow<Boolean> = settingsRepository.settings.map { it.analysisEnabled }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    // ── Recherche en cours : texte validé et pastilles ─────────────────────────────

    private val input = MutableStateFlow("")
    private var preparation: Preparation? = null

    private val session = SearchSession(
        handle = savedStateHandle,
        scope = viewModelScope,
        resolve = ::resolve,
        remember = { key, replacing -> repository.remember(key, replacing) },
    )

    /** Texte validé et pastilles ; vide : page d'exploration. */
    val filters: StateFlow<SearchFilters> = session.filters

    /** Vrai pendant l'analyse d'un texte validé (recherche par le sens). */
    val searching: StateFlow<Boolean> = session.searching

    /** Requête des résultats, commune à la grille et à la visionneuse. */
    val gridQuery: StateFlow<MediaQuery?> = session.query

    init {
        // Préparée pendant la frappe (anti-rebond), la recherche par le sens est le plus
        // souvent prête à la validation ; une nouvelle saisie annule la précédente.
        viewModelScope.launch {
            input.debounce(PREPARE_DEBOUNCE_MS).distinctUntilChanged().collect { text ->
                if (!SearchQueryParser.isEmpty(repository.parse(text, named.value.orEmpty()))) prepare(text)
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

    /**
     * Valide [text] : il remplace le texte de la recherche, les pastilles restent. Faux (rien ne
     * change) si la recherche ne filtrerait rien : texte sans critère et aucune pastille.
     */
    fun submit(text: String): Boolean {
        val clean = text.trim()
        val current = filters.value
        if (current.chips.isEmpty() && SearchQueryParser.isEmpty(repository.parse(clean, named.value.orEmpty()))) return false
        update(current.copy(text = clean))
        return true
    }

    /** Retire le texte de la recherche ; sans pastille, retour à l'exploration. */
    fun clearText() = update(filters.value.copy(text = ""))

    fun addChip(chip: SearchChip) = update(filters.value + chip)

    fun removeChip(chip: SearchChip) = update(filters.value - chip)

    /**
     * Reprend [suggestion] en pastille, appliquée aussitôt aux résultats. Renvoie le texte [typed]
     * sans ses mots ; s'il n'en reste rien, la recherche ne garde que ses pastilles.
     */
    fun applySuggestion(suggestion: ChipSuggestion, typed: String): String {
        val rest = SearchChipDetector.remainingText(typed, suggestion, named.value.orEmpty(), repository.locale)
        val next = filters.value + suggestion.chip
        update(if (rest.isEmpty()) next.copy(text = "") else next)
        return rest
    }

    /** Relance une recherche mémorisée ; ses personnes disparues sont retrouvées par leur nom. */
    fun restore(entry: RecentSearch) {
        val known = people.value.mapTo(HashSet()) { it.id } + named.value.orEmpty().keys
        session.restore(entry, entry.filters.relinkPeople(known, named.value.orEmpty()))
        gridSelection.clear()
    }

    /** Quitte la recherche : retour à l'exploration. */
    fun clear() = update(SearchFilters())

    private fun update(filters: SearchFilters) {
        session.update(filters)
        gridSelection.clear()
    }

    /** Requête complète de [text] : celle préparée pendant la frappe si possible. */
    private suspend fun resolve(text: String): MediaQuery {
        val people = named.filterNotNull().first()
        return withTimeoutOrNull(SUBMIT_TIMEOUT_MS) {
            try {
                prepare(text).await()
            } catch (cancelled: CancellationException) {
                // Préparation remplacée par une saisie plus récente : calcul direct.
                ensureActive()
                repository.resolve(text, people)
            }
        } ?: repository.parse(text, people)
    }

    /** Requête complète de [text], partagée entre la préparation et la validation. */
    private fun prepare(text: String): Deferred<MediaQuery> {
        preparation?.takeIf { it.text == text && !it.result.isCancelled }?.let { return it.result }
        preparation?.result?.cancel()
        return viewModelScope.async { repository.resolve(text, named.filterNotNull().first()) }
            .also { preparation = Preparation(text, it) }
    }

    private class Preparation(val text: String, val result: Deferred<MediaQuery>)

    // ── Résultats ──────────────────────────────────────────────────────────────────

    val columns: StateFlow<Int> = settingsRepository.settings.map { it.gridColumns }.distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, settingsRepository.current.value.gridColumns)

    private val groupLevel: StateFlow<GroupLevel?> = combine(settingsRepository.settings, columns) { settings, cols ->
        DateGrouping.resolve(settings.groupMode, cols)
    }.distinctUntilChanged().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    // Sans recherche, une grille vide : la suivante ne montre pas un instant les anciens résultats.
    private val paged = gridQuery.flatMapLatest { query -> query?.let(mediaRepository::paged) ?: flowOf(PagingData.empty()) }
        .cachedIn(viewModelScope)

    val items: Flow<PagingData<GridItem>> = combine(paged, groupLevel) { data, level -> data.toGridItems(level) }
        .cachedIn(viewModelScope)

    /** Nombre de résultats et la requête comptée ; -1 avant le premier. */
    private val counted: StateFlow<Pair<MediaQuery?, Int>> = gridQuery
        .flatMapLatest { query -> query?.let { current -> mediaRepository.observeCount(current).map { current to it } } ?: flowOf(null to -1) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null to -1)

    /** Nombre de résultats ; -1 avant le premier. */
    val count: StateFlow<Int> = counted.map { it.second }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), -1)

    /**
     * Aucun résultat pour la recherche affichée : compté pour elle (et non pour la précédente, le
     * temps du nouveau compte), texte analysé.
     */
    val noResults: StateFlow<Boolean> = combine(counted, gridQuery, searching) { (query, count), shown, searching ->
        !searching && query != null && query == shown && count == 0
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val histogram: StateFlow<List<DateBucket>> = gridQuery
        .flatMapLatest { query -> query?.let { mediaRepository.observeHistogram(it, GroupLevel.MONTH) } ?: flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Recherche sans résultat : ce que donnerait le retrait de chaque pastille, ou du texte, les plus
     * fructueux d'abord ; vide sinon.
     */
    val relaxations: StateFlow<List<Relaxation>> = combine(noResults, session.relaxations) { empty, candidates ->
        if (empty) candidates else emptyList()
    }.distinctUntilChanged()
        .flatMapLatest { candidates ->
            if (candidates.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(candidates.map { (chip, query) -> mediaRepository.observeCount(query).map { Relaxation(chip, it) } }) { all ->
                    all.filter { it.count > 0 }.sortedByDescending { it.count }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Années et mois récents où la recherche, sans sa période, a des médias (choix d'une période). */
    val periodChoices: StateFlow<PeriodChoices> = session.withoutPeriod
        .flatMapLatest { mediaRepository.observeHistogram(it, GroupLevel.MONTH) }
        .map { PeriodChoices.from(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PeriodChoices())

    private val gridSelection = GridSelection(viewModelScope, mediaRepository)
    val selection: StateFlow<Set<Long>> = gridSelection.selected

    fun setSelection(ids: Set<Long>) = gridSelection.set(ids)
    fun clearSelection() = gridSelection.clear()
    fun selectAll() {
        gridQuery.value?.let(gridSelection::selectAll)
    }

    fun setColumns(columns: Int) {
        viewModelScope.launch { settingsRepository.setGridColumns(columns) }
    }

    suspend fun gridIndexOf(mediaId: Long): Int? =
        gridQuery.value?.let { mediaRepository.gridPositionOf(it, mediaId, groupLevel.value) }

    // ── Historique et réglages ─────────────────────────────────────────────────────

    fun forget(entry: RecentSearch) {
        viewModelScope.launch { repository.forget(entry.key) }
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clearHistory() }
    }

    fun enableAnalysis() {
        viewModelScope.launch { settingsRepository.setAnalysisEnabled(true) }
    }

    private companion object {
        const val PREPARE_DEBOUNCE_MS = 300L

        /** Au-delà, la recherche s'affiche avec les seules correspondances exactes. */
        const val SUBMIT_TIMEOUT_MS = 4_000L

        /** Lieux proposés au choix d'une pastille (les premiers forment la rangée « Lieux »). */
        const val PLACE_CHOICES = 200
    }
}
