package app.focale.ui.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Gif
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.Screenshot
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.focale.R
import app.focale.core.image.thumbnail
import app.focale.core.model.Album
import app.focale.core.model.MediaQuery
import app.focale.core.permissions.MediaAccess
import app.focale.ui.actions.NameDialog
import app.focale.ui.components.MediaPermissionScreen
import app.focale.ui.navigation.CollectionKind
import app.focale.ui.navigation.LocalTabsNavController
import app.focale.ui.navigation.TrashRoute
import app.focale.ui.navigation.openCollection
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/** Onglet Albums : collections automatiques, albums virtuels, dossiers de l'appareil. */
@Composable
fun AlbumsScreen(viewModel: AlbumsViewModel = hiltViewModel()) {
    val access by viewModel.access.collectAsStateWithLifecycle()
    if (access == MediaAccess.DENIED) {
        MediaPermissionScreen(onAccessChanged = {})
        return
    }
    val tabsNav = LocalTabsNavController.current
    val userAlbums by viewModel.userAlbums.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val hiddenFolders by viewModel.hiddenFolders.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyGridState()
    val fabExpanded by remember { derivedStateOf { gridState.firstVisibleItemIndex == 0 } }
    var creating by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    if (creating) {
        NameDialog(
            title = stringResource(R.string.album_new),
            label = stringResource(R.string.album_name),
            confirm = stringResource(R.string.action_create),
            onConfirm = { name ->
                scope.launch {
                    val id = viewModel.createAlbum(name)
                    tabsNav.openCollection(name.trim(), MediaQuery(albumId = id), CollectionKind.USER_ALBUM, id)
                }
            },
            onDismiss = { creating = false },
        )
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text(stringResource(R.string.tab_albums)) },
                scrollBehavior = scrollBehavior,
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.album_new)) },
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                expanded = fabExpanded,
                onClick = { creating = true },
            )
        },
    ) { padding ->
        val layoutDirection = LocalLayoutDirection.current
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Adaptive(minSize = 152.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(
                start = padding.calculateStartPadding(layoutDirection) + 16.dp,
                end = padding.calculateEndPadding(layoutDirection) + 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding() + 96.dp,
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(key = "collections", span = { GridItemSpan(maxLineSpan) }) {
                CollectionsRow(counts)
            }
            item(key = "user-header", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(stringResource(R.string.albums_mine))
            }
            if (userAlbums.isEmpty()) {
                item(key = "user-empty", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyAlbumsHint(onCreate = { creating = true })
                }
            }
            items(userAlbums, key = { "user-${it.id}" }) { album ->
                AlbumCard(album) {
                    tabsNav.openCollection(album.name, MediaQuery(albumId = album.id), CollectionKind.USER_ALBUM, album.id)
                }
            }
            item(key = "folder-header", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(stringResource(R.string.albums_device))
            }
            items(folders, key = { "folder-${it.bucketId}" }) { folder ->
                AlbumCard(folder, hiddenFromTimeline = folder.bucketId in hiddenFolders) {
                    tabsNav.openCollection(folder.name, MediaQuery(bucketId = folder.bucketId), CollectionKind.FOLDER)
                }
            }
        }
    }
}

@Composable
private fun CollectionsRow(counts: CollectionCounts) {
    val tabsNav = LocalTabsNavController.current
    val colors = MaterialTheme.colorScheme
    val favoritesTitle = stringResource(R.string.collection_favorites)
    val videosTitle = stringResource(R.string.filter_videos)
    val screenshotsTitle = stringResource(R.string.filter_screenshots)
    val gifsTitle = stringResource(R.string.filter_gifs)
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 4.dp),
    ) {
        CollectionChip(favoritesTitle, counts.favorites, Icons.Outlined.Favorite, MaterialShapes.Heart, colors.tertiaryContainer, colors.onTertiaryContainer) {
            tabsNav.openCollection(favoritesTitle, AlbumsViewModel.FAVORITES, CollectionKind.FAVORITES)
        }
        CollectionChip(videosTitle, counts.videos, Icons.Outlined.Videocam, MaterialShapes.Cookie9Sided, colors.primaryContainer, colors.onPrimaryContainer) {
            tabsNav.openCollection(videosTitle, AlbumsViewModel.VIDEOS)
        }
        CollectionChip(screenshotsTitle, counts.screenshots, Icons.Outlined.Screenshot, MaterialShapes.Square, colors.secondaryContainer, colors.onSecondaryContainer) {
            tabsNav.openCollection(screenshotsTitle, AlbumsViewModel.SCREENSHOTS)
        }
        CollectionChip(gifsTitle, counts.gifs, Icons.Outlined.Gif, MaterialShapes.Sunny, colors.primaryContainer, colors.onPrimaryContainer) {
            tabsNav.openCollection(gifsTitle, AlbumsViewModel.GIFS)
        }
        CollectionChip(stringResource(R.string.collection_trash), counts.trash, Icons.Outlined.DeleteOutline, MaterialShapes.Pill, colors.errorContainer, colors.onErrorContainer) {
            tabsNav.navigate(TrashRoute)
        }
    }
}

@Composable
private fun CollectionChip(
    title: String,
    count: Int,
    icon: ImageVector,
    shape: RoundedPolygon,
    container: Color,
    content: Color,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.width(132.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(shape.toShape())
                    .background(container),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmallEmphasized,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmallEmphasized,
        modifier = Modifier.padding(top = 12.dp, start = 4.dp),
    )
}

@Composable
private fun EmptyAlbumsHint(onCreate: () -> Unit) {
    Surface(
        onClick = onCreate,
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(20.dp)) {
            Icon(Icons.Outlined.PhotoAlbum, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = stringResource(R.string.albums_empty_hint),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

/** Carte d'album : couverture arrondie, nom et nombre d'éléments. */
@Composable
fun AlbumCard(album: Album, hiddenFromTimeline: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Surface(
            onClick = onClick,
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
        ) {
            val cover = album.cover
            if (cover != null) {
                AsyncImage(
                    model = cover.thumbnail(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.PhotoAlbum,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                }
            }
            if (hiddenFromTimeline) {
                Box(Modifier.padding(8.dp), contentAlignment = Alignment.TopEnd) {
                    Icon(
                        Icons.Outlined.VisibilityOff,
                        contentDescription = stringResource(R.string.folder_hidden),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(6.dp)
                            .size(18.dp),
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
