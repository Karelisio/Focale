package app.focale.ui.actions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.AutoAwesomeMosaic
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.material3.HorizontalFloatingToolbar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.focale.R

/** Action supplémentaire du menu « Plus » de la barre de sélection. */
data class ExtraAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/**
 * Barre d'actions flottante (Material 3 Expressive) du mode sélection. [onCollage] : « Créer un
 * collage » en tête du menu, dès deux éléments sélectionnés.
 */
@Composable
fun SelectionActionsBar(
    visible: Boolean,
    selectionCount: Int,
    onShare: () -> Unit,
    onFavorite: () -> Unit,
    onAddToAlbum: () -> Unit,
    onTrash: () -> Unit,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onRename: () -> Unit,
    onMoveToVault: () -> Unit,
    onSelectAll: () -> Unit,
    modifier: Modifier = Modifier,
    extraActions: List<ExtraAction> = emptyList(),
    onCollage: (() -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically(spring(dampingRatio = Spring.DampingRatioLowBouncy)) { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        var menuOpen by remember { mutableStateOf(false) }
        HorizontalFloatingToolbar(
            expanded = true,
            colors = FloatingToolbarDefaults.vibrantFloatingToolbarColors(),
            modifier = Modifier
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        ) {
            ToolbarIcon(Icons.Rounded.Share, stringResource(R.string.action_share), onShare)
            ToolbarIcon(Icons.Outlined.FavoriteBorder, stringResource(R.string.action_favorite), onFavorite)
            ToolbarIcon(Icons.Outlined.LibraryAdd, stringResource(R.string.action_add_to_album), onAddToAlbum)
            ToolbarIcon(Icons.Outlined.DeleteOutline, stringResource(R.string.action_trash), onTrash)
            Box {
                ToolbarIcon(Icons.Rounded.MoreVert, stringResource(R.string.action_more)) { menuOpen = true }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (onCollage != null && selectionCount >= 2) {
                        MenuEntry(Icons.Outlined.AutoAwesomeMosaic, stringResource(R.string.collage_action)) {
                            menuOpen = false
                            onCollage()
                        }
                    }
                    MenuEntry(Icons.Outlined.ContentCopy, stringResource(R.string.action_copy_to)) {
                        menuOpen = false
                        onCopy()
                    }
                    MenuEntry(Icons.AutoMirrored.Outlined.DriveFileMove, stringResource(R.string.action_move_to)) {
                        menuOpen = false
                        onMove()
                    }
                    if (selectionCount == 1) {
                        MenuEntry(Icons.Outlined.DriveFileRenameOutline, stringResource(R.string.action_rename)) {
                            menuOpen = false
                            onRename()
                        }
                    }
                    MenuEntry(Icons.Outlined.Lock, stringResource(R.string.action_move_to_vault)) {
                        menuOpen = false
                        onMoveToVault()
                    }
                    extraActions.forEach { action ->
                        MenuEntry(action.icon, action.label) {
                            menuOpen = false
                            action.onClick()
                        }
                    }
                    MenuEntry(Icons.Outlined.SelectAll, stringResource(R.string.action_select_all)) {
                        menuOpen = false
                        onSelectAll()
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolbarIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, shapes = IconButtonDefaults.shapes()) {
        Icon(icon, contentDescription = label)
    }
}

@Composable
private fun MenuEntry(icon: ImageVector, label: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        onClick = onClick,
    )
}
