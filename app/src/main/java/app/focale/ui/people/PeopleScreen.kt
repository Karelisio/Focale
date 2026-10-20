package app.focale.ui.people

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focale.R
import app.focale.core.settings.PeopleSort
import app.focale.people.Person
import app.focale.ui.components.EmptyState
import app.focale.ui.navigation.LocalTabsNavController
import app.focale.ui.navigation.PersonRoute

/** Toutes les personnes reconnues, en grille de vignettes rondes. */
@Composable
fun PeopleScreen(onBack: () -> Unit, viewModel: PeopleViewModel = hiltViewModel()) {
    val tabsNav = LocalTabsNavController.current
    val people by viewModel.people.collectAsStateWithLifecycle()
    val suggestion by viewModel.suggestion.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val showHidden by viewModel.showHidden.collectAsStateWithLifecycle()
    val analysisEnabled by viewModel.analysisEnabled.collectAsStateWithLifecycle()
    PeopleContent(
        people = people,
        suggestion = suggestion,
        sort = sort,
        showHidden = showHidden,
        analysisEnabled = analysisEnabled,
        onBack = onBack,
        onOpen = { tabsNav.navigate(PersonRoute(it.id)) { launchSingleTop = true } },
        onSortChange = viewModel::setSort,
        onShowHiddenChange = viewModel::setShowHidden,
        onPinnedChange = viewModel::setPinned,
        onMerge = viewModel::merge,
        onRefuse = viewModel::refuse,
        onEnableAnalysis = viewModel::enableAnalysis,
    )
}

/**
 * Contenu de l'écran Personnes, sans état : suggestion de fusion en tête, puis la grille.
 * Appui long sur une personne : l'épingler ou la désépingler.
 */
@Composable
internal fun PeopleContent(
    people: List<Person>?,
    suggestion: MergeProposal?,
    sort: PeopleSort,
    showHidden: Boolean,
    analysisEnabled: Boolean,
    onBack: () -> Unit,
    onOpen: (Person) -> Unit,
    onSortChange: (PeopleSort) -> Unit,
    onShowHiddenChange: (Boolean) -> Unit,
    onPinnedChange: (Person, Boolean) -> Unit,
    onMerge: (MergeProposal) -> Unit,
    onRefuse: (MergeProposal) -> Unit,
    onEnableAnalysis: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val haptics = LocalHapticFeedback.current
    var menuPersonId by rememberSaveable { mutableStateOf<Long?>(null) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.people_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    SortMenu(sort = sort, onSortChange = onSortChange)
                    PeopleMenu(showHidden = showHidden, onShowHiddenChange = onShowHiddenChange)
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        val list = people ?: return@Scaffold
        if (list.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Outlined.Face,
                    title = stringResource(R.string.people_empty_title),
                    message = stringResource(if (analysisEnabled) R.string.people_empty_message else R.string.people_analysis_off_message),
                    shape = MaterialShapes.Flower,
                    action = if (analysisEnabled) {
                        null
                    } else {
                        {
                            Button(onClick = onEnableAnalysis, shapes = ButtonDefaults.shapes()) {
                                Text(stringResource(R.string.search_analysis_enable))
                            }
                        }
                    },
                )
            }
            return@Scaffold
        }
        val layoutDirection = LocalLayoutDirection.current
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection) + 12.dp,
                end = padding.calculateEndPadding(layoutDirection) + 12.dp,
                top = padding.calculateTopPadding() + 8.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (suggestion != null) {
                item(key = "merge-suggestion", span = { GridItemSpan(maxLineSpan) }, contentType = "suggestion") {
                    AnimatedContent(
                        targetState = suggestion,
                        contentKey = { it.target.id to it.source.id },
                        label = "suggestion",
                        modifier = Modifier.animateItem().padding(bottom = 8.dp),
                    ) { shown ->
                        MergeSuggestionCard(
                            target = shown.target,
                            source = shown.source,
                            onMerge = { onMerge(shown) },
                            onDifferent = { onRefuse(shown) },
                            onOpen = onOpen,
                        )
                    }
                }
            }
            items(list, key = { it.id }) { person ->
                val pinLabel = stringResource(if (person.pinned) R.string.people_unpin else R.string.people_pin)
                Box(Modifier.animateItem()) {
                    PersonGridTile(
                        person = person,
                        onClick = { onOpen(person) },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuPersonId = person.id
                        },
                        onLongClickLabel = pinLabel,
                    )
                    DropdownMenu(expanded = menuPersonId == person.id, onDismissRequest = { menuPersonId = null }) {
                        DropdownMenuItem(
                            text = { Text(pinLabel) },
                            leadingIcon = { Icon(Icons.Outlined.PushPin, contentDescription = null) },
                            onClick = {
                                menuPersonId = null
                                onPinnedChange(person, !person.pinned)
                            },
                        )
                    }
                }
            }
        }
    }
}

/** Choix de l'ordre de la liste ; les personnes épinglées restent en tête. */
@Composable
private fun SortMenu(sort: PeopleSort, onSortChange: (PeopleSort) -> Unit) {
    val options = listOf(
        Triple(PeopleSort.PHOTOS, R.string.people_sort_photos, Icons.Outlined.PhotoLibrary),
        Triple(PeopleSort.NAME, R.string.people_sort_name, Icons.Outlined.SortByAlpha),
        Triple(PeopleSort.RECENT, R.string.people_sort_recent, Icons.Outlined.History),
    )
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.AutoMirrored.Rounded.Sort, contentDescription = stringResource(R.string.people_sort))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (option, label, icon) ->
                val selected = option == sort
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    leadingIcon = { Icon(icon, contentDescription = null) },
                    trailingIcon = if (selected) {
                        { Icon(Icons.Rounded.Check, contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        onSortChange(option)
                    },
                    modifier = Modifier.semantics { this.selected = selected },
                )
            }
        }
    }
}

@Composable
private fun PeopleMenu(showHidden: Boolean, onShowHiddenChange: (Boolean) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.people_show_hidden)) },
                trailingIcon = { Checkbox(checked = showHidden, onCheckedChange = null) },
                onClick = {
                    open = false
                    onShowHiddenChange(!showHidden)
                },
            )
        }
    }
}
