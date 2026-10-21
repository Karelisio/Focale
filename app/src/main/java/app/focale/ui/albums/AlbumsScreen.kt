package app.focale.ui.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ripple
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
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
import app.focale.core.model.MediaItem
import app.focale.core.model.MediaQuery
import app.focale.core.permissions.MediaAccess
import app.focale.data.SmartAlbum
import app.focale.people.Person
import app.focale.ui.actions.MediaActionsHost
import app.focale.ui.actions.NameDialog
import app.focale.ui.actions.source
import app.focale.ui.components.MediaPermissionScreen
import app.focale.ui.components.clickableWithOptions
import app.focale.ui.navigation.CollectionKind
import app.focale.ui.navigation.LocalRootNavController
import app.focale.ui.navigation.LocalTabsNavController
import app.focale.ui.navigation.PeopleRoute
import app.focale.ui.navigation.PersonRoute
import app.focale.ui.navigation.TrashRoute
import app.focale.ui.navigation.openCollection
import app.focale.ui.navigation.openSlideshow
import app.focale.ui.people.PeopleSectionViewModel
import app.focale.ui.people.peopleSection
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * Onglet Albums : collections automatiques, personnes, albums automatiques (contenu reconnu),
 * albums virtuels, dossiers de l'appareil. Un appui long sur un album virtuel ou un dossier en
 * donne les options sans l'ouvrir.
 */
@Composable
fun AlbumsScreen(viewModel: AlbumsViewModel = hiltViewModel(), peopleViewModel: PeopleSectionViewModel = hiltViewModel()) {
    val access by viewModel.access.collectAsStateWithLifecycle()
    if (access == MediaAccess.DENIED) {
        MediaPermissionScreen(onAccessChanged = {})
        return
    }
    val tabsNav = LocalTabsNavController.current
    val rootNav = LocalRootNavController.current
    val userAlbums by viewModel.userAlbums.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val hiddenFolders by viewModel.hiddenFolders.collectAsStateWithLifecycle()
    val smartAlbums by viewModel.smartAlbums.collectAsStateWithLifecycle()
    val counts by viewModel.counts.collectAsStateWithLifecycle()
    val people by peopleViewModel.people.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Album passé dans le coffre depuis ses options : confirmation, progression et bilan, comme
    // depuis l'album ouvert.
    MediaActionsHost(actions = viewModel.actions, snackbarHostState = snackbarHostState)

    AlbumsContent(
        counts = counts,
        people = people,
        smartAlbums = smartAlbums,
        userAlbums = userAlbums,
        folders = folders,
        hiddenFolders = hiddenFolders,
        snackbarHostState = snackbarHostState,
        onOpenCollection = { title, query, kind -> tabsNav.openCollection(title, query, kind) },
        onOpenTrash = { tabsNav.navigate(TrashRoute) },
        onOpenPerson = { tabsNav.navigate(PersonRoute(it.id)) { launchSingleTop = true } },
        onSeeAllPeople = { tabsNav.navigate(PeopleRoute) { launchSingleTop = true } },
        onOpenAlbum = { album ->
            when (album) {
                is Album.User -> tabsNav.openCollection(album.name, MediaQuery(albumId = album.id), CollectionKind.USER_ALBUM, album.id)
                is Album.Folder -> tabsNav.openCollection(album.name, MediaQuery(bucketId = album.bucketId), CollectionKind.FOLDER)
            }
        },
        onCreateAlbum = { name ->
            scope.launch {
                val id = viewModel.createAlbum(name)
                tabsNav.openCollection(name.trim(), MediaQuery(albumId = id), CollectionKind.USER_ALBUM, id)
            }
        },
        onSlideshow = { rootNav.openSlideshow(it.source.query) },
        onRename = viewModel::renameAlbum,
        onDelete = viewModel::deleteAlbum,
        onHiddenChange = viewModel::setFolderHidden,
        onMoveToVault = viewModel::requestMoveToVault,
    )
}

/**
 * Contenu de l'onglet, sans ViewModel ni navigation (tests). Appui long sur un album virtuel ou
 * un dossier : feuille de ses options ([AlbumOptions]).
 */
@Composable
internal fun AlbumsContent(
    counts: CollectionCounts,
    people: List<Person>,
    smartAlbums: List<SmartAlbum>,
    userAlbums: List<Album.User>,
    folders: List<Album.Folder>,
    hiddenFolders: Set<Long>,
    snackbarHostState: SnackbarHostState,
    onOpenCollection: (title: String, query: MediaQuery, kind: CollectionKind) -> Unit,
    onOpenTrash: () -> Unit,
    onOpenPerson: (Person) -> Unit,
    onSeeAllPeople: () -> Unit,
    onOpenAlbum: (Album) -> Unit,
    onCreateAlbum: (String) -> Unit,
    onSlideshow: (Album) -> Unit,
    onRename: (Album.User, String) -> Unit,
    onDelete: (Album.User) -> Unit,
    onHiddenChange: (Album.Folder, Boolean) -> Unit,
    onMoveToVault: (Album) -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val gridState = rememberLazyGridState()
    val fabExpanded by remember { derivedStateOf { gridState.firstVisibleItemIndex == 0 } }
    var creating by rememberSaveable { mutableStateOf(false) }
    // Album dont les options sont ouvertes, par sa clé dans la grille : relu dans les listes, il
    // y est à jour (nombre, couverture, dossier masqué) et s'efface s'il disparaît.
    var optionsKey by rememberSaveable { mutableStateOf<String?>(null) }

    if (creating) {
        NameDialog(
            title = stringResource(R.string.album_new),
            label = stringResource(R.string.album_name),
            confirm = stringResource(R.string.action_create),
            onConfirm = onCreateAlbum,
            onDismiss = { creating = false },
        )
    }
    val optionsAlbum = optionsKey?.let { wanted ->
        userAlbums.firstOrNull { it.gridKey == wanted } ?: folders.firstOrNull { it.gridKey == wanted }
    }
    if (optionsAlbum != null) {
        key(optionsAlbum.gridKey) {
            AlbumOptions(
                album = optionsAlbum,
                hidden = optionsAlbum is Album.Folder && optionsAlbum.bucketId in hiddenFolders,
                onSlideshow = { onSlideshow(optionsAlbum) },
                onRename = { name -> if (optionsAlbum is Album.User) onRename(optionsAlbum, name) },
                onHiddenChange = { hidden -> if (optionsAlbum is Album.Folder) onHiddenChange(optionsAlbum, hidden) },
                onMoveToVault = { onMoveToVault(optionsAlbum) },
                onDelete = { if (optionsAlbum is Album.User) onDelete(optionsAlbum) },
                onDismiss = { optionsKey = null },
            )
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                CollectionsRow(counts, onOpen = onOpenCollection, onOpenTrash = onOpenTrash)
            }
            peopleSection(people = people, onOpen = onOpenPerson, onSeeAll = onSeeAllPeople)
            smartAlbumsSection(smartAlbums, onOpen = { album, title -> onOpenCollection(title, album.query, CollectionKind.QUERY) })
            item(key = "user-header", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(stringResource(R.string.albums_mine))
            }
            if (userAlbums.isEmpty()) {
                item(key = "user-empty", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyAlbumsHint(onCreate = { creating = true })
                }
            }
            items(userAlbums, key = { it.gridKey }) { album ->
                AlbumCard(album, onLongClick = { optionsKey = album.gridKey }) { onOpenAlbum(album) }
            }
            item(key = "folder-header", span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(stringResource(R.string.albums_device))
            }
            items(folders, key = { it.gridKey }) { folder ->
                AlbumCard(folder, hiddenFromTimeline = folder.bucketId in hiddenFolders, onLongClick = { optionsKey = folder.gridKey }) {
                    onOpenAlbum(folder)
                }
            }
        }
    }
}

/** Clé d'un album dans la grille, qui désigne aussi celui dont les options sont ouvertes. */
private val Album.gridKey: String
    get() = when (this) {
        is Album.User -> "user-$id"
        is Album.Folder -> "folder-$bucketId"
    }

@Composable
private fun CollectionsRow(
    counts: CollectionCounts,
    onOpen: (title: String, query: MediaQuery, kind: CollectionKind) -> Unit,
    onOpenTrash: () -> Unit,
) {
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
            onOpen(favoritesTitle, AlbumsViewModel.FAVORITES, CollectionKind.FAVORITES)
        }
        CollectionChip(videosTitle, counts.videos, Icons.Outlined.Videocam, MaterialShapes.Cookie9Sided, colors.primaryContainer, colors.onPrimaryContainer) {
            onOpen(videosTitle, AlbumsViewModel.VIDEOS, CollectionKind.QUERY)
        }
        CollectionChip(screenshotsTitle, counts.screenshots, Icons.Outlined.Screenshot, MaterialShapes.Square, colors.secondaryContainer, colors.onSecondaryContainer) {
            onOpen(screenshotsTitle, AlbumsViewModel.SCREENSHOTS, CollectionKind.QUERY)
        }
        CollectionChip(gifsTitle, counts.gifs, Icons.Outlined.Gif, MaterialShapes.Sunny, colors.primaryContainer, colors.onPrimaryContainer) {
            onOpen(gifsTitle, AlbumsViewModel.GIFS, CollectionKind.QUERY)
        }
        CollectionChip(stringResource(R.string.collection_trash), counts.trash, Icons.Outlined.DeleteOutline, MaterialShapes.Pill, colors.errorContainer, colors.onErrorContainer) {
            onOpenTrash()
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
internal fun SectionHeader(title: String) {
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

/**
 * Carte d'album : couverture arrondie, nom et nombre d'éléments. [onLongClick] : options de
 * l'album, sans l'ouvrir.
 */
@Composable
fun AlbumCard(album: Album, hiddenFromTimeline: Boolean = false, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    AlbumCard(album.name, album.count, album.cover, hiddenFromTimeline = hiddenFromTimeline, onLongClick = onLongClick, onClick = onClick)
}

/**
 * Carte d'album à partir de ses éléments ; [placeholder] tient lieu de couverture absente. Toute
 * la carte se touche, lue d'un bloc par TalkBack ; l'ondulation reste sur la couverture.
 * [onLongClick] : options de l'album (retour haptique de l'appui long, action « Options de
 * l'album » pour TalkBack).
 */
@Composable
fun AlbumCard(
    name: String,
    count: Int,
    cover: MediaItem?,
    placeholder: ImageVector = Icons.Outlined.PhotoAlbum,
    hiddenFromTimeline: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .clickableWithOptions(interactionSource, stringResource(R.string.album_options), onOptions = onLongClick, onClick = onClick),
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
                        placeholder,
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
            text = name,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
        )
        Text(
            text = pluralStringResource(R.plurals.media_count, count, count),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
