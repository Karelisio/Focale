package app.focale.ui.viewer

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focale.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Affiche la feuille « Texte » ouverte depuis le menu de la visionneuse. */
@Composable
fun PhotoTextSheetHost(viewModel: PhotoTextViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val current = state ?: return
    PhotoTextSheet(state = current, onDismiss = viewModel::dismiss)
}

/** Texte reconnu dans la photo, sélectionnable, avec « Copier tout ». */
@Composable
private fun PhotoTextSheet(state: PhotoTextState, onDismiss: () -> Unit) {
    // Ouverte en entier : sous un long texte (qui défile), le bouton reste visible.
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column {
                Text(stringResource(R.string.text_title), style = MaterialTheme.typography.headlineSmallEmphasized)
                Text(
                    text = stringResource(R.string.text_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            when (state) {
                PhotoTextState.Reading -> ReadingText()
                is PhotoTextState.Found -> FoundText(state.text)
                PhotoTextState.Empty -> TextMessage(
                    icon = Icons.Outlined.TextFields,
                    title = stringResource(R.string.text_empty_title),
                    message = stringResource(R.string.text_empty_message),
                )
                PhotoTextState.Failed -> TextMessage(
                    icon = Icons.Outlined.BrokenImage,
                    title = stringResource(R.string.text_error_title),
                    message = stringResource(R.string.text_error_message),
                )
            }
        }
    }
}

/** Lecture en cours ; l'indicateur n'apparaît que si elle dure (texte déjà lu : immédiat). */
@Composable
private fun ReadingText() {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(READING_DELAY_MS)
        visible = true
    }
    Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
        AnimatedVisibility(visible = visible, enter = fadeIn()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                LoadingIndicator(Modifier.size(48.dp))
                Text(
                    text = stringResource(R.string.text_reading),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ColumnScope.FoundText(text: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val label = stringResource(R.string.text_clip_label)
    var copied by remember(text) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(COPIED_MS)
            copied = false
        }
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f, fill = false),
    ) {
        SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            )
        }
    }
    Button(
        onClick = {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text)))
                copied = true
            }
        },
        shapes = ButtonDefaults.shapes(),
        modifier = Modifier.align(Alignment.End),
    ) {
        Icon(
            imageVector = if (copied) Icons.Rounded.Check else Icons.Outlined.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(stringResource(if (copied) R.string.text_copied else R.string.text_copy_all))
    }
}

/** Aucun texte, ou photo illisible : icône dans une forme Expressive, titre et message. */
@Composable
private fun TextMessage(icon: ImageVector, title: String, message: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private const val READING_DELAY_MS = 250L
private const val COPIED_MS = 2_000L
