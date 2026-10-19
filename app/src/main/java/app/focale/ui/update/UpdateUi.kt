package app.focale.ui.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.core.util.Formatters
import app.focale.ui.whatsnew.ReleaseNotesBlocks
import app.focale.update.AppUpdate
import app.focale.update.ReleaseNotes
import app.focale.update.UpdateError
import app.focale.update.UpdateState
import app.focale.update.update
import kotlin.math.roundToInt

/** Description de l'état courant de la mise à jour. */
@Composable
fun updateStatusText(state: UpdateState): String = when (state) {
    UpdateState.Idle -> stringResource(R.string.update_status_idle)
    UpdateState.Checking -> stringResource(R.string.update_status_checking)
    UpdateState.UpToDate -> stringResource(R.string.update_status_up_to_date)
    is UpdateState.Available -> stringResource(
        R.string.update_status_available,
        state.update.version,
        Formatters.fileSize(LocalContext.current, state.update.apkSize),
    )
    is UpdateState.Downloading -> stringResource(R.string.update_status_downloading, (state.progress * 100).roundToInt())
    is UpdateState.Installing -> stringResource(R.string.update_status_installing)
    is UpdateState.Failed -> stringResource(
        when (state.error) {
            UpdateError.NETWORK -> R.string.update_error_network
            UpdateError.INTEGRITY -> R.string.update_error_integrity
            UpdateError.INSTALL -> R.string.update_error_install
        },
    )
}

/** Progression (recherche, téléchargement, installation) et actions possibles. */
@Composable
fun UpdateControls(
    state: UpdateState,
    onCheck: () -> Unit,
    onInstall: (AppUpdate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uriHandler = LocalUriHandler.current
    val update = state.update
    var showNotes by rememberSaveable { mutableStateOf(false) }
    if (showNotes && update != null) {
        ReleaseNotesSheet(
            update = update,
            canInstall = state is UpdateState.Available,
            onInstall = {
                showNotes = false
                onInstall(update)
            },
            onDismiss = { showNotes = false },
        )
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state) {
            UpdateState.Checking, is UpdateState.Installing -> LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            is UpdateState.Downloading -> LinearWavyProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
            else -> Unit
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state is UpdateState.Available -> {
                    Button(onClick = { onInstall(state.update) }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.update_action_install))
                    }
                    TextButton(
                        onClick = {
                            if (state.update.notes.isBlank()) uriHandler.openUri(state.update.pageUrl) else showNotes = true
                        },
                        shapes = ButtonDefaults.shapes(),
                    ) {
                        Text(stringResource(R.string.update_action_notes))
                    }
                }
                state is UpdateState.Failed && update != null && state.error == UpdateError.INSTALL -> {
                    Button(onClick = { uriHandler.openUri(update.pageUrl) }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.update_action_github))
                    }
                }
                state is UpdateState.Failed && update != null -> {
                    Button(onClick = { onInstall(update) }, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.update_action_retry))
                    }
                }
                state == UpdateState.Idle || state == UpdateState.UpToDate || state is UpdateState.Failed -> {
                    FilledTonalButton(onClick = onCheck, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.update_action_check))
                    }
                }
            }
        }
    }
}

/** Bannière de l'onglet Plus, affichée tant qu'une mise à jour est proposée. */
@Composable
fun UpdateBanner(state: UpdateState, onCheck: () -> Unit, onInstall: (AppUpdate) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Card(
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(MaterialShapes.Cookie9Sided.toShape())
                        .background(colors.primary),
                ) {
                    Icon(Icons.Outlined.SystemUpdate, contentDescription = null, tint = colors.onPrimary)
                }
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.update_available_title), style = MaterialTheme.typography.titleMediumEmphasized)
                    Text(updateStatusText(state), style = MaterialTheme.typography.bodyMedium)
                }
            }
            UpdateControls(state = state, onCheck = onCheck, onInstall = onInstall)
        }
    }
}

/** Nouveautés de la version proposée, lues dans l'application. */
@Composable
private fun ReleaseNotesSheet(update: AppUpdate, canInstall: Boolean, onInstall: () -> Unit, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val blocks = remember(update.notes) { ReleaseNotes.parse(update.notes) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.update_notes_title, update.version),
                style = MaterialTheme.typography.headlineSmallEmphasized,
            )
            ReleaseNotesBlocks(blocks)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 16.dp)) {
                if (canInstall) {
                    Button(onClick = onInstall, shapes = ButtonDefaults.shapes()) {
                        Text(stringResource(R.string.update_action_install))
                    }
                }
                TextButton(onClick = { uriHandler.openUri(update.pageUrl) }, shapes = ButtonDefaults.shapes()) {
                    Text(stringResource(R.string.update_action_github))
                }
            }
        }
    }
}
