package app.focale.ui.search

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
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focale.R
import app.focale.core.image.thumbnail
import app.focale.core.model.MediaFilter
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaQuery
import app.focale.core.model.SearchTerm
import app.focale.core.permissions.MediaAccess
import app.focale.data.PlaceSuggestion
import app.focale.data.ThingSuggestion
import app.focale.ml.CountryResolver
import app.focale.ml.LabelDictionary
import app.focale.people.Person
import app.focale.ui.analysis.AnalysisStatusCard
import app.focale.ui.components.MediaPermissionScreen
import app.focale.ui.navigation.CollectionKind
import app.focale.ui.navigation.LocalTabsNavController
import app.focale.ui.navigation.PersonRoute
import app.focale.ui.navigation.openCollection
import app.focale.ui.people.PeopleRow
import app.focale.ui.people.matchingPeople
import app.focale.ui.people.peopleSuggestions
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/** Onglet Recherche : barre de recherche M3, lieux, choses, catégories, nettoyage. */
@Composable
fun SearchScreen(viewModel: SearchViewModel = hiltViewModel()) {
    val access by viewModel.access.collectAsStateWithLifecycle()
    if (access == MediaAccess.DENIED) {
        MediaPermissionScreen(onAccessChanged = {})
        return
    }
    val tabsNav = LocalTabsNavController.current
    val things by viewModel.things.collectAsStateWithLifecycle()
    val places by viewModel.places.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val analysisStatus by viewModel.analysisStatus.collectAsStateWithLifecycle()
    val analysisEnabled by viewModel.analysisEnabled.collectAsStateWithLifecycle()
    val searching by viewModel.searching.collectAsStateWithLifecycle()
    val locale = LocalConfiguration.current.locales[0]

    val searchBarState = rememberSearchBarState()
    val textFieldState = rememberTextFieldState()
    val scope = rememberCoroutineScope()
    val scrollBehavior = SearchBarDefaults.enterAlwaysSearchBarScrollBehavior()

    fun open(title: String, query: MediaQuery) {
        scope.launch { searchBarState.animateToCollapsed() }
        tabsNav.openCollection(title, query, CollectionKind.QUERY)
    }

    fun openPerson(person: Person) {
        scope.launch { searchBarState.animateToCollapsed() }
        tabsNav.navigate(PersonRoute(person.id)) { launchSingleTop = true }
    }

    fun submit(text: String) = viewModel.submit(text)

    // Requêtes validées, complétées par la recherche par le sens.
    LaunchedEffect(viewModel) {
        viewModel.results.collect { result -> open(result.title, result.query) }
    }
    DisposableEffect(viewModel) {
        onDispose { viewModel.cancelPending() }
    }
    LaunchedEffect(textFieldState) {
        snapshotFlow { textFieldState.text.toString() }.collect(viewModel::onTextChange)
    }
    LaunchedEffect(searchBarState.currentValue) {
        if (searchBarState.currentValue == SearchBarValue.Expanded) viewModel.warmUp()
    }

    val inputField = @Composable {
        SearchBarDefaults.InputField(
            textFieldState = textFieldState,
            searchBarState = searchBarState,
            onSearch = ::submit,
            placeholder = { Text(stringResource(R.string.search_placeholder)) },
            leadingIcon = {
                if (searchBarState.currentValue == SearchBarValue.Expanded) {
                    IconButton(onClick = { scope.launch { searchBarState.animateToCollapsed() } }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                } else {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                }
            },
            trailingIcon = {
                if (textFieldState.text.isNotEmpty()) {
                    IconButton(onClick = { textFieldState.setTextAndPlaceCursorAtEnd("") }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_clear))
                    }
                }
            },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            AppBarWithSearch(
                state = searchBarState,
                inputField = inputField,
                scrollBehavior = scrollBehavior,
            )
        },
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
                CategoriesRow(onOpen = ::open)
            }

            if (people.isNotEmpty()) {
                item(key = "people-header", span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle(stringResource(R.string.people_title), Icons.Outlined.Face)
                }
                item(key = "people", span = { GridItemSpan(maxLineSpan) }) { PeopleRow(people, onOpen = ::openPerson) }
            }

            if (places.isNotEmpty()) {
                item(key = "places-header", span = { GridItemSpan(maxLineSpan) }) {
                    SectionTitle(stringResource(R.string.search_places), Icons.Outlined.LocationOn)
                }
                item(key = "places", span = { GridItemSpan(maxLineSpan) }) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(places, key = { "${it.name}-${it.countryCode}" }) { place ->
                            PlaceCard(place, locale) {
                                open(
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
                        open(name, MediaQuery(terms = listOf(SearchTerm(labels = listOf(thing.label)))))
                    }
                }
            }
        }
    }

    ExpandedFullScreenSearchBar(state = searchBarState, inputField = inputField) {
        val text by remember { snapshotFlow { textFieldState.text.toString() } }.collectAsState(initial = "")
        SearchSuggestions(
            text = text,
            searching = searching,
            recent = recent,
            things = things,
            places = places,
            people = people,
            locale = locale,
            onSearch = ::submit,
            onPickPerson = ::openPerson,
            onPickThing = { thing, name -> open(name, MediaQuery(terms = listOf(SearchTerm(labels = listOf(thing.label))))) },
            onPickPlace = { place ->
                open(
                    place.names.display(locale),
                    MediaQuery(
                        terms = listOf(
                            SearchTerm(place = LabelDictionary.normalize(place.name), exactPlace = true),
                            SearchTerm(countries = listOf(place.countryCode)),
                        ),
                    ),
                )
            },
            onForget = viewModel::forget,
            onFill = { textFieldState.setTextAndPlaceCursorAtEnd(it) },
        )
    }
}

@Composable
private fun SearchSuggestions(
    text: String,
    searching: Boolean,
    recent: List<String>,
    things: List<ThingSuggestion>,
    places: List<PlaceSuggestion>,
    people: List<Person>,
    locale: java.util.Locale,
    onSearch: (String) -> Unit,
    onPickPerson: (Person) -> Unit,
    onPickThing: (ThingSuggestion, String) -> Unit,
    onPickPlace: (PlaceSuggestion) -> Unit,
    onForget: (String) -> Unit,
    onFill: (String) -> Unit,
) {
    val key = LabelDictionary.normalize(text)
    val matchingThings = if (key.length < 2) emptyList() else things.filter { thing ->
        val entry = LabelDictionary.entry(thing.label)
        listOfNotNull(entry?.fr, entry?.en, thing.label).any { LabelDictionary.normalize(it).split(' ').any { w -> w.startsWith(key) } } ||
            thing.label in LabelDictionary.match(key)
    }.take(6)
    val matchingPlaces = if (key.length < 2) emptyList() else places.filter { place ->
        // Nom GeoNames, français ou anglais : « lisb » trouve Lisbon / Lisbonne.
        place.names.forms.any { name -> LabelDictionary.normalize(name).split(' ').any { it.startsWith(key) } } ||
            LabelDictionary.normalize(CountryResolver.displayName(place.countryCode, locale)).startsWith(key)
    }.take(6)

    LazyColumn(Modifier.fillMaxSize()) {
        if (searching) {
            item(key = "searching") { LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) }
        }
        if (text.isBlank()) {
            items(recent, key = { "recent-$it" }) { entry ->
                ListItem(
                    onClick = {
                        onFill(entry)
                        onSearch(entry)
                    },
                    leadingContent = { Icon(Icons.Outlined.History, contentDescription = null) },
                    trailingContent = {
                        IconButton(onClick = { onForget(entry) }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_remove))
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                ) { Text(entry) }
            }
            item(key = "hint") {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.search_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.search_semantic_hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
            peopleSuggestions(matchingPeople(people, key), onPickPerson)
            items(matchingThings, key = { "t-${it.label}" }) { thing ->
                val name = LabelDictionary.displayName(thing.label, locale)
                ListItem(
                    onClick = { onPickThing(thing, name) },
                    supportingContent = { Text(pluralStringResource(R.plurals.media_count, thing.count, thing.count)) },
                    leadingContent = { SuggestionThumb(thing.cover) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                ) { Text(name) }
            }
            items(matchingPlaces, key = { "p-${it.name}-${it.countryCode}" }) { place ->
                ListItem(
                    onClick = { onPickPlace(place) },
                    supportingContent = { Text(CountryResolver.displayName(place.countryCode, locale)) },
                    leadingContent = { SuggestionThumb(place.cover) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                ) { Text(place.names.display(locale)) }
            }
        }
    }
}

@Composable
private fun SuggestionThumb(cover: MediaItem?) {
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
private fun PlaceCard(place: PlaceSuggestion, locale: java.util.Locale, onClick: () -> Unit) {
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
