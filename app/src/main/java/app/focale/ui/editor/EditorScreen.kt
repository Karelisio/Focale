package app.focale.ui.editor

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSliderState
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focale.R
import app.focale.edit.Adjustment
import app.focale.edit.ColorMatrices
import app.focale.edit.CropAspect
import app.focale.edit.EditState
import app.focale.edit.FilterPreset
import app.focale.edit.NormRect
import app.focale.edit.SaveMode
import app.focale.ui.components.Choice
import app.focale.ui.components.ConnectedChoiceGroup
import app.focale.ui.theme.PhotoTheme
import kotlin.math.roundToInt

/** Éditeur photo plein écran, toujours sombre, teinté par la photo. */
@Composable
fun EditorScreen(onClose: () -> Unit, viewModel: EditorViewModel = hiltViewModel()) {
    val seed by viewModel.seed.collectAsStateWithLifecycle()
    PhotoTheme(seed = seed, forceDark = true) {
        EditorContent(viewModel, onClose)
    }
}

@Composable
private fun EditorContent(viewModel: EditorViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val tool by viewModel.tool.collectAsStateWithLifecycle()
    val adjustment by viewModel.adjustment.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val canUndo by viewModel.canUndo.collectAsStateWithLifecycle()
    val canRedo by viewModel.canRedo.collectAsStateWithLifecycle()
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    var showOriginal by remember { mutableStateOf(false) }

    val requestClose = { if (viewModel.hasChanges) confirmDiscard = true else onClose() }
    BackHandler(onBack = requestClose)

    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                is EditorEvent.Saved -> {
                    Toast.makeText(
                        context,
                        if (event.mode == SaveMode.COPY) R.string.editor_saved_copy else R.string.editor_saved_replace,
                        Toast.LENGTH_SHORT,
                    ).show()
                    onClose()
                }
                EditorEvent.Failed -> Toast.makeText(context, R.string.editor_failed, Toast.LENGTH_LONG).show()
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

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        EditorTopBar(
            canUndo = canUndo,
            canRedo = canRedo,
            canSave = viewModel.hasChanges && !saving,
            canReplace = viewModel.canReplace,
            onClose = requestClose,
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
            onSave = viewModel::save,
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val image = preview
            if (image == null) {
                LoadingIndicator()
            } else {
                EditorCanvas(
                    image = image,
                    state = state,
                    cropMode = tool == EditorTool.CROP,
                    showOriginal = showOriginal,
                    ratio = viewModel.cropRatio(),
                    onGestureStart = viewModel::beginGesture,
                    onCropChange = viewModel::dragCrop,
                    onCompare = { showOriginal = it },
                )
            }
            if (saving) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f)),
                    contentAlignment = Alignment.Center,
                ) { LoadingIndicator(Modifier.size(64.dp)) }
            }
        }
        AnimatedContent(
            targetState = tool,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "toolPanel",
        ) { current ->
            when (current) {
                EditorTool.CROP -> CropPanel(state, onRotate = viewModel::rotate, onFlip = viewModel::flip, onAspect = viewModel::setAspect)
                EditorTool.FILTERS -> FiltersPanel(preview, state, onSelect = viewModel::setFilter)
                EditorTool.ADJUST -> AdjustPanel(
                    state = state,
                    selected = adjustment,
                    onSelect = viewModel::selectAdjustment,
                    onStart = viewModel::beginGesture,
                    onValue = viewModel::setAdjustmentValue,
                    onReset = viewModel::resetAdjustment,
                )
            }
        }
        ConnectedChoiceGroup(
            choices = listOf(
                Choice(EditorTool.CROP, stringResource(R.string.editor_crop), Icons.Outlined.Crop),
                Choice(EditorTool.FILTERS, stringResource(R.string.editor_filters), Icons.Outlined.AutoFixHigh),
                Choice(EditorTool.ADJUST, stringResource(R.string.editor_adjust), Icons.Outlined.Tune),
            ),
            selected = tool,
            onSelect = viewModel::selectTool,
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun EditorTopBar(
    canUndo: Boolean,
    canRedo: Boolean,
    canSave: Boolean,
    canReplace: Boolean,
    onClose: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSave: (SaveMode) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
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
            text = stringResource(R.string.editor_title),
            style = MaterialTheme.typography.titleLargeEmphasized,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        IconButton(onClick = onUndo, enabled = canUndo, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = stringResource(R.string.editor_undo))
        }
        IconButton(onClick = onRedo, enabled = canRedo, shapes = IconButtonDefaults.shapes()) {
            Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = stringResource(R.string.editor_redo))
        }
        Box {
            Button(onClick = { menuOpen = true }, enabled = canSave, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.editor_save))
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_save_copy)) },
                    onClick = {
                        menuOpen = false
                        onSave(SaveMode.COPY)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.editor_save_replace)) },
                    enabled = canReplace,
                    onClick = {
                        menuOpen = false
                        onSave(SaveMode.REPLACE)
                    },
                )
            }
        }
    }
}

/**
 * Aperçu : en mode recadrage, l'image entière et le cadre ; sinon la zone recadrée.
 * Un appui long affiche l'original (sans filtre ni réglage).
 */
@Composable
private fun EditorCanvas(
    image: ImageBitmap,
    state: EditState,
    cropMode: Boolean,
    showOriginal: Boolean,
    ratio: Float?,
    onGestureStart: () -> Unit,
    onCropChange: (NormRect) -> Unit,
    onCompare: (Boolean) -> Unit,
) {
    val density = LocalDensity.current
    val colorFilter = remember(state.filter, state.brightness, state.contrast, state.saturation, state.warmth, showOriginal) {
        val values = state.colorMatrix()
        if (showOriginal || values.contentEquals(ColorMatrices.identity)) null else ColorFilter.colorMatrix(ColorMatrix(values))
    }
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .padding(20.dp),
    ) {
        val region = if (cropMode) NormRect.Full else state.crop
        val regionWidth = region.width * image.width
        val regionHeight = region.height * image.height
        val containerWidth = with(density) { maxWidth.toPx() }
        val containerHeight = with(density) { maxHeight.toPx() }
        val scale = minOf(containerWidth / regionWidth, containerHeight / regionHeight)
        val drawWidth = regionWidth * scale
        val drawHeight = regionHeight * scale
        val left = (containerWidth - drawWidth) / 2f
        val top = (containerHeight - drawHeight) / 2f

        Canvas(
            Modifier
                .fillMaxSize()
                .then(
                    if (!cropMode) {
                        Modifier.pointerInput(Unit) {
                            detectTapGestures(onPress = {
                                onCompare(true)
                                tryAwaitRelease()
                                onCompare(false)
                            })
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            drawImage(
                image = image,
                srcOffset = IntOffset((region.left * image.width).roundToInt(), (region.top * image.height).roundToInt()),
                srcSize = IntSize(regionWidth.roundToInt().coerceAtLeast(1), regionHeight.roundToInt().coerceAtLeast(1)),
                dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                dstSize = IntSize(drawWidth.roundToInt(), drawHeight.roundToInt()),
                colorFilter = colorFilter,
            )
        }
        if (cropMode) {
            CropOverlay(
                imageRect = Rect(left, top, left + drawWidth, top + drawHeight),
                crop = state.crop,
                ratio = ratio,
                accent = MaterialTheme.colorScheme.primary,
                onGestureStart = onGestureStart,
                onCropChange = onCropChange,
            )
        }
    }
}

@Composable
private fun CropPanel(state: EditState, onRotate: () -> Unit, onFlip: () -> Unit, onAspect: (CropAspect) -> Unit) {
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
            modifier = Modifier.fillMaxWidth(),
        ) {
            FilledTonalIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onRotate()
                },
                shapes = IconButtonDefaults.shapes(),
            ) { Icon(Icons.AutoMirrored.Rounded.RotateRight, contentDescription = stringResource(R.string.editor_rotate)) }
            FilledTonalIconButton(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                    onFlip()
                },
                shapes = IconButtonDefaults.shapes(),
            ) { Icon(Icons.Rounded.Flip, contentDescription = stringResource(R.string.editor_flip)) }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            modifier = Modifier.padding(top = 8.dp),
        ) {
            items(CropAspect.entries) { aspect ->
                FilterChip(
                    selected = state.aspect == aspect,
                    onClick = { onAspect(aspect) },
                    label = { Text(aspectLabel(aspect)) },
                    shape = MaterialTheme.shapes.large,
                )
            }
        }
    }
}

@Composable
private fun aspectLabel(aspect: CropAspect): String = when (aspect) {
    CropAspect.FREE -> stringResource(R.string.editor_aspect_free)
    CropAspect.ORIGINAL -> stringResource(R.string.editor_aspect_original)
    CropAspect.SQUARE -> "1:1"
    CropAspect.FOUR_THREE -> "4:3"
    CropAspect.THREE_TWO -> "3:2"
    CropAspect.SIXTEEN_NINE -> "16:9"
    CropAspect.NINE_SIXTEEN -> "9:16"
}

@Composable
private fun FiltersPanel(preview: ImageBitmap?, state: EditState, onSelect: (FilterPreset) -> Unit) {
    val haptics = LocalHapticFeedback.current
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    ) {
        items(FilterPreset.entries) { filter ->
            val selected = state.filter == filter
            val scale by animateFloatAsState(
                targetValue = if (selected) 1.06f else 1f,
                animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                label = "filterScale",
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .width(76.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
                    .clickable {
                        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                        onSelect(filter)
                    },
            ) {
                val shape = RoundedCornerShape(if (selected) 26.dp else 16.dp)
                Box(
                    Modifier
                        .size(72.dp)
                        .clip(shape)
                        .border(if (selected) 3.dp else 0.dp, MaterialTheme.colorScheme.primary, shape)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                ) {
                    if (preview != null) {
                        Image(
                            bitmap = preview,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            colorFilter = ColorFilter.colorMatrix(ColorMatrix(ColorMatrices.preset(filter))),
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    filterLabel(filter),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun filterLabel(filter: FilterPreset): String = stringResource(
    when (filter) {
        FilterPreset.NONE -> R.string.filter_none
        FilterPreset.VIVID -> R.string.filter_vivid
        FilterPreset.MONO -> R.string.filter_mono
        FilterPreset.SEPIA -> R.string.filter_sepia
        FilterPreset.WARM -> R.string.filter_warm
        FilterPreset.COOL -> R.string.filter_cool
        FilterPreset.VINTAGE -> R.string.filter_vintage
        FilterPreset.DRAMATIC -> R.string.filter_dramatic
        FilterPreset.FADE -> R.string.filter_fade
        FilterPreset.NOIR -> R.string.filter_noir
    },
)

@Composable
private fun AdjustPanel(
    state: EditState,
    selected: Adjustment,
    onSelect: (Adjustment) -> Unit,
    onStart: () -> Unit,
    onValue: (Float) -> Unit,
    onReset: (Adjustment) -> Unit,
) {
    val value = state.value(selected)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Adjustment.entries) { adjustment ->
                val amount = state.value(adjustment)
                FilterChip(
                    selected = adjustment == selected,
                    onClick = { onSelect(adjustment) },
                    label = {
                        Text(
                            if (amount == 0f) adjustmentLabel(adjustment) else "${adjustmentLabel(adjustment)} ${(amount * 100).roundToInt()}",
                        )
                    },
                    shape = MaterialTheme.shapes.large,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            val sliderState = rememberSliderState(value = value, trackRange = -1f..1f)
            // Synchronise le curseur avec l'état (annuler / changement de réglage).
            LaunchedEffect(selected, value) { if (sliderState.value != value) sliderState.value = value }
            var dragging by remember { mutableStateOf(false) }
            Slider(
                state = sliderState,
                onValueChange = {
                    if (!dragging) {
                        dragging = true
                        onStart()
                    }
                    sliderState.value = it
                    onValue(it)
                },
                onValueChangeFinished = { dragging = false },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = (value * 100).roundToInt().toString(),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .width(44.dp)
                    .clickable { onReset(selected) }
                    .padding(start = 12.dp),
            )
        }
    }
}

@Composable
private fun adjustmentLabel(adjustment: Adjustment): String = stringResource(
    when (adjustment) {
        Adjustment.BRIGHTNESS -> R.string.adjust_brightness
        Adjustment.CONTRAST -> R.string.adjust_contrast
        Adjustment.SATURATION -> R.string.adjust_saturation
        Adjustment.WARMTH -> R.string.adjust_warmth
    },
)
