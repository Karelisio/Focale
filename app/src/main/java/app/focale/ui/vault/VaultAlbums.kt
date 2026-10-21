package app.focale.ui.vault

import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.focale.R
import app.focale.ui.actions.NameDialog
import app.focale.ui.components.clickableWithOptions
import app.focale.vault.VaultAlbum
import app.focale.vault.VaultDestination
import app.focale.vault.VaultImage
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

/** Médias arrivés dans le dossier d'origine d'un album du coffre depuis son dernier import. */
@Immutable
data class NewMedia(val photos: Int, val videos: Int, val folderName: String) {
    val count: Int get() = photos + videos
}

/** Titre de section d'une grille du coffre. */
@Composable
internal fun VaultSectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmallEmphasized,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 12.dp),
    )
}

/**
 * Section « Albums » du coffre : titre et rangée de tuiles. Appui long sur une tuile : les options
 * de l'album, celles de son menu (renommer, sortir du coffre, supprimer), sans l'ouvrir.
 */
@Composable
internal fun VaultAlbumsShelf(
    albums: List<VaultAlbum>,
    onOpen: (VaultAlbum) -> Unit,
    onRename: (VaultAlbum, String) -> Unit,
    onRestore: (VaultAlbum) -> Unit,
    onDelete: (VaultAlbum) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Album dont les options sont ouvertes, relu dans [albums] : il s'efface s'il disparaît.
    var optionsId by rememberSaveable { mutableStateOf<Long?>(null) }
    albums.firstOrNull { it.id == optionsId }?.let { album ->
        key(album.id) {
            VaultAlbumOptions(
                album = album,
                onRename = { onRename(album, it) },
                onRestore = { onRestore(album) },
                onDelete = { onDelete(album) },
                onDismiss = { optionsId = null },
            )
        }
    }
    Column(modifier.fillMaxWidth()) {
        VaultSectionHeader(stringResource(R.string.vault_albums_title))
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = 16.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(albums, key = { it.id }) { album ->
                VaultAlbumTile(
                    album = album,
                    onClick = { onOpen(album) },
                    onLongClick = { optionsId = album.id },
                    modifier = Modifier.width(TILE_WIDTH),
                )
            }
        }
    }
}

/**
 * Tuile d'album : couverture (miniature chiffrée), nom et nombre d'éléments. Toute la tuile se
 * touche, lue d'un bloc par TalkBack ; l'ondulation reste sur la couverture. [onLongClick] : options
 * de l'album (retour haptique de l'appui long, action « Options de l'album » pour TalkBack).
 */
@Composable
internal fun VaultAlbumTile(
    album: VaultAlbum,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        modifier.clickableWithOptions(interactionSource, stringResource(R.string.album_options), onOptions = onLongClick, onClick = onClick),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(MaterialTheme.shapes.extraLarge)
                .indication(interactionSource, ripple()),
        ) {
            val cover = album.coverThumbFileName
            if (cover != null) {
                AsyncImage(
                    model = rememberVaultThumbnail(cover),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = album.placeholder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
        }
        Text(
            text = album.name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
        Text(
            text = pluralStringResource(R.plurals.media_count, album.count, album.count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * Bannière d'un album-dossier : les médias arrivés depuis dans son dossier d'origine,
 * à chiffrer d'un geste dans l'album, sans rien déchiffrer de ce qui s'y trouve déjà.
 */
@Composable
internal fun NewMediaBanner(media: NewMedia, enabled: Boolean, onAdd: () -> Unit, modifier: Modifier = Modifier) {
    val text = when {
        media.videos == 0 -> pluralStringResource(R.plurals.vault_album_new_photos, media.count, media.count, media.folderName)
        media.photos == 0 -> pluralStringResource(R.plurals.vault_album_new_videos, media.count, media.count, media.folderName)
        else -> pluralStringResource(R.plurals.vault_album_new_items, media.count, media.count, media.folderName)
    }
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Icon(Icons.Outlined.AddPhotoAlternate, contentDescription = null)
            Text(text = text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Button(onClick = onAdd, enabled = enabled, shapes = ButtonDefaults.shapes()) {
                Text(stringResource(R.string.vault_add))
            }
        }
    }
}

/**
 * Bannière des éléments dont l'original est resté dans la galerie (déplacement interrompu) :
 * les retirer de la galerie ne chiffre rien, leurs copies sont déjà dans le coffre.
 */
@Composable
internal fun LeftInGalleryBanner(count: Int, enabled: Boolean, onRemove: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        shape = MaterialTheme.shapes.large,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.PhotoLibrary, contentDescription = null)
                Text(
                    text = pluralStringResource(R.plurals.vault_left_in_gallery, count, count),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
            }
            Button(
                onClick = onRemove,
                enabled = enabled,
                shapes = ButtonDefaults.shapes(),
                modifier = Modifier.align(Alignment.End).padding(top = 8.dp),
            ) {
                Text(stringResource(R.string.vault_left_in_gallery_action))
            }
        }
    }
}

/**
 * Choix de l'album où ranger une sélection du coffre : un nouvel album, hors album si
 * [showNone], ou l'un des [albums]. Rien n'est déchiffré : seul le classement change.
 */
@Composable
internal fun VaultAlbumPickerSheet(
    count: Int,
    albums: List<VaultAlbum>,
    showNone: Boolean,
    onPick: (VaultDestination) -> Unit,
    onDismiss: () -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    if (creating) {
        NameDialog(
            title = stringResource(R.string.album_new),
            label = stringResource(R.string.album_name),
            confirm = stringResource(R.string.action_create),
            onConfirm = { onPick(VaultDestination.NewAlbum(it)) },
            onDismiss = { creating = false },
        )
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text = pluralStringResource(R.plurals.vault_move_to_album_title, count, count),
            style = MaterialTheme.typography.titleLargeEmphasized,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        val first = if (showNone) 2 else 1
        val total = albums.size + first
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            modifier = Modifier.navigationBarsPadding(),
        ) {
            item {
                SegmentedListItem(
                    onClick = { creating = true },
                    shapes = ListItemDefaults.segmentedShapes(0, total),
                    leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null) },
                ) { Text(stringResource(R.string.album_new)) }
            }
            if (showNone) {
                item {
                    SegmentedListItem(
                        onClick = { onPick(VaultDestination.None) },
                        shapes = ListItemDefaults.segmentedShapes(1, total),
                        leadingContent = { Icon(Icons.Outlined.PhotoLibrary, contentDescription = null) },
                        supportingContent = { Text(stringResource(R.string.vault_album_none_hint)) },
                    ) { Text(stringResource(R.string.vault_album_none)) }
                }
            }
            itemsIndexed(albums, key = { _, album -> album.id }) { index, album ->
                SegmentedListItem(
                    onClick = { onPick(VaultDestination.Album(album.id)) },
                    shapes = ListItemDefaults.segmentedShapes(index + first, total),
                    leadingContent = { VaultAlbumThumb(album) },
                    supportingContent = { Text(pluralStringResource(R.plurals.media_count, album.count, album.count)) },
                ) { Text(album.name) }
            }
        }
    }
}

/** Couverture d'un album du coffre en vignette arrondie ; l'icône de l'album faute de couverture. */
@Composable
internal fun VaultAlbumThumb(album: VaultAlbum, size: Dp = 48.dp) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.size(size),
    ) {
        val cover = album.coverThumbFileName
        if (cover != null) {
            AsyncImage(
                model = rememberVaultThumbnail(cover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(album.placeholder, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

/** Icône d'un album sans couverture : dossier d'origine ou album simple. */
private val VaultAlbum.placeholder: ImageVector
    get() = if (sourcePath != null) Icons.Outlined.Folder else Icons.Outlined.PhotoAlbum

/** Miniature chiffrée [fileName], avec la clé de cache partagée par la grille et la visionneuse. */
@Composable
internal fun rememberVaultThumbnail(fileName: String): ImageRequest {
    val context = LocalContext.current
    return remember(fileName) {
        ImageRequest.Builder(context)
            .data(VaultImage(fileName))
            .size(THUMBNAIL_SIDE)
            .memoryCacheKey(thumbnailKey(fileName))
            .build()
    }
}

private val TILE_WIDTH = 140.dp
