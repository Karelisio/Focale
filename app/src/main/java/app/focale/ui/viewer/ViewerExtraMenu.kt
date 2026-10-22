package app.focale.ui.viewer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.RotateRight
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Print
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import app.focale.R
import app.focale.core.model.MediaItem
import app.focale.ui.navigation.LocalRootNavController
import app.focale.ui.navigation.openSlideshow

/** Entrées de menu ajoutées par d'autres fonctionnalités (texte, impression, diaporama, coffre…). */
@Composable
fun ViewerExtraMenuItems(viewModel: ViewerViewModel, item: MediaItem?, dismiss: () -> Unit, onRotate: (() -> Unit)? = null) {
    if (item == null) return
    val rootNav = LocalRootNavController.current
    // Même rotation que le geste à deux doigts, pour qui ne peut pas le faire.
    if (onRotate != null) {
        ViewerMenuItem(Icons.AutoMirrored.Outlined.RotateRight, stringResource(R.string.editor_rotate)) {
            dismiss()
            onRotate()
        }
    }
    if (!item.isVideo) {
        val texts: PhotoTextViewModel = hiltViewModel()
        ViewerMenuItem(Icons.Outlined.TextFields, stringResource(R.string.action_text)) {
            dismiss()
            texts.open(item)
        }
        PrintMenuItem {
            dismiss()
            viewModel.onCurrent { print(it) }
        }
    }
    ViewerMenuItem(Icons.Outlined.Slideshow, stringResource(R.string.action_slideshow)) {
        dismiss()
        rootNav.openSlideshow(viewModel.query, startId = item.id)
    }
    ViewerMenuItem(Icons.Outlined.Lock, stringResource(R.string.action_move_to_vault)) {
        dismiss()
        viewModel.onCurrent { requestMoveToVault(it) }
    }
}

/** « Imprimer » : photos de la galerie seulement (ni vidéo, ni coffre). */
@Composable
internal fun PrintMenuItem(onPrint: () -> Unit) {
    ViewerMenuItem(Icons.Outlined.Print, stringResource(R.string.action_print), onPrint)
}

/** Feuilles ouvertes par ces entrées (texte de la photo…), affichées au-dessus de la visionneuse. */
@Composable
fun ViewerExtraSheets() {
    PhotoTextSheetHost()
}
