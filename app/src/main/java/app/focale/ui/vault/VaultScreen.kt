package app.focale.ui.vault

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.RestorePage
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingToolbarDefaults
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
import app.focale.ui.components.SelectionIndicator
import app.focale.ui.components.SelectionTopBar
import app.focale.vault.VaultImage
import app.focale.vault.VaultItem
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Coffre-fort : création du code, déverrouillage (code ou biométrie), puis grille du
 * contenu chiffré avec restauration et suppression. Captures d'écran interdites.
 */
@Composable
fun VaultScreen(
    onBack: () -> Unit,
    onOpen: (VaultItem) -> Unit,
    onAdd: () -> Unit,
    viewModel: VaultViewModel = hiltViewModel(),
) {
    SecureWindow()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val changingPin by viewModel.changingPin.collectAsStateWithLifecycle()
    val biometricEnabled by viewModel.biometricEnabled.collectAsStateWithLifecycle()
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

    val pane = when {
        mode == VaultMode.LOADING -> VaultPane.LOADING
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
                snackbarHostState = snackbarHostState,
                biometricAvailable = biometricAvailable && enableBiometrics != null,
                onBack = onBack,
                onOpen = onOpen,
                onAdd = onAdd,
                onToggleBiometric = { enabled ->
                    if (enabled) enableBiometrics?.invoke() else viewModel.setBiometricEnabled(false)
                },
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
    snackbarHostState: SnackbarHostState,
    biometricAvailable: Boolean,
    onBack: () -> Unit,
    onOpen: (VaultItem) -> Unit,
    onAdd: () -> Unit,
    onToggleBiometric: (Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val items by viewModel.items.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val biometricEnabled by viewModel.biometricEnabled.collectAsStateWithLifecycle()
    val selecting = selection.isNotEmpty()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyGridState()
    val fabExpanded by remember { derivedStateOf { gridState.firstVisibleItemIndex == 0 } }
    BackHandler(enabled = selecting, onBack = viewModel::clearSelection)

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            icon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null) },
            title = { Text(pluralStringResource(R.plurals.trash_delete_confirm, selection.size, selection.size)) },
            text = { Text(stringResource(R.string.vault_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.deleteSelection()
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }

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
                        IconButton(onClick = viewModel::lock, shapes = IconButtonDefaults.shapes()) {
                            Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.action_lock))
                        }
                        VaultMenu(
                            biometricAvailable = biometricAvailable,
                            biometricEnabled = biometricEnabled,
                            onToggleBiometric = onToggleBiometric,
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
                list.isEmpty() -> EmptyState(
                    icon = Icons.Outlined.Lock,
                    title = stringResource(R.string.vault_empty_title),
                    message = stringResource(R.string.vault_empty_message),
                    shape = MaterialShapes.Gem,
                    modifier = Modifier.padding(padding),
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
                    if (busy) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(16.dp))
                        }
                    }
                    items(list, key = { it.id }) { item ->
                        val selected = item.id in selection
                        VaultCell(
                            item = item,
                            selected = selected,
                            selectionMode = selecting,
                            onClick = {
                                if (selecting) {
                                    haptics.performHapticFeedback(
                                        if (selected) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn,
                                    )
                                    viewModel.toggle(item.id)
                                } else {
                                    onOpen(item)
                                }
                            },
                            onLongClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.toggle(item.id)
                            },
                        )
                    }
                }
            }

            if (selecting) {
                HorizontalFloatingToolbar(
                    expanded = true,
                    colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(bottom = 16.dp),
                ) {
                    TextButton(onClick = viewModel::restoreSelection, enabled = !busy) {
                        Icon(Icons.Outlined.RestorePage, contentDescription = null)
                        Text(stringResource(R.string.action_restore), modifier = Modifier.padding(start = 8.dp))
                    }
                    TextButton(onClick = { confirmDelete = true }, enabled = !busy) {
                        Icon(Icons.Outlined.DeleteForever, contentDescription = null)
                        Text(stringResource(R.string.action_delete), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

/** Réglages propres au coffre, accessibles seulement une fois déverrouillé. */
@Composable
private fun VaultMenu(
    biometricAvailable: Boolean,
    biometricEnabled: Boolean,
    onToggleBiometric: (Boolean) -> Unit,
    onChangePin: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
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
                val context = LocalContext.current
                AsyncImage(
                    model = remember(thumbName) {
                        ImageRequest.Builder(context)
                            .data(VaultImage(thumbName))
                            .size(THUMBNAIL_SIDE)
                            .memoryCacheKey(thumbnailKey(thumbName))
                            .build()
                    },
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
