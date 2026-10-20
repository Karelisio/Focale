package app.focale.ui.people

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PersonRemove
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.SwitchAccount
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focale.R
import app.focale.core.model.MediaQuery
import app.focale.people.FaceThumbnail
import app.focale.people.Person
import app.focale.people.ReviewFace
import app.focale.ui.components.EmptyState
import app.focale.ui.components.LoadingState
import app.focale.ui.components.SelectionIndicator
import app.focale.ui.components.SelectionTopBar
import app.focale.ui.navigation.LocalMainChrome
import app.focale.ui.navigation.LocalRootNavController
import app.focale.ui.navigation.RouteCodec
import app.focale.ui.navigation.ViewerRoute
import app.focale.ui.theme.PhotoTheme
import kotlinx.coroutines.launch

/** Revue des visages d'une personne ; le thème suit la photo de sa vignette. */
@Composable
fun FaceReviewScreen(onBack: () -> Unit, viewModel: FaceReviewViewModel = hiltViewModel()) {
    val seed by viewModel.seedColor.collectAsStateWithLifecycle()
    PhotoTheme(seed = seed) {
        FaceReviewHost(viewModel, onBack)
    }
}

/** Feuille ou dialogue ouvert depuis la revue. */
private enum class ReviewSheet { MOVE, NEW_PERSON }

@Composable
private fun FaceReviewHost(viewModel: FaceReviewViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val rootNav = LocalRootNavController.current
    val chrome = LocalMainChrome.current
    val person by viewModel.person.collectAsStateWithLifecycle()
    val faces by viewModel.faces.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val undoLabel = stringResource(R.string.action_undo)
    var sheet by rememberSaveable { mutableStateOf<ReviewSheet?>(null) }

    // Mode sélection : la barre de navigation laisse la place aux actions.
    val selecting = selection.isNotEmpty()
    LaunchedEffect(selecting) {
        chrome.navigationHidden = selecting
        // Sélection perdue (fin du processus) : plus rien à déplacer.
        if (!selecting) sheet = null
    }
    DisposableEffect(Unit) { onDispose { chrome.navigationHidden = false } }

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            launch {
                val result = snackbarHostState.showSnackbar(
                    message = message.text.resolve(context),
                    actionLabel = if (message.undo != null) undoLabel else null,
                    withDismissAction = message.undo == null,
                    duration = if (message.undo != null) SnackbarDuration.Long else SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) message.undo?.invoke()
            }
        }
    }

    when (sheet) {
        ReviewSheet.MOVE -> {
            val others by viewModel.others.collectAsStateWithLifecycle()
            MoveSheet(
                people = others,
                onPick = { target ->
                    sheet = null
                    viewModel.moveSelection(target)
                },
                onNewPerson = { sheet = ReviewSheet.NEW_PERSON },
                onDismiss = { sheet = null },
            )
        }
        ReviewSheet.NEW_PERSON -> NewPersonDialog(
            onConfirm = { name ->
                sheet = null
                viewModel.moveSelectionToNewPerson(name)
            },
            onDismiss = { sheet = null },
        )
        null -> Unit
    }

    // Appui sur un visage : sa photo, parmi celles de la personne.
    val query = remember(viewModel.personId) { RouteCodec.encode(MediaQuery(personId = viewModel.personId)) }
    FaceReviewContent(
        person = person,
        faces = faces,
        selection = selection,
        onSelectionChange = viewModel::setSelection,
        onOpen = { face -> rootNav.navigate(ViewerRoute(query, face.face.mediaId)) { launchSingleTop = true } },
        onSelectAll = viewModel::selectAll,
        onRemove = viewModel::removeSelection,
        onMove = { sheet = ReviewSheet.MOVE },
        onBack = onBack,
        snackbarHostState = snackbarHostState,
    )
}

/**
 * Grille des visages, les moins ressemblants d'abord. Appui long : sélection, puis appuis
 * pour l'étendre ; les actions de la sélection flottent en bas de l'écran.
 */
@Composable
internal fun FaceReviewContent(
    person: Person?,
    faces: List<ReviewFace>?,
    selection: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    onOpen: (ReviewFace) -> Unit,
    onSelectAll: () -> Unit,
    onRemove: () -> Unit,
    onMove: () -> Unit,
    onBack: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val selecting = selection.isNotEmpty()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    BackHandler(enabled = selecting) { onSelectionChange(emptySet()) }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selecting) {
                SelectionTopBar(count = selection.size, onClear = { onSelectionChange(emptySet()) }) {
                    IconButton(onClick = onSelectAll, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.action_select_all))
                    }
                }
            } else {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.people_review_faces)) },
                    subtitle = if (person != null && faces != null) {
                        { Text("${personLabel(person)} · ${pluralStringResource(R.plurals.people_review_count, faces.size, faces.size)}") }
                    } else {
                        null
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            when {
                faces == null -> LoadingState(Modifier.padding(padding))
                faces.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Face,
                    title = stringResource(R.string.people_review_empty),
                    shape = MaterialShapes.Flower,
                    modifier = Modifier.padding(padding),
                )
                else -> FaceGrid(faces, selection, onSelectionChange, onOpen, padding)
            }
            ReviewActionsBar(
                visible = selecting,
                onRemove = onRemove,
                onMove = onMove,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun FaceGrid(
    faces: List<ReviewFace>,
    selection: Set<Long>,
    onSelectionChange: (Set<Long>) -> Unit,
    onOpen: (ReviewFace) -> Unit,
    padding: PaddingValues,
) {
    val haptics = LocalHapticFeedback.current
    val layoutDirection = LocalLayoutDirection.current
    val selecting = selection.isNotEmpty()
    val selectLabel = stringResource(R.string.action_select)
    val openLabel = stringResource(R.string.people_review_open_photo)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 76.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(
            start = padding.calculateStartPadding(layoutDirection) + 16.dp,
            end = padding.calculateEndPadding(layoutDirection) + 16.dp,
            top = padding.calculateTopPadding() + 4.dp,
            bottom = padding.calculateBottomPadding() + if (selecting) 104.dp else 24.dp,
        ),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(key = "hint", span = { GridItemSpan(maxLineSpan) }) {
            Text(
                text = stringResource(R.string.people_review_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 6.dp),
            )
        }
        items(faces, key = { it.face.faceId }) { face ->
            val id = face.face.faceId
            val selected = id in selection
            FaceCell(
                face = face.face,
                selected = selected,
                selecting = selecting,
                onClickLabel = if (selecting) null else openLabel,
                onLongClickLabel = selectLabel,
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSelectionChange(selection + id)
                },
                onClick = {
                    if (selecting) {
                        haptics.performHapticFeedback(if (selected) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                        onSelectionChange(if (selected) selection - id else selection + id)
                    } else {
                        onOpen(face)
                    }
                },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/** Visage rond ; sélectionné, il se resserre dans un anneau et porte une coche. */
@Composable
private fun FaceCell(
    face: FaceThumbnail,
    selected: Boolean,
    selecting: Boolean,
    onClickLabel: String?,
    onLongClickLabel: String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 0.8f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "faceScale",
    )
    val ring = MaterialTheme.colorScheme.primary
    Box(modifier.aspectRatio(1f)) {
        // Cercle entier : cible de l'appui et anneau ; le visage se resserre à l'intérieur.
        Box(
            Modifier
                .fillMaxSize()
                .then(if (selected) Modifier.border(3.dp, ring, CircleShape) else Modifier)
                .clip(CircleShape)
                .combinedClickable(
                    onClickLabel = onClickLabel,
                    onLongClickLabel = onLongClickLabel,
                    onLongClick = onLongClick,
                    onClick = onClick,
                ),
        ) {
            FaceAvatar(
                face = face,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
            )
        }
        if (selecting) SelectionIndicator(selected = selected, modifier = Modifier.align(Alignment.TopStart))
    }
}

/** Actions de la sélection : barre flottante Expressive, libellés visibles. */
@Composable
private fun ReviewActionsBar(visible: Boolean, onRemove: () -> Unit, onMove: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(spring(dampingRatio = Spring.DampingRatioLowBouncy)) { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
            modifier = Modifier
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            ToolbarAction(Icons.Outlined.PersonRemove, stringResource(R.string.people_review_remove), onRemove)
            ToolbarAction(Icons.Outlined.SwitchAccount, stringResource(R.string.people_review_move), onMove)
        }
    }
}

@Composable
private fun ToolbarAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(),
        colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Text(label, modifier = Modifier.padding(start = ButtonDefaults.IconSpacing))
    }
}

/** Choix de la personne qui reçoit les visages : une nouvelle, ou une autre (nommées d'abord). */
@Composable
private fun MoveSheet(people: List<Person>, onPick: (Person) -> Unit, onNewPerson: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = stringResource(R.string.people_review_move),
            style = MaterialTheme.typography.titleLargeEmphasized,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        val count = people.size + 1
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            item(key = "new") {
                SegmentedListItem(
                    onClick = onNewPerson,
                    shapes = ListItemDefaults.segmentedShapes(0, count),
                    leadingContent = {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primaryContainer),
                        ) {
                            Icon(Icons.Outlined.PersonAdd, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    },
                ) {
                    Text(stringResource(R.string.people_review_new_person))
                }
            }
            itemsIndexed(people, key = { _, person -> person.id }) { index, person ->
                SegmentedListItem(
                    onClick = { onPick(person) },
                    shapes = ListItemDefaults.segmentedShapes(index + 1, count),
                    leadingContent = { FaceAvatar(person.cover, Modifier.size(48.dp)) },
                    supportingContent = {
                        Text(pluralStringResource(R.plurals.people_photo_count, person.photoCount, person.photoCount))
                    },
                ) {
                    Text(personLabel(person), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * Nom de la nouvelle personne, obligatoire : une personne sans nom de moins de trois photos
 * n'apparaît dans aucune liste, et ses visages seraient introuvables.
 */
@Composable
private fun NewPersonDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
        title = { Text(stringResource(R.string.people_review_new_person)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.people_name_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.people_review_move_action))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
