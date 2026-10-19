package app.focale.ui.viewer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.focale.R
import app.focale.core.model.MediaItem
import app.focale.ui.navigation.LocalRootNavController
import app.focale.ui.navigation.openSlideshow

/** Entrées de menu ajoutées par d'autres fonctionnalités (diaporama, coffre…). */
@Composable
fun ViewerExtraMenuItems(viewModel: ViewerViewModel, item: MediaItem?, dismiss: () -> Unit) {
    if (item == null) return
    val rootNav = LocalRootNavController.current
    ViewerMenuItem(Icons.Outlined.Slideshow, stringResource(R.string.action_slideshow)) {
        dismiss()
        rootNav.openSlideshow(viewModel.query, startId = item.id)
    }
    ViewerMenuItem(Icons.Outlined.Lock, stringResource(R.string.action_move_to_vault)) {
        dismiss()
        viewModel.onCurrent { requestMoveToVault(it) }
    }
}
