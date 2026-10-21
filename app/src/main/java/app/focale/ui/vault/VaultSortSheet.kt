package app.focale.ui.vault

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SdStorage
import androidx.compose.material.icons.outlined.SortByAlpha
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.focale.R
import app.focale.core.settings.VaultAlbumOrder
import app.focale.core.settings.VaultAlbumSort
import app.focale.core.settings.VaultItemOrder
import app.focale.core.settings.VaultSort
import app.focale.ui.components.SortChoices
import app.focale.ui.components.SortOption
import app.focale.ui.components.SortSectionTitle
import app.focale.ui.components.SortSheet

/** Bouton qui ouvre la feuille de tri, comme dans les grilles de la galerie. */
@Composable
internal fun VaultSortButton(onClick: () -> Unit) {
    IconButton(onClick = onClick, shapes = IconButtonDefaults.shapes()) {
        Icon(Icons.Rounded.Tune, contentDescription = stringResource(R.string.vault_sort_title))
    }
}

/**
 * Tri du coffre, présenté comme celui des grilles de la galerie : ses éléments et, sur l'écran
 * principal ([albumSort] non nul), ses albums. Le choix est mémorisé.
 */
@Composable
internal fun VaultSortSheet(
    sort: VaultSort,
    onSortChange: (VaultSort) -> Unit,
    onDismiss: () -> Unit,
    albumSort: VaultAlbumSort? = null,
    onAlbumSortChange: (VaultAlbumSort) -> Unit = {},
) {
    SortSheet(title = stringResource(R.string.vault_sort_title), onDismiss = onDismiss) {
        if (albumSort != null) SortSectionTitle(stringResource(R.string.vault_sort_items))
        SortChoices(
            options = ITEM_OPTIONS,
            selected = sort.order,
            ascending = sort.ascending,
            onSelect = { onSortChange(sort.copy(order = it)) },
            onAscendingChange = { onSortChange(sort.copy(ascending = it)) },
        )
        if (albumSort != null) {
            SortSectionTitle(stringResource(R.string.vault_albums_title))
            SortChoices(
                options = ALBUM_OPTIONS,
                selected = albumSort.order,
                ascending = albumSort.ascending,
                onSelect = { onAlbumSortChange(albumSort.copy(order = it)) },
                onAscendingChange = { onAlbumSortChange(albumSort.copy(ascending = it)) },
            )
        }
    }
}

private val ITEM_OPTIONS = listOf(
    SortOption(VaultItemOrder.DATE_TAKEN, R.string.sort_date_taken, Icons.Outlined.CalendarMonth),
    SortOption(VaultItemOrder.DATE_ADDED, R.string.vault_sort_date_added, Icons.Outlined.Schedule),
    SortOption(VaultItemOrder.NAME, R.string.sort_name, Icons.Outlined.SortByAlpha),
    SortOption(VaultItemOrder.SIZE, R.string.sort_size, Icons.Outlined.SdStorage),
)

private val ALBUM_OPTIONS = listOf(
    SortOption(VaultAlbumOrder.NAME, R.string.sort_name, Icons.Outlined.SortByAlpha),
    SortOption(VaultAlbumOrder.DATE, R.string.vault_sort_album_created, Icons.Outlined.Event),
    SortOption(VaultAlbumOrder.COUNT, R.string.vault_sort_album_count, Icons.Outlined.PhotoLibrary),
)
