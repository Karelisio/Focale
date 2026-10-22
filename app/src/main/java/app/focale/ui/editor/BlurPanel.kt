package app.focale.ui.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BlurOn
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Rectangle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.edit.BlurShape
import app.focale.edit.BlurStyle
import app.focale.ui.components.Choice
import app.focale.ui.components.ConnectedChoiceGroup

/**
 * Panneau de l'outil Flouter : forme des prochaines zones et bouton « Visages », style (« Flou »
 * ou « Pixels ») et suppression de la zone sélectionnée, puis intensité et taille du pinceau
 * (estompée hors du pinceau, pour que l'aperçu garde sa taille). Style et intensité montrés sont
 * ceux de la zone sélectionnée, sinon ceux des prochaines zones.
 */
@Composable
fun BlurPanel(
    options: BlurOptions,
    hasSelection: Boolean,
    detectingFaces: Boolean,
    onShape: (BlurShape) -> Unit,
    onStyle: (BlurStyle) -> Unit,
    onIntensityStart: () -> Unit,
    onIntensity: (Float) -> Unit,
    onBrushSize: (Float) -> Unit,
    onFaces: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconToggleGroup(
                items = BlurShape.entries,
                selected = options.shape,
                label = { shapeLabel(it) },
                onSelect = onShape,
                modifier = Modifier.weight(1f),
            ) { shape, label -> Icon(shapeIcon(shape), contentDescription = label, modifier = Modifier.size(ToggleButtonDefaults.IconSize)) }
            val facesLabel = stringResource(R.string.blur_faces_description)
            FilledTonalButton(
                onClick = onFaces,
                enabled = !detectingFaces,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.semantics { contentDescription = facesLabel },
            ) {
                if (detectingFaces) {
                    LoadingIndicator(Modifier.size(ButtonDefaults.IconSize))
                } else {
                    Icon(Icons.Outlined.Face, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                }
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.blur_faces))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
            ConnectedChoiceGroup(
                choices = listOf(
                    Choice(BlurStyle.BLUR, stringResource(R.string.blur_style_blur), Icons.Outlined.BlurOn),
                    Choice(BlurStyle.PIXELATE, stringResource(R.string.blur_style_pixelate), Icons.Outlined.GridOn),
                ),
                selected = options.style,
                onSelect = onStyle,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete, enabled = hasSelection, shapes = IconButtonDefaults.shapes(), modifier = Modifier.padding(start = 8.dp)) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.blur_delete))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Icon(Icons.Outlined.BlurOn, contentDescription = null, modifier = Modifier.size(20.dp))
            IntensitySlider(options.intensity, onIntensityStart, onIntensity, Modifier.weight(1f).padding(horizontal = 8.dp))
            val brushing = options.shape == BlurShape.BRUSH
            Icon(Icons.Outlined.Brush, contentDescription = null, modifier = Modifier.size(20.dp).alpha(if (brushing) 1f else DISABLED_ALPHA))
            val brushLabel = stringResource(R.string.blur_brush_size)
            val brushState = rememberSliderState(value = options.brushSize, trackRange = BlurOptions.MIN_BRUSH..BlurOptions.MAX_BRUSH)
            Slider(
                state = brushState,
                onValueChange = {
                    brushState.value = it
                    onBrushSize(it)
                },
                enabled = brushing,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp)
                    .semantics { contentDescription = brushLabel },
            )
        }
    }
}

/** Intensité, synchronisée avec l'état (sélection, annuler) ; une étape d'historique par geste. */
@Composable
private fun IntensitySlider(value: Float, onStart: () -> Unit, onValue: (Float) -> Unit, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.blur_intensity)
    val state = rememberSliderState(value = value)
    LaunchedEffect(value) { if (state.value != value) state.value = value }
    var dragging by remember { mutableStateOf(false) }
    Slider(
        state = state,
        onValueChange = {
            if (!dragging) {
                dragging = true
                onStart()
            }
            state.value = it
            onValue(it)
        },
        onValueChangeFinished = { dragging = false },
        modifier = modifier.semantics { contentDescription = label },
    )
}

@Composable
private fun shapeLabel(shape: BlurShape): String = stringResource(
    when (shape) {
        BlurShape.RECTANGLE -> R.string.blur_rectangle
        BlurShape.ELLIPSE -> R.string.blur_ellipse
        BlurShape.BRUSH -> R.string.blur_brush
    },
)

private fun shapeIcon(shape: BlurShape): ImageVector = when (shape) {
    BlurShape.RECTANGLE -> Icons.Outlined.Rectangle
    BlurShape.ELLIPSE -> Icons.Outlined.Circle
    BlurShape.BRUSH -> Icons.Outlined.Brush
}
