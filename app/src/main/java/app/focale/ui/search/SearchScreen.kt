package app.focale.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AppBarWithSearch
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarScrollBehavior
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import app.focale.R
import app.focale.core.image.thumbnail
import app.focale.core.model.MediaFilter
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaQuery
import app.focale.core.model.SearchTerm
import app.focale.core.permissions.MediaAccess
import app.focale.data.ChipSuggestion
import app.focale.data.PlaceSuggestion
import app.focale.data.SearchChip
import app.focale.data.SearchChipDetector
import app.focale.data.ThingSuggestion
import app.focale.ml.CountryResolver
import app.focale.ml.LabelDictionary
import app.focale.people.Person
import app.focale.ui.analysis.AnalysisStatusCard
import app.focale.ui.components.MediaGridScaffold
import app.focale.ui.components.MediaPermissionScreen
import app.focale.ui.navigation.CollectionKind
import app.focale.ui.navigation.LocalRootNavController
import app.focale.ui.navigation.LocalTabsNavController
import app.focale.ui.navigation.PersonRoute
import app.focale.ui.navigation.openCollection
import app.focale.ui.navigation.openSlideshow
import app.focale.ui.people.FaceAvatar
import app.focale.ui.people.PeopleRow
import coil3.compose.AsyncImage
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Onglet Recherche : barre de recherche M3 et pastilles de filtre (personnes, lieu, période, type)
 * combinées au texte ; les résultats s'affichent dans l'onglet et se mettent à jour à chaque
 * pastille. Sans recherche : lieux, choses, personnes et catégories à explorer.
 */
@Composable
fun SearchScreen(viewModel: SearchViewModel = hiltViewModel()) {
    val access by viewModel.access.collectAsStateWithLifecycle()
    if (access == MediaAccess.DENIED) {
        MediaPermissionScreen(onAccessChanged = {})
        return
    }
    val tabsNav = LocalTabsNavController.current
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val things by viewModel.things.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val named by viewModel.namedPeople.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]
    val peopleById = remember(people) { people.associateBy { it.id } }

    val searchBarState = rememberSearchBarState()
    val textFieldState = rememberTextFieldState(initialText = filters.text)
    val scope = rememberCoroutineScope()
    var picker by rememberSaveable { mutableStateOf<SearchFilterKind?>(null) }
    var pickingDates by rememberSaveable { mutableStateOf(false) }
    val expanded = searchBarState.currentValue == SearchBarValue.Expanded

    fun collapse() {
        scope.launch { searchBarState.animateToCollapsed() }
    }

    fun open(title: String, query: MediaQuery) {
        collapse()
        tabsNav.openCollection(title, query, CollectionKind.QUERY)
    }

    fun openPerson(person: Person) {
        collapse()
        tabsNav.navigate(PersonRoute(person.id)) { launchSingleTop = true }
    }

    fun submit(text: String) {
        if (viewModel.submit(text)) collapse()
    }

    // Texte de la recherche changé sans la frappe (historique, pastille, effacement) : le champ suit.
    LaunchedEffect(filters.text) {
        if (textFieldState.text.toString().trim() != filters.text) textFieldState.setTextAndPlaceCursorAtEnd(filters.text)
    }
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collect(viewModel::onTextChange)
    }
    LaunchedEffect(expanded) {
        if (expanded) {
            viewModel.warmUp()
        } else {
            // Refermée sans valider : le champ revient au texte de la recherche affichée.
            val text = viewModel.filters.value.text
            if (textFieldState.text.toString().trim() != text) textFieldState.setTextAndPlaceCursorAtEnd(text)
        }
    }
    // Retour depuis les résultats : la recherche se referme (la sélection passe avant).
    BackHandler(enabled = !filters.isEmpty && !expanded, onBack = viewModel::clear)

    val inputField = @Composable {
        SearchBarDefaults.InputField(
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            onSearch = ::submit,
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
            leadingIcon = {
                if (searchBarState.currentValue == SearchBarValue.Expanded) {
                    IconButton(onClick = ::collapse, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                } else {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                }
            },
            trailingIcon = {
                if (textFieldState.text.isNotEmpty()) {
                    IconButton(
                        onClick = {
                            textFieldState.setTextAndPlaceCursorAtEnd("")
                            // Barre refermée : le texte quitte la recherche affichée, les pastilles restent.
                            if (searchBarState.currentValue == SearchBarValue.Collapsed) viewModel.clearText()
                        },
                        shapes = IconButtonDefaults.shapes(),
                    ) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear))
                    }
                }
            },
        )
    }
    // Chaque mode (exploration, résultats) a son défilement : la barre réapparaît en changeant de mode.
    val topBar = @Composable { scrollBehavior: SearchBarScrollBehavior ->
        AppBarWithSearch(state = searchBarState, inputField = inputField, scrollBehavior = scrollBehavior)
    }
    val kinds = remember(people.isEmpty(), places.isEmpty()) {
        SearchFilterKind.entries.filterTo(mutableSetOf()) { kind ->
            when (kind) {
                SearchFilterKind.PERSON -> people.isNotEmpty()
                SearchFilterKind.PLACE -> places.isNotEmpty()
                else -> true
            }
        }
    }
    val filterBar = @Composable { padding: PaddingValues ->
        SearchFilterBar(
            chips = filters.chips,
            people = peopleById,
            onRemove = viewModel::removeChip,
            onAdd = { picker = it },
            onPickType = { viewModel.addChip(SearchChip.Type(it)) },
            kinds = kinds,
            contentPadding = padding,
        )
    }

    if (filters.isEmpty) {
        SearchExplore(
            viewModel = viewModel,
            topBar = topBar,
            filterBar = filterBar,
            people = people,
            places = places,
            things = things,
            locale = locale,
            onOpen = ::open,
            onOpenPerson = ::openPerson,
        )
    } else {
        SearchResults(viewModel = viewModel, topBar = topBar, filterBar = filterBar, people = peopleById)
    }

    ExpandedFullScreenSearchBar(state = searchBarState, inputField = inputField) {
        val text by remember { snapshotFlow { textFieldState.text.toString() } }.collectAsState(initial = "")
        val suggestions = remember(text, named, places, locale) { SearchChipDetector.detect(text, named, places, locale = locale) }
        SearchSuggestions(
            text = text,
            searching = searching,
            recent = recent,
            suggestions = suggestions,
            things = things,
            people = peopleById,
            places = places,
            locale = locale,
            onSearch = ::submit,
            onSuggestion = { suggestion ->
                val rest = viewModel.applySuggestion(suggestion, text)
                textFieldState.setTextAndPlaceCursorAtEnd(rest)
                if (rest.isEmpty()) collapse()
            },
            onPickThing = { name ->
                textFieldState.setTextAndPlaceCursorAtEnd(name)
                submit(name)
            },
            onRestore = { entry ->
                viewModel.restore(entry)
                collapse()
            },
            onForget = viewModel::forget,
        )
    }

    when (picker) {
        SearchFilterKind.PERSON -> PersonPickerSheet(
            people = people.filter { person -> filters.chips.none { it is SearchChip.Person && it.id == person.id } },
            onPick = { person ->
                picker = null
                viewModel.addChip(SearchChip.Person(person.id, person.name))
            },
            onDismiss = { picker = null },
        )
        SearchFilterKind.PLACE -> PlacePickerSheet(
            places = places,
            locale = locale,
            onPick = { place ->
                picker = null
                viewModel.addChip(SearchChip.Place.of(place))
            },
            onDismiss = { picker = null },
        )
        SearchFilterKind.PERIOD -> {
            val choices by viewModel.periodChoices.collectAsStateWithLifecycle()
            PeriodPickerSheet(
                choices = choices,
                onPick = { period ->
                    picker = null
                    viewModel.addChip(period)
                },
                onPickDates = {
                    picker = null
                    pickingDates = true
                },
                onDismiss = { picker = null },
            )
        }
        // Le type se choisit dans le menu de sa pastille.
        SearchFilterKind.TYPE, null -> Unit
    }
    if (pickingDates) {
        DateRangeDialog(
            onConfirm = { period ->
                pickingDates = false
                viewModel.addChip(period)
            },
            onDismiss = { pickingDates = false },
        )
    }
}

/**
 * Résultats de la recherche : pastilles, nombre de résultats, puis la grille commune (sélection,
 * actions, visionneuse sur la même requête). Sans résultat, ce que donnerait le retrait d'une pastille.
 */
@Composable
private fun SearchResults(
    viewModel: SearchViewModel,
    topBar: @Composable (SearchBarScrollBehavior) -> Unit,
    filterBar: @Composable (PaddingValues) -> Unit,
    people: Map<Long, Person>,
) {
    val scrollBehavior = SearchBarDefaults.enterAlwaysSearchBarScrollBehavior()
    val rootNav = LocalRootNavController.current
    val resources = LocalResources.current
    val items = viewModel.items.collectAsLazyPagingItems()
    val query by viewModel.gridQuery.collectAsStateWithLifecycle()
    val filters by viewModel.filters.collectAsStateWithLifecycle()
    val columns by viewModel.columns.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val count by viewModel.count.collectAsStateWithLifecycle()
    val histogram by viewModel.histogram.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val relaxations by viewModel.relaxations.collectAsStateWithLifecycle()
    val empty by viewModel.noResults.collectAsStateWithLifecycle()
    val shown = query

    MediaGridScaffold(
        items = items,
        query = shown ?: NoResults,
        columns = columns,
        selection = selection,
        onSelectionChange = viewModel::setSelection,
        onClearSelection = viewModel::clearSelection,
        onSelectAll = viewModel::selectAll,
        actions = viewModel.actions,
        topBar = { topBar(scrollBehavior) },
        onColumnsChange = viewModel::setColumns,
        histogram = histogram,
        gridIndexOf = viewModel::gridIndexOf,
        leadingItemCount = if (empty) 3 else 2,
        leadingContent = {
            item(key = "search-filters", span = { GridItemSpan(maxLineSpan) }) {
                filterBar(PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp))
            }
            item(key = "search-summary", span = { GridItemSpan(maxLineSpan) }) {
                SearchResultsHeader(
                    count = if (shown == null) -1 else count,
                    searching = searching,
                    onClear = viewModel::clear,
                    onSlideshow = shown?.takeIf { count > 0 }?.let { { rootNav.openSlideshow(it) } },
                )
            }
            if (empty) {
                item(key = "search-empty", span = { GridItemSpan(maxLineSpan) }) {
                    SearchNoResults(
                        relaxations = relaxations,
                        label = { relaxation ->
                            relaxation.chip?.let { SearchFilterLabels.chip(resources, it, people) } ?: filters.text
                        },
                        onRelax = { relaxation ->
                            relaxation.chip?.let(viewModel::removeChip) ?: viewModel.clearText()
                        },
                    )
                }
            }
        },
        nestedScrollConnection = scrollBehavior.nestedScrollConnection,
    )
}

/** Requête de la grille tant qu'aucun résultat n'est prêt (texte en cours d'analyse). */
private val NoResults = MediaQuery(mediaIds = emptyList())

/** Page d'exploration : pastilles à ajouter, analyse, catégories, personnes, lieux et choses. */
@Composable
private fun SearchExplore(
    viewModel: SearchViewModel,
    topBar: @Composable (SearchBarScrollBehavior) -> Unit,
    filterBar: @Composable (PaddingValues) -> Unit,
    people: List<Person>,
    places: List<PlaceSuggestion>,
    things: List<ThingSuggestion>,
    locale: Locale,
    onOpen: (String, MediaQuery) -> Unit,
    onOpenPerson: (Person) -> Unit,
) {
    val analysisStatus by viewModel.analysisStatus.collectAsStateWithLifecycle()
    val analysisEnabled by viewModel.analysisEnabled.collectAsStateWithLifecycle()
    val scrollBehavior = SearchBarDefaults.enterAlwaysSearchBarScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = { topBar(scrollBehavior) },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 108.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "filters", span = { GridItemSpan(maxLineSpan) }) { filterBar(PaddingValues(0.dp)) }

            val status = analysisStatus
            if (!analysisEnabled) {
                item(key = "analysis-off", span = { GridItemSpan(maxLineSpan) }) {
                    AnalysisDisabledCard(onEnable = viewModel::enableAnalysis)
                }
            } else if (status != null && !status.complete) {
                // Tant qu'une analyse n'est pas finie : avancement de chacune et « Analyser maintenant ».
                item(key = "progress", span = { GridItemSpan(maxLineSpan) }) {
                    AnalysisStatusCard(status = status, onRunNow = viewModel::runAnalysisNow)
                }
            }

            item(key = "categories", span = { GridItemSpan(maxLineSpan) }) {
                CategoriesRow(onOpen = onOpen)
            }

            if (people.isNotEmpty()) {
                item(key = "people-header", span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle(stringResource(R.string.people_title), Icons.Outlined.Face)
                }
                item(key = "people", span = { GridItemSpan(maxLineSpan) }) { PeopleRow(people, onOpen = onOpenPerson) }
            }

            if (places.isNotEmpty()) {
                item(key = "places-header", span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle(stringResource(R.string.search_places), Icons.Outlined.LocationOn)
                }
                item(key = "places", span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(places.take(PLACES_ROW), key = { "${it.name}-${it.countryCode}" }) { place ->
                            PlaceCard(place, locale) {
                                onOpen(
                                    place.names.display(locale),
                                    MediaQuery(
                                        terms = listOf(
                                            SearchTerm(place = LabelDictionary.normalize(place.name), exactPlace = true),
                                            SearchTerm(countries = listOf(place.countryCode)),
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                }
            }

            if (things.isNotEmpty()) {
                item(key = "things-header", span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle(stringResource(R.string.search_things), Icons.Outlined.Sell)
                }
                items(things, key = { "thing-${it.label}" }) { thing ->
                    val name = LabelDictionary.displayName(thing.label, locale)
                    ThingTile(name, thing.cover) {
                        onOpen(name, MediaQuery(terms = listOf(SearchTerm(labels = listOf(thing.label)))))
                    }
                }
            }
        }
    }
}

/** Lieux de la rangée « Lieux » de la page d'exploration. */
private const val PLACES_ROW = 40

@Composable
private fun SearchSuggestions(
    text: String,
    searching: Boolean,
    recent: List<RecentSearch>,
    suggestions: List<ChipSuggestion>,
    things: List<ThingSuggestion>,
    people: Map<Long, Person>,
    places: List<PlaceSuggestion>,
    locale: Locale,
    onSearch: (String) -> Unit,
    onSuggestion: (ChipSuggestion) -> Unit,
    onPickThing: (String) -> Unit,
    onRestore: (RecentSearch) -> Unit,
    onForget: (RecentSearch) -> Unit,
) {
    val resources = LocalResources.current
    val key = LabelDictionary.normalize(text)
    val matchingThings = if (key.length < 2) emptyList() else things.filter { thing ->
        val entry = LabelDictionary.entry(thing.label)
        listOfNotNull(entry?.fr, entry?.en, thing.label).any { LabelDictionary.normalize(it).split(' ').any { w -> w.startsWith(key) } } ||
            thing.label in LabelDictionary.match(key)
    }.take(6)

    LazyColumn(Modifier.fillMaxSize()) {
        if (searching) {
            item(key = "searching") { LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
        }
        if (text.isBlank()) {
            items(recent, key = { "recent-${it.key}" }) { entry ->
                ListItem(
                    onClick = { onRestore(entry) },
                    leadingContent = { Icon(Icons.Outlined.History, contentDescription = null) },
                    trailingContent = {
                        IconButton(onClick = { onForget(entry) }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_remove))
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                ) { Text(SearchFilterLabels.entry(resources, entry.filters, people), maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            item(key = "hint") {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(R.string.search_hint, R.string.search_filter_hint, R.string.search_semantic_hint).forEach { hint ->
                        Text(
                            text = stringResource(hint),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            item(key = "search-text") {
                ListItem(
                    onClick = { onSearch(text) },
                    leadingContent = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                ) { Text(stringResource(R.string.search_for, text.trim())) }
            }
            items(suggestions, key = { "chip-${it.chip}" }) { suggestion ->
                ChipSuggestionItem(suggestion, people, places, locale) { onSuggestion(suggestion) }
            }
            items(matchingThings, key = { "t-${it.label}" }) { thing ->
                val name = LabelDictionary.displayName(thing.label, locale)
                ListItem(
                    onClick = { onPickThing(name) },
                    supportingContent = { Text(pluralStringResource(R.plurals.media_count, thing.count, thing.count)) },
                    leadingContent = { SuggestionThumb(thing.cover) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                ) { Text(name) }
            }
        }
    }
}

/** Personne, lieu, période ou type reconnus dans la saisie : un appui en fait une pastille. */
@Composable
private fun ChipSuggestionItem(
    suggestion: ChipSuggestion,
    people: Map<Long, Person>,
    places: List<PlaceSuggestion>,
    locale: Locale,
    onClick: () -> Unit,
) {
    val resources = LocalResources.current
    val chip = suggestion.chip
    val details = when (chip) {
        is SearchChip.Person -> people[chip.id]?.let { person ->
            pluralStringResource(R.plurals.people_photo_count, person.photoCount, person.photoCount)
        }
        is SearchChip.Place -> chip.country?.let { CountryResolver.displayName(it, locale) }
        else -> null
    }
    val kind = stringResource(
        when (chip) {
            is SearchChip.Person -> R.string.search_filter_person
            is SearchChip.Place -> R.string.search_filter_place
            is SearchChip.Period -> R.string.search_filter_period
            is SearchChip.Type -> R.string.search_filter_type
        },
    )
    ListItem(
        onClick = onClick,
        leadingContent = {
            when (chip) {
                is SearchChip.Person -> FaceAvatar(people[chip.id]?.cover, Modifier.size(48.dp))
                is SearchChip.Place -> SuggestionThumb(places.firstOrNull { it.name == chip.name && it.countryCode == chip.country }?.cover)
                else -> SuggestionIcon(chip.icon())
            }
        },
        supportingContent = { Text(listOfNotNull(kind, details).joinToString(" · ")) },
        trailingContent = { Icon(Icons.Rounded.AddCircleOutline, contentDescription = stringResource(R.string.search_filter_add)) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    ) { Text(SearchFilterLabels.chip(resources, chip, people)) }
}

@Composable
internal fun SuggestionThumb(cover: MediaItem?) {
    Box(
        Modifier
            .size(48.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer),
    ) {
        if (cover != null) {
            AsyncImage(model = cover.thumbnail(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SuggestionIcon(icon: ImageVector) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun CategoriesRow(onOpen: (String, MediaQuery) -> Unit) {
    val videos = stringResource(R.string.filter_videos)
    val screenshots = stringResource(R.string.filter_screenshots)
    val gifs = stringResource(R.string.filter_gifs)
    val favorites = stringResource(R.string.collection_favorites)
    val selfies = stringResource(R.string.search_selfies)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        CategoryChip(videos, Icons.Outlined.Videocam) { onOpen(videos, MediaQuery(filter = MediaFilter.VIDEOS)) }
        CategoryChip(selfies, Icons.Outlined.AutoAwesome) {
            onOpen(selfies, MediaQuery(terms = listOf(SearchTerm(labels = listOf("Selfie")))))
        }
        CategoryChip(screenshots, Icons.Outlined.Screenshot) { onOpen(screenshots, MediaQuery(filter = MediaFilter.SCREENSHOTS)) }
        CategoryChip(gifs, Icons.Outlined.Gif) { onOpen(gifs, MediaQuery(filter = MediaFilter.GIFS)) }
        CategoryChip(favorites, Icons.Outlined.Favorite) { onOpen(favorites, MediaQuery(favoritesOnly = true)) }
    }
}

@Composable
private fun CategoryChip(label: String, icon: ImageVector, onClick: () -> Unit) {
    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        shape = MaterialTheme.shapes.large,
    )
}

@Composable
private fun SectionTitle(title: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(title, style = MaterialTheme.typography.headlineSmallEmphasized, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun PlaceCard(place: PlaceSuggestion, locale: Locale, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.width(150.dp).aspectRatio(0.8f),
    ) {
        Box {
            place.cover?.let {
                AsyncImage(model = it.thumbnail(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                Text(place.names.display(locale), style = MaterialTheme.typography.titleMediumEmphasized, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    CountryResolver.displayName(place.countryCode, locale),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ThingTile(name: String, cover: MediaItem?, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            cover?.let {
                AsyncImage(model = it.thumbnail(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
        )
    }
}

@Composable
private fun AnalysisDisabledCard(onEnable: () -> Unit) {
    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Text(stringResource(R.string.search_analysis_off_title), style = MaterialTheme.typography.titleMediumEmphasized)
            Text(
                stringResource(R.string.search_analysis_off_message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
            Button(onClick = onEnable, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.search_analysis_enable)) }
        }
    }
}
