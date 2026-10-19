package app.focale.ui.viewer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.focale.R
import app.focale.core.model.MediaItem

/** Entrées de menu ajoutées par d'autres fonctionnalités (coffre, diaporama…). */
@Composable
fun ViewerExtraMenuItems(viewModel: ViewerViewModel, item: MediaItem?, dismiss: () -> Unit) {
    if (item == null) return
    ViewerMenuItem(Icons.Outlined.Lock, stringResource(R.string.action_move_to_vault)) {
        dismiss()
        viewModel.onCurrent { requestMoveToVault(it) }
    }
}
