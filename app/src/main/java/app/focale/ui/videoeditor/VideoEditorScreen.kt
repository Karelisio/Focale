package app.focale.ui.videoeditor

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.ContentFrame
import app.focale.R
import app.focale.core.image.ThumbnailSizes
import app.focale.core.image.thumbnail
import app.focale.core.model.MediaItem
import app.focale.ui.theme.PhotoTheme
import app.focale.video.TrimFormat
import app.focale.video.TrimRange
import app.focale.video.TrimRequest
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.text.NumberFormat

/** Montage vidéo plein écran, toujours sombre, teinté par la vidéo. */
@Composable
fun VideoEditorScreen(onClose: () -> Unit, viewModel: VideoEditorViewModel = hiltViewModel()) {
    val seed by viewModel.seed.collectAsStateWithLifecycle()
    PhotoTheme(seed = seed, forceDark = true) {
        VideoEditorContent(viewModel, onClose)
    }
}

@Composable
private fun VideoEditorContent(viewModel: VideoEditorViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val item by viewModel.item.collectAsStateWithLifecycle()
    val range by viewModel.range.collectAsStateWithLifecycle()
    val muted by viewModel.muted.collectAsStateWithLifecycle()
    val hasAudio by viewModel.hasAudio.collectAsStateWithLifecycle()
    val playing by viewModel.playing.collectAsStateWithLifecycle()
    val frames by viewModel.frames.collectAsStateWithLifecycle()
    val position by viewModel.position.collectAsStateWithLifecycle()
    val exportProgress by viewModel.exportProgress.collectAsStateWithLifecycle()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }

    val exporting = exportProgress != null
    val hasChanges = range?.let { TrimRequest(it, removeAudio = muted && hasAudio).hasChanges } == true
    val requestClose: () -> Unit = {
        when {
            exporting -> viewModel.cancelExport()
            hasChanges -> confirmDiscard = true
            else -> onClose()
        }
    }
    BackHandler(onBack = requestClose)
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { viewModel.pause() }
    KeepScreenOn(exporting)

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                VideoEditorEvent.Saved -> {
                    Toast.makeText(context, R.string.video_editor_saved, Toast.LENGTH_SHORT).show()
                    onClose()
                }
                VideoEditorEvent.Failed -> Toast.makeText(context, R.string.video_editor_failed, Toast.LENGTH_LONG).show()
                VideoEditorEvent.Unavailable -> {
                    Toast.makeText(context, R.string.video_editor_unavailable, Toast.LENGTH_LONG).show()
                    onClose()
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.editor_discard_title)) },
            text = { Text(stringResource(R.string.editor_discard_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onClose()
                }) { Text(stringResource(R.string.editor_discard)) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    VideoEditorLayout(
        range = range,
        frames = frames,
        position = { position },
        playing = playing,
        muted = muted,
        hasAudio = hasAudio,
        exportProgress = exportProgress,
        canSave = hasChanges && !exporting,
        onClose = requestClose,
        onSave = viewModel::save,
        onTogglePlay = viewModel::togglePlay,
        onMutedChange = viewModel::setMuted,
        onTrimStart = viewModel::beginTrim,
        onTrim = viewModel::trim,
        onTrimEnd = viewModel::endTrim,
        onCancelExport = viewModel::cancelExport,
        preview = { previewModifier ->
            VideoPreview(
                player = viewModel.player,
                item = item,
                playing = playing,
                onToggle = viewModel::togglePlay,
                modifier = previewModifier,
            )
        },
    )
}

/**
 * Mise en page de l'éditeur, indépendante du ViewModel (captures d'écran) ; [preview] affiche
 * la vidéo. Sur un écran bas (téléphone en paysage), la vidéo passe à gauche et les outils à
 * droite, pour garder un aperçu de taille utile.
 */
@Composable
internal fun VideoEditorLayout(
    range: TrimRange?,
    frames: List<ImageBitmap?>,
    position: () -> Long,
    playing: Boolean,
    muted: Boolean,
    hasAudio: Boolean,
    exportProgress: Float?,
    canSave: Boolean,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onTogglePlay: () -> Unit,
    onMutedChange: (Boolean) -> Unit,
    onTrimStart: (TrimHandle) -> Unit,
    onTrim: (TrimHandle, Long) -> Long,
    onTrimEnd: () -> Unit,
    onCancelExport: () -> Unit,
    preview: @Composable (Modifier) -> Unit,
) {
    val exporting = exportProgress != null
    val tools: @Composable () -> Unit = {
        range?.let { current ->
            TrimPanel(
                range = current,
                position = position,
                frames = frames,
                enabled = !exporting,
                onTrimStart = onTrimStart,
                onTrim = onTrim,
                onTrimEnd = onTrimEnd,
            )
        }
        EditorControls(
            playing = playing,
            muted = muted,
            hasAudio = hasAudio,
            enabled = range != null && !exporting,
            onTogglePlay = onTogglePlay,
            onMutedChange = onMutedChange,
        )
    }
    // Surface : fournit aussi la couleur de contenu (titre, icônes) assortie au fond.
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            EditorPanes(
                sideBySide = maxWidth > maxHeight && maxHeight < SideBySideMaxHeight,
                canSave = canSave,
                onClose = onClose,
                onSave = onSave,
                preview = preview,
                tools = tools,
            )
            if (exportProgress != null) {
                ExportOverlay(progress = { exportProgress }, onCancel = onCancelExport)
            }
        }
    }
}

/** Barre supérieure, puis la vidéo et les outils l'une sous l'autre, ou côte à côte. */
@Composable
private fun EditorPanes(
    sideBySide: Boolean,
    canSave: Boolean,
    onClose: () -> Unit,
    onSave: () -> Unit,
    preview: @Composable (Modifier) -> Unit,
    tools: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        VideoEditorTopBar(canSave = canSave, onClose = onClose, onSave = onSave)
        if (sideBySide) {
            Row(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .navigationBarsPadding(),
            ) {
                preview(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                Column(
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .width(SidePanelWidth)
                        .fillMaxHeight(),
                ) { tools() }
            }
        } else {
            preview(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )
            Column(Modifier.navigationBarsPadding()) { tools() }
        }
    }
}

@Composable
private fun VideoEditorTopBar(canSave: Boolean, onClose: () -> Unit, onSave: () -> Unit) {
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
            text = stringResource(R.string.video_editor_title),
            style = MaterialTheme.typography.titleLargeEmphasized,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
        )
        Button(
            onClick = onSave,
            enabled = canSave,
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.padding(end = 8.dp),
        ) {
            Text(stringResource(R.string.video_editor_save_copy), maxLines = 1)
        }
    }
}

/** Aperçu : un appui lance ou interrompt la lecture (limitée à la plage choisie). */
@OptIn(UnstableApi::class)
@Composable
private fun VideoPreview(
    player: Player,
    item: MediaItem?,
    playing: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(
                interactionSource = null,
                indication = null,
                onClickLabel = stringResource(if (playing) R.string.action_pause else R.string.action_play),
                onClick = onToggle,
            ),
        contentAlignment = Alignment.Center,
    ) {
        ContentFrame(
            player = player,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
            keepContentOnReset = true,
            shutter = { item?.let { VideoPoster(it) } },
        )
        AnimatedVisibility(visible = !playing, enter = fadeIn() + scaleIn(initialScale = 0.8f), exit = fadeOut() + scaleOut(targetScale = 0.8f)) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = Color.Black.copy(alpha = 0.45f),
                contentColor = Color.White,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.padding(16.dp).size(36.dp))
            }
        }
    }
}

@Composable
private fun VideoPoster(item: MediaItem) {
    val context = LocalContext.current
    val bucket = ThumbnailSizes.lastGridBucket
    AsyncImage(
        model = remember(item.id, bucket) {
            ImageRequest.Builder(context)
                .data(item.thumbnail())
                .size(bucket)
                .memoryCacheKey(ThumbnailSizes.memoryKey(item.id, item.dateModified, bucket))
                .build()
        },
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.fillMaxSize(),
    )
}

/** Temps de début et de fin, durée conservée, puis la frise. */
@Composable
private fun TrimPanel(
    range: TrimRange,
    position: () -> Long,
    frames: List<ImageBitmap?>,
    enabled: Boolean,
    onTrimStart: (TrimHandle) -> Unit,
    onTrim: (TrimHandle, Long) -> Long,
    onTrimEnd: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    var active by remember { mutableStateOf<TrimHandle?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
        ) {
            TimeLabel(
                text = TrimFormat.position(range.startMs, locale),
                highlighted = active == TrimHandle.START,
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    text = TrimFormat.length(range.lengthMs, locale),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            TimeLabel(
                text = TrimFormat.position(range.endMs, locale),
                highlighted = active == TrimHandle.END,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        TrimBar(
            range = range,
            position = position,
            frames = frames,
            enabled = enabled,
            onTrimStart = { handle ->
                active = handle
                onTrimStart(handle)
            },
            onTrim = onTrim,
            onTrimEnd = {
                active = null
                onTrimEnd()
            },
        )
        if (!range.canTrim) {
            Text(
                text = stringResource(R.string.video_editor_too_short),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun TimeLabel(text: String, highlighted: Boolean, textAlign: TextAlign, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = if (highlighted) FontWeight.Bold else null,
        color = if (highlighted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = textAlign,
        maxLines = 1,
        modifier = modifier,
    )
}

/** Lecture et son coupé (appliqué à l'aperçu comme à la copie). */
@Composable
private fun EditorControls(
    playing: Boolean,
    muted: Boolean,
    hasAudio: Boolean,
    enabled: Boolean,
    onTogglePlay: () -> Unit,
    onMutedChange: (Boolean) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
    ) {
        FilledTonalIconButton(
            onClick = onTogglePlay,
            enabled = enabled,
            shapes = IconButtonDefaults.shapes(),
            modifier = Modifier.size(IconButtonDefaults.mediumContainerSize()),
        ) {
            Icon(
                imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = stringResource(if (playing) R.string.action_pause else R.string.action_play),
            )
        }
        ToggleButton(
            checked = muted && hasAudio,
            onCheckedChange = { checked ->
                haptics.performHapticFeedback(if (checked) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)
                onMutedChange(checked)
            },
            enabled = enabled && hasAudio,
        ) {
            Icon(
                imageVector = if (muted && hasAudio) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = null,
                modifier = Modifier.size(ToggleButtonDefaults.IconSize),
            )
            Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
            Text(stringResource(R.string.video_editor_mute), maxLines = 1)
        }
    }
}

/** Progression de l'export, par-dessus l'éditeur (qui ne reçoit plus les appuis), annulable. */
@Composable
private fun ExportOverlay(progress: () -> Float, onCancel: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val animated by animateFloatAsState(targetValue = progress(), label = "exportProgress")
    Surface(color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f), modifier = Modifier.fillMaxSize()) {
        Box(contentAlignment = Alignment.Center) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .padding(32.dp)
                    .widthIn(max = 420.dp),
            ) {
                Column(Modifier.padding(24.dp)) {
                    Text(stringResource(R.string.video_editor_exporting), style = MaterialTheme.typography.titleLargeEmphasized)
                    LinearWavyProgressIndicator(
                        progress = { animated },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = remember(locale) { NumberFormat.getPercentInstance(locale) }.format(animated.toDouble()),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
                    }
                }
            }
        }
    }
}

private val SideBySideMaxHeight = 480.dp
private val SidePanelWidth = 360.dp

/** Écran maintenu allumé pendant l'export. */
@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        view.keepScreenOn = enabled
        onDispose { view.keepScreenOn = false }
    }
}
