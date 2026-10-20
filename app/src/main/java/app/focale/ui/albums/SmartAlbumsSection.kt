package app.focale.ui.albums

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material.icons.outlined.SportsSoccer
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.data.SmartAlbum
import app.focale.data.SmartAlbumKind

/**
 * Section « Albums automatiques » d'une grille pleine largeur : titre et rangée de tuiles.
 * Rien n'est ajouté tant qu'aucune catégorie ne réunit quelques médias.
 */
fun LazyGridScope.smartAlbumsSection(albums: List<SmartAlbum>, onOpen: (SmartAlbum, String) -> Unit) {
    if (albums.isEmpty()) return
    item(key = "smart-albums", span = { GridItemSpan(maxLineSpan) }) {
        SmartAlbumsShelf(albums, onOpen)
    }
}

/** Titre et rangée des albums automatiques ; [onOpen] reçoit l'album et son titre localisé. */
@Composable
fun SmartAlbumsShelf(albums: List<SmartAlbum>, onOpen: (SmartAlbum, String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        SectionHeader(stringResource(R.string.smart_albums_title))
        // Même écart que sous les autres titres de l'onglet (grille espacée de 16 dp).
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            items(albums, key = { it.kind.name }) { album ->
                val title = stringResource(album.kind.title)
                Box(Modifier.width(TILE_WIDTH)) {
                    AlbumCard(title, album.count, album.cover, placeholder = album.kind.icon) { onOpen(album, title) }
                }
            }
        }
    }
}

private val TILE_WIDTH = 140.dp

@get:StringRes
private val SmartAlbumKind.title: Int
    get() = when (this) {
        SmartAlbumKind.DOCUMENTS -> R.string.smart_album_documents
        SmartAlbumKind.RECEIPTS -> R.string.smart_album_receipts
        SmartAlbumKind.ANIMALS -> R.string.smart_album_animals
        SmartAlbumKind.FOOD -> R.string.smart_album_food
        SmartAlbumKind.LANDSCAPES -> R.string.smart_album_landscapes
        SmartAlbumKind.CELEBRATIONS -> R.string.smart_album_celebrations
        SmartAlbumKind.VEHICLES -> R.string.smart_album_vehicles
        SmartAlbumKind.SPORT -> R.string.smart_album_sport
    }

/** Icône affichée faute de couverture. */
private val SmartAlbumKind.icon: ImageVector
    get() = when (this) {
        SmartAlbumKind.DOCUMENTS -> Icons.Outlined.Description
        SmartAlbumKind.RECEIPTS -> Icons.Outlined.Receipt
        SmartAlbumKind.ANIMALS -> Icons.Outlined.Pets
        SmartAlbumKind.FOOD -> Icons.Outlined.Restaurant
        SmartAlbumKind.LANDSCAPES -> Icons.Outlined.Landscape
        SmartAlbumKind.CELEBRATIONS -> Icons.Outlined.Celebration
        SmartAlbumKind.VEHICLES -> Icons.Outlined.DirectionsCar
        SmartAlbumKind.SPORT -> Icons.Outlined.SportsSoccer
    }
