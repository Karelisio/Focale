package app.focale.ui.vault

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.RestorePage
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.SettingsBackupRestore
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withResumed
import app.focale.R
import app.focale.core.util.Formatters
import app.focale.ui.components.EmptyState
import app.focale.ui.components.rememberNotificationPermission
import app.focale.ui.components.SelectionIndicator
import app.focale.ui.components.SelectionTopBar
import app.focale.vault.VaultAlbum
import app.focale.vault.VaultArchive
import app.focale.vault.VaultItem
import coil3.compose.AsyncImage
import java.time.LocalDate
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coffre-fort : création du code, déverrouillage (code ou biométrie), puis albums et grille
 * du contenu chiffré avec restauration, suppression, rangement en albums, sauvegarde et
 * restauration d'une sauvegarde. Captures d'écran interdites.
 *
 * @param onUnlocked déverrouillage seul, demandé depuis la galerie : appelé une fois le coffre
 *   ouvert, sans en montrer le contenu.
 */
@Composable
fun VaultScreen(
    onBack: () -> Unit,
    onOpen: (VaultItem) -> Unit,
    onAdd: () -> Unit,
    onOpenAlbum: (VaultAlbum) -> Unit,
    onUnlocked: (() -> Unit)? = null,
    viewModel: VaultViewModel = hiltViewModel(),
    backupViewModel: VaultBackupViewModel = hiltViewModel(),
) {
    SecureWindow()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val changingPin by viewModel.changingPin.collectAsStateWithLifecycle()
    val biometricEnabled by viewModel.biometricEnabled.collectAsStateWithLifecycle()
    val backupRunning by backupViewModel.running.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    var offerBiometric by rememberSaveable { mutableStateOf(false) }
    var autoPrompted by rememberSaveable { mutableStateOf(false) }
    var biometricAvailable by remember { mutableStateOf(canUseBiometrics(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { biometricAvailable = canUseBiometrics(context) }

    val unlockWithBiometrics = rememberBiometricPrompt(viewModel::onBiometricSuccess)
    // L'activation est confirmée par une première authentification réussie.
    val enableBiometrics = rememberBiometricPrompt { viewModel.setBiometricEnabled(true) }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is VaultEvent.Message -> launch { snackbarHostState.showSnackbar(event.text.resolve(context)) }
                VaultEvent.OfferBiometric -> offerBiometric = canUseBiometrics(context)
            }
        }
    }
    LaunchedEffect(backupViewModel) {
        backupViewModel.events.collect { message -> launch { snackbarHostState.showSnackbar(message.resolve(context)) } }
    }

    // Sélecteurs système déclarés hors des volets : leur résultat arrive même si le coffre
    // s'est verrouillé entre-temps.
    val createArchive = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(VaultArchive.MIME_TYPE),
        backupViewModel::onTargetChosen,
    )
    val openArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), backupViewModel::onArchiveChosen)
    val resources = LocalResources.current
    val pickBackupTarget: () -> Unit = {
        try {
            // Nom proposé du jour de l'export, d'où la résolution au moment du clic.
            createArchive.launch(resources.getString(R.string.vault_backup_file_name, LocalDate.now().toString()))
        } catch (error: ActivityNotFoundException) {
            backupViewModel.onPickerUnavailable()
        }
    }
    val pickArchive: () -> Unit = {
        backupViewModel.beforeRestorePicker()
        try {
            // Tous types : selon le fournisseur, une archive `.fvault` n'a pas toujours le même.
            openArchive.launch(arrayOf("*/*"))
        } catch (error: ActivityNotFoundException) {
            backupViewModel.onPickerUnavailable()
        }
    }
    if (backupRunning) KeepScreenOn()

    // Invite biométrique automatique une fois par verrouillage, écran au premier plan.
    LaunchedEffect(mode, biometricEnabled, biometricAvailable) {
        if (mode == VaultMode.UNLOCKED) autoPrompted = false
        val prompt = unlockWithBiometrics ?: return@LaunchedEffect
        if (mode == VaultMode.LOCKED && biometricEnabled && biometricAvailable && !autoPrompted) {
            lifecycle.withResumed {
                autoPrompted = true
                prompt()
            }
        }
    }

    if (offerBiometric) {
        AlertDialog(
            onDismissRequest = { offerBiometric = false },
            icon = { Icon(Icons.Outlined.Fingerprint, contentDescription = null) },
            title = { Text(stringResource(R.string.vault_enable_biometric_title)) },
            text = { Text(stringResource(R.string.vault_enable_biometric_message)) },
            confirmButton = {
                TextButton(onClick = {
                    offerBiometric = false
                    enableBiometrics?.invoke()
                }) { Text(stringResource(R.string.action_enable)) }
            },
            dismissButton = {
                TextButton(onClick = { offerBiometric = false }) { Text(stringResource(R.string.action_not_now)) }
            },
        )
    }

    val currentOnUnlocked by rememberUpdatedState(onUnlocked)
    LaunchedEffect(mode) { if (mode == VaultMode.UNLOCKED) currentOnUnlocked?.invoke() }

    val pane = when {
        mode == VaultMode.LOADING -> VaultPane.LOADING
        mode == VaultMode.UNLOCKED && onUnlocked != null -> VaultPane.LOADING
        mode == VaultMode.UNLOCKED && !changingPin -> VaultPane.CONTENT
        else -> VaultPane.PIN
    }
    AnimatedContent(
        targetState = pane,
        transitionSpec = { (fadeIn(tween(240)) + scaleIn(initialScale = 0.94f)) togetherWith fadeOut(tween(120)) },
        label = "vaultPane",
    ) { current ->
        when (current) {
            VaultPane.LOADING -> Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface))
            VaultPane.PIN -> VaultLockPane(
                viewModel = viewModel,
                onBack = if (changingPin) viewModel::cancelChangePin else onBack,
                onBiometric = unlockWithBiometrics?.takeIf { biometricEnabled && biometricAvailable },
            )
            VaultPane.CONTENT -> VaultContentPane(
                viewModel = viewModel,
                backupViewModel = backupViewModel,
                snackbarHostState = snackbarHostState,
                biometricAvailable = biometricAvailable && enableBiometrics != null,
                onBack = onBack,
                onOpen = onOpen,
                onAdd = onAdd,
                onOpenAlbum = onOpenAlbum,
                onToggleBiometric = { enabled ->
                    if (enabled) enableBiometrics?.invoke() else viewModel.setBiometricEnabled(false)
                },
                onPickBackupTarget = pickBackupTarget,
                onRestoreBackup = pickArchive,
            )
        }
    }
}

private enum class VaultPane { LOADING, PIN, CONTENT }

@Composable
private fun VaultLockPane(viewModel: VaultViewModel, onBack: () -> Unit, onBiometric: (() -> Unit)?) {
    val pin by viewModel.pin.collectAsStateWithLifecycle()
    val lockedUntil by viewModel.lockedUntil.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lockedUntil) {
        while (true) {
            now = System.currentTimeMillis()
            if (now >= lockedUntil) break
            delay(1_000)
        }
    }
    val remaining = lockedUntil - now
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = {
                    IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        val step = pin.step
        PinEntry(
            title = stringResource(step.titleRes()),
            message = stringResource(step.messageRes()),
            length = pin.length,
            error = if (remaining > 0) {
                stringResource(R.string.vault_locked_out, Formatters.duration(remaining))
            } else {
                pin.error?.asString()
            },
            errorTick = pin.errorTick,
            confirmLabel = stringResource(step.confirmRes()),
            enabled = remaining <= 0,
            checking = pin.checking,
            onDigit = viewModel::onDigit,
            onBackspace = viewModel::onBackspace,
            onConfirm = viewModel::onConfirm,
            onBiometric = onBiometric?.takeIf { step == PinStep.UNLOCK },
            modifier = Modifier.padding(padding),
        )
    }
}

private fun PinStep.titleRes(): Int = when (this) {
    PinStep.CREATE -> R.string.vault_setup_title
    PinStep.CONFIRM -> R.string.vault_confirm_title
    PinStep.UNLOCK -> R.string.vault_unlock_title
    PinStep.CURRENT -> R.string.vault_current_pin_title
    PinStep.NEW -> R.string.vault_new_pin_title
    PinStep.CONFIRM_NEW -> R.string.vault_confirm_new_title
}

private fun PinStep.messageRes(): Int = when (this) {
    PinStep.CREATE -> R.string.vault_setup_message
    PinStep.CONFIRM, PinStep.CONFIRM_NEW -> R.string.vault_confirm_message
    PinStep.UNLOCK, PinStep.CURRENT -> R.string.vault_unlock_message
    PinStep.NEW -> R.string.vault_new_pin_message
}

private fun PinStep.confirmRes(): Int = when (this) {
    PinStep.CREATE, PinStep.CURRENT, PinStep.NEW -> R.string.action_continue
    PinStep.CONFIRM -> R.string.vault_create
    PinStep.UNLOCK -> R.string.vault_unlock
    PinStep.CONFIRM_NEW -> R.string.action_save
}

@Composable
private fun VaultContentPane(
    viewModel: VaultViewModel,
    backupViewModel: VaultBackupViewModel,
    snackbarHostState: SnackbarHostState,
    biometricAvailable: Boolean,
    onBack: () -> Unit,
    onOpen: (VaultItem) -> Unit,
    onAdd: () -> Unit,
    onOpenAlbum: (VaultAlbum) -> Unit,
    onToggleBiometric: (Boolean) -> Unit,
    onPickBackupTarget: () -> Unit,
    onRestoreBackup: () -> Unit,
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val biometricEnabled by viewModel.biometricEnabled.collectAsStateWithLifecycle()
    val lastBackupAt by backupViewModel.lastBackupAt.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val albumSort by viewModel.albumSort.collectAsStateWithLifecycle()
    val leftInGallery by viewModel.leftInGallery.collectAsStateWithLifecycle()
    val newMediaAlerts by viewModel.newMediaAlerts.collectAsStateWithLifecycle()
    val requestNotifications = rememberNotificationPermission()
    val selecting = selection.isNotEmpty()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var choosingAlbum by rememberSaveable { mutableStateOf(false) }
    var sorting by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyGridState()
    val fabExpanded by remember { derivedStateOf { gridState.firstVisibleItemIndex == 0 } }
    BackHandler(enabled = selecting, onBack = viewModel::clearSelection)

    if (confirmDelete) {
        VaultDeleteDialog(
            count = selection.size,
            onConfirm = {
                confirmDelete = false
                viewModel.deleteSelection()
            },
            onDismiss = { confirmDelete = false },
        )
    }
    if (choosingAlbum && selecting) {
        VaultAlbumPickerSheet(
            count = selection.size,
            albums = albums,
            showNone = true,
            onPick = {
                choosingAlbum = false
                viewModel.moveSelection(it)
            },
            onDismiss = { choosingAlbum = false },
        )
    }
    if (sorting) {
        VaultSortSheet(
            sort = sort,
            onSortChange = viewModel::setSort,
            albumSort = albumSort,
            onAlbumSortChange = viewModel::setAlbumSort,
            onDismiss = { sorting = false },
        )
    }
    VaultBackupDialogs(viewModel = backupViewModel, onPickTarget = onPickBackupTarget)

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selecting) {
                SelectionTopBar(count = selection.size, onClear = viewModel::clearSelection) {
                    IconButton(onClick = viewModel::selectAll, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.action_select_all))
                    }
                }
            } else {
                LargeFlexibleTopAppBar(
                    title = { Text(stringResource(R.string.vault_title)) },
                    subtitle = {
                        items?.let { Text(pluralStringResource(R.plurals.vault_count, it.size, it.size)) }
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        if (!items.isNullOrEmpty() || albums.isNotEmpty()) VaultSortButton(onClick = { sorting = true })
                        IconButton(onClick = viewModel::lock, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.action_lock))
                        }
                        VaultMenu(
                            canBackup = !items.isNullOrEmpty(),
                            biometricAvailable = biometricAvailable,
                            biometricEnabled = biometricEnabled,
                            newMediaAlerts = newMediaAlerts,
                            onBackup = backupViewModel::requestBackup,
                            onRestoreBackup = onRestoreBackup,
                            onToggleBiometric = onToggleBiometric,
                            onToggleNewMediaAlerts = { enabled ->
                                viewModel.setNewMediaAlerts(enabled)
                                if (enabled) requestNotifications()
                            },
                            onChangePin = viewModel::startChangePin,
                        )
                    },
                    scrollBehavior = scrollBehavior,
                )
            }
        },
        floatingActionButton = {
            if (!selecting) {
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.vault_add)) },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    expanded = fabExpanded,
                    onClick = onAdd,
                )
            }
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        Box(Modifier.fillMaxSize()) {
            val list = items
            when {
                list == null -> Unit
                list.isEmpty() && albums.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Lock,
                    title = stringResource(R.string.vault_empty_title),
                    message = stringResource(R.string.vault_empty_message),
                    shape = MaterialShapes.Gem,
                    modifier = Modifier.padding(padding),
                    action = {
                        FilledTonalButton(onClick = onRestoreBackup, shapes = ButtonDefaults.shapes()) {
                            Icon(
                                imageVector = Icons.Outlined.SettingsBackupRestore,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                            Text(
                                text = stringResource(R.string.vault_restore_action),
                                modifier = Modifier.padding(start = ButtonDefaults.IconSpacing),
                            )
                        }
                    },
                )
                else -> LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Adaptive(minSize = 110.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                    contentPadding = PaddingValues(
                        start = padding.calculateStartPadding(layoutDirection),
                        end = padding.calculateEndPadding(layoutDirection),
                        top = padding.calculateTopPadding(),
                        bottom = padding.calculateBottomPadding() + 104.dp,
                    ),
                ) {
                    if (leftInGallery > 0) {
                        item(key = "left", span = { GridItemSpan(maxLineSpan) }, contentType = "left") {
                            LeftInGalleryBanner(count = leftInGallery, enabled = !busy, onRemove = viewModel::removeLeftOriginals)
                        }
                    }
                    lastBackupAt?.let { at ->
                        item(key = "backup", span = { GridItemSpan(maxLineSpan) }, contentType = "backup") {
                            BackupStatus(lastBackupAt = at, onBackup = backupViewModel::requestBackup)
                        }
                    }
                    if (busy) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                        }
                    }
                    if (albums.isNotEmpty()) {
                        item(key = "albums", span = { GridItemSpan(maxLineSpan) }, contentType = "albums") {
                            VaultAlbumsShelf(albums, onOpen = onOpenAlbum)
                        }
                        item(key = "all", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                            VaultSectionHeader(stringResource(R.string.vault_all_items))
                        }
                    }
                    vaultItems(list, selection, onToggle = viewModel::toggle, onOpen = onOpen)
                }
            }

            if (selecting) {
                VaultSelectionToolbar(
                    busy = busy,
                    onRestore = viewModel::restoreSelection,
                    onMoveToAlbum = { choosingAlbum = true },
                    onDelete = { confirmDelete = true },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/** Cases des éléments [items] : un appui ouvre ou, en mode sélection, coche ; l'appui long sélectionne. */
internal fun LazyGridScope.vaultItems(
    items: List<VaultItem>,
    selection: Set<Long>,
    onToggle: (Long) -> Unit,
    onOpen: (VaultItem) -> Unit,
) {
    val selecting = selection.isNotEmpty()
    items(items, key = { it.id }) { item ->
        val haptics = LocalHapticFeedback.current
        val selected = item.id in selection
        VaultCell(
            item = item,
            selected = selected,
            selectionMode = selecting,
            onClick = {
                if (selecting) {
                    haptics.performHapticFeedback(if (selected) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                    onToggle(item.id)
                } else {
                    onOpen(item)
                }
            },
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onToggle(item.id)
            },
        )
    }
}

/** Barre flottante du mode sélection : restaurer, ranger dans un album, supprimer. */
@Composable
internal fun VaultSelectionToolbar(
    busy: Boolean,
    onRestore: () -> Unit,
    onMoveToAlbum: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HorizontalFloatingToolbar(
        expanded = true,
        colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
        modifier = modifier
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
    ) {
        TextButton(onClick = onRestore, enabled = !busy) {
            Icon(Icons.Outlined.RestorePage, contentDescription = null)
            Text(stringResource(R.string.action_restore), modifier = Modifier.padding(start = 8.dp))
        }
        IconButton(onClick = onMoveToAlbum, enabled = !busy, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.AutoMirrored.Outlined.DriveFileMove, contentDescription = stringResource(R.string.vault_move_to_album))
        }
        TextButton(onClick = onDelete, enabled = !busy) {
            Icon(Icons.Outlined.DeleteForever, contentDescription = null)
            Text(stringResource(R.string.action_delete), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

/** Confirmation de la suppression définitive de [count] éléments du coffre. */
@Composable
internal fun VaultDeleteDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null) },
        title = { Text(pluralStringResource(R.plurals.trash_delete_confirm, count, count)) },
        text = { Text(stringResource(R.string.vault_delete_message)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Sauvegarde et réglages propres au coffre, accessibles seulement une fois déverrouillé. */
@Composable
private fun VaultMenu(
    canBackup: Boolean,
    biometricAvailable: Boolean,
    biometricEnabled: Boolean,
    newMediaAlerts: Boolean,
    onBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onToggleBiometric: (Boolean) -> Unit,
    onToggleNewMediaAlerts: (Boolean) -> Unit,
    onChangePin: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vault_backup_action)) },
                leadingIcon = { Icon(Icons.Outlined.Backup, contentDescription = null) },
                enabled = canBackup,
                onClick = {
                    open = false
                    onBackup()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vault_restore_action)) },
                leadingIcon = { Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = null) },
                onClick = {
                    open = false
                    onRestoreBackup()
                },
            )
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vault_change_pin)) },
                leadingIcon = { Icon(Icons.Outlined.Password, contentDescription = null) },
                onClick = {
                    open = false
                    onChangePin()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(
                            if (biometricAvailable || biometricEnabled) R.string.vault_biometric else R.string.vault_biometric_unavailable,
                        ),
                    )
                },
                leadingIcon = { Icon(Icons.Outlined.Fingerprint, contentDescription = null) },
                trailingIcon = if (biometricEnabled) {
                    { Icon(Icons.Rounded.Check, contentDescription = null) }
                } else {
                    null
                },
                enabled = biometricAvailable || biometricEnabled,
                onClick = {
                    open = false
                    onToggleBiometric(!biometricEnabled)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.vault_notify_setting)) },
                leadingIcon = { Icon(Icons.Outlined.NotificationsActive, contentDescription = null) },
                trailingIcon = if (newMediaAlerts) {
                    { Icon(Icons.Rounded.Check, contentDescription = null) }
                } else {
                    null
                },
                onClick = {
                    open = false
                    onToggleNewMediaAlerts(!newMediaAlerts)
                },
            )
        }
    }
}

@Composable
private fun VaultCell(
    item: VaultItem,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val scale by animateFloatAsState(
        targetValue = if (selected) 0.86f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "vaultScale",
    )
    val corner by animateDpAsState(
        targetValue = if (selected) 24.dp else 10.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy),
        label = "vaultCorner",
    )
    Box(
        Modifier
            .aspectRatio(1f)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(RoundedCornerShape(corner))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            val thumbName = item.thumbFileName
            if (thumbName != null) {
                AsyncImage(
                    model = rememberVaultThumbnail(thumbName),
                    contentDescription = item.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(Icons.Rounded.Image, contentDescription = item.displayName, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (item.isVideo) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(5.dp)
                        .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                    Text(Formatters.duration(item.durationMs), style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
        if (selectionMode) {
            SelectionIndicator(selected = selected, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
        }
    }
}
