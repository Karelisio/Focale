package app.focale.ui.collage

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focale.R
import app.focale.collage.CollageLayouts
import app.focale.collage.DetailRequest
import app.focale.core.model.MediaItem

/**
 * Écran de collage : aperçu à la proportion choisie, réglages (disposition, format, bordures,
 * fond) et enregistrement. Enregistré, le collage s'ouvre dans la visionneuse ([onSaved]) ;
 * « Enregistrer et partager » ouvre en plus le partage.
 */
@Composable
fun CollageScreen(onClose: () -> Unit, onSaved: (MediaItem) -> Unit, viewModel: CollageViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val editor by viewModel.editor.collectAsStateWithLifecycle()
    val items by viewModel.items.collectAsStateWithLifecycle()
    val previews by viewModel.previews.collectAsStateWithLifecycle()
    val details by viewModel.details.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val truncatedMessage = pluralStringResource(R.plurals.collage_truncated, CollageLayouts.MAX_PHOTOS, CollageLayouts.MAX_PHOTOS)
    var truncationShown by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is CollageEvent.Saved -> {
                    Toast.makeText(context, R.string.collage_saved, Toast.LENGTH_SHORT).show()
                    if (event.item != null) onSaved(event.item) else onClose()
                    event.share?.let(context::startActivity)
                }
                CollageEvent.Failed -> Toast.makeText(context, R.string.collage_failed, Toast.LENGTH_LONG).show()
                CollageEvent.Unavailable -> {
                    Toast.makeText(context, R.string.collage_not_enough_photos, Toast.LENGTH_LONG).show()
                    onClose()
                }
            }
        }
    }
    // Sélection de plus de neuf photos : les neuf premières, dit une fois.
    LaunchedEffect(viewModel) {
        if (viewModel.truncated && !truncationShown) {
            truncationShown = true
            snackbarHostState.showSnackbar(truncatedMessage, withDismissAction = true)
        }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxSize()) {
        val current = editor
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { LoadingIndicator() }
        } else {
            CollageContent(
                editor = current,
                items = items,
                previews = previews,
                details = details,
                onDetails = viewModel::requestDetails,
                saving = saving,
                onClose = onClose,
                onSave = viewModel::save,
                snackbarHostState = snackbarHostState,
            )
        }
    }
}

/**
 * Contenu de l'écran, sans ViewModel (tests, captures) : barre supérieure, aperçu, aide aux
 * gestes, panneau du réglage ouvert et choix du réglage. Fermer un collage modifié demande
 * confirmation.
 */
@Composable
internal fun CollageContent(
    editor: CollageEditor,
    items: Map<Long, MediaItem>,
    previews: Map<Long, ImageBitmap>,
    saving: Boolean,
    onClose: () -> Unit,
    onSave: (share: Boolean) -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    initialTool: CollageTool = CollageTool.LAYOUT,
    details: Map<Long, CollageDetailImage> = emptyMap(),
    onDetails: (Map<Long, DetailRequest>) -> Unit = {},
) {
    val state by editor.state.collectAsStateWithLifecycle()
    val canUndo by editor.canUndo.collectAsStateWithLifecycle()
    val canRedo by editor.canRedo.collectAsStateWithLifecycle()
    var tool by rememberSaveable { mutableStateOf(initialTool) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val requestClose = { if (canUndo) confirmDiscard = true else onClose() }
    BackHandler(onBack = requestClose)

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.collage_discard_title)) },
            text = { Text(stringResource(R.string.collage_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onClose()
                }) { Text(stringResource(R.string.collage_discard)) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    Column(Modifier.fillMaxSize()) {
        CollageTopBar(
            canUndo = canUndo,
            canRedo = canRedo,
            canSave = !saving,
            onClose = requestClose,
            onUndo = { editor.undo() },
            onRedo = { editor.redo() },
            onSave = { onSave(false) },
            onShare = { onSave(true) },
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            BoxWithConstraints(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Collage le plus grand possible à sa proportion.
                val ratio = state.aspect.ratio
                val wide = maxWidth / maxHeight > ratio
                val width = if (wide) maxHeight * ratio else maxWidth
                val height = if (wide) maxHeight else maxWidth / ratio
                CollageCanvas(
                    state = state,
                    items = items,
                    previews = previews,
                    onFrameStart = editor::beginGesture,
                    onFrame = { cell, pan, zoom, focus, cellSize, imageSize ->
                        editor.frame(cell, pan.x, pan.y, zoom, focus.x, focus.y, cellSize.width, cellSize.height, imageSize.width, imageSize.height)
                    },
                    onSwap = editor::swap,
                    onZoomStep = { cell, factor, cellSize, imageSize ->
                        editor.zoomStep(cell, factor, cellSize.width, cellSize.height, imageSize.width, imageSize.height)
                    },
                    onResetFrame = editor::resetFrame,
                    details = details,
                    onDetails = onDetails,
                    modifier = Modifier.size(width, height),
                )
            }
            if (saving) SavingOverlay()
            SnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
        }
        Text(
            text = stringResource(R.string.collage_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 4.dp),
        )
        AnimatedContent(
            targetState = tool,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "collagePanel",
        ) { current ->
            when (current) {
                CollageTool.LAYOUT -> LayoutPanel(editor.layouts, state.layout.id, state.aspect, onSelect = editor::selectLayout)
                CollageTool.FORMAT -> FormatPanel(state.aspect, onSelect = editor::setAspect)
                CollageTool.BORDERS -> BordersPanel(
                    spacing = state.spacing,
                    corners = state.corners,
                    onSpacingStart = editor::beginGesture,
                    onSpacing = editor::setSpacing,
                    onCornersStart = editor::beginGesture,
                    onCorners = editor::setCorners,
                )
                CollageTool.BACKGROUND -> BackgroundPanel(state.background, onSelect = editor::setBackground)
            }
        }
        CollageToolChoices(tool = tool, onSelect = { tool = it })
    }
}

@Composable
internal fun CollageTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    canSave: Boolean,
    onClose: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
    ) {
        IconButton(onClick = onClose, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close))
        }
        Text(
            text = stringResource(R.string.collage_title),
            style = MaterialTheme.typography.titleLargeEmphasized,
            maxLines = 1,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        IconButton(onClick = onUndo, enabled = canUndo, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = stringResource(R.string.editor_undo))
        }
        IconButton(onClick = onRedo, enabled = canRedo, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = stringResource(R.string.editor_redo))
        }
        IconButton(onClick = onShare, enabled = canSave, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.Rounded.Share, contentDescription = stringResource(R.string.collage_save_and_share))
        }
        Button(onClick = onSave, enabled = canSave, shapes = ButtonDefaults.shapes(), modifier = Modifier.padding(end = 4.dp)) {
            Text(stringResource(R.string.collage_save))
        }
    }
}

/** Voile et indicateur pendant le rendu et l'écriture du collage. */
@Composable
private fun SavingOverlay() {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
            ) {
                LoadingIndicator(Modifier.size(48.dp))
                Text(stringResource(R.string.collage_saving), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
