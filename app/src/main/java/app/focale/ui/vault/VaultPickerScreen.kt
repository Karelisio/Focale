package app.focale.ui.vault

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import app.focale.R
import app.focale.ui.actions.MediaActionsHost
import app.focale.ui.components.MediaGrid

/**
 * Sélection de médias de la galerie à déplacer dans le coffre : chaque appui
 * sélectionne, l'appui long suivi d'un glissement sélectionne une plage.
 */
@Composable
fun VaultPickerScreen(onBack: () -> Unit, viewModel: VaultPickerViewModel = hiltViewModel()) {
    val items = viewModel.items.collectAsLazyPagingItems()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val unlocked by viewModel.unlocked.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(unlocked) { if (!unlocked) onBack() }

    // Déplacement terminé : retour au coffre, qui affiche les nouveaux éléments.
    MediaActionsHost(actions = viewModel.actions, snackbarHostState = snackbarHostState, onActionDone = onBack)

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (selection.isEmpty()) {
                            stringResource(R.string.vault_picker_title)
                        } else {
                            pluralStringResource(R.plurals.selection_count, selection.size, selection.size)
                        },
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack, shapes = IconButtonDefaults.shapes()) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close))
                    }
                },
            )
        },
        floatingActionButton = {
            AnimatedVisibility(visible = selection.isNotEmpty(), enter = scaleIn(), exit = scaleOut()) {
                ExtendedFloatingActionButton(
                    text = { Text(stringResource(R.string.action_move_to_vault)) },
                    icon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    onClick = viewModel::moveToVault,
                )
            }
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        MediaGrid(
            items = items,
            columns = viewModel.columns,
            selection = selection,
            onSelectionChange = viewModel::setSelection,
            onOpen = { viewModel.setSelection(selection + it.id) },
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection),
                end = padding.calculateEndPadding(layoutDirection),
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
        )
    }
}
