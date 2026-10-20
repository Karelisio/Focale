package app.focale.ui.people

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Merge
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.PersonSearch
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import app.focale.R
import app.focale.people.FaceThumbnail
import app.focale.people.Person
import app.focale.ui.actions.ExtraAction
import app.focale.ui.actions.NameDialog
import app.focale.ui.components.EmptyState
import app.focale.ui.components.MediaGridScaffold
import app.focale.ui.components.SortSheet
import app.focale.ui.navigation.LocalRootNavController
import app.focale.ui.navigation.LocalTabsNavController
import app.focale.ui.navigation.PersonFacesRoute
import app.focale.ui.navigation.PersonRoute
import app.focale.ui.navigation.openCollection
import app.focale.ui.navigation.openSlideshow
import app.focale.ui.theme.PhotoTheme
import kotlinx.coroutines.launch

/** Photos d'une personne ; le thème suit la photo de sa vignette. */
@Composable
fun PersonScreen(onBack: () -> Unit, viewModel: PersonViewModel = hiltViewModel()) {
    val seed by viewModel.seedColor.collectAsStateWithLifecycle()
    PhotoTheme(seed = seed) {
        PersonContent(viewModel, onBack)
    }
}

/** Dialogue ou feuille ouvert depuis l'écran d'une personne. */
private enum class PersonSheet { NAME, COVER, MERGE, TOGETHER }

@Composable
private fun PersonContent(viewModel: PersonViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val rootNav = LocalRootNavController.current
    val tabsNav = LocalTabsNavController.current
    val items = viewModel.items.collectAsLazyPagingItems()
    val person by viewModel.person.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val columns by viewModel.columns.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val count by viewModel.count.collectAsStateWithLifecycle()
    val histogram by viewModel.histogram.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.action_undo)

    var sheet by rememberSaveable { mutableStateOf<PersonSheet?>(null) }
    var showSort by rememberSaveable { mutableStateOf(false) }
    var mergeTargetId by rememberSaveable { mutableStateOf<Long?>(null) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is PersonEvent.Message -> launch {
                    val result = snackbarHostState.showSnackbar(
                        message = event.text.resolve(context),
                        actionLabel = if (event.undo != null) undoLabel else null,
                        withDismissAction = event.undo == null,
                        duration = if (event.undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
                    )
                    if (result == SnackbarResult.ActionPerformed) event.undo?.invoke()
                }
                is PersonEvent.Merged -> tabsNav.navigate(PersonRoute(event.personId)) {
                    popUpTo<PersonRoute> { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    if (showSort) {
        SortSheet(
            sort = sort,
            onSortChange = viewModel::setSort,
            filter = filter,
            onFilterChange = viewModel::setFilter,
            onDismiss = { showSort = false },
        )
    }
    val current = person
    when (sheet) {
        PersonSheet.NAME -> NameDialog(
            title = stringResource(R.string.people_name_title),
            label = stringResource(R.string.people_name_label),
            confirm = stringResource(R.string.action_save),
            initial = current?.name.orEmpty(),
            onConfirm = viewModel::rename,
            onDismiss = { sheet = null },
        )
        PersonSheet.COVER -> {
            LaunchedEffect(Unit) { viewModel.loadFaces() }
            val faces by viewModel.faces.collectAsStateWithLifecycle()
            CoverSheet(
                faces = faces,
                currentFaceId = current?.cover?.faceId,
                onPick = { face ->
                    sheet = null
                    viewModel.setCover(face.faceId)
                },
                onDismiss = { sheet = null },
            )
        }
        PersonSheet.MERGE -> {
            val others by viewModel.others.collectAsStateWithLifecycle()
            MergeSheet(
                people = others,
                onPick = { target ->
                    sheet = null
                    mergeTargetId = target.id
                },
                onDismiss = { sheet = null },
            )
        }
        PersonSheet.TOGETHER -> if (current != null) {
            val candidates by viewModel.together.collectAsStateWithLifecycle()
            val chosen by viewModel.togetherSelection.collectAsStateWithLifecycle()
            val togetherCount by viewModel.togetherCount.collectAsStateWithLifecycle()
            TogetherSheet(
                person = current,
                candidates = candidates,
                selection = chosen,
                onSelectionChange = viewModel::setTogetherSelection,
                photoCount = togetherCount,
                onShow = {
                    val others = candidates.orEmpty().map { it.person }.filter { it.id in chosen }
                    sheet = null
                    tabsNav.openCollection(togetherTitle(resources, listOf(current) + others), viewModel.togetherQuery())
                    viewModel.setTogetherSelection(emptySet())
                },
                onDismiss = {
                    sheet = null
                    viewModel.setTogetherSelection(emptySet())
                },
            )
        }
        null -> Unit
    }
    mergeTargetId?.let { targetId ->
        val others by viewModel.others.collectAsStateWithLifecycle()
        val target = others.firstOrNull { it.id == targetId }
        if (current != null && target != null) {
            MergeDialog(
                source = current,
                target = target,
                onConfirm = {
                    mergeTargetId = null
                    viewModel.mergeInto(targetId)
                },
                onDismiss = { mergeTargetId = null },
            )
        }
    }

    val extraActions = buildList {
        add(ExtraAction(stringResource(R.string.people_remove_from_person), Icons.Outlined.PersonRemove, viewModel::removeSelection))
        if (selection.size == 1) {
            add(ExtraAction(stringResource(R.string.people_set_cover), Icons.Outlined.Image, viewModel::setSelectionAsCover))
        }
    }

    MediaGridScaffold(
        items = items,
        query = query,
        columns = columns,
        selection = selection,
        onSelectionChange = viewModel::setSelection,
        onClearSelection = viewModel::clearSelection,
        onSelectAll = viewModel::selectAll,
        actions = viewModel.actions,
        onColumnsChange = viewModel::setColumns,
        histogram = if (sort.isChronological) histogram else emptyList(),
        gridIndexOf = viewModel::gridIndexOf,
        extraSelectionActions = extraActions,
        snackbarHostState = snackbarHostState,
        leadingItemCount = 1,
        leadingContent = {
            item(key = "person-header", span = { GridItemSpan(maxLineSpan) }) {
                PersonHeader(
                    person = current,
                    onCover = { sheet = PersonSheet.COVER },
                    onName = { sheet = PersonSheet.NAME },
                )
            }
        },
        topBar = { scrollBehavior ->
            LargeFlexibleTopAppBar(
                title = {
                    Text(
                        text = current?.let { personLabel(it) }.orEmpty(),
                        modifier = Modifier.clickable(onClickLabel = stringResource(R.string.people_edit_name)) {
                            sheet = PersonSheet.NAME
                        },
                    )
                },
                subtitle = if (count >= 0) {
                    { Text(pluralStringResource(R.plurals.people_photo_count, count, count)) }
                } else {
                    null
                },
                navigationIcon = {
                    IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (count > 0) {
                        IconButton(onClick = { rootNav.openSlideshow(query) }, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Outlined.Slideshow, contentDescription = stringResource(R.string.action_slideshow))
                        }
                    }
                    IconButton(onClick = { showSort = true }, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Tune, contentDescription = stringResource(R.string.sort_title))
                    }
                    if (current != null) {
                        PersonMenu(
                            person = current,
                            onTogether = { sheet = PersonSheet.TOGETHER },
                            onEditName = { sheet = PersonSheet.NAME },
                            onRemoveName = { viewModel.rename(null) },
                            onChooseCover = { sheet = PersonSheet.COVER },
                            onReviewFaces = { tabsNav.navigate(PersonFacesRoute(viewModel.personId)) { launchSingleTop = true } },
                            onMerge = { sheet = PersonSheet.MERGE },
                            onHiddenChange = viewModel::setHidden,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        emptyContent = {
            EmptyState(
                icon = Icons.Outlined.Face,
                title = stringResource(R.string.people_person_empty),
                shape = MaterialShapes.Flower,
            )
        },
    )
}

/** En-tête : grande vignette (appui : choisir la vignette) et « Ajouter un nom ». */
@Composable
private fun PersonHeader(person: Person?, onCover: () -> Unit, onName: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 16.dp),
    ) {
        FaceAvatar(
            face = person?.cover,
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .clickable(onClickLabel = stringResource(R.string.people_choose_cover), onClick = onCover),
        )
        if (person != null && person.name == null) {
            FilledTonalButton(
                onClick = onName,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.padding(top = 16.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Text(stringResource(R.string.people_add_name), modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
            }
        }
    }
}

@Composable
private fun PersonMenu(
    person: Person,
    onTogether: () -> Unit,
    onEditName: () -> Unit,
    onRemoveName: () -> Unit,
    onChooseCover: () -> Unit,
    onReviewFaces: () -> Unit,
    onMerge: () -> Unit,
    onHiddenChange: (Boolean) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    fun pick(action: () -> Unit) {
        open = false
        action()
    }
    Box {
        IconButton(onClick = { open = true }, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.people_together)) },
                leadingIcon = { Icon(Icons.Outlined.Groups, contentDescription = null) },
                onClick = { pick(onTogether) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (person.name == null) R.string.people_add_name else R.string.people_edit_name)) },
                leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null) },
                onClick = { pick(onEditName) },
            )
            if (person.name != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.people_remove_name)) },
                    leadingIcon = { Icon(Icons.Outlined.AccountCircle, contentDescription = null) },
                    onClick = { pick(onRemoveName) },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.people_choose_cover)) },
                leadingIcon = { Icon(Icons.Outlined.Face, contentDescription = null) },
                onClick = { pick(onChooseCover) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.people_review_faces)) },
                leadingIcon = { Icon(Icons.Outlined.PersonSearch, contentDescription = null) },
                onClick = { pick(onReviewFaces) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.people_merge)) },
                leadingIcon = { Icon(Icons.Outlined.Merge, contentDescription = null) },
                onClick = { pick(onMerge) },
            )
            DropdownMenuItem(
                text = { Text(stringResource(if (person.hidden) R.string.people_unhide else R.string.people_hide)) },
                leadingIcon = {
                    Icon(if (person.hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, contentDescription = null)
                },
                onClick = { pick { onHiddenChange(!person.hidden) } },
            )
        }
    }
}

/** Choix de la vignette parmi les visages de la personne, les meilleurs d'abord. */
@Composable
private fun CoverSheet(
    faces: List<FaceThumbnail>?,
    currentFaceId: Long?,
    onPick: (FaceThumbnail) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.people_cover_title),
            style = MaterialTheme.typography.titleLargeEmphasized,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (faces == null) {
            Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) { LoadingIndicator() }
            return@ModalBottomSheet
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 76.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            items(faces, key = { it.faceId }) { face ->
                val selected = face.faceId == currentFaceId
                FaceAvatar(
                    face = face,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .then(
                            if (selected) {
                                Modifier.border(BorderStroke(3.dp, MaterialTheme.colorScheme.primary), CircleShape)
                            } else {
                                Modifier
                            },
                        )
                        .clip(CircleShape)
                        .clickable(onClickLabel = stringResource(R.string.people_set_cover)) { onPick(face) },
                )
            }
        }
    }
}

/** Choix de la personne à laquelle rattacher celle-ci. */
@Composable
private fun MergeSheet(people: List<Person>, onPick: (Person) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.people_merge_title),
            style = MaterialTheme.typography.titleLargeEmphasized,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (people.isEmpty()) {
            Text(
                text = stringResource(R.string.people_merge_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(24.dp),
            )
            return@ModalBottomSheet
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            itemsIndexed(people, key = { _, person -> person.id }) { index, person ->
                SegmentedListItem(
                    onClick = { onPick(person) },
                    shapes = ListItemDefaults.segmentedShapes(index, people.size),
                    leadingContent = { FaceAvatar(person.cover, Modifier.size(48.dp)) },
                    supportingContent = {
                        Text(pluralStringResource(R.plurals.people_photo_count, person.photoCount, person.photoCount))
                    },
                ) {
                    PersonName(person, centered = false)
                }
            }
        }
    }
}

@Composable
private fun MergeDialog(source: Person, target: Person, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Merge, contentDescription = null) },
        title = { Text(stringResource(R.string.people_merge_confirm_title)) },
        text = { Text(stringResource(R.string.people_merge_confirm_message, personLabel(source), personLabel(target))) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.people_merge_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
