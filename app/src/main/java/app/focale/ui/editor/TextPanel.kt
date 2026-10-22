package app.focale.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.focale.R
import app.focale.edit.TextBackground
import app.focale.ui.theme.FocaleDisplayFamily

/**
 * Panneau de l'outil Texte : ajout d'un texte au centre, style de fond en boutons connectés,
 * gras, suppression du texte sélectionné, palette du dessin puis taille, avec son aperçu.
 * Les réglages montrés sont ceux du texte sélectionné, sinon ceux du prochain texte.
 *
 * @param ink pastille à cocher : null si la couleur du texte sélectionné n'est pas dans la palette.
 */
@Composable
fun TextPanel(
    options: TextOptions,
    ink: InkColor?,
    hasSelection: Boolean,
    onAdd: () -> Unit,
    onInk: (InkColor) -> Unit,
    onBackground: (TextBackground) -> Unit,
    onBold: (Boolean) -> Unit,
    onSizeStart: () -> Unit,
    onSize: (Float) -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalIconButton(onClick = onAdd, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.text_add))
            }
            IconToggleGroup(
                items = TextBackground.entries,
                selected = options.background,
                label = { backgroundLabel(it) },
                onSelect = onBackground,
                modifier = Modifier.weight(1f),
            ) { background, label -> BackgroundGlyph(background, label) }
            FilledTonalIconToggleButton(
                checked = options.bold,
                onCheckedChange = onBold,
                shapes = IconButtonDefaults.toggleableShapes(),
            ) { Icon(Icons.Outlined.FormatBold, contentDescription = stringResource(R.string.text_bold)) }
            IconButton(onClick = onDelete, enabled = hasSelection, shapes = IconButtonDefaults.shapes()) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.text_delete))
            }
        }
        InkPalette(selected = ink, enabled = true, onSelect = onInk, modifier = Modifier.padding(top = 4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val position = TextOptions.sliderPosition(options.size)
            SizeSample(position, Modifier.size(PREVIEW_SIZE))
            val label = stringResource(R.string.text_size)
            val sliderState = rememberSliderState(value = position)
            // Synchronise le curseur avec l'état (pincement, sélection, annuler).
            LaunchedEffect(position) { if (sliderState.value != position) sliderState.value = position }
            var dragging by remember { mutableStateOf(false) }
            Slider(
                state = sliderState,
                onValueChange = {
                    if (!dragging) {
                        dragging = true
                        onSizeStart()
                    }
                    sliderState.value = it
                    onSize(TextOptions.sizeAt(it))
                },
                onValueChangeFinished = { dragging = false },
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 12.dp)
                    .semantics { contentDescription = label },
            )
        }
    }
}

/** « Aa » à une taille qui suit le curseur, sur la pastille de l'aperçu d'épaisseur du dessin. */
@Composable
private fun SizeSample(position: Float, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Text(
            "Aa",
            fontFamily = FocaleDisplayFamily,
            fontWeight = FontWeight.Medium,
            fontSize = (12f + 16f * position).sp,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** « A » dessiné dans le style de fond : nu, sur un pavé, ou en contour. */
@Composable
private fun BackgroundGlyph(background: TextBackground, label: String) {
    val color = LocalContentColor.current
    val modifier = Modifier.semantics { contentDescription = label }
    val style = MaterialTheme.typography.titleMedium.copy(fontFamily = FocaleDisplayFamily, fontWeight = FontWeight.Bold)
    when (background) {
        TextBackground.NONE -> Text("A", style = style, color = color, modifier = modifier)
        TextBackground.BOX -> Box(
            contentAlignment = Alignment.Center,
            modifier = modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(color),
        ) { Text("A", style = style, color = if (color.luminance() > 0.5f) Color.Black else Color.White) }
        TextBackground.OUTLINE -> Text("A", style = style.copy(drawStyle = Stroke(width = 3f)), color = color, modifier = modifier)
    }
}

@Composable
private fun backgroundLabel(background: TextBackground): String = stringResource(
    when (background) {
        TextBackground.NONE -> R.string.text_background_none
        TextBackground.BOX -> R.string.text_background_box
        TextBackground.OUTLINE -> R.string.text_background_outline
    },
)

/**
 * Saisie d'un texte, sur plusieurs lignes : « OK » l'ajoute ou le modifie, « Supprimer »
 * (texte existant) le retire ; un texte vidé est retiré, un nouveau texte vide n'est pas ajouté.
 */
@Composable
fun TextInputDialog(draft: TextDraft, onConfirm: (String) -> Unit, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var value by rememberSaveable(draft, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(draft.initial, TextRange(draft.initial.length)))
    }
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (draft.id == null) R.string.text_add else R.string.text_edit_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(stringResource(R.string.text_field_label)) },
                minLines = 2,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .testTag(TEXT_FIELD_TAG),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value.text) }, enabled = draft.id != null || value.text.isNotBlank()) {
                Text(stringResource(R.string.text_done))
            }
        },
        dismissButton = {
            Row {
                if (draft.id != null) TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
            }
        },
    )
    LaunchedEffect(Unit) { focus.requestFocus() }
}

/** Repère du champ de saisie, pour les tests. */
const val TEXT_FIELD_TAG = "text_input"
